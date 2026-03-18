package com.cxalloy.integration.service;

import com.cxalloy.integration.dto.SavedReportRequest;
import com.cxalloy.integration.model.*;
import com.cxalloy.integration.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SavedReportService {

    private static final List<String> DEFAULT_SECTIONS = List.of(
            "summary", "personnel", "activities", "upcoming", "safety", "checklists", "issues", "tests", "equipment", "commercials"
    );
    private static final List<String> BROWSER_PATHS = List.of(
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
    );
    private static final List<String> TAG_ORDER = List.of("white", "red", "yellow", "green", "blue", "unknown");
    private static final List<String> ISSUE_PRIORITY_ORDER = List.of(
            "P1 - Critical", "P2 - High", "P3 - Medium", "P4 - Low", "Unknown"
    );

    private static final Set<String> CLOSED_ISSUE_STATUSES = Set.of(
            "issue_closed", "closed", "complete", "completed", "accepted_by_owner"
    );

    private static final Set<String> CLOSED_CHECKLIST_STATUSES = Set.of(
            "finished", "complete", "completed", "done", "closed", "checklist_approved", "approved", "signed_off"
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_DATE,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    );

    private final SavedReportRepository savedReportRepository;
    private final ProjectRepository projectRepository;
    private final ChecklistRepository checklistRepository;
    private final IssueRepository issueRepository;
    private final EquipmentRepository equipmentRepository;
    private final CxTaskRepository taskRepository;
    private final PersonRepository personRepository;
    private final ProjectAccessService projectAccessService;
    private final ObjectMapper objectMapper;

    public SavedReportService(SavedReportRepository savedReportRepository,
                              ProjectRepository projectRepository,
                              ChecklistRepository checklistRepository,
                              IssueRepository issueRepository,
                              EquipmentRepository equipmentRepository,
                              CxTaskRepository taskRepository,
                              PersonRepository personRepository,
                              ProjectAccessService projectAccessService,
                              ObjectMapper objectMapper) {
        this.savedReportRepository = savedReportRepository;
        this.projectRepository = projectRepository;
        this.checklistRepository = checklistRepository;
        this.issueRepository = issueRepository;
        this.equipmentRepository = equipmentRepository;
        this.taskRepository = taskRepository;
        this.personRepository = personRepository;
        this.projectAccessService = projectAccessService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getReports(String projectId) {
        projectAccessService.requireProjectAccess(projectId);
        return savedReportRepository.findByProjectIdOrderByGeneratedAtDesc(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReport(Long id) {
        SavedReport report = savedReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + id));
        projectAccessService.requireProjectAccess(report.getProjectId());
        return toResponse(report);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getFilterOptions(String projectId) {
        projectAccessService.requireProjectAccess(projectId);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("issueStatuses", issueRepository.findByProjectId(projectId).stream()
                .map(Issue::getStatus)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        options.put("checklistStatuses", checklistRepository.findByProjectId(projectId).stream()
                .map(Checklist::getStatus)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        options.put("equipmentTypes", equipmentRepository.findByProjectId(projectId).stream()
                .map(Equipment::getEquipmentType)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        return options;
    }

    public Map<String, Object> generateReport(SavedReportRequest request) {
        if (request == null || !StringUtils.hasText(request.getProjectId())) {
            throw new IllegalArgumentException("projectId is required");
        }

        String projectId = request.getProjectId().trim();
        projectAccessService.requireProjectAccess(projectId);

        Project project = projectRepository.findByExternalId(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        String normalizedReportType = normalizeReportType(request.getReportType());
        Range range = resolveRange(normalizedReportType, request.getDateFrom(), request.getDateTo());
        List<String> sections = normalizeSections(request.getSections());

        List<Checklist> checklists = applyChecklistFilters(checklistRepository.findByProjectId(projectId), request, range);
        List<Issue> issues = applyIssueFilters(issueRepository.findByProjectId(projectId), request, range);
        List<Equipment> equipment = applyEquipmentFilters(equipmentRepository.findByProjectId(projectId), request);
        List<CxTask> tasks = applyTaskFilters(taskRepository.findByProjectId(projectId), range);
        List<Person> persons = personRepository.findByProjectId(projectId);

        Map<String, Object> filters = buildFilters(request, range);
        Map<String, Object> manualContent = buildManualContent(request);
        Map<String, Object> reportData = buildReportData(normalizedReportType, project, range, sections, checklists, issues, equipment, tasks, persons, manualContent, filters);

        SavedReport report = new SavedReport();
        report.setProjectId(projectId);
        report.setProjectName(project.getName());
        report.setReportType(normalizedReportType);
        report.setDateFrom(range.from().toString());
        report.setDateTo(range.to().toString());
        report.setTitle(resolveTitle(project, request.getTitle(), report.getReportType()));
        report.setSubtitle(reportSubtitle(project, report.getReportType(), range));
        report.setSectionsJson(writeValue(sections));
        report.setFiltersJson(writeValue(filters));
        report.setManualContentJson(writeValue(manualContent));
        report.setReportJson(writeValue(reportData));
        report.setChecklistCount(checklists.size());
        report.setIssueCount(issues.size());
        report.setTaskCount(tasks.size());
        report.setEquipmentCount(equipment.size());
        report.setGeneratedBy(projectAccessService.currentUsername());
        report.setGeneratedAt(LocalDateTime.now());

        return toResponse(savedReportRepository.save(report));
    }

    @Transactional(readOnly = true)
    public byte[] downloadReport(Long id, String format) {
        SavedReport report = savedReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + id));
        projectAccessService.requireProjectAccess(report.getProjectId());
        if ("pdf".equals(normalizeDownloadFormat(format))) {
            return buildPdf(report);
        }
        if ("csv".equals(normalizeDownloadFormat(format))) {
            return buildCsv(report).getBytes(StandardCharsets.UTF_8);
        }
        return report.getReportJson().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public String downloadFileName(Long id, String format) {
        SavedReport report = savedReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + id));
        String base = slugify(StringUtils.hasText(report.getTitle()) ? report.getTitle() : "saved-report-" + id);
        String normalizedFormat = normalizeDownloadFormat(format);
        return base + ("csv".equals(normalizedFormat) ? ".csv" : "pdf".equals(normalizedFormat) ? ".pdf" : ".json");
    }

    private Map<String, Object> buildReportData(String reportType,
                                                Project project,
                                                Range range,
                                                List<String> sections,
                                                List<Checklist> checklists,
                                                List<Issue> issues,
                                                List<Equipment> equipment,
                                                List<CxTask> tasks,
                                                List<Person> persons,
                                                Map<String, Object> manualContent,
                                                Map<String, Object> filters) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        data.put("project", Map.of(
                "projectId", project.getExternalId(),
                "projectName", project.getName(),
                "client", valueOrEmpty(project.getClient()),
                "location", valueOrEmpty(project.getLocation())
        ));
        data.put("range", Map.of(
                "from", range.from().toString(),
                "to", range.to().toString(),
                "label", prettyDate(range.from()) + " - " + prettyDate(range.to())
        ));
        data.put("filters", filters);
        data.put("manualContent", manualContent);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checklists", Map.of(
                "total", checklists.size(),
                "closed", checklists.stream().filter(this::isClosedChecklist).count(),
                "open", checklists.stream().filter(checklist -> !isClosedChecklist(checklist)).count()
        ));
        summary.put("issues", Map.of(
                "total", issues.size(),
                "closed", issues.stream().filter(this::isClosedIssue).count(),
                "open", issues.stream().filter(issue -> !isClosedIssue(issue)).count()
        ));
        summary.put("tasks", Map.of(
                "total", tasks.size(),
                "closed", tasks.stream().filter(this::isClosedTask).count(),
                "open", tasks.stream().filter(task -> !isClosedTask(task)).count()
        ));
        summary.put("equipment", Map.of(
                "total", equipment.size(),
                "tests", equipment.stream().mapToInt(item -> item.getTestCount() == null ? 0 : item.getTestCount()).sum()
        ));
        summary.put("personnel", persons.size());
        data.put("summary", summary);

        data.put("sections", sections);
        data.put("sectionData", buildSectionData(reportType, sections, checklists, issues, equipment, tasks, persons, manualContent));
        return data;
    }

    private Map<String, Object> buildSectionData(String reportType,
                                                 List<String> sections,
                                                 List<Checklist> checklists,
                                                 List<Issue> issues,
                                                 List<Equipment> equipment,
                                                 List<CxTask> tasks,
                                                 List<Person> persons,
                                                 Map<String, Object> manualContent) {
        Map<String, Object> sectionData = new LinkedHashMap<>();
        int rowLimit = "custom".equals(reportType) ? 80 : 20;
        int upcomingLimit = "custom".equals(reportType) ? 30 : 15;
        int testsLimit = "custom".equals(reportType) ? 40 : 20;

        if (sections.contains("personnel")) {
            Map<String, Long> byCompany = persons.stream()
                    .collect(Collectors.groupingBy(person -> defaultLabel(person.getCompany(), "Unknown"), LinkedHashMap::new, Collectors.counting()));
            List<Map<String, Object>> people = persons.stream()
                    .sorted(Comparator.comparing(this::personName, String.CASE_INSENSITIVE_ORDER))
                    .limit(rowLimit)
                    .map(person -> row(
                            "name", personName(person),
                            "email", valueOrEmpty(person.getEmail()),
                            "company", defaultLabel(person.getCompany(), "Unknown"),
                            "role", defaultLabel(person.getRole(), "Unknown")
                    ))
                    .toList();
            sectionData.put("personnel", Map.of("byCompany", byCompany, "rows", people, "totalRows", persons.size()));
        }

        if (sections.contains("activities")) {
            List<Map<String, Object>> activityRows = tasks.stream()
                    .limit(rowLimit)
                    .map(task -> row(
                            "title", valueOrEmpty(task.getTitle()),
                            "status", defaultLabel(task.getStatus(), "Unknown"),
                            "priority", defaultLabel(task.getPriority(), "Unknown"),
                            "dueDate", valueOrEmpty(task.getDueDate()),
                            "assignedTo", valueOrEmpty(task.getAssignedTo())
                    ))
                    .toList();
            sectionData.put("activities", Map.of("rows", activityRows, "totalRows", tasks.size()));
        }

        if (sections.contains("upcoming")) {
            LocalDate today = LocalDate.now();
            LocalDate soon = today.plusDays(14);
            List<Map<String, Object>> upcomingRows = tasks.stream()
                    .filter(task -> !isClosedTask(task))
                    .filter(task -> {
                        LocalDate due = parseDate(task.getDueDate());
                        return due != null && (!due.isBefore(today) && !due.isAfter(soon));
                    })
                    .sorted(Comparator.comparing(task -> parseDate(task.getDueDate()), Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(upcomingLimit)
                    .map(task -> row(
                            "title", valueOrEmpty(task.getTitle()),
                            "dueDate", valueOrEmpty(task.getDueDate()),
                            "status", defaultLabel(task.getStatus(), "Unknown"),
                            "assignedTo", valueOrEmpty(task.getAssignedTo())
                    ))
                    .toList();
            sectionData.put("upcoming", Map.of("rows", upcomingRows, "totalRows", upcomingRows.size()));
        }

        if (sections.contains("checklists")) {
            Map<String, Long> byTag = orderedCountMap(
                    checklists.stream().collect(Collectors.groupingBy(checklist -> defaultLabel(checklist.getTagLevel(), "unknown"), Collectors.counting())),
                    TAG_ORDER
            );
            Map<String, Long> byStatus = orderedCountMap(
                    checklists.stream().collect(Collectors.groupingBy(checklist -> defaultLabel(checklist.getStatus(), "unknown"), Collectors.counting())),
                    List.of()
            );
            List<Map<String, Object>> checklistRows = checklists.stream()
                    .limit(rowLimit)
                    .map(checklist -> row(
                            "name", valueOrEmpty(checklist.getName()),
                            "status", defaultLabel(checklist.getStatus(), "Unknown"),
                            "category", defaultLabel(checklist.getChecklistType(), "Unknown"),
                            "tagLevel", defaultLabel(checklist.getTagLevel(), "unknown"),
                            "assignedTo", valueOrEmpty(checklist.getAssignedTo()),
                            "dueDate", valueOrEmpty(checklist.getDueDate())
                    ))
                    .toList();
            sectionData.put("checklists", Map.of(
                    "byTag", byTag,
                    "byStatus", byStatus,
                    "progressByCategory", checklistProgressByTag(checklists),
                    "rows", checklistRows,
                    "totalRows", checklists.size()
            ));
        }

        if (sections.contains("issues")) {
            Map<String, Long> byStatus = orderedCountMap(
                    issues.stream().collect(Collectors.groupingBy(issue -> defaultLabel(issue.getStatus(), "unknown"), Collectors.counting())),
                    List.of()
            );
            Map<String, Long> byPriority = orderedCountMap(
                    issues.stream().collect(Collectors.groupingBy(issue -> defaultLabel(issue.getPriority(), "Unknown"), Collectors.counting())),
                    ISSUE_PRIORITY_ORDER
            );
            List<Map<String, Object>> issueRows = issues.stream()
                    .sorted(Comparator.comparing(issue -> isClosedIssue(issue) ? 1 : 0))
                    .limit(rowLimit)
                    .map(issue -> row(
                            "title", valueOrEmpty(issue.getTitle()),
                            "status", defaultLabel(issue.getStatus(), "Unknown"),
                            "priority", defaultLabel(issue.getPriority(), "Unknown"),
                            "assignee", valueOrEmpty(issue.getAssignee()),
                            "location", valueOrEmpty(issue.getLocation())
                    ))
                    .toList();
            sectionData.put("issues", Map.of(
                    "byStatus", byStatus,
                    "byPriority", byPriority,
                    "topLocations", topIssueLocations(issues),
                    "progressByCategory", issueProgressByPriority(issues),
                    "rows", issueRows,
                    "totalRows", issues.size()
            ));
        }

        if (sections.contains("tests")) {
            int totalTests = equipment.stream().mapToInt(item -> item.getTestCount() == null ? 0 : item.getTestCount()).sum();
            List<Map<String, Object>> testRows = equipment.stream()
                    .filter(item -> item.getTestCount() != null && item.getTestCount() > 0)
                    .sorted(Comparator.comparing(item -> item.getTestCount() == null ? 0 : item.getTestCount(), Comparator.reverseOrder()))
                    .limit(testsLimit)
                    .map(item -> row(
                            "equipment", firstNonBlank(item.getName(), item.getTag(), item.getExternalId()),
                            "type", defaultLabel(item.getEquipmentType(), "Unknown"),
                            "tests", item.getTestCount()
                    ))
                    .toList();
            sectionData.put("tests", Map.of("totalTests", totalTests, "rows", testRows, "totalRows", testRows.size()));
        }

        if (sections.contains("equipment")) {
            Map<String, Long> byType = orderedCountMap(
                    equipment.stream().collect(Collectors.groupingBy(item -> defaultLabel(item.getEquipmentType(), "Unknown"), Collectors.counting())),
                    List.of()
            );
            List<Map<String, Object>> equipmentRows = equipment.stream()
                    .limit(rowLimit)
                    .map(item -> row(
                            "name", firstNonBlank(item.getName(), item.getTag(), item.getExternalId()),
                            "type", defaultLabel(item.getEquipmentType(), "Unknown"),
                            "status", defaultLabel(item.getStatus(), "Unknown"),
                            "system", defaultLabel(item.getSystemName(), "Unassigned"),
                            "tests", item.getTestCount() == null ? 0 : item.getTestCount()
                    ))
                    .toList();
            sectionData.put("equipment", Map.of("byType", byType, "rows", equipmentRows, "totalRows", equipment.size()));
        }

        if (sections.contains("summary")) {
            sectionData.put("summary", Map.of("text", valueOrEmpty((String) manualContent.get("summaryText"))));
        }
        if (sections.contains("safety")) {
            sectionData.put("safety", Map.of("text", valueOrEmpty((String) manualContent.get("safetyNotes"))));
        }
        if (sections.contains("commercials")) {
            sectionData.put("commercials", Map.of("text", valueOrEmpty((String) manualContent.get("commercialNotes"))));
        }

        return sectionData;
    }

    private List<Checklist> applyChecklistFilters(List<Checklist> source, SavedReportRequest request, Range range) {
        Set<String> statuses = normalizeValues(request.getChecklistStatuses());
        return source.stream()
                .filter(checklist -> statuses.isEmpty() || statuses.contains(normalize(checklist.getStatus())))
                .filter(checklist -> withinRange(firstDate(checklist.getCreatedAt(), checklist.getUpdatedAt(), checklist.getDueDate()), range))
                .toList();
    }

    private List<Issue> applyIssueFilters(List<Issue> source, SavedReportRequest request, Range range) {
        Set<String> statuses = normalizeValues(request.getIssueStatuses());
        return source.stream()
                .filter(issue -> statuses.isEmpty() || statuses.contains(normalize(issue.getStatus())))
                .filter(issue -> withinRange(firstDate(issue.getCreatedAt(), issue.getUpdatedAt(), issue.getDueDate()), range))
                .toList();
    }

    private List<Equipment> applyEquipmentFilters(List<Equipment> source, SavedReportRequest request) {
        Set<String> types = normalizeValues(request.getEquipmentTypes());
        return source.stream()
                .filter(item -> types.isEmpty() || types.contains(normalize(item.getEquipmentType())))
                .toList();
    }

    private List<CxTask> applyTaskFilters(List<CxTask> source, Range range) {
        return source.stream()
                .filter(task -> withinRange(firstDate(task.getCreatedAt(), task.getUpdatedAt(), task.getDueDate(), task.getCompletedDate()), range))
                .toList();
    }

    private Map<String, Object> buildFilters(SavedReportRequest request, Range range) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("reportType", normalizeReportType(request.getReportType()));
        filters.put("dateFrom", range.from().toString());
        filters.put("dateTo", range.to().toString());
        filters.put("issueStatuses", normalizeList(request.getIssueStatuses()));
        filters.put("checklistStatuses", normalizeList(request.getChecklistStatuses()));
        filters.put("equipmentTypes", normalizeList(request.getEquipmentTypes()));
        return filters;
    }

    private Map<String, Object> buildManualContent(SavedReportRequest request) {
        Map<String, Object> manualContent = new LinkedHashMap<>();
        manualContent.put("summaryText", valueOrEmpty(request.getSummaryText()));
        manualContent.put("safetyNotes", valueOrEmpty(request.getSafetyNotes()));
        manualContent.put("commercialNotes", valueOrEmpty(request.getCommercialNotes()));
        return manualContent;
    }

    private Map<String, Object> toResponse(SavedReport report) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", report.getId());
        response.put("projectId", report.getProjectId());
        response.put("projectName", report.getProjectName());
        response.put("title", report.getTitle());
        response.put("subtitle", report.getSubtitle());
        response.put("reportType", report.getReportType());
        response.put("dateFrom", report.getDateFrom());
        response.put("dateTo", report.getDateTo());
        response.put("sections", readList(report.getSectionsJson()));
        response.put("filters", readMap(report.getFiltersJson()));
        response.put("manualContent", readMap(report.getManualContentJson()));
        response.put("reportData", readMap(report.getReportJson()));
        response.put("checklistCount", report.getChecklistCount());
        response.put("issueCount", report.getIssueCount());
        response.put("taskCount", report.getTaskCount());
        response.put("equipmentCount", report.getEquipmentCount());
        response.put("generatedBy", report.getGeneratedBy());
        response.put("generatedAt", report.getGeneratedAt());
        response.put("updatedAt", report.getUpdatedAt());
        return response;
    }

    private Range resolveRange(String reportType, String dateFrom, String dateTo) {
        String normalizedType = normalizeReportType(reportType);
        LocalDate today = LocalDate.now();
        if ("daily".equals(normalizedType)) {
            return new Range(today, today);
        }
        if ("weekly".equals(normalizedType)) {
            LocalDate start = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            return new Range(start, start.plusDays(6));
        }
        LocalDate from = parseDate(dateFrom);
        LocalDate to = parseDate(dateTo);
        if (from == null && to == null) {
            from = today.minusDays(6);
            to = today;
        } else if (from == null) {
            from = to;
        } else if (to == null) {
            to = from;
        }
        if (to.isBefore(from)) {
            LocalDate temp = from;
            from = to;
            to = temp;
        }
        return new Range(from, to);
    }

    private String resolveTitle(Project project, String requestedTitle, String reportType) {
        if (StringUtils.hasText(requestedTitle)) {
            return requestedTitle.trim();
        }
        return project.getName() + " " + capitalize(reportType) + " Report";
    }

    private String reportSubtitle(Project project, String reportType, Range range) {
        return capitalize(reportType) + " report - " + prettyDate(range.from()) + " - " + prettyDate(range.to()) + " - " + defaultLabel(project.getLocation(), "No location");
    }

    private String buildCsv(SavedReport report) {
        Map<String, Object> reportData = readMap(report.getReportJson());
        StringBuilder csv = new StringBuilder();
        appendCsvRow(csv, "Field", "Value");
        appendCsvRow(csv, "Title", report.getTitle());
        appendCsvRow(csv, "Project", report.getProjectName());
        appendCsvRow(csv, "Type", report.getReportType());
        appendCsvRow(csv, "Date From", report.getDateFrom());
        appendCsvRow(csv, "Date To", report.getDateTo());
        appendCsvRow(csv, "", "");

        Map<String, Object> summary = asMap(reportData.get("summary"));
        appendCsvRow(csv, "Summary Metric", "Value");
        summary.forEach((key, value) -> appendCsvRow(csv, key, String.valueOf(value)));
        appendCsvRow(csv, "", "");

        Map<String, Object> sectionData = asMap(reportData.get("sectionData"));
        sectionData.forEach((section, value) -> {
            appendCsvRow(csv, section.toUpperCase(Locale.ROOT), "");
            if (value instanceof Map<?, ?> mapValue) {
                Map<String, Object> typed = mapValue.entrySet().stream()
                        .collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
                typed.forEach((key, nested) -> {
                    if (nested instanceof List<?> list) {
                        appendCsvRow(csv, key, "");
                        if (!list.isEmpty() && list.get(0) instanceof Map<?, ?> firstRow) {
                            List<String> headers = firstRow.keySet().stream().map(String::valueOf).toList();
                            appendCsvRow(csv, headers.toArray(String[]::new));
                            list.forEach(item -> {
                                Map<?, ?> row = (Map<?, ?>) item;
                                appendCsvRow(csv, headers.stream().map(header -> {
                                    Object cell = row.containsKey(header) ? row.get(header) : "";
                                    return String.valueOf(cell == null ? "" : cell);
                                }).toArray(String[]::new));
                            });
                        }
                    } else {
                        appendCsvRow(csv, key, String.valueOf(nested));
                    }
                });
            }
            appendCsvRow(csv, "", "");
        });
        return csv.toString();
    }

    private byte[] buildPdf(SavedReport report) {
        Path htmlPath = null;
        Path pdfPath = null;
        try {
            htmlPath = Files.createTempFile("saved-report-", ".html");
            pdfPath = Files.createTempFile("saved-report-", ".pdf");
            Files.writeString(htmlPath, buildPdfHtml(report), StandardCharsets.UTF_8);

            String browserPath = findBrowserBinary();
            Process process = new ProcessBuilder(
                    browserPath,
                    "--headless",
                    "--disable-gpu",
                    "--no-pdf-header-footer",
                    "--print-to-pdf=" + pdfPath.toString(),
                    htmlPath.toUri().toString()
            ).redirectErrorStream(true).start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0 || !Files.exists(pdfPath) || Files.size(pdfPath) == 0) {
                throw new IllegalStateException("PDF generation failed" + (StringUtils.hasText(output) ? ": " + output : ""));
            }

            return Files.readAllBytes(pdfPath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PDF report: " + e.getMessage(), e);
        } finally {
            try {
                if (htmlPath != null) Files.deleteIfExists(htmlPath);
            } catch (Exception ignored) {
            }
            try {
                if (pdfPath != null) Files.deleteIfExists(pdfPath);
            } catch (Exception ignored) {
            }
        }
    }

    private String buildPdfHtml(SavedReport report) {
        Map<String, Object> reportData = readMap(report.getReportJson());
        Map<String, Object> project = asMap(reportData.get("project"));
        Map<String, Object> summary = asMap(reportData.get("summary"));
        Map<String, Object> sectionData = asMap(reportData.get("sectionData"));
        List<String> sections = readList(report.getSectionsJson());

        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <title>Saved Report</title>
                  <style>
                    @page { size: A4; margin: 18mm 14mm; }
                    * { box-sizing: border-box; }
                    body { margin: 0; font-family: 'Segoe UI', Arial, sans-serif; color: #0f172a; background: #eef3fb; }
                    .page { width: 100%; }
                    .hero { background: linear-gradient(135deg, #0f172a 0%, #1d4ed8 55%, #38bdf8 100%); color: white; border-radius: 22px; padding: 28px 30px; box-shadow: 0 18px 60px rgba(15,23,42,0.22); }
                    .eyebrow { font-size: 11px; font-weight: 700; letter-spacing: 0.18em; text-transform: uppercase; opacity: 0.76; }
                    .title { font-size: 28px; font-weight: 800; line-height: 1.15; margin-top: 12px; }
                    .subtitle { font-size: 13px; line-height: 1.7; margin-top: 10px; color: rgba(255,255,255,0.86); }
                    .hero-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 22px; margin-top: 18px; }
                    .hero-card { background: rgba(255,255,255,0.12); border: 1px solid rgba(255,255,255,0.14); border-radius: 16px; padding: 14px 16px; }
                    .hero-card-label { font-size: 10px; text-transform: uppercase; letter-spacing: 0.1em; opacity: 0.72; font-weight: 700; }
                    .hero-card-value { font-size: 15px; margin-top: 8px; font-weight: 700; }
                    .stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 18px 0 0; }
                    .stat { background: #ffffff; border: 1px solid #dbe7f5; border-radius: 18px; padding: 16px; box-shadow: 0 8px 30px rgba(15,23,42,0.05); }
                    .stat-label { font-size: 10px; text-transform: uppercase; letter-spacing: 0.12em; color: #64748b; font-weight: 700; }
                    .stat-value { font-size: 26px; font-weight: 800; margin-top: 8px; }
                    .stat-sub { font-size: 12px; color: #64748b; margin-top: 6px; line-height: 1.45; }
                    .section { margin-top: 18px; background: #ffffff; border: 1px solid #dbe7f5; border-radius: 18px; overflow: hidden; box-shadow: 0 8px 30px rgba(15,23,42,0.05); }
                    .section-head { padding: 14px 18px; background: #f8fbff; border-bottom: 1px solid #e2ebf7; }
                    .section-title { font-size: 15px; font-weight: 800; color: #0f172a; }
                    .section-body { padding: 16px 18px 18px; }
                    .chips { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
                    .chip { padding: 6px 10px; border-radius: 999px; font-size: 11px; font-weight: 700; background: #eff6ff; border: 1px solid #bfdbfe; color: #1d4ed8; }
                    .note { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 14px; padding: 12px 14px; color: #334155; font-size: 12px; line-height: 1.7; white-space: pre-wrap; }
                    .chart-grid { display: grid; gap: 10px; margin-bottom: 14px; }
                    .chart-row { border: 1px solid #dbe7f5; border-radius: 14px; padding: 12px 14px; background: #f8fbff; }
                    .chart-meta { display: flex; justify-content: space-between; gap: 10px; font-size: 12px; font-weight: 700; color: #0f172a; margin-bottom: 8px; }
                    .chart-sub { font-size: 11px; color: #64748b; margin-bottom: 8px; }
                    .bar-track { display: flex; width: 100%; height: 10px; border-radius: 999px; overflow: hidden; background: #dbe7f5; }
                    .bar-open { background: #f97316; }
                    .bar-closed { background: #22c55e; }
                    table { width: 100%; border-collapse: collapse; margin-top: 10px; }
                    th { text-align: left; font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; font-weight: 800; padding: 9px 10px; background: #f8fafc; border-bottom: 1px solid #e2e8f0; }
                    td { padding: 10px; font-size: 12px; color: #1e293b; border-bottom: 1px solid #eef2f7; vertical-align: top; }
                    tr:last-child td { border-bottom: none; }
                    .footer { margin-top: 16px; font-size: 11px; color: #64748b; text-align: right; }
                    .divider { height: 1px; background: #dbe7f5; margin: 12px 0; }
                  </style>
                </head>
                <body>
                  <div class="page">
                """);

        html.append("<div class=\"hero\">");
        html.append("<div class=\"eyebrow\">Modem IQ Report Export</div>");
        html.append("<div class=\"title\">").append(escapeHtml(report.getTitle())).append("</div>");
        html.append("<div class=\"subtitle\">").append(escapeHtml(report.getSubtitle())).append("</div>");
        html.append("<div class=\"hero-grid\">");
        html.append(heroCard("Project", firstNonBlank((String) project.get("projectName"), report.getProjectName(), report.getProjectId())));
        html.append(heroCard("Client / Location", firstNonBlank((String) project.get("client"), "") + (StringUtils.hasText((String) project.get("location")) ? " - " + project.get("location") : "")));
        html.append(heroCard("Reporting Window", report.getDateFrom() + " to " + report.getDateTo()));
        html.append(heroCard("Generated", prettyDateTime(report.getGeneratedAt()) + " by " + escapeHtml(report.getGeneratedBy())));
        html.append("</div></div>");

        html.append("<div class=\"stats\">");
        html.append(statCard("Checklists", metricValue(summary, "checklists", "total"), metricLine(summary, "checklists")));
        html.append(statCard("Issues", metricValue(summary, "issues", "total"), metricLine(summary, "issues")));
        html.append(statCard("Tasks", metricValue(summary, "tasks", "total"), metricLine(summary, "tasks")));
        html.append(statCard("Equipment", metricValue(summary, "equipment", "total"), "Tests in scope: " + metricValue(summary, "equipment", "tests")));
        html.append("</div>");

        for (String section : sections) {
            html.append("<section class=\"section\">");
            html.append("<div class=\"section-head\"><div class=\"section-title\">").append(escapeHtml(sectionTitle(section))).append("</div></div>");
            html.append("<div class=\"section-body\">");

            Map<String, Object> data = asMap(sectionData.get(section));
            if (data.containsKey("text")) {
                html.append("<div class=\"note\">").append(escapeHtml(String.valueOf(data.get("text")))).append("</div>");
            }

            appendChipGroup(html, data.get("byCompany"));
            appendChipGroup(html, data.get("byStatus"));
            appendChipGroup(html, data.get("byTag"));
            appendChipGroup(html, data.get("byPriority"));
            appendChipGroup(html, data.get("byType"));
            appendChipGroup(html, data.get("topLocations"));
            appendProgressCharts(html, data.get("progressByCategory"));

            if (data.containsKey("totalTests")) {
                html.append("<div class=\"note\">Total tests in selected scope: ").append(escapeHtml(String.valueOf(data.get("totalTests")))).append("</div>");
            }

            appendRowsTable(html, data.get("rows"));

            if (data.isEmpty()) {
                html.append("<div class=\"note\">No data in this section for the current saved report filters.</div>");
            }

            html.append("</div></section>");
        }

        html.append("<div class=\"footer\">Generated from saved project report data</div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private void appendCsvRow(StringBuilder csv, String... cells) {
        csv.append(Arrays.stream(cells)
                        .map(cell -> "\"" + String.valueOf(cell == null ? "" : cell).replace("\"", "\"\"") + "\"")
                        .collect(Collectors.joining(",")))
                .append('\n');
    }

    private String findBrowserBinary() {
        return BROWSER_PATHS.stream()
                .filter(path -> Files.exists(Path.of(path)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No local Edge or Chrome installation found for PDF export"));
    }

    private String heroCard(String label, String value) {
        return "<div class=\"hero-card\"><div class=\"hero-card-label\">" + escapeHtml(label) + "</div><div class=\"hero-card-value\">" + escapeHtml(value) + "</div></div>";
    }

    private String statCard(String label, Object value, String sub) {
        return "<div class=\"stat\"><div class=\"stat-label\">" + escapeHtml(label) + "</div><div class=\"stat-value\">" + escapeHtml(String.valueOf(value)) + "</div><div class=\"stat-sub\">" + escapeHtml(sub) + "</div></div>";
    }

    private String metricValue(Map<String, Object> summary, String group, String field) {
        Object value = asMap(summary.get(group)).get(field);
        return String.valueOf(value == null ? 0 : value);
    }

    private String metricLine(Map<String, Object> summary, String group) {
        Map<String, Object> values = asMap(summary.get(group));
        return String.valueOf(values.getOrDefault("closed", 0)) + " closed / " + String.valueOf(values.getOrDefault("open", 0)) + " open";
    }

    private void appendChipGroup(StringBuilder html, Object value) {
        if (!(value instanceof Map<?, ?> mapValue) || mapValue.isEmpty()) {
            return;
        }
        html.append("<div class=\"chips\">");
        mapValue.forEach((key, count) -> html.append("<span class=\"chip\">")
                .append(escapeHtml(String.valueOf(key)))
                .append(": ")
                .append(escapeHtml(String.valueOf(count)))
                .append("</span>"));
        html.append("</div>");
    }

    private void appendRowsTable(StringBuilder html, Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> firstRow)) {
            return;
        }
        List<String> headers = firstRow.keySet().stream().map(String::valueOf).toList();
        html.append("<table><thead><tr>");
        headers.forEach(header -> html.append("<th>").append(escapeHtml(readableLabel(header))).append("</th>"));
        html.append("</tr></thead><tbody>");
        list.stream().limit(12).forEach(item -> {
            Map<?, ?> row = (Map<?, ?>) item;
            html.append("<tr>");
            headers.forEach(header -> {
                Object cell = row.containsKey(header) ? row.get(header) : "";
                html.append("<td>").append(escapeHtml(String.valueOf(cell == null ? "" : cell))).append("</td>");
            });
            html.append("</tr>");
        });
        html.append("</tbody></table>");
    }

    private void appendProgressCharts(StringBuilder html, Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        html.append("<div class=\"chart-grid\">");
        list.stream().limit(8).forEach(item -> {
            if (!(item instanceof Map<?, ?> entry)) {
                return;
            }
            long open = longValue(entry.get("open"));
            long closed = longValue(entry.get("closed"));
            long total = Math.max(longValue(entry.get("total")), open + closed);
            double openWidth = total == 0 ? 0 : (open * 100.0) / total;
            double closedWidth = total == 0 ? 0 : (closed * 100.0) / total;
            Object category = entry.containsKey("category") ? entry.get("category") : "Unknown";
            html.append("<div class=\"chart-row\">");
            html.append("<div class=\"chart-meta\"><span>")
                    .append(escapeHtml(String.valueOf(category)))
                    .append("</span><span>")
                    .append(escapeHtml(String.valueOf(total)))
                    .append(" total</span></div>");
            html.append("<div class=\"chart-sub\">Open ")
                    .append(escapeHtml(String.valueOf(open)))
                    .append(" / Closed ")
                    .append(escapeHtml(String.valueOf(closed)))
                    .append("</div>");
            html.append("<div class=\"bar-track\">");
            html.append("<div class=\"bar-open\" style=\"width: ").append(String.format(Locale.US, "%.2f", openWidth)).append("%\"></div>");
            html.append("<div class=\"bar-closed\" style=\"width: ").append(String.format(Locale.US, "%.2f", closedWidth)).append("%\"></div>");
            html.append("</div></div>");
        });
        html.append("</div>");
    }

    private Map<String, Long> orderedCountMap(Map<String, Long> rawCounts, List<String> preferredOrder) {
        LinkedHashMap<String, Long> ordered = new LinkedHashMap<>();
        if (rawCounts == null || rawCounts.isEmpty()) {
            return ordered;
        }

        Set<String> consumed = new LinkedHashSet<>();
        for (String preferred : preferredOrder) {
            rawCounts.entrySet().stream()
                    .filter(entry -> preferred.equalsIgnoreCase(entry.getKey()))
                    .findFirst()
                    .ifPresent(entry -> {
                        ordered.put(entry.getKey(), entry.getValue());
                        consumed.add(entry.getKey());
                    });
        }

        rawCounts.entrySet().stream()
                .filter(entry -> !consumed.contains(entry.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    private List<Map<String, Object>> checklistProgressByTag(List<Checklist> checklists) {
        return progressByCategory(
                checklists,
                checklist -> defaultLabel(checklist.getTagLevel(), "unknown"),
                this::isClosedChecklist,
                TAG_ORDER
        );
    }

    private List<Map<String, Object>> issueProgressByPriority(List<Issue> issues) {
        return progressByCategory(
                issues,
                issue -> defaultLabel(issue.getPriority(), "Unknown"),
                this::isClosedIssue,
                ISSUE_PRIORITY_ORDER
        );
    }

    private <T> List<Map<String, Object>> progressByCategory(List<T> items,
                                                             java.util.function.Function<T, String> categoryExtractor,
                                                             java.util.function.Predicate<T> closedPredicate,
                                                             List<String> preferredOrder) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (T item : items) {
            String category = defaultLabel(categoryExtractor.apply(item), "Unknown");
            long[] values = counts.computeIfAbsent(category, key -> new long[3]);
            if (closedPredicate.test(item)) {
                values[1]++;
            } else {
                values[0]++;
            }
            values[2]++;
        }

        Map<String, Long> orderingHelper = orderedCountMap(
                counts.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[2])),
                preferredOrder
        );

        return orderingHelper.keySet().stream()
                .map(category -> {
                    long[] values = counts.get(category);
                    return row(
                            "category", category,
                            "open", values[0],
                            "closed", values[1],
                            "total", values[2]
                    );
                })
                .toList();
    }

    private Map<String, Long> topIssueLocations(List<Issue> issues) {
        return issues.stream()
                .map(issue -> defaultLabel(issue.getLocation(), "Unassigned"))
                .collect(Collectors.groupingBy(location -> location, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)))
                .limit(6)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private String sectionTitle(String section) {
        return switch (section) {
            case "summary" -> "Executive Summary";
            case "personnel" -> "Personnel";
            case "activities" -> "Activities";
            case "upcoming" -> "Upcoming";
            case "safety" -> "Safety Notes";
            case "checklists" -> "Checklist Progress";
            case "issues" -> "Issue Register";
            case "tests" -> "Test Snapshot";
            case "equipment" -> "Equipment Overview";
            case "commercials" -> "Commercial Notes";
            default -> capitalize(section);
        };
    }

    private String readableLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return capitalize(normalized.substring(0, 1)) + normalized.substring(1);
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String prettyDateTime(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        return value.format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm"));
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String normalizeReportType(String reportType) {
        String value = normalize(reportType);
        if ("daily".equals(value) || "weekly".equals(value) || "custom".equals(value)) {
            return value;
        }
        return "custom";
    }

    private String normalizeDownloadFormat(String format) {
        if ("csv".equalsIgnoreCase(format)) {
            return "csv";
        }
        if ("pdf".equalsIgnoreCase(format)) {
            return "pdf";
        }
        return "json";
    }

    private List<String> normalizeSections(List<String> sections) {
        List<String> normalized = normalizeList(sections);
        return normalized.isEmpty() ? DEFAULT_SECTIONS : normalized;
    }

    private List<String> normalizeList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .distinct()
                .toList();
    }

    private Set<String> normalizeValues(List<String> values) {
        return new LinkedHashSet<>(normalizeList(values));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isClosedChecklist(Checklist checklist) {
        return CLOSED_CHECKLIST_STATUSES.contains(normalize(checklist.getStatus()));
    }

    private boolean isClosedIssue(Issue issue) {
        return CLOSED_ISSUE_STATUSES.contains(normalize(issue.getStatus()));
    }

    private boolean isClosedTask(CxTask task) {
        return CLOSED_CHECKLIST_STATUSES.contains(normalize(task.getStatus()));
    }

    private LocalDate firstDate(String... values) {
        for (String value : values) {
            LocalDate date = parseDate(value);
            if (date != null) {
                return date;
            }
        }
        return null;
    }

    private boolean withinRange(LocalDate date, Range range) {
        if (date == null) {
            return true;
        }
        return !date.isBefore(range.from()) && !date.isAfter(range.to());
    }

    private LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                TemporalAccessor parsed = formatter.parseBest(value, LocalDateTime::from, LocalDate::from, OffsetDateTime::from);
                if (parsed instanceof LocalDate date) {
                    return date;
                }
                if (parsed instanceof LocalDateTime dateTime) {
                    return dateTime.toLocalDate();
                }
                if (parsed instanceof OffsetDateTime offsetDateTime) {
                    return offsetDateTime.toLocalDate();
                }
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize report payload", e);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> readList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private String personName(Person person) {
        return firstNonBlank(
                ((valueOrEmpty(person.getFirstName()) + " " + valueOrEmpty(person.getLastName())).trim()),
                person.getEmail(),
                person.getExternalId()
        );
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String defaultLabel(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String prettyDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
    }

    private String capitalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String slugify(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length - 1; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    private record Range(LocalDate from, LocalDate to) {}
}
