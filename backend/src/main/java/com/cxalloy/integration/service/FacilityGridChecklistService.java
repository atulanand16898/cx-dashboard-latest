package com.cxalloy.integration.service;

import com.cxalloy.integration.client.FacilityGridApiClient;
import com.cxalloy.integration.config.FacilityGridApiProperties;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Checklist;
import com.cxalloy.integration.repository.ChecklistRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
@Transactional
public class FacilityGridChecklistService extends BaseProjectService {

    private static final int PAGE_LIMIT = 5000;
    private static final String KIND_CHECKLIST = "checklist";
    private static final String KIND_FUNCTIONAL_TEST = "functional_test";

    private final ChecklistRepository checklistRepository;
    private final FacilityGridApiClient facilityGridApiClient;
    private final FacilityGridApiProperties facilityGridApiProperties;
    private final FacilityGridAuthService facilityGridAuthService;

    public FacilityGridChecklistService(ChecklistRepository checklistRepository,
                                        FacilityGridApiClient facilityGridApiClient,
                                        FacilityGridApiProperties facilityGridApiProperties,
                                        FacilityGridAuthService facilityGridAuthService) {
        this.checklistRepository = checklistRepository;
        this.facilityGridApiClient = facilityGridApiClient;
        this.facilityGridApiProperties = facilityGridApiProperties;
        this.facilityGridAuthService = facilityGridAuthService;
    }

    @Caching(evict = {
        @CacheEvict(value = "checklists-by-project", allEntries = true),
        @CacheEvict(value = "checklists-all", allEntries = true)
    })
    public SyncResult syncAllChecklists(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog(checklistListPath(pid), "GET");
        int totalSynced = 0;
        long deadline = start + TimeUnit.SECONDS.toMillis(checklistSyncTimeoutSeconds());

        try {
            ensureWithinDeadline(deadline);
            String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
            totalSynced += syncKind(pid, accessToken, KIND_CHECKLIST, deadline);
            totalSynced += syncKind(pid, accessToken, KIND_FUNCTIONAL_TEST, deadline);

            long duration = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, duration, null);
            return new SyncResult(checklistListPath(pid), "SUCCESS", totalSynced,
                    "Synced " + totalSynced + " Facility Grid checklist records", duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", totalSynced, duration, e.getMessage());
            throw new RuntimeException("Facility Grid checklist sync failed: " + e.getMessage(), e);
        }
    }

    private int syncKind(String projectId, String accessToken, String kind, long deadline) throws Exception {
        String endpoint = listPath(projectId, kind);
        int totalSynced = 0;
        int offset = 0;

        while (true) {
            ensureWithinDeadline(deadline);
            String pagePath = paged(endpoint, PAGE_LIMIT, offset);
            String raw = facilityGridApiClient.get(pagePath, accessToken);
            saveRaw(pagePath, "facilitygrid_" + kind + "_list_o" + offset, projectId, raw);

            int count = parseAndSave(raw, projectId, kind);
            totalSynced += count;
            if (count < PAGE_LIMIT) {
                break;
            }
            offset += PAGE_LIMIT;
        }
        return totalSynced;
    }

    private void ensureWithinDeadline(long deadline) {
        if (System.currentTimeMillis() <= deadline) {
            return;
        }
        throw new RuntimeException("Facility Grid checklist sync timed out after "
                + checklistSyncTimeoutSeconds() + " seconds. Try syncing again.");
    }

    private int checklistSyncTimeoutSeconds() {
        return Math.max(15, facilityGridApiProperties.getChecklistSyncTimeoutSeconds());
    }

    private int parseAndSave(String json, String projectId, String kind) throws Exception {
        if (!StringUtils.hasText(json)) {
            return 0;
        }

        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new IllegalStateException("Facility Grid " + kind + " response was not an array");
        }

        List<Checklist> rows = new ArrayList<>();
        for (JsonNode node : root) {
            rows.add(map(node, projectId, kind));
        }
        rows.forEach(this::upsert);
        return rows.size();
    }

    private Checklist map(JsonNode node, String projectId, String kind) {
        Checklist checklist = new Checklist();

        String externalId = getAsText(node, "uuid", null);
        String testType = getAsText(node, "test_type", null);
        String status = normalizeChecklistStatus(getAsText(node, "status", null));

        checklist.setExternalId(externalId);
        checklist.setProvider(currentProviderKey());
        checklist.setSourceKey(sourceKey(kind, externalId));
        checklist.setProjectId(projectId);
        checklist.setName(getAsText(node, "name", null));
        checklist.setDescription(firstNonBlank(getAsText(node, "code", null), getAsText(node, "sort_code", null)));
        checklist.setStatus(status);
        checklist.setChecklistType(firstNonBlank(testType, kindLabel(kind)));
        checklist.setTagLevel(normalizeTagLevel(
                firstNonBlank(getAsText(node, "cx_level", null), testType, getAsText(node, "name", null))));

        JsonNode assetNode = node.get("assets");
        checklist.setAssetId(resolveAssetUuid(assetNode));
        checklist.setAssignedTo(getAsText(node, "signed_off_by_participant_uuid", null));
        checklist.setDueDate(null);

        String completedDate = getAsText(node, "signed_off_datetime", null);
        checklist.setCompletedDate(completedDate);
        checklist.setCreatedAt(null);
        checklist.setUpdatedAt(firstNonBlank(
                completedDate,
                status.equals("checklist_approved") ? completedDate : null));
        checklist.setRawJson(node.toString());
        checklist.setSyncedAt(now());
        return checklist;
    }

    private void upsert(Checklist checklist) {
        if (!StringUtils.hasText(checklist.getExternalId())) {
            logger.warn("Skipping Facility Grid checklist row with null externalId");
            return;
        }

        checklistRepository.findBySourceKey(checklist.getSourceKey()).ifPresentOrElse(existing -> {
            existing.setProvider(checklist.getProvider());
            existing.setSourceKey(checklist.getSourceKey());
            existing.setProjectId(checklist.getProjectId());
            existing.setName(checklist.getName());
            existing.setDescription(checklist.getDescription());
            existing.setStatus(checklist.getStatus());
            existing.setChecklistType(checklist.getChecklistType());
            existing.setTagLevel(checklist.getTagLevel());
            existing.setAssetId(checklist.getAssetId());
            existing.setAssignedTo(checklist.getAssignedTo());
            existing.setDueDate(checklist.getDueDate());
            existing.setCompletedDate(checklist.getCompletedDate());
            existing.setCreatedAt(checklist.getCreatedAt());
            existing.setUpdatedAt(checklist.getUpdatedAt());
            existing.setRawJson(checklist.getRawJson());
            existing.setSyncedAt(checklist.getSyncedAt());
            checklistRepository.save(existing);
        }, () -> checklistRepository.save(checklist));
    }

    private String resolveAssetUuid(JsonNode assetNode) {
        if (assetNode == null || assetNode.isNull()) {
            return null;
        }
        if (assetNode.isObject()) {
            return getAsText(assetNode, "uuid", null);
        }
        if (assetNode.isArray() && assetNode.size() > 0) {
            JsonNode first = assetNode.get(0);
            if (first.isObject()) {
                return getAsText(first, "uuid", null);
            }
            return first.asText(null);
        }
        return assetNode.asText(null);
    }

    private String normalizeChecklistStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "not_started";
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "not_started", "new", "open" -> "not_started";
            case "in_progress", "started", "active" -> "in_progress";
            case "complete", "completed", "passed" -> "complete";
            case "checklist_approved", "approved", "signed_off" -> "checklist_approved";
            case "returned_with_comments", "returned", "rejected" -> "returned_with_comments";
            case "on_hold" -> "on_hold";
            case "cancelled", "canceled" -> "cancelled";
            default -> normalized;
        };
    }

    private String normalizeTagLevel(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "white";
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.matches(".*\\bred\\b.*") || value.contains("level-1") || value.contains("level 1") || value.matches(".*\\bl1\\b.*") || value.contains("fat")) {
            return "red";
        }
        if (value.matches(".*\\byellow\\b.*") || value.contains("level-2") || value.contains("level 2") || value.matches(".*\\bl2\\b.*")
                || value.contains("installation") || value.contains("qa/qc") || value.contains("ivc")) {
            return "yellow";
        }
        if (value.matches(".*\\bgreen\\b.*") || value.contains("level-3") || value.contains("level 3") || value.matches(".*\\bl3\\b.*")
                || value.contains("startup") || value.contains("pre-functional") || value.contains("prefunctional")) {
            return "green";
        }
        if (value.matches(".*\\bblue\\b.*") || value.contains("level-4") || value.contains("level 4") || value.matches(".*\\bl4\\b.*")
                || value.contains("functional performance") || value.contains("fpt")) {
            return "blue";
        }
        return "white";
    }

    private String listPath(String projectId, String kind) {
        return switch (kind) {
            case KIND_FUNCTIONAL_TEST -> expandProjectPath(
                    facilityGridApiProperties.getFunctionalTestPathTemplate(),
                    projectId,
                    "/api/v2_2/project/{project_id}/functional_test");
            default -> checklistListPath(projectId);
        };
    }

    private String checklistListPath(String projectId) {
        return expandProjectPath(
                facilityGridApiProperties.getChecklistPathTemplate(),
                projectId,
                "/api/v2_2/project/{project_id}/checklist");
    }

    private String expandProjectPath(String template, String projectId, String fallback) {
        String value = StringUtils.hasText(template) ? template : fallback;
        return value.replace("{project_id}", projectId);
    }

    private String sourceKey(String kind, String externalId) {
        if (!StringUtils.hasText(externalId)) {
            return null;
        }
        return currentProviderKey() + ":" + kind + ":" + externalId.trim();
    }

    private String kindLabel(String kind) {
        return KIND_FUNCTIONAL_TEST.equals(kind) ? "Functional Performance Testing" : "Checklist";
    }

    private String paged(String path, int limit, int offset) {
        String separator = path.contains("?") ? "&" : "?";
        return path + separator + "limit=" + limit + "&offset=" + offset;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
