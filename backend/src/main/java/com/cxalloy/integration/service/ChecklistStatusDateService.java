package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.client.FacilityGridApiClient;
import com.cxalloy.integration.config.FacilityGridApiProperties;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Checklist;
import com.cxalloy.integration.model.ChecklistStatusDate;
import com.cxalloy.integration.model.DataProvider;
import com.cxalloy.integration.repository.ChecklistRepository;
import com.cxalloy.integration.repository.ChecklistStatusDateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@Transactional
public class ChecklistStatusDateService extends BaseProjectService {

    private static final int PAGE_SIZE = 500;
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("M/d/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("M/d/yyyy hh:mm:ss a", Locale.ENGLISH)
    );

    private final ChecklistRepository checklistRepository;
    private final ChecklistStatusDateRepository checklistStatusDateRepository;
    private final CxAlloyApiClient apiClient;
    private final FacilityGridApiClient facilityGridApiClient;
    private final FacilityGridApiProperties facilityGridApiProperties;
    private final FacilityGridAuthService facilityGridAuthService;

    public ChecklistStatusDateService(ChecklistRepository checklistRepository,
                                      ChecklistStatusDateRepository checklistStatusDateRepository,
                                      CxAlloyApiClient apiClient,
                                      FacilityGridApiClient facilityGridApiClient,
                                      FacilityGridApiProperties facilityGridApiProperties,
                                      FacilityGridAuthService facilityGridAuthService) {
        this.checklistRepository = checklistRepository;
        this.checklistStatusDateRepository = checklistStatusDateRepository;
        this.apiClient = apiClient;
        this.facilityGridApiClient = facilityGridApiClient;
        this.facilityGridApiProperties = facilityGridApiProperties;
        this.facilityGridAuthService = facilityGridAuthService;
    }

    @Transactional(readOnly = true)
    public Optional<ChecklistStatusDate> getOne(String projectId, String checklistExternalId) {
        return checklistStatusDateRepository.findByProjectIdAndChecklistExternalIdAndProvider(
                resolveProjectId(projectId),
                checklistExternalId,
                currentProviderKey());
    }

    @Transactional(readOnly = true)
    public List<ChecklistStatusDate> getByProject(String projectId) {
        return checklistStatusDateRepository.findByProjectIdAndProvider(resolveProjectId(projectId), currentProviderKey());
    }

    public SyncResult syncOne(String projectId, String checklistExternalId) {
        return currentProvider() == DataProvider.FACILITY_GRID
                ? syncOneFacilityGrid(projectId, checklistExternalId)
                : syncOneCxAlloy(projectId, checklistExternalId);
    }

    public SyncResult syncProject(String projectId) {
        return currentProvider() == DataProvider.FACILITY_GRID
                ? syncProjectFacilityGrid(projectId)
                : syncProjectCxAlloy(projectId);
    }

    private SyncResult syncOneCxAlloy(String projectId, String checklistExternalId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        Checklist checklist = checklistRepository.findByExternalIdAndProvider(checklistExternalId, currentProviderKey())
                .filter(row -> pid.equals(row.getProjectId()))
                .orElseThrow(() -> new IllegalArgumentException("Checklist not found for project " + pid + ": " + checklistExternalId));

        JsonNode sectionNode = findChecklistSectionNode(pid, checklist);
        if (sectionNode == null) {
            throw new IllegalStateException("No checklistsection row found for checklist " + checklistExternalId + " in project " + pid);
        }

        ChecklistStatusDate record = checklistStatusDateRepository
                .findByProjectIdAndChecklistExternalIdAndProvider(pid, checklistExternalId, currentProviderKey())
                .orElseGet(ChecklistStatusDate::new);

        populateCxAlloyRecord(record, pid, checklist, sectionNode);
        checklistStatusDateRepository.save(record);

        long duration = System.currentTimeMillis() - start;
        String message = "Stored status dates for checklist " + checklistExternalId
                + " using checklistsection status=" + record.getLastKnownStatus()
                + " status_change_date=" + record.getSourceStatusChangeRaw();
        return new SyncResult("/checklistsection", "SUCCESS", 1, message, duration);
    }

    private SyncResult syncProjectCxAlloy(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        List<Checklist> checklists = checklistRepository.findByProjectId(pid).stream()
                .filter(checklist -> providerContextService.matchesCurrentProvider(checklist.getProvider()))
                .toList();

        Map<String, Checklist> byExternalId = new HashMap<>();
        Map<String, Checklist> byName = new HashMap<>();
        for (Checklist checklist : checklists) {
            if (checklist.getExternalId() != null) {
                byExternalId.put(checklist.getExternalId(), checklist);
            }
            if (checklist.getName() != null) {
                byName.put(checklist.getName(), checklist);
            }
        }

        List<JsonNode> sectionNodes = fetchChecklistSectionNodes(pid);
        int synced = 0;
        for (JsonNode node : sectionNodes) {
            Checklist checklist = resolveChecklist(node, byExternalId, byName);
            if (checklist == null) {
                continue;
            }
            ChecklistStatusDate record = checklistStatusDateRepository
                    .findByProjectIdAndChecklistExternalIdAndProvider(pid, checklist.getExternalId(), currentProviderKey())
                    .orElseGet(ChecklistStatusDate::new);
            populateCxAlloyRecord(record, pid, checklist, node);
            checklistStatusDateRepository.save(record);
            synced++;
        }

        long duration = System.currentTimeMillis() - start;
        return new SyncResult("/checklistsection", "SUCCESS", synced,
                "Stored status dates for " + synced + " checklists in project " + pid, duration);
    }

    private SyncResult syncOneFacilityGrid(String projectId, String checklistExternalId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        long deadline = start + TimeUnit.SECONDS.toMillis(checklistStatusSyncTimeoutSeconds());
        Checklist checklist = checklistRepository.findByExternalIdAndProvider(checklistExternalId, currentProviderKey())
                .filter(row -> pid.equals(row.getProjectId()))
                .orElseThrow(() -> new IllegalArgumentException("Checklist not found for project " + pid + ": " + checklistExternalId));

        ensureFacilityGridStatusSyncWithinDeadline(deadline);
        String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
        List<JsonNode> detailNodes = fetchFacilityGridDetailNodes(pid, checklist, accessToken, deadline);

        ChecklistStatusDate record = checklistStatusDateRepository
                .findByProjectIdAndChecklistExternalIdAndProvider(pid, checklistExternalId, currentProviderKey())
                .orElseGet(ChecklistStatusDate::new);
        populateFacilityGridRecord(record, pid, checklist, detailNodes);
        checklistStatusDateRepository.save(record);

        long duration = System.currentTimeMillis() - start;
        return new SyncResult("/facilitygrid/checklist-details", "SUCCESS", 1,
                "Stored status dates for Facility Grid checklist " + checklistExternalId, duration);
    }

    private SyncResult syncProjectFacilityGrid(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        long deadline = start + TimeUnit.SECONDS.toMillis(checklistStatusSyncTimeoutSeconds());
        List<Checklist> checklists = checklistRepository.findByProjectId(pid).stream()
                .filter(checklist -> providerContextService.matchesCurrentProvider(checklist.getProvider()))
                .toList();

        ensureFacilityGridStatusSyncWithinDeadline(deadline);
        String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
        int synced = 0;
        for (Checklist checklist : checklists) {
            ensureFacilityGridStatusSyncWithinDeadline(deadline);
            List<JsonNode> detailNodes = fetchFacilityGridDetailNodes(pid, checklist, accessToken, deadline);
            ChecklistStatusDate record = checklistStatusDateRepository
                    .findByProjectIdAndChecklistExternalIdAndProvider(pid, checklist.getExternalId(), currentProviderKey())
                    .orElseGet(ChecklistStatusDate::new);
            populateFacilityGridRecord(record, pid, checklist, detailNodes);
            checklistStatusDateRepository.save(record);
            synced++;
        }

        long duration = System.currentTimeMillis() - start;
        return new SyncResult("/facilitygrid/checklist-details", "SUCCESS", synced,
                "Stored status dates for " + synced + " Facility Grid checklist rows in project " + pid, duration);
    }

    private void populateCxAlloyRecord(ChecklistStatusDate record, String projectId, Checklist checklist, JsonNode sectionNode) {
        record.setProvider(currentProviderKey());
        record.setProjectId(projectId);
        record.setChecklistExternalId(checklist.getExternalId());
        record.setChecklistName(checklist.getName());
        record.setSourceChecklistSectionId(getAsText(sectionNode, "checklistsection_id", null));
        record.setSourceStatusChangeRaw(firstNonBlank(
                getAsText(sectionNode, "date_closed", null),
                getAsText(sectionNode, "status_change_date", null)));
        record.setSourceDateCreatedRaw(getAsText(sectionNode, "date_created", null));
        record.setSourcePayload(sectionNode.toString());

        String normalizedStatus = normalizeChecklistSectionStatus(getAsText(sectionNode, "status", null));
        record.setLastKnownStatus(normalizedStatus);

        LocalDate statusChangeDate = parseDate(firstNonBlank(
                getAsText(sectionNode, "date_closed", null),
                getAsText(sectionNode, "status_change_date", null)));
        if (statusChangeDate != null) {
            applyStatusDate(record, normalizedStatus, statusChangeDate);
        }
        record.setSyncedAt(now());
    }

    private void populateFacilityGridRecord(ChecklistStatusDate record, String projectId, Checklist checklist, List<JsonNode> detailNodes) {
        record.setProvider(currentProviderKey());
        record.setProjectId(projectId);
        record.setChecklistExternalId(checklist.getExternalId());
        record.setChecklistName(checklist.getName());
        record.setSourceChecklistSectionId(checklist.getSourceKey());
        record.setSourceDateCreatedRaw(checklist.getCreatedAt());
        record.setSourcePayload(buildPayload(checklist, detailNodes));

        String normalizedStatus = normalizeChecklistSectionStatus(checklist.getStatus());
        record.setLastKnownStatus(normalizedStatus);

        String modifiedOn = firstNonBlank(
                maxDateString(detailNodes, "modified_on"),
                maxDateString(detailNodes, "signed_off_on_datetime"),
                checklist.getUpdatedAt(),
                checklist.getCreatedAt(),
                checklist.getActualFinishDate());
        record.setSourceStatusChangeRaw(modifiedOn);

        LocalDate candidateDate = parseDate(firstNonBlank(
                maxDateString(detailNodes, "signed_off_on_datetime"),
                checklist.getActualFinishDate(),
                modifiedOn));
        if (candidateDate != null) {
            applyStatusDate(record, normalizedStatus, candidateDate);
        }
        record.setSyncedAt(now());
    }

    private void applyStatusDate(ChecklistStatusDate record, String normalizedStatus, LocalDate candidateDate) {
        switch (normalizedStatus) {
            case "open" -> record.setLatestOpenDate(maxDate(record.getLatestOpenDate(), candidateDate));
            case "in_progress" -> record.setLatestInProgressDate(maxDate(record.getLatestInProgressDate(), candidateDate));
            case "finished" -> record.setLatestFinishedDate(maxDate(record.getLatestFinishedDate(), candidateDate));
            default -> { }
        }
    }

    private JsonNode findChecklistSectionNode(String projectId, Checklist checklist) {
        for (JsonNode node : fetchChecklistSectionNodes(projectId)) {
            String sectionId = getAsText(node, "checklistsection_id", null);
            String sectionName = getAsText(node, "section_name", null);
            if (checklist.getExternalId().equals(sectionId) || checklist.getName().equals(sectionName)) {
                return node;
            }
        }
        return null;
    }

    private List<JsonNode> fetchChecklistSectionNodes(String projectId) {
        List<JsonNode> nodes = new ArrayList<>();
        int page = 1;
        while (page <= 50) {
            String path = page == 1
                    ? "/checklistsection?project_id=" + projectId
                    : "/checklistsection?project_id=" + projectId + "&page=" + page;
            String raw = apiClient.get(path);
            JsonNode root;
            try {
                root = objectMapper.readTree(raw);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse checklistsection response", e);
            }

            JsonNode data = extractData(root, "/checklistsection project=" + projectId + " page=" + page);
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            data.forEach(nodes::add);
            if (data.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return nodes;
    }

    private List<JsonNode> fetchFacilityGridDetailNodes(String projectId, Checklist checklist, String accessToken, long deadline) {
        try {
            ensureFacilityGridStatusSyncWithinDeadline(deadline);
            String raw = facilityGridApiClient.get(facilityGridDetailPath(projectId, checklist), accessToken);
            JsonNode root = objectMapper.readTree(raw);
            if (!root.isArray()) {
                return List.of();
            }
            List<JsonNode> nodes = new ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        } catch (Exception e) {
            logger.warn("Facility Grid checklist detail fetch failed for {} in project {}: {}",
                    checklist.getExternalId(), projectId, e.getMessage());
            return List.of();
        }
    }

    private void ensureFacilityGridStatusSyncWithinDeadline(long deadline) {
        if (System.currentTimeMillis() <= deadline) {
            return;
        }
        throw new RuntimeException("Facility Grid checklist status sync timed out after "
                + checklistStatusSyncTimeoutSeconds() + " seconds. Try syncing again.");
    }

    private int checklistStatusSyncTimeoutSeconds() {
        return Math.max(15, facilityGridApiProperties.getChecklistStatusSyncTimeoutSeconds());
    }

    private String facilityGridDetailPath(String projectId, Checklist checklist) {
        boolean functionalTest = checklist.getSourceKey() != null && checklist.getSourceKey().contains(":functional_test:");
        String template = functionalTest
                ? facilityGridApiProperties.getFunctionalTestDetailsPathTemplate()
                : facilityGridApiProperties.getChecklistDetailsPathTemplate();

        String fallback = functionalTest
                ? "/api/v2_2/project/{project_id}/functional_test/{test_uuid}/details"
                : "/api/v2_2/project/{project_id}/checklist/{test_uuid}/details";

        String path = StringUtils.hasText(template) ? template : fallback;
        return path.replace("{project_id}", projectId).replace("{test_uuid}", checklist.getExternalId());
    }

    private Checklist resolveChecklist(JsonNode node, Map<String, Checklist> byExternalId, Map<String, Checklist> byName) {
        String sectionId = getAsText(node, "checklistsection_id", null);
        if (sectionId != null && byExternalId.containsKey(sectionId)) {
            return byExternalId.get(sectionId);
        }
        String sectionName = getAsText(node, "section_name", null);
        if (sectionName != null && byName.containsKey(sectionName)) {
            return byName.get(sectionName);
        }
        return null;
    }

    private String normalizeChecklistSectionStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "unknown";
        }
        String lower = rawStatus.trim().toLowerCase(Locale.ROOT).replace(" ", "_").replace("-", "_");
        return switch (lower) {
            case "not_started", "open", "new" -> "open";
            case "started", "in_progress", "inprogress", "active", "returned_with_comments" -> "in_progress";
            case "finished", "complete", "completed", "approved", "checklist_approved", "closed", "passed", "signed_off", "tag_complete" -> "finished";
            default -> lower;
        };
    }

    private LocalDate maxDate(LocalDate existing, LocalDate candidate) {
        if (candidate == null) {
            return existing;
        }
        if (existing == null || candidate.isAfter(existing)) {
            return candidate;
        }
        return existing;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (Exception ignored) {
            }
            try {
                return LocalDateTime.parse(value, formatter).toLocalDate();
            } catch (Exception ignored) {
            }
            try {
                return OffsetDateTime.parse(value, formatter).toLocalDate();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String maxDateString(List<JsonNode> nodes, String fieldName) {
        String best = null;
        LocalDate bestDate = null;
        for (JsonNode node : nodes) {
            String candidate = getAsText(node, fieldName, null);
            LocalDate parsed = parseDate(candidate);
            if (parsed != null && (bestDate == null || parsed.isAfter(bestDate))) {
                bestDate = parsed;
                best = candidate;
            }
        }
        return best;
    }

    private String buildPayload(Checklist checklist, List<JsonNode> detailNodes) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("checklist", objectMapper.readTree(checklist.getRawJson()));
            payload.put("details", detailNodes);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return checklist.getRawJson();
        }
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
