package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Checklist;
import com.cxalloy.integration.model.ChecklistStatusDate;
import com.cxalloy.integration.repository.ChecklistRepository;
import com.cxalloy.integration.repository.ChecklistStatusDateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ChecklistStatusDateService(
            ChecklistRepository checklistRepository,
            ChecklistStatusDateRepository checklistStatusDateRepository,
            CxAlloyApiClient apiClient) {
        this.checklistRepository = checklistRepository;
        this.checklistStatusDateRepository = checklistStatusDateRepository;
        this.apiClient = apiClient;
    }

    @Transactional(readOnly = true)
    public Optional<ChecklistStatusDate> getOne(String projectId, String checklistExternalId) {
        return checklistStatusDateRepository.findByProjectIdAndChecklistExternalId(resolveProjectId(projectId), checklistExternalId);
    }

    @Transactional(readOnly = true)
    public List<ChecklistStatusDate> getByProject(String projectId) {
        return checklistStatusDateRepository.findByProjectId(resolveProjectId(projectId));
    }

    public SyncResult syncOne(String projectId, String checklistExternalId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        Checklist checklist = checklistRepository.findByExternalId(checklistExternalId)
                .filter(row -> pid.equals(row.getProjectId()))
                .orElseThrow(() -> new IllegalArgumentException("Checklist not found for project " + pid + ": " + checklistExternalId));

        JsonNode sectionNode = findChecklistSectionNode(pid, checklist);
        if (sectionNode == null) {
            throw new IllegalStateException("No checklistsection row found for checklist " + checklistExternalId + " in project " + pid);
        }

        ChecklistStatusDate record = checklistStatusDateRepository
                .findByProjectIdAndChecklistExternalId(pid, checklistExternalId)
                .orElseGet(ChecklistStatusDate::new);

        record.setProjectId(pid);
        record.setChecklistExternalId(checklistExternalId);
        record.setChecklistName(checklist.getName());
        record.setSourceChecklistSectionId(getAsText(sectionNode, "checklistsection_id", null));
        record.setSourceStatusChangeRaw(getAsText(sectionNode, "status_change_date", null));
        record.setSourceDateCreatedRaw(getAsText(sectionNode, "date_created", null));
        record.setSourcePayload(sectionNode.toString());

        String rawStatus = getAsText(sectionNode, "status", null);
        String normalizedStatus = normalizeChecklistSectionStatus(rawStatus);
        record.setLastKnownStatus(normalizedStatus);

        LocalDate statusChangeDate = parseDate(getAsText(sectionNode, "status_change_date", null));
        if (statusChangeDate != null) {
            switch (normalizedStatus) {
                case "open" -> record.setLatestOpenDate(maxDate(record.getLatestOpenDate(), statusChangeDate));
                case "in_progress" -> record.setLatestInProgressDate(maxDate(record.getLatestInProgressDate(), statusChangeDate));
                case "finished" -> record.setLatestFinishedDate(maxDate(record.getLatestFinishedDate(), statusChangeDate));
                default -> { }
            }
        }

        record.setSyncedAt(now());
        checklistStatusDateRepository.save(record);

        long duration = System.currentTimeMillis() - start;
        String message = "Stored status dates for checklist " + checklistExternalId
                + " using checklistsection status=" + normalizedStatus
                + " status_change_date=" + record.getSourceStatusChangeRaw();
        return new SyncResult("/checklistsection", "SUCCESS", 1, message, duration);
    }

    public SyncResult syncProject(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        List<Checklist> checklists = checklistRepository.findByProjectId(pid);
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
            upsertStatusDate(pid, checklist, node);
            synced++;
        }

        long duration = System.currentTimeMillis() - start;
        return new SyncResult("/checklistsection", "SUCCESS", synced,
                "Stored status dates for " + synced + " checklists in project " + pid, duration);
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

    private void upsertStatusDate(String projectId, Checklist checklist, JsonNode sectionNode) {
        ChecklistStatusDate record = checklistStatusDateRepository
                .findByProjectIdAndChecklistExternalId(projectId, checklist.getExternalId())
                .orElseGet(ChecklistStatusDate::new);

        record.setProjectId(projectId);
        record.setChecklistExternalId(checklist.getExternalId());
        record.setChecklistName(checklist.getName());
        record.setSourceChecklistSectionId(getAsText(sectionNode, "checklistsection_id", null));
        record.setSourceStatusChangeRaw(getAsText(sectionNode, "status_change_date", null));
        record.setSourceDateCreatedRaw(getAsText(sectionNode, "date_created", null));
        record.setSourcePayload(sectionNode.toString());

        String rawStatus = getAsText(sectionNode, "status", null);
        String normalizedStatus = normalizeChecklistSectionStatus(rawStatus);
        record.setLastKnownStatus(normalizedStatus);

        LocalDate statusChangeDate = parseDate(getAsText(sectionNode, "status_change_date", null));
        if (statusChangeDate != null) {
            switch (normalizedStatus) {
                case "open" -> record.setLatestOpenDate(maxDate(record.getLatestOpenDate(), statusChangeDate));
                case "in_progress" -> record.setLatestInProgressDate(maxDate(record.getLatestInProgressDate(), statusChangeDate));
                case "finished" -> record.setLatestFinishedDate(maxDate(record.getLatestFinishedDate(), statusChangeDate));
                default -> { }
            }
        }

        record.setSyncedAt(now());
        checklistStatusDateRepository.save(record);
    }

    private String normalizeChecklistSectionStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "unknown";
        }
        String lower = rawStatus.trim().toLowerCase(Locale.ROOT).replace(" ", "_").replace("-", "_");
        return switch (lower) {
            case "not_started", "open", "new" -> "open";
            case "started", "in_progress", "inprogress", "active" -> "in_progress";
            case "finished", "complete", "completed", "approved", "checklist_approved", "closed" -> "finished";
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
}
