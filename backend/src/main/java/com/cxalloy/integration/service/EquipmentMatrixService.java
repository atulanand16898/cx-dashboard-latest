package com.cxalloy.integration.service;

import com.cxalloy.integration.dto.EquipmentMatrixDto;
import com.cxalloy.integration.dto.EquipmentMatrixDto.ChecklistLevelStat;
import com.cxalloy.integration.dto.EquipmentMatrixDto.EquipmentRow;
import com.cxalloy.integration.model.Checklist;
import com.cxalloy.integration.model.Equipment;
import com.cxalloy.integration.model.Issue;
import com.cxalloy.integration.repository.ChecklistRepository;
import com.cxalloy.integration.repository.EquipmentRepository;
import com.cxalloy.integration.repository.IssueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class EquipmentMatrixService {

    private final EquipmentRepository equipmentRepository;
    private final ChecklistRepository checklistRepository;
    private final IssueRepository issueRepository;
    private final ObjectMapper objectMapper;
    private final ProviderContextService providerContextService;

    public EquipmentMatrixService(
            EquipmentRepository equipmentRepository,
            ChecklistRepository checklistRepository,
            IssueRepository issueRepository,
            ObjectMapper objectMapper,
            ProviderContextService providerContextService) {
        this.equipmentRepository = equipmentRepository;
        this.checklistRepository = checklistRepository;
        this.issueRepository = issueRepository;
        this.objectMapper = objectMapper;
        this.providerContextService = providerContextService;
    }

    public EquipmentMatrixDto buildMatrix(String projectId) {
        List<Equipment> equipmentList = projectId != null
                ? equipmentRepository.findByProjectId(projectId)
                : equipmentRepository.findAll();
        equipmentList = equipmentList.stream()
                .filter(equipment -> providerContextService.matchesCurrentProvider(equipment.getProvider()))
                .toList();

        List<Checklist> checklists = projectId != null
                ? checklistRepository.findByProjectId(projectId)
                : checklistRepository.findAll();
        checklists = checklists.stream()
                .filter(checklist -> providerContextService.matchesCurrentProvider(checklist.getProvider()))
                .toList();

        List<Issue> issues = projectId != null
                ? issueRepository.findByProjectId(projectId)
                : issueRepository.findAll();
        issues = issues.stream()
                .filter(issue -> providerContextService.matchesCurrentProvider(issue.getProvider()))
                .toList();

        Map<String, List<Checklist>> checklistsByAssetId = new LinkedHashMap<>();
        Map<String, List<Checklist>> checklistsByAssetName = new LinkedHashMap<>();
        Map<String, List<Checklist>> checklistsByPrefix = new LinkedHashMap<>();
        for (Checklist checklist : checklists) {
            index(checklistsByAssetId, resolveChecklistAssetId(checklist), checklist);
            index(checklistsByAssetName, resolveChecklistAssetName(checklist), checklist);
            index(checklistsByPrefix, extractChecklistPrefix(checklist.getName()), checklist);
        }

        Map<String, List<Issue>> issuesByAssetId = new LinkedHashMap<>();
        Map<String, List<Issue>> issuesByAssetName = new LinkedHashMap<>();
        Map<String, List<Issue>> issuesByChecklistId = new LinkedHashMap<>();
        for (Issue issue : issues) {
            index(issuesByAssetId, resolveIssueAssetId(issue), issue);
            index(issuesByAssetName, resolveIssueAssetName(issue), issue);
            if ("checklist".equalsIgnoreCase(blankToNull(issue.getSourceType()))) {
                index(issuesByChecklistId, blankToNull(issue.getSourceId()), issue);
            } else {
                index(issuesByChecklistId, resolveIssueSourceId(issue), issue);
            }
        }

        List<EquipmentRow> rows = new ArrayList<>();
        for (Equipment equipment : equipmentList) {
            List<Checklist> equipmentChecklists = resolveEquipmentChecklists(
                    equipment, checklistsByAssetId, checklistsByAssetName, checklistsByPrefix);
            Map<String, List<Checklist>> byTag = splitChecklistsByTag(equipmentChecklists);
            List<Issue> equipmentIssues = resolveEquipmentIssues(
                    equipment, equipmentChecklists, issuesByAssetId, issuesByAssetName, issuesByChecklistId);
            TestCounts testCounts = resolveTestCounts(equipment);

            EquipmentRow row = new EquipmentRow();
            row.setEquipmentId(String.valueOf(equipment.getId()));
            row.setExternalId(firstNonBlank(equipment.getExternalId(), equipment.getTag(), String.valueOf(equipment.getId())));
            row.setName(equipment.getName());
            row.setTag(equipment.getTag());
            row.setDescription(equipment.getDescription());
            row.setStatus(resolveEquipmentStatus(equipment.getStatus()));
            row.setSystemName(resolveSystemDisplayName(equipment));
            row.setAssignedTo(resolveAssignedTo(equipment, equipmentChecklists));
            row.setSpaceName(resolveEquipmentSpaceName(equipment));
            row.setEquipmentType(equipment.getEquipmentType());
            row.setDiscipline(equipment.getDiscipline());

            row.setL1Checklist(buildChecklistStat(byTag.getOrDefault("red", Collections.emptyList()), issuesByChecklistId));
            row.setL2Checklist(buildChecklistStat(byTag.getOrDefault("yellow", Collections.emptyList()), issuesByChecklistId));
            row.setL3Checklist(buildChecklistStat(byTag.getOrDefault("green", Collections.emptyList()), issuesByChecklistId));

            int checklistTotal = equipmentChecklists.size();
            int checklistClosed = (int) equipmentChecklists.stream().filter(this::isChecklistClosed).count();
            int issuesClosed = (int) equipmentIssues.stream().filter(this::isIssueClosed).count();

            row.setChecklistTotal(checklistTotal);
            row.setChecklistClosed(checklistClosed);
            row.setIssuesClosed(issuesClosed);
            row.setIssuesOpen(Math.max(0, equipmentIssues.size() - issuesClosed));
            row.setTestsTotal(testCounts.total);
            row.setTestsClosed(testCounts.closed);
            row.setTotal(checklistTotal);

            rows.add(row);
        }

        rows.sort(Comparator
                .comparing((EquipmentRow row) -> blankToDefault(row.getSystemName(), "zzzz"), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> blankToDefault(row.getName(), blankToDefault(row.getTag(), row.getExternalId())), String.CASE_INSENSITIVE_ORDER));

        EquipmentMatrixDto dto = new EquipmentMatrixDto();
        dto.setRows(rows);
        dto.setTotalUnits(rows.size());
        dto.setTotalSystems((int) rows.stream()
                .map(EquipmentRow::getSystemName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .count());
        dto.setTotalTypes((int) rows.stream()
                .map(EquipmentRow::getEquipmentType)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .count());
        return dto;
    }

    private Map<String, List<Checklist>> splitChecklistsByTag(List<Checklist> equipmentChecklists) {
        Map<String, List<Checklist>> byTag = new LinkedHashMap<>();
        byTag.put("red", new ArrayList<>());
        byTag.put("yellow", new ArrayList<>());
        byTag.put("green", new ArrayList<>());
        for (Checklist checklist : equipmentChecklists) {
            String tag = resolveChecklistMatrixTag(checklist);
            if (tag != null) {
                byTag.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(checklist);
            }
        }
        return byTag;
    }

    private List<Checklist> resolveEquipmentChecklists(
            Equipment equipment,
            Map<String, List<Checklist>> checklistsByAssetId,
            Map<String, List<Checklist>> checklistsByAssetName,
            Map<String, List<Checklist>> checklistsByPrefix) {
        LinkedHashMap<String, Checklist> result = new LinkedHashMap<>();
        addChecklists(result, checklistsByAssetId.get(equipment.getExternalId()));
        addChecklists(result, checklistsByAssetId.get(equipment.getTag()));
        addChecklists(result, checklistsByAssetName.get(equipment.getName()));
        addChecklists(result, checklistsByAssetName.get(equipment.getTag()));
        addChecklists(result, checklistsByPrefix.get(equipment.getName()));
        addChecklists(result, checklistsByPrefix.get(equipment.getTag()));
        return new ArrayList<>(result.values());
    }

    private List<Issue> resolveEquipmentIssues(
            Equipment equipment,
            List<Checklist> equipmentChecklists,
            Map<String, List<Issue>> issuesByAssetId,
            Map<String, List<Issue>> issuesByAssetName,
            Map<String, List<Issue>> issuesByChecklistId) {
        LinkedHashMap<String, Issue> result = new LinkedHashMap<>();
        addIssues(result, issuesByAssetId.get(equipment.getExternalId()));
        addIssues(result, issuesByAssetId.get(equipment.getTag()));
        addIssues(result, issuesByAssetName.get(equipment.getName()));
        addIssues(result, issuesByAssetName.get(equipment.getTag()));
        for (Checklist checklist : equipmentChecklists) {
            addIssues(result, issuesByChecklistId.get(blankToNull(checklist.getExternalId())));
            addIssues(result, issuesByChecklistId.get(resolveChecklistExternalId(checklist)));
        }
        return new ArrayList<>(result.values());
    }

    private ChecklistLevelStat buildChecklistStat(
            List<Checklist> checklists,
            Map<String, List<Issue>> issuesByChecklistId) {
        ChecklistLevelStat stat = new ChecklistLevelStat();
        if (checklists == null || checklists.isEmpty()) {
            stat.setStatusLabel("Not Started");
            return stat;
        }

        int total = checklists.size();
        int closed = (int) checklists.stream().filter(this::isChecklistClosed).count();
        int returned = (int) checklists.stream()
                .filter(checklist -> "returned_with_comments".equals(checklist.getStatus()))
                .count();
        int inProgress = (int) checklists.stream()
                .filter(checklist -> "in_progress".equals(checklist.getStatus()))
                .count();

        LinkedHashMap<String, Issue> linkedIssues = new LinkedHashMap<>();
        for (Checklist checklist : checklists) {
            addIssues(linkedIssues, issuesByChecklistId.get(blankToNull(checklist.getExternalId())));
            addIssues(linkedIssues, issuesByChecklistId.get(resolveChecklistExternalId(checklist)));
        }

        int issuesClosed = (int) linkedIssues.values().stream().filter(this::isIssueClosed).count();

        stat.setTotal(total);
        stat.setClosed(closed);
        stat.setIssueCount(linkedIssues.size());
        stat.setIssuesClosed(issuesClosed);
        stat.setCompletionPct(total > 0 ? (int) Math.round(100.0 * closed / total) : 0);

        if (closed == total) {
            stat.setStatusLabel("Checklist Approved");
        } else if (returned > 0 && (closed + returned) == total) {
            stat.setStatusLabel("Returned with Comments");
        } else if (returned > 0 || closed > 0 || inProgress > 0) {
            stat.setStatusLabel("In Progress");
        } else {
            stat.setStatusLabel("Not Started");
        }
        return stat;
    }

    private boolean isChecklistClosed(Checklist checklist) {
        String status = blankToNull(checklist.getStatus());
        if (status == null) return false;
        String normalized = status.toLowerCase(Locale.ROOT);
        return normalized.equals("checklist_approved")
                || normalized.equals("tag_complete")
                || normalized.equals("complete")
                || normalized.equals("completed")
                || normalized.equals("finished")
                || normalized.equals("approved")
                || normalized.equals("signed_off")
                || normalized.equals("closed")
                || normalized.equals("done")
                || normalized.equals("accepted_by_owner");
    }

    private boolean isIssueClosed(Issue issue) {
        String status = blankToNull(issue.getStatus());
        if (status == null) return false;
        String normalized = status.toLowerCase(Locale.ROOT);
        return normalized.equals("issue_closed")
                || normalized.equals("accepted_by_owner")
                || normalized.equals("closed")
                || normalized.equals("done");
    }

    private String resolveChecklistMatrixTag(Checklist checklist) {
        String[] candidates = {
                blankToNull(checklist.getTagLevel()),
                blankToNull(checklist.getName()),
                blankToNull(checklist.getChecklistType()),
                extractTextFromRaw(checklist.getRawJson(), "type_name"),
                extractTextFromRaw(checklist.getRawJson(), "checklist_type"),
                extractTextFromRaw(checklist.getRawJson(), "name")
        };
        for (String candidate : candidates) {
            String tag = matchMatrixTag(candidate);
            if (tag != null) return tag;
        }
        return null;
    }

    private String matchMatrixTag(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT).trim();
        if (lower.matches(".*\\bl2[-_\\s]?a\\b.*") || lower.matches(".*\\blevel[-_\\s]?2[-_\\s]?a\\b.*")) return "red";
        if (lower.matches(".*\\bl2[-_\\s]?b\\b.*") || lower.matches(".*\\blevel[-_\\s]?2[-_\\s]?b\\b.*")) return "yellow";
        if (lower.matches(".*\\bl1\\b.*") || lower.contains("level-1") || lower.contains("level 1")) return "red";
        if (lower.matches(".*\\bl2\\b.*") || lower.contains("level-2") || lower.contains("level 2")) return "yellow";
        if (lower.matches(".*\\bl3\\b.*") || lower.contains("level-3") || lower.contains("level 3")) return "green";
        if ("red".equals(lower) || lower.matches(".*\\bred\\b.*")) return "red";
        if ("yellow".equals(lower) || lower.matches(".*\\byellow\\b.*")) return "yellow";
        if ("green".equals(lower) || lower.matches(".*\\bgreen\\b.*")) return "green";
        return null;
    }

    private String resolveEquipmentStatus(String status) {
        return blankToDefault(status, "Not Assigned");
    }

    private String resolveSystemDisplayName(Equipment equipment) {
        String systemName = firstNonBlank(
                blankToNull(equipment.getSystemName()),
                extractEquipmentSystemNameFromRaw(equipment.getRawJson()));
        String equipmentType = blankToNull(equipment.getEquipmentType());
        if (systemName != null && equipmentType != null) {
            String sys = systemName.toLowerCase(Locale.ROOT);
            String type = equipmentType.toLowerCase(Locale.ROOT);
            if (type.contains(sys)) return equipmentType;
            if (sys.contains(type)) return systemName;
            return systemName;
        }
        return firstNonBlank(systemName, equipmentType, equipment.getDiscipline(), equipment.getBuildingId(), "General");
    }

    private String resolveAssignedTo(Equipment equipment, List<Checklist> equipmentChecklists) {
        for (Checklist checklist : equipmentChecklists) {
            String assignedTo = blankToNull(checklist.getAssignedTo());
            if (assignedTo != null) return assignedTo;
            String assignedName = extractTextFromRaw(checklist.getRawJson(), "assigned_name");
            if (assignedName != null) return assignedName;
        }
        return firstNonBlank(
                extractTextFromRaw(equipment.getRawJson(), "assigned_name"),
                extractTextFromRaw(equipment.getRawJson(), "space"),
                blankToNull(equipment.getSpaceId()),
                blankToNull(equipment.getSystemName()));
    }

    private String resolveEquipmentSpaceName(Equipment equipment) {
        return firstNonBlank(
                extractTextFromRaw(equipment.getRawJson(), "space"),
                blankToNull(equipment.getSpaceId()));
    }

    private TestCounts resolveTestCounts(Equipment equipment) {
        if (equipment.getRawJson() == null || equipment.getRawJson().isBlank()) {
            int count = equipment.getTestCount() != null ? equipment.getTestCount() : 0;
            return new TestCounts(count, 0);
        }

        try {
            JsonNode root = objectMapper.readTree(equipment.getRawJson());
            if (root.has("tests") && root.get("tests").isArray()) {
                int total = root.get("tests").size();
                int closed = 0;
                for (JsonNode test : root.get("tests")) {
                    String status = null;
                    if (test.has("status")) status = test.get("status").asText();
                    if (test.has("state") && (status == null || status.isBlank())) status = test.get("state").asText();
                    if (status != null && isClosedStatus(status)) closed++;
                }
                return new TestCounts(total, closed);
            }
            int total = firstPositiveInt(
                    equipment.getTestCount(),
                    intFromJson(root, "test_count"),
                    intFromJson(root, "tests_count"));
            return new TestCounts(total, 0);
        } catch (Exception ignored) {
            int total = equipment.getTestCount() != null ? equipment.getTestCount() : 0;
            return new TestCounts(total, 0);
        }
    }

    private boolean isClosedStatus(String status) {
        String normalized = status.toLowerCase(Locale.ROOT).replace(" ", "_").replace("-", "_");
        return normalized.equals("complete")
                || normalized.equals("completed")
                || normalized.equals("finished")
                || normalized.equals("approved")
                || normalized.equals("closed")
                || normalized.equals("passed")
                || normalized.equals("done");
    }

    private int intFromJson(JsonNode root, String fieldName) {
        if (!root.has(fieldName) || root.get(fieldName).isNull()) return 0;
        try {
            return root.get(fieldName).asInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int firstPositiveInt(Integer... candidates) {
        for (Integer candidate : candidates) {
            if (candidate != null && candidate > 0) return candidate;
        }
        return 0;
    }

    private String resolveChecklistAssetId(Checklist checklist) {
        return firstNonBlank(
                blankToNull(checklist.getAssetId()),
                extractTextFromRaw(checklist.getRawJson(), "asset_key"),
                extractTextFromRaw(checklist.getRawJson(), "asset_id"),
                extractTextFromRaw(checklist.getRawJson(), "equipment_id"));
    }

    private String resolveChecklistAssetName(Checklist checklist) {
        return firstNonBlank(
                extractTextFromRaw(checklist.getRawJson(), "asset_name"),
                extractChecklistPrefix(checklist.getName()));
    }

    private String resolveChecklistExternalId(Checklist checklist) {
        return firstNonBlank(
                blankToNull(checklist.getExternalId()),
                extractTextFromRaw(checklist.getRawJson(), "checklist_id"),
                extractTextFromRaw(checklist.getRawJson(), "id"));
    }

    private String resolveIssueAssetId(Issue issue) {
        return firstNonBlank(
                blankToNull(issue.getAssetId()),
                extractTextFromRaw(issue.getRawJson(), "asset_key"),
                extractTextFromRaw(issue.getRawJson(), "asset_id"));
    }

    private String resolveIssueAssetName(Issue issue) {
        return extractTextFromRaw(issue.getRawJson(), "asset_name");
    }

    private String resolveIssueSourceId(Issue issue) {
        return firstNonBlank(
                blankToNull(issue.getSourceId()),
                extractTextFromRaw(issue.getRawJson(), "source_id"));
    }

    private String extractEquipmentSystemNameFromRaw(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (root.has("systems") && root.get("systems").isArray() && root.get("systems").size() > 0) {
                JsonNode system = root.get("systems").get(0);
                if (system.has("name") && !system.get("name").isNull()) {
                    return blankToNull(system.get("name").asText());
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String extractChecklistPrefix(String name) {
        if (name == null || !name.contains(" - ")) return null;
        return blankToNull(name.substring(0, name.indexOf(" - ")).trim());
    }

    private String extractTextFromRaw(String rawJson, String fieldName) {
        if (rawJson == null || rawJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (!root.has(fieldName) || root.get(fieldName).isNull()) return null;
            JsonNode node = root.get(fieldName);
            if (node.isTextual() || node.isNumber()) return blankToNull(node.asText());
        } catch (Exception ignored) {
        }
        return null;
    }

    private void index(Map<String, List<Checklist>> index, String key, Checklist checklist) {
        if (key == null) return;
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(checklist);
    }

    private void index(Map<String, List<Issue>> index, String key, Issue issue) {
        if (key == null) return;
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(issue);
    }

    private void addChecklists(LinkedHashMap<String, Checklist> target, Collection<Checklist> source) {
        if (source == null) return;
        for (Checklist checklist : source) {
            String key = firstNonBlank(blankToNull(checklist.getExternalId()), checklist.getName(), String.valueOf(checklist.getId()));
            target.putIfAbsent(key, checklist);
        }
    }

    private void addIssues(LinkedHashMap<String, Issue> target, Collection<Issue> source) {
        if (source == null) return;
        for (Issue issue : source) {
            String key = firstNonBlank(blankToNull(issue.getExternalId()), issue.getTitle(), String.valueOf(issue.getId()));
            target.putIfAbsent(key, issue);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized != null ? normalized : fallback;
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class TestCounts {
        private final int total;
        private final int closed;

        private TestCounts(int total, int closed) {
            this.total = total;
            this.closed = closed;
        }
    }
}
