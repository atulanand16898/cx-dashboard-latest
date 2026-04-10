package com.cxalloy.integration.service;

import com.cxalloy.integration.client.FacilityGridApiClient;
import com.cxalloy.integration.config.FacilityGridApiProperties;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Issue;
import com.cxalloy.integration.repository.IssueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class FacilityGridIssueService extends BaseProjectService {

    private static final int PAGE_LIMIT = 5000;

    private final IssueRepository issueRepository;
    private final FacilityGridApiClient facilityGridApiClient;
    private final FacilityGridApiProperties facilityGridApiProperties;
    private final FacilityGridAuthService facilityGridAuthService;

    public FacilityGridIssueService(IssueRepository issueRepository,
                                    FacilityGridApiClient facilityGridApiClient,
                                    FacilityGridApiProperties facilityGridApiProperties,
                                    FacilityGridAuthService facilityGridAuthService) {
        this.issueRepository = issueRepository;
        this.facilityGridApiClient = facilityGridApiClient;
        this.facilityGridApiProperties = facilityGridApiProperties;
        this.facilityGridAuthService = facilityGridAuthService;
    }

    @Caching(evict = {
        @CacheEvict(value = "issues-by-project", allEntries = true),
        @CacheEvict(value = "issues-all", allEntries = true)
    })
    public SyncResult syncAllIssues(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        String endpoint = issueListPath(pid);
        ApiSyncLog log = startLog(endpoint, "GET");
        int totalSynced = 0;

        try {
            String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
            int offset = 0;
            while (true) {
                String pagePath = paged(endpoint, PAGE_LIMIT, offset);
                String raw = facilityGridApiClient.get(pagePath, accessToken);
                saveRaw(pagePath, "facilitygrid_issues_list_o" + offset, pid, raw);

                int count = parseAndSave(raw, pid);
                totalSynced += count;
                if (count < PAGE_LIMIT) {
                    break;
                }
                offset += PAGE_LIMIT;
            }

            long duration = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, duration, null);
            return new SyncResult(endpoint, "SUCCESS", totalSynced,
                    "Synced " + totalSynced + " Facility Grid issues", duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", totalSynced, duration, e.getMessage());
            throw new RuntimeException("Facility Grid issue sync failed: " + e.getMessage(), e);
        }
    }

    @Caching(evict = {
        @CacheEvict(value = "issues-by-project", allEntries = true),
        @CacheEvict(value = "issues-all", allEntries = true)
    })
    public SyncResult syncIssueById(String issueId, String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        String endpoint = issuePath(pid, issueId);
        ApiSyncLog log = startLog(endpoint, "GET");

        try {
            String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
            String raw = facilityGridApiClient.get(endpoint, accessToken);
            saveRaw(endpoint, "facilitygrid_issue_single", issueId, raw);

            JsonNode root = objectMapper.readTree(raw);
            Issue issue = map(root.isArray() ? root.get(0) : root, pid);
            upsertIssue(issue);

            long duration = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", 1, duration, null);
            return new SyncResult(endpoint, "SUCCESS", 1, "Facility Grid issue synced", duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, duration, e.getMessage());
            throw new RuntimeException("Facility Grid issue sync failed: " + e.getMessage(), e);
        }
    }

    private int parseAndSave(String json, String projectId) throws Exception {
        if (!StringUtils.hasText(json)) {
            return 0;
        }

        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new IllegalStateException("Facility Grid issues response was not an array");
        }

        List<Issue> issues = new ArrayList<>();
        for (JsonNode node : root) {
            issues.add(map(node, projectId));
        }
        issues.forEach(this::upsertIssue);
        return issues.size();
    }

    private Issue map(JsonNode node, String projectId) {
        Issue issue = new Issue();

        String externalId = getAsText(node, "uuid", null);
        issue.setExternalId(externalId);
        issue.setProvider(currentProviderKey());
        issue.setSourceKey(sourceKeyFor(externalId));
        issue.setProjectId(projectId);
        issue.setTitle(resolveTitle(node));
        issue.setDescription(getAsText(node, "description", null));

        String status = normalizeIssueStatus(node.get("status"));
        issue.setStatus(status);
        issue.setPriority(resolvePriority(node.get("priority")));

        JsonNode reportedBy = node.get("reported_by");
        issue.setAssignee(getAsText(node, "responsibility_name", null));
        issue.setReporter(firstNonBlank(
                getAsText(reportedBy, "email", null),
                getAsText(reportedBy, "uuid", null)));
        issue.setCreatedBy(getAsText(reportedBy, "uuid", null));
        issue.setDueDate(getAsText(node, "resolution_due", null));

        JsonNode assetNode = node.get("asset_uuid");
        issue.setAssetId(firstNonBlank(
                getAsText(node, "equipment_uuid", null),
                getAsText(assetNode, "uuid", null)));
        issue.setSourceType(getAsText(assetNode, "asset_type", null));
        issue.setSourceId(getAsText(assetNode, "uuid", null));

        issue.setLocation(getAsText(node, "location", null));
        issue.setActualFinishDate(isClosedStatus(status) ? getAsText(node, "last_status_modification", null) : null);
        issue.setCreatedAt(getAsText(node, "reported_on", null));
        issue.setUpdatedAt(getAsText(node, "last_status_modification", null));
        issue.setRawJson(node.toString());
        issue.setSyncedAt(now());
        return issue;
    }

    private void upsertIssue(Issue issue) {
        if (!StringUtils.hasText(issue.getExternalId())) {
            logger.warn("Skipping Facility Grid issue with null externalId");
            return;
        }

        issueRepository.findBySourceKey(issue.getSourceKey()).ifPresentOrElse(existing -> {
            existing.setProvider(issue.getProvider());
            existing.setSourceKey(issue.getSourceKey());
            existing.setProjectId(issue.getProjectId());
            existing.setTitle(issue.getTitle());
            existing.setDescription(issue.getDescription());
            existing.setStatus(issue.getStatus());
            existing.setPriority(issue.getPriority());
            existing.setAssignee(issue.getAssignee());
            existing.setReporter(issue.getReporter());
            existing.setCreatedBy(issue.getCreatedBy());
            existing.setDueDate(issue.getDueDate());
            existing.setAssetId(issue.getAssetId());
            existing.setSourceType(issue.getSourceType());
            existing.setSourceId(issue.getSourceId());
            existing.setLocation(issue.getLocation());
            existing.setActualFinishDate(issue.getActualFinishDate());
            existing.setCreatedAt(issue.getCreatedAt());
            existing.setUpdatedAt(issue.getUpdatedAt());
            existing.setRawJson(issue.getRawJson());
            existing.setSyncedAt(issue.getSyncedAt());
            issueRepository.save(existing);
        }, () -> issueRepository.save(issue));
    }

    private String resolveTitle(JsonNode node) {
        String code = getAsText(node, "issue_code", null);
        if (StringUtils.hasText(code)) {
            return code.trim();
        }
        JsonNode number = node.get("issue_number");
        if (number != null && !number.isNull()) {
            return "Issue #" + number.asText();
        }
        String description = getAsText(node, "description", null);
        if (StringUtils.hasText(description)) {
            String trimmed = description.trim();
            return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
        }
        return "Facility Grid Issue";
    }

    private String resolvePriority(JsonNode priorityNode) {
        if (priorityNode == null || priorityNode.isNull()) {
            return null;
        }
        if (priorityNode.isObject()) {
            return firstNonBlank(getAsText(priorityNode, "name", null), getAsText(priorityNode, "caption", null));
        }
        return priorityNode.asText(null);
    }

    private String normalizeIssueStatus(JsonNode statusNode) {
        String raw = null;
        if (statusNode != null && !statusNode.isNull()) {
            raw = statusNode.isObject()
                    ? firstNonBlank(getAsText(statusNode, "name", null), getAsText(statusNode, "caption", null))
                    : statusNode.asText(null);
        }
        if (!StringUtils.hasText(raw)) {
            return "open";
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "open", "opened", "issue_opened" -> "open";
            case "closed", "issue_closed" -> "issue_closed";
            case "accepted", "accepted_by_owner" -> "accepted_by_owner";
            case "ready_for_retest" -> "ready_for_retest";
            case "gc_to_verify" -> "gc_to_verify";
            case "cxa_to_verify" -> "cxa_to_verify";
            case "in_progress", "correction_in_progress" -> "correction_in_progress";
            case "additional_information_needed" -> "additional_information_needed";
            default -> normalized;
        };
    }

    private boolean isClosedStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        return switch (status) {
            case "issue_closed", "accepted_by_owner", "closed", "done", "resolved", "completed" -> true;
            default -> false;
        };
    }

    private String issueListPath(String projectId) {
        return expandProjectPath(facilityGridApiProperties.getIssuesPathTemplate(), projectId,
                "/api/v2_2/project/{project_id}/issues");
    }

    private String issuePath(String projectId, String issueId) {
        String template = facilityGridApiProperties.getIssuePathTemplate();
        if (!StringUtils.hasText(template)) {
            template = "/api/v2_2/project/{project_id}/issue/{issue_uuid}";
        }
        return template.replace("{project_id}", projectId).replace("{issue_uuid}", issueId);
    }

    private String expandProjectPath(String template, String projectId, String fallback) {
        String value = StringUtils.hasText(template) ? template : fallback;
        return value.replace("{project_id}", projectId);
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
