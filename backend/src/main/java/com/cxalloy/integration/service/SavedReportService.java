package com.cxalloy.integration.service;

import com.cxalloy.integration.dto.SavedReportRequest;
import com.cxalloy.integration.model.*;
import com.cxalloy.integration.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional
public class SavedReportService {

    private static final List<String> DEFAULT_SECTIONS = List.of(
            "summary", "personnel", "activities", "upcoming", "safety", "checklists", "issues", "tests", "equipment", "commercials"
    );
    private static final List<String> BROWSER_PATHS = List.of(
            "/usr/bin/chromium-headless-shell",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
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
    private final ChecklistStatusDateRepository checklistStatusDateRepository;
    private final IssueRepository issueRepository;
    private final EquipmentRepository equipmentRepository;
    private final CxTaskRepository taskRepository;
    private final PersonRepository personRepository;
    private final ProjectAccessService projectAccessService;
    private final ObjectMapper objectMapper;

    public SavedReportService(SavedReportRepository savedReportRepository,
                              ProjectRepository projectRepository,
                              ChecklistRepository checklistRepository,
                              ChecklistStatusDateRepository checklistStatusDateRepository,
                              IssueRepository issueRepository,
                              EquipmentRepository equipmentRepository,
                              CxTaskRepository taskRepository,
                              PersonRepository personRepository,
                              ProjectAccessService projectAccessService,
                              ObjectMapper objectMapper) {
        this.savedReportRepository = savedReportRepository;
        this.projectRepository = projectRepository;
        this.checklistRepository = checklistRepository;
        this.checklistStatusDateRepository = checklistStatusDateRepository;
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

        List<Checklist> allChecklists = hydrateChecklistStatusDates(projectId, checklistRepository.findByProjectId(projectId));
        List<Issue> allIssues = issueRepository.findByProjectId(projectId);
        List<Equipment> allEquipment = equipmentRepository.findByProjectId(projectId);
        List<CxTask> allTasks = taskRepository.findByProjectId(projectId);
        List<Person> persons = personRepository.findByProjectId(projectId);

        List<Checklist> checklists = applyChecklistFilters(allChecklists, request, range);
        List<Issue> issues = applyIssueFilters(allIssues, request, range);
        List<Equipment> equipment = applyEquipmentFilters(allEquipment, request);
        List<CxTask> tasks = applyTaskFilters(allTasks, range);

        Map<String, Object> filters = buildFilters(request, range);
        Map<String, Object> manualContent = buildManualContent(request);
        Map<String, Object> reportData = buildReportData(
                normalizedReportType,
                project,
                range,
                sections,
                allChecklists,
                allIssues,
                allEquipment,
                allTasks,
                persons,
                checklists,
                issues,
                equipment,
                tasks,
                manualContent,
                filters
        );

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
                                                List<Checklist> allChecklists,
                                                List<Issue> allIssues,
                                                List<Equipment> allEquipment,
                                                List<CxTask> allTasks,
                                                List<Person> persons,
                                                List<Checklist> checklists,
                                                List<Issue> issues,
                                                List<Equipment> equipment,
                                                List<CxTask> tasks,
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
                "total", allChecklists.size(),
                "closed", allChecklists.stream().filter(this::isClosedChecklist).count(),
                "open", allChecklists.stream().filter(checklist -> !isClosedChecklist(checklist)).count()
        ));
        summary.put("issues", Map.of(
                "total", allIssues.size(),
                "closed", allIssues.stream().filter(this::isClosedIssue).count(),
                "open", allIssues.stream().filter(issue -> !isClosedIssue(issue)).count()
        ));
        summary.put("tasks", Map.of(
                "total", allTasks.size(),
                "closed", allTasks.stream().filter(this::isClosedTask).count(),
                "open", allTasks.stream().filter(task -> !isClosedTask(task)).count()
        ));
        summary.put("equipment", Map.of(
                "total", allEquipment.size(),
                "tests", allEquipment.stream().mapToInt(item -> item.getTestCount() == null ? 0 : item.getTestCount()).sum()
        ));
        summary.put("personnel", persons.size());
        data.put("summary", summary);

        data.put("sections", sections);
        data.put("sectionData", buildSectionData(reportType, sections, checklists, issues, equipment, tasks, persons, manualContent));
        data.put("executive", buildExecutiveData(project, range, allChecklists, allIssues, allEquipment, allTasks, persons, manualContent));
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

    private List<Checklist> hydrateChecklistStatusDates(String projectId, List<Checklist> checklists) {
        Map<String, ChecklistStatusDate> byChecklist = checklistStatusDateRepository.findByProjectId(projectId).stream()
                .filter(item -> StringUtils.hasText(item.getChecklistExternalId()))
                .collect(Collectors.toMap(
                        ChecklistStatusDate::getChecklistExternalId,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        checklists.forEach(checklist -> {
            ChecklistStatusDate statusDate = byChecklist.get(checklist.getExternalId());
            if (statusDate == null) {
                return;
            }
            checklist.setLatestOpenDate(statusDate.getLatestOpenDate());
            checklist.setLatestInProgressDate(statusDate.getLatestInProgressDate());
            checklist.setLatestFinishedDate(statusDate.getLatestFinishedDate());
        });
        return checklists;
    }

    private Map<String, Object> buildExecutiveData(Project project,
                                                   Range range,
                                                   List<Checklist> checklists,
                                                   List<Issue> issues,
                                                   List<Equipment> equipment,
                                                   List<CxTask> tasks,
                                                   List<Person> persons,
                                                   Map<String, Object> manualContent) {
        List<Map<String, Object>> tests = extractTests(equipment);
        long totalChecklists = checklists.size();
        long closedChecklists = checklists.stream().filter(this::isClosedChecklist).count();
        long openChecklists = totalChecklists - closedChecklists;
        long totalIssues = issues.size();
        long openIssues = issues.stream().filter(issue -> !isClosedIssue(issue)).count();

        double overallCompletion = percent(closedChecklists, totalChecklists);
        double planCompletion = calculatePlannedCompletion(checklists);
        double recentDailyRunRate = calculateDailyRunRate(checklists, 28);
        LocalDate forecastDate = estimateForecastDate(checklists, recentDailyRunRate);

        Map<String, Object> executiveSummary = row(
                "overallCompletionPct", formatPercent(overallCompletion),
                "plannedCompletionPct", formatPercent(planCompletion),
                "forecastCompletion", forecastDate == null ? "TBD" : prettyDate(forecastDate),
                "remainingChecklists", openChecklists,
                "openIssues", openIssues,
                "dailyRunRate", formatOneDecimal(recentDailyRunRate),
                "narrative", buildExecutiveNarrative(overallCompletion, closedChecklists, totalChecklists, openIssues, forecastDate)
        );

        return row(
                "projectDetails", buildProjectDetails(project, manualContent),
                "peopleOnSite", buildPeopleOnSiteRows(manualContent, persons),
                "executiveSummary", executiveSummary,
                "periodInsights", buildPeriodInsights(range, checklists, issues, tests),
                "tagSummary", buildTagSummary(checklists),
                "issueStatusTable", buildIssueStatusTable(issues),
                "checklistStatusTable", buildChecklistStatusTable(checklists),
                "trend4Weeks", buildFourWeekTrend(checklists, issues, tests),
                "planVsActual", buildPlanVsActualCurve(checklists),
                "equipmentMatrix", buildEquipmentMatrixSummary(equipment, checklists),
                "overdueItems", buildOverdueChecklistRows(checklists),
                "staleChecklists", buildStaleChecklistRows(checklists),
                "openLongerThan30", buildOpenChecklistRows(checklists, 30),
                "topIssueEquipment", buildTopIssueEquipmentRows(equipment, checklists, issues)
        );
    }

    private Map<String, Object> buildProjectDetails(Project project, Map<String, Object> manualContent) {
        return row(
                "description", firstNonBlank(stringValue(manualContent.get("projectDescription")), project.getDescription(), project.getName()),
                "client", firstNonBlank(stringValue(manualContent.get("clientName")), project.getClient(), project.getBuildingOwner()),
                "projectCode", firstNonBlank(stringValue(manualContent.get("projectCode")), project.getNumber(), project.getExternalId()),
                "shiftWindow", firstNonBlank(stringValue(manualContent.get("shiftWindow")), "Not provided"),
                "author", firstNonBlank(stringValue(manualContent.get("reportAuthor")), projectAccessService.currentUsername()),
                "location", firstNonBlank(project.getLocation(), "No location")
        );
    }

    private List<Map<String, Object>> buildPeopleOnSiteRows(Map<String, Object> manualContent, List<Person> persons) {
        String raw = stringValue(manualContent.get("peopleOnSite"));
        if (StringUtils.hasText(raw)) {
            return Arrays.stream(raw.split("\\r?\\n"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(line -> {
                        String[] parts = Arrays.stream(line.split("\\|"))
                                .map(String::trim)
                                .toArray(String[]::new);
                        return row(
                                "name", parts.length > 0 ? parts[0] : "",
                                "role", parts.length > 1 ? parts[1] : "",
                                "onsiteOffsite", parts.length > 2 ? parts[2] : "",
                                "task", parts.length > 3 ? parts[3] : "",
                                "manDays", parts.length > 4 ? parts[4] : "1"
                        );
                    })
                    .toList();
        }

        return persons.stream()
                .limit(8)
                .map(person -> row(
                        "name", personName(person),
                        "role", firstNonBlank(person.getRole(), "Team"),
                        "onsiteOffsite", "Onsite",
                        "task", "Cx Support",
                        "manDays", "1"
                ))
                .toList();
    }

    private Map<String, Object> buildPeriodInsights(Range range,
                                                    List<Checklist> checklists,
                                                    List<Issue> issues,
                                                    List<Map<String, Object>> tests) {
        long tagsGranted = checklists.stream()
                .filter(this::isClosedChecklist)
                .filter(checklist -> withinRange(checklistFinishedDate(checklist), range))
                .count();
        long issuesClosed = issues.stream()
                .filter(this::isClosedIssue)
                .filter(issue -> withinRange(issueClosedDate(issue), range))
                .count();
        long testsDone = tests.stream()
                .filter(this::isClosedTest)
                .filter(test -> withinRange(testClosedDate(test), range))
                .count();
        long started = checklists.stream()
                .filter(checklist -> withinRange(checklistStartedDate(checklist), range))
                .count();

        Map<String, Long> grantedByTag = orderedCountMap(
                checklists.stream()
                        .filter(this::isClosedChecklist)
                        .filter(checklist -> withinRange(checklistFinishedDate(checklist), range))
                        .collect(Collectors.groupingBy(checklist -> defaultLabel(checklist.getTagLevel(), "unknown"), Collectors.counting())),
                TAG_ORDER
        );

        return row(
                "tagsGranted", tagsGranted,
                "issuesClosed", issuesClosed,
                "testsDone", testsDone,
                "started", started,
                "grantedByTag", grantedByTag
        );
    }

    private List<Map<String, Object>> buildTagSummary(List<Checklist> checklists) {
        return TAG_ORDER.stream()
                .filter(tag -> !"unknown".equalsIgnoreCase(tag))
                .map(tag -> {
                    List<Checklist> bucket = checklists.stream()
                            .filter(checklist -> tag.equalsIgnoreCase(defaultLabel(checklist.getTagLevel(), "white")))
                            .toList();
                    long total = bucket.size();
                    long closed = bucket.stream().filter(this::isClosedChecklist).count();
                    return row(
                            "tagLevel", capitalize(tag),
                            "planned", total,
                            "complete", closed,
                            "percentage", formatPercent(percent(closed, total))
                    );
                })
                .filter(entry -> longValue(entry.get("planned")) > 0)
                .toList();
    }

    private List<Map<String, Object>> buildChecklistStatusTable(List<Checklist> checklists) {
        long total = Math.max(1, checklists.size());
        return orderedCountMap(
                checklists.stream().collect(Collectors.groupingBy(checklist -> defaultLabel(checklist.getStatus(), "unknown"), Collectors.counting())),
                List.of("not_started", "in_progress", "returned_with_comments", "finished", "fwt_passed", "fwt_passed_with_issues", "fwt_failed")
        ).entrySet().stream()
                .map(entry -> row(
                        "status", readableLabel(entry.getKey()),
                        "count", entry.getValue(),
                        "percentage", formatPercent((entry.getValue() * 100.0) / total)
                ))
                .toList();
    }

    private List<Map<String, Object>> buildIssueStatusTable(List<Issue> issues) {
        long total = Math.max(1, issues.size());
        return orderedCountMap(
                issues.stream().collect(Collectors.groupingBy(issue -> defaultLabel(issue.getStatus(), "unknown"), Collectors.counting())),
                List.of("open", "correction_in_progress", "additional_information_needed", "gc_to_verify", "ready_for_retest", "cxa_to_verify", "issue_closed", "accepted_by_owner", "recommendation")
        ).entrySet().stream()
                .map(entry -> row(
                        "status", readableLabel(entry.getKey()),
                        "count", entry.getValue(),
                        "percentage", formatPercent((entry.getValue() * 100.0) / total)
                ))
                .toList();
    }

    private List<Map<String, Object>> buildFourWeekTrend(List<Checklist> checklists,
                                                         List<Issue> issues,
                                                         List<Map<String, Object>> tests) {
        LocalDate today = LocalDate.now();
        LocalDate startOfCurrentWeek = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int offset = 3; offset >= 0; offset--) {
            LocalDate start = startOfCurrentWeek.minusWeeks(offset);
            LocalDate end = start.plusDays(6);
            Range weekRange = new Range(start, end);
            rows.add(row(
                    "week", weekLabel(start),
                    "tagsGranted", checklists.stream().filter(this::isClosedChecklist).filter(checklist -> withinRange(checklistFinishedDate(checklist), weekRange)).count(),
                    "issuesClosed", issues.stream().filter(this::isClosedIssue).filter(issue -> withinRange(issueClosedDate(issue), weekRange)).count(),
                    "testsDone", tests.stream().filter(this::isClosedTest).filter(test -> withinRange(testClosedDate(test), weekRange)).count(),
                    "started", checklists.stream().filter(checklist -> withinRange(checklistStartedDate(checklist), weekRange)).count()
            ));
        }
        return rows;
    }

    private Map<String, Object> buildPlanVsActualCurve(List<Checklist> checklists) {
        Map<LocalDate, Long> planned = new TreeMap<>();
        Map<LocalDate, Long> actual = new TreeMap<>();

        for (Checklist checklist : checklists) {
            LocalDate plannedDate = parseDate(checklist.getDueDate());
            if (plannedDate != null) {
                planned.merge(startOfWeek(plannedDate), 1L, Long::sum);
            }
            LocalDate actualDate = checklistFinishedDate(checklist);
            if (actualDate != null) {
                actual.merge(startOfWeek(actualDate), 1L, Long::sum);
            }
        }

        TreeSet<LocalDate> allBuckets = new TreeSet<>();
        allBuckets.addAll(planned.keySet());
        allBuckets.addAll(actual.keySet());

        long cumulativePlan = 0;
        long cumulativeActual = 0;
        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDate bucket : allBuckets) {
            long plannedCount = planned.getOrDefault(bucket, 0L);
            long actualCount = actual.getOrDefault(bucket, 0L);
            cumulativePlan += plannedCount;
            cumulativeActual += actualCount;
            points.add(row(
                    "label", weekLabel(bucket),
                    "planned", plannedCount,
                    "actual", actualCount,
                    "cumPlanned", cumulativePlan,
                    "cumActual", cumulativeActual
            ));
        }

        return row("points", points);
    }

    private Map<String, Object> buildEquipmentMatrixSummary(List<Equipment> equipment, List<Checklist> checklists) {
        Map<String, Equipment> equipmentLookup = new LinkedHashMap<>();
        for (Equipment item : equipment) {
            for (String alias : List.of(item.getExternalId(), item.getTag(), item.getName())) {
                String key = normalize(alias);
                if (StringUtils.hasText(key) && !equipmentLookup.containsKey(key)) {
                    equipmentLookup.put(key, item);
                }
            }
        }

        Map<String, Map<String, long[]>> grouped = new LinkedHashMap<>();
        for (Checklist checklist : checklists) {
            String level = checklistLevel(checklist);
            if (level == null) {
                continue;
            }
            Equipment item = resolveChecklistEquipment(checklist, equipmentLookup);
            String discipline = normalizeDiscipline(item == null ? null : item.getDiscipline());
            String type = item == null ? "Unmatched" : firstNonBlank(item.getEquipmentType(), item.getSystemName(), item.getName(), "Unmatched");
            Map<String, long[]> levelMap = grouped.computeIfAbsent(discipline + "::" + type, ignored -> {
                Map<String, long[]> created = new LinkedHashMap<>();
                LEVEL_ORDER().forEach(key -> created.put(key, new long[2]));
                return created;
            });
            long[] counts = levelMap.get(level);
            counts[0]++;
            if (isClosedChecklist(checklist)) {
                counts[1]++;
            }
        }

        List<Map<String, Object>> rows = grouped.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split("::", 2);
                    Map<String, long[]> levels = entry.getValue();
                    return row(
                            "discipline", parts[0],
                            "equipmentType", parts.length > 1 ? parts[1] : parts[0],
                            "L1", formatPercent(percent(levels.get("L1")[1], levels.get("L1")[0])),
                            "L2", formatPercent(percent(levels.get("L2")[1], levels.get("L2")[0])),
                            "L3", formatPercent(percent(levels.get("L3")[1], levels.get("L3")[0])),
                            "L4", formatPercent(percent(levels.get("L4")[1], levels.get("L4")[0]))
                    );
                })
                .sorted(Comparator.comparing(entry -> stringValue(entry.get("discipline")) + "::" + stringValue(entry.get("equipmentType")), String.CASE_INSENSITIVE_ORDER))
                .limit(10)
                .toList();

        return row("rows", rows);
    }

    private List<Map<String, Object>> buildOverdueChecklistRows(List<Checklist> checklists) {
        LocalDate today = LocalDate.now();
        return checklists.stream()
                .filter(checklist -> !isClosedChecklist(checklist))
                .map(checklist -> row(
                        "name", valueOrEmpty(checklist.getName()),
                        "status", readableLabel(defaultLabel(checklist.getStatus(), "unknown")),
                        "dueDate", valueOrEmpty(checklist.getDueDate()),
                        "daysLate", daysBetween(parseDate(checklist.getDueDate()), today),
                        "link", buildChecklistLink(checklist.getProjectId(), checklist.getExternalId())
                ))
                .filter(row -> {
                    Object daysLate = row.get("daysLate");
                    return daysLate instanceof Number number && number.longValue() > 0;
                })
                .sorted(Comparator.comparing(row -> -longValue(row.get("daysLate"))))
                .limit(5)
                .toList();
    }

    private List<Map<String, Object>> buildStaleChecklistRows(List<Checklist> checklists) {
        LocalDate today = LocalDate.now();
        return checklists.stream()
                .filter(checklist -> !isClosedChecklist(checklist))
                .map(checklist -> {
                    LocalDate anchor = firstNonNull(checklist.getLatestInProgressDate(), checklist.getLatestOpenDate(), parseDate(checklist.getUpdatedAt()), parseDate(checklist.getCreatedAt()));
                    return row(
                            "name", valueOrEmpty(checklist.getName()),
                            "status", readableLabel(defaultLabel(checklist.getStatus(), "unknown")),
                            "ageDays", daysBetween(anchor, today),
                            "link", buildChecklistLink(checklist.getProjectId(), checklist.getExternalId())
                    );
                })
                .filter(row -> longValue(row.get("ageDays")) > 30)
                .sorted(Comparator.comparing(row -> -longValue(row.get("ageDays"))))
                .limit(5)
                .toList();
    }

    private List<Map<String, Object>> buildOpenChecklistRows(List<Checklist> checklists, int minimumDays) {
        LocalDate today = LocalDate.now();
        return checklists.stream()
                .filter(checklist -> !isClosedChecklist(checklist))
                .map(checklist -> {
                    LocalDate opened = firstNonNull(checklist.getLatestOpenDate(), checklist.getLatestInProgressDate(), parseDate(checklist.getCreatedAt()));
                    return row(
                            "name", valueOrEmpty(checklist.getName()),
                            "status", readableLabel(defaultLabel(checklist.getStatus(), "unknown")),
                            "openDays", daysBetween(opened, today),
                            "link", buildChecklistLink(checklist.getProjectId(), checklist.getExternalId())
                    );
                })
                .filter(row -> longValue(row.get("openDays")) > minimumDays)
                .sorted(Comparator.comparing(row -> -longValue(row.get("openDays"))))
                .limit(5)
                .toList();
    }

    private List<Map<String, Object>> buildTopIssueEquipmentRows(List<Equipment> equipment,
                                                                 List<Checklist> checklists,
                                                                 List<Issue> issues) {
        Map<String, Equipment> byAsset = equipment.stream()
                .filter(item -> StringUtils.hasText(item.getExternalId()))
                .collect(Collectors.toMap(Equipment::getExternalId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, Long> checklistCounts = checklists.stream()
                .filter(item -> StringUtils.hasText(item.getAssetId()))
                .collect(Collectors.groupingBy(Checklist::getAssetId, Collectors.counting()));

        return issues.stream()
                .filter(issue -> StringUtils.hasText(issue.getAssetId()))
                .collect(Collectors.groupingBy(Issue::getAssetId))
                .entrySet().stream()
                .map(entry -> {
                    Equipment item = byAsset.get(entry.getKey());
                    long total = entry.getValue().size();
                    long open = entry.getValue().stream().filter(issue -> !isClosedIssue(issue)).count();
                    long closed = total - open;
                    return row(
                            "equipment", item == null ? entry.getKey() : firstNonBlank(item.getName(), item.getTag(), item.getExternalId()),
                            "type", item == null ? "Unknown" : firstNonBlank(item.getEquipmentType(), item.getSystemName(), "Unknown"),
                            "total", total,
                            "open", open,
                            "closed", closed,
                            "checklists", checklistCounts.getOrDefault(entry.getKey(), 0L),
                            "link", item == null ? "" : buildEquipmentLink(item.getProjectId(), item.getExternalId())
                    );
                })
                .sorted(Comparator.comparing((Map<String, Object> row) -> longValue(row.get("total"))).reversed())
                .limit(5)
                .toList();
    }

    private String buildExecutiveNarrative(double overallCompletion,
                                           long closedChecklists,
                                           long totalChecklists,
                                           long openIssues,
                                           LocalDate forecastDate) {
        return "Outstanding execution - " + formatPercent(overallCompletion) + " checklist completion (" + closedChecklists + " of " + totalChecklists + "). "
                + openIssues + " open issues remaining."
                + (forecastDate == null ? "" : " Forecast completion is " + prettyDate(forecastDate) + ".");
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
        manualContent.put("projectDescription", valueOrEmpty(request.getProjectDescription()));
        manualContent.put("clientName", valueOrEmpty(request.getClientName()));
        manualContent.put("projectCode", valueOrEmpty(request.getProjectCode()));
        manualContent.put("shiftWindow", valueOrEmpty(request.getShiftWindow()));
        manualContent.put("reportAuthor", valueOrEmpty(request.getReportAuthor()));
        manualContent.put("peopleOnSite", valueOrEmpty(request.getPeopleOnSite()));
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
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
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
            try {
                return buildPdfWithPdfBox(report);
            } catch (Exception fallback) {
                throw new IllegalStateException(
                        "Failed to generate PDF report. Browser path error: " + e.getMessage() + ". PDFBox fallback error: " + fallback.getMessage(),
                        fallback
                );
            }
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
        Map<String, Object> executive = asMap(reportData.get("executive"));
        Map<String, Object> projectDetails = asMap(executive.get("projectDetails"));
        Map<String, Object> executiveSummary = asMap(executive.get("executiveSummary"));
        Map<String, Object> periodInsights = asMap(executive.get("periodInsights"));
        List<Map<String, Object>> checklistStatusTable = asListOfMaps(executive.get("checklistStatusTable"));
        List<Map<String, Object>> issueStatusTable = asListOfMaps(executive.get("issueStatusTable"));
        List<Map<String, Object>> tagSummary = asListOfMaps(executive.get("tagSummary"));
        List<Map<String, Object>> trend4Weeks = asListOfMaps(executive.get("trend4Weeks"));
        List<Map<String, Object>> staleChecklists = asListOfMaps(executive.get("staleChecklists"));
        List<Map<String, Object>> overdueItems = asListOfMaps(executive.get("overdueItems"));
        List<Map<String, Object>> openLongerThan30 = asListOfMaps(executive.get("openLongerThan30"));
        List<Map<String, Object>> topIssueEquipment = asListOfMaps(executive.get("topIssueEquipment"));
        List<Map<String, Object>> peopleOnSite = asListOfMaps(executive.get("peopleOnSite"));
        List<Map<String, Object>> equipmentMatrixRows = asListOfMaps(asMap(executive.get("equipmentMatrix")).get("rows"));
        List<Map<String, Object>> planCurve = asListOfMaps(asMap(executive.get("planVsActual")).get("points"));

        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <title>Saved Report</title>
                  <style>
                    @page { size: A4 landscape; margin: 10mm 10mm; }
                    * { box-sizing: border-box; }
                    body { margin: 0; font-family: 'Segoe UI', Arial, sans-serif; color: #0f172a; background: #eef3fb; }
                    .page { width: 100%; min-height: 100%; }
                    .page-break { page-break-after: always; }
                    .hero { background: linear-gradient(135deg, #0f172a 0%, #172554 48%, #2563eb 100%); color: white; border-radius: 18px; padding: 16px 18px; box-shadow: 0 14px 40px rgba(15,23,42,0.18); }
                    .eyebrow { font-size: 9px; font-weight: 800; letter-spacing: 0.18em; text-transform: uppercase; opacity: 0.78; }
                    .title { font-size: 24px; font-weight: 800; line-height: 1.1; margin-top: 8px; }
                    .subtitle { font-size: 11px; line-height: 1.5; margin-top: 8px; color: rgba(255,255,255,0.84); }
                    .hero-grid { display: grid; grid-template-columns: 1.2fr 1fr; gap: 12px; margin-top: 12px; }
                    .hero-card { background: rgba(255,255,255,0.10); border: 1px solid rgba(255,255,255,0.12); border-radius: 12px; padding: 10px 12px; }
                    .hero-card-label { font-size: 9px; text-transform: uppercase; letter-spacing: 0.1em; opacity: 0.72; font-weight: 700; }
                    .hero-card-value { font-size: 12px; margin-top: 6px; font-weight: 700; }
                    .stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin: 12px 0 0; }
                    .stat { background: #ffffff; border: 1px solid #dbe7f5; border-top-width: 4px; border-radius: 14px; padding: 12px; box-shadow: 0 8px 24px rgba(15,23,42,0.04); }
                    .stat.stat--green { border-top-color: #22c55e; }
                    .stat.stat--amber { border-top-color: #f59e0b; }
                    .stat.stat--red { border-top-color: #ef4444; }
                    .stat.stat--blue { border-top-color: #3b82f6; }
                    .stat-label { font-size: 9px; text-transform: uppercase; letter-spacing: 0.12em; color: #64748b; font-weight: 800; }
                    .stat-value { font-size: 20px; font-weight: 800; margin-top: 6px; }
                    .stat-sub { font-size: 11px; color: #64748b; margin-top: 5px; line-height: 1.4; }
                    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
                    .grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-top: 12px; }
                    .section { background: #ffffff; border: 1px solid #dbe7f5; border-radius: 16px; overflow: hidden; box-shadow: 0 8px 24px rgba(15,23,42,0.04); }
                    .section-head { padding: 10px 14px; background: #f8fbff; border-bottom: 1px solid #e2ebf7; }
                    .section-title { font-size: 13px; font-weight: 800; color: #0f172a; }
                    .section-body { padding: 12px 14px 14px; }
                    .note { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 10px 12px; color: #334155; font-size: 11px; line-height: 1.6; white-space: pre-wrap; }
                    .mini-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 10px; }
                    .mini-stat { border-radius: 12px; padding: 10px 11px; color: #0f172a; background: #f8fbff; border: 1px solid #dbe7f5; border-left: 4px solid #94a3b8; }
                    .mini-stat.mini-stat--green { border-left-color: #22c55e; background: #f0fdf4; }
                    .mini-stat.mini-stat--amber { border-left-color: #f59e0b; background: #fffbeb; }
                    .mini-stat.mini-stat--red { border-left-color: #ef4444; background: #fef2f2; }
                    .mini-stat.mini-stat--blue { border-left-color: #3b82f6; background: #eff6ff; }
                    .mini-stat.mini-stat--slate { border-left-color: #94a3b8; background: #f8fafc; }
                    .mini-label { font-size: 9px; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; font-weight: 800; }
                    .mini-value { font-size: 18px; font-weight: 800; margin-top: 5px; }
                    .mini-sub { font-size: 10px; color: #64748b; margin-top: 4px; }
                    .tag-pill-row { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
                    .tag-pill { padding: 5px 9px; border-radius: 999px; font-size: 10px; font-weight: 700; background: #eff6ff; border: 1px solid #bfdbfe; color: #1d4ed8; }
                    table { width: 100%; border-collapse: collapse; margin-top: 8px; }
                    th { text-align: left; font-size: 9px; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; font-weight: 800; padding: 7px 8px; background: #f8fafc; border-bottom: 1px solid #e2e8f0; }
                    td { padding: 8px; font-size: 10px; color: #1e293b; border-bottom: 1px solid #eef2f7; vertical-align: top; }
                    tbody tr:nth-child(even) td { background: #fbfdff; }
                    tr:last-child td { border-bottom: none; }
                    .badge { display: inline-block; padding: 4px 8px; border-radius: 999px; font-size: 9px; font-weight: 800; letter-spacing: 0.04em; }
                    .badge.badge--green { background: #dcfce7; color: #166534; }
                    .badge.badge--amber { background: #fef3c7; color: #92400e; }
                    .badge.badge--red { background: #fee2e2; color: #991b1b; }
                    .badge.badge--blue { background: #dbeafe; color: #1d4ed8; }
                    .badge.badge--slate { background: #e2e8f0; color: #334155; }
                    .metric-chip { display: inline-block; min-width: 56px; text-align: center; padding: 5px 8px; border-radius: 10px; font-weight: 800; font-size: 10px; }
                    .metric-chip.metric-chip--green { background: #dcfce7; color: #166534; }
                    .metric-chip.metric-chip--amber { background: #fef3c7; color: #92400e; }
                    .metric-chip.metric-chip--red { background: #fee2e2; color: #991b1b; }
                    .metric-chip.metric-chip--blue { background: #dbeafe; color: #1d4ed8; }
                    .metric-chip.metric-chip--slate { background: #e2e8f0; color: #334155; }
                    .footer { margin-top: 10px; font-size: 10px; color: #64748b; text-align: right; }
                    .link { color: #2563eb; text-decoration: none; font-weight: 700; }
                    .curve-wrap { margin-top: 10px; background: #f8fbff; border: 1px solid #dbe7f5; border-radius: 14px; padding: 10px; }
                    .caption { font-size: 10px; color: #64748b; margin-top: 6px; }
                  </style>
                </head>
                <body>
                  <div class="page page-break">
                """);

        html.append("<div class=\"hero\">");
        html.append("<div class=\"eyebrow\">Modem IQ Report Export</div>");
        html.append("<div class=\"title\">").append(escapeHtml(report.getTitle())).append("</div>");
        html.append("<div class=\"subtitle\">").append(escapeHtml(report.getSubtitle())).append("</div>");
        html.append("<div class=\"hero-grid\">");
        html.append(heroCard("Project", firstNonBlank((String) project.get("projectName"), report.getProjectName(), report.getProjectId())));
        html.append(heroCard("Client / Location", firstNonBlank(stringValue(projectDetails.get("client")), (String) project.get("client"), "") + (StringUtils.hasText(stringValue(projectDetails.get("location"))) ? " - " + stringValue(projectDetails.get("location")) : "")));
        html.append(heroCard("Reporting Window", report.getDateFrom() + " to " + report.getDateTo()));
        html.append(heroCard("Generated", prettyDateTime(report.getGeneratedAt()) + " by " + escapeHtml(stringValue(projectDetails.get("author")))));
        html.append("</div></div>");

        html.append("<div class=\"stats\">");
        html.append(statCard("Checklists", metricValue(summary, "checklists", "total"), metricLine(summary, "checklists")));
        html.append(statCard("Issues", metricValue(summary, "issues", "total"), metricLine(summary, "issues")));
        html.append(statCard("Tasks", metricValue(summary, "tasks", "total"), metricLine(summary, "tasks")));
        html.append(statCard("Equipment", metricValue(summary, "equipment", "total"), "Tests in scope: " + metricValue(summary, "equipment", "tests")));
        html.append("</div>");

        html.append("<div class=\"grid-2\">");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Project Details</div></div><div class=\"section-body\">");
        appendKeyValueTable(html, projectDetails);
        html.append("</div></section>");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Executive Summary</div></div><div class=\"section-body\">");
        html.append("<div class=\"note\">").append(escapeHtml(stringValue(executiveSummary.get("narrative")))).append("</div>");
        html.append("<div class=\"mini-grid\">");
        html.append(miniStat("Overall Completion", stringValue(executiveSummary.get("overallCompletionPct")), metricValue(summary, "checklists", "closed") + "/" + metricValue(summary, "checklists", "total") + " closed"));
        html.append(miniStat("Plan Completion", stringValue(executiveSummary.get("plannedCompletionPct")), "Due-date baseline"));
        html.append(miniStat("Forecast", stringValue(executiveSummary.get("forecastCompletion")), "Projected finish"));
        html.append(miniStat("Daily Pace", stringValue(executiveSummary.get("dailyRunRate")), "checklists/day"));
        html.append("</div></div></section>");
        html.append("</div>");

        html.append("<div class=\"grid-3\">");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Period Insights</div></div><div class=\"section-body\">");
        html.append("<div class=\"mini-grid\">");
        html.append(miniStat("Tags Granted", stringValue(periodInsights.get("tagsGranted")), report.getReportType()));
        html.append(miniStat("Issues Closed", stringValue(periodInsights.get("issuesClosed")), report.getReportType()));
        html.append(miniStat("Tests Done", stringValue(periodInsights.get("testsDone")), report.getReportType()));
        html.append(miniStat("Started", stringValue(periodInsights.get("started")), report.getReportType()));
        html.append("</div>");
        html.append("<div class=\"tag-pill-row\">");
        asMap(periodInsights.get("grantedByTag")).forEach((key, value) -> html.append("<span class=\"tag-pill\">")
                .append(escapeHtml(capitalize(String.valueOf(key))))
                .append(": ")
                .append(escapeHtml(String.valueOf(value)))
                .append("</span>"));
        html.append("</div></div></section>");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Checklists</div></div><div class=\"section-body\">");
        appendSimpleTable(html, checklistStatusTable, List.of("status", "count", "percentage"), Map.of("status", "Status", "count", "Count", "percentage", "Percentage"), null);
        html.append("</div></section>");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Construction Issues</div></div><div class=\"section-body\">");
        appendSimpleTable(html, issueStatusTable, List.of("status", "count", "percentage"), Map.of("status", "Status", "count", "Count", "percentage", "Percentage"), null);
        html.append("</div></section>");
        html.append("</div>");

        html.append("<div class=\"grid-2\">");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Tag Completion Percentage</div></div><div class=\"section-body\">");
        appendSimpleTable(html, tagSummary, List.of("tagLevel", "planned", "complete", "percentage"), Map.of("tagLevel", "Tag Level", "planned", "Planned", "complete", "Complete", "percentage", "Total % Complete"), null);
        html.append("</div></section>");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Week on Week Trend</div></div><div class=\"section-body\">");
        appendSimpleTable(html, trend4Weeks, List.of("week", "tagsGranted", "issuesClosed", "testsDone", "started"), Map.of("week", "Week", "tagsGranted", "Tags", "issuesClosed", "Issues Closed", "testsDone", "Tests", "started", "Started"), null);
        html.append("</div></section>");
        html.append("</div>");

        html.append("<div class=\"footer\">High-level project insight report - page 1 of 2</div>");
        html.append("</div>");

        html.append("<div class=\"page\">");
        html.append("<div class=\"grid-2\">");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Plan vs Actual Curve</div></div><div class=\"section-body\">");
        html.append(renderCurveSvg(planCurve));
        html.append("<div class=\"caption\">Planned line uses checklist due dates. Actual line uses latest finished status dates with fallback for older rows.</div>");
        html.append("</div></section>");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">People on Site</div></div><div class=\"section-body\">");
        appendSimpleTable(html, peopleOnSite, List.of("name", "role", "onsiteOffsite", "task", "manDays"), Map.of("name", "CxA Name", "role", "Role", "onsiteOffsite", "Onsite/Offsite", "task", "Task", "manDays", "Man Days"), null);
        html.append("</div></section>");
        html.append("</div>");

        html.append("<div class=\"grid-2\">");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Equipment Matrix - High Level</div></div><div class=\"section-body\">");
        appendSimpleTable(html, equipmentMatrixRows, List.of("discipline", "equipmentType", "L1", "L2", "L3", "L4"), Map.of("discipline", "Discipline", "equipmentType", "Equipment Type", "L1", "L1", "L2", "L2", "L3", "L3", "L4", "L4"), null);
        html.append("</div></section>");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Checklist Watchlist</div></div><div class=\"section-body\">");
        appendSimpleTable(html, overdueItems, List.of("name", "status", "dueDate", "daysLate"), Map.of("name", "Overdue Item", "status", "Status", "dueDate", "Planned Date", "daysLate", "Days Late"), "link");
        html.append("<div style=\"height:8px\"></div>");
        appendSimpleTable(html, staleChecklists, List.of("name", "status", "ageDays"), Map.of("name", "Stale Checklist", "status", "Status", "ageDays", "Age"), "link");
        html.append("</div></section>");
        html.append("</div>");

        html.append("<div class=\"grid-2\">");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Open Checklists More Than 30 Days</div></div><div class=\"section-body\">");
        appendSimpleTable(html, openLongerThan30, List.of("name", "status", "openDays"), Map.of("name", "Checklist", "status", "Status", "openDays", "Open Days"), "link");
        html.append("</div></section>");
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">Top 5 Equipment With Max Issues</div></div><div class=\"section-body\">");
        appendSimpleTable(html, topIssueEquipment, List.of("equipment", "type", "total", "open", "closed", "checklists"), Map.of("equipment", "Equipment", "type", "Type", "total", "Issues", "open", "Open", "closed", "Closed", "checklists", "Checklists"), "link");
        html.append("</div></section>");
        html.append("</div>");

        html.append("<div class=\"footer\">High-level project insight report - page 2 of 2</div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private String miniStat(String label, String value, String sub) {
        return "<div class=\"mini-stat " + miniStatTone(label, value) + "\"><div class=\"mini-label\">" + escapeHtml(label) + "</div><div class=\"mini-value\">"
                + escapeHtml(value) + "</div><div class=\"mini-sub\">" + escapeHtml(sub) + "</div></div>";
    }

    private void appendKeyValueTable(StringBuilder html, Map<String, Object> values) {
        List<Map<String, Object>> rows = List.of(
                row("label", "Description", "value", stringValue(values.get("description"))),
                row("label", "Client", "value", stringValue(values.get("client"))),
                row("label", "Project ID", "value", stringValue(values.get("projectCode"))),
                row("label", "Start / Finish Time", "value", stringValue(values.get("shiftWindow"))),
                row("label", "Author", "value", stringValue(values.get("author")))
        );
        appendSimpleTable(html, rows, List.of("label", "value"), Map.of("label", "Field", "value", "Value"), null);
    }

    private void appendSimpleTable(StringBuilder html,
                                   List<Map<String, Object>> rows,
                                   List<String> fieldOrder,
                                   Map<String, String> labels,
                                   String linkField) {
        if (rows == null || rows.isEmpty()) {
            html.append("<div class=\"note\">No data available for this section.</div>");
            return;
        }
        html.append("<table><thead><tr>");
        fieldOrder.forEach(field -> html.append("<th>").append(escapeHtml(labels.getOrDefault(field, readableLabel(field)))).append("</th>"));
        if (StringUtils.hasText(linkField)) {
            html.append("<th>Link</th>");
        }
        html.append("</tr></thead><tbody>");
        rows.forEach(row -> {
            html.append("<tr>");
            fieldOrder.forEach(field -> html.append("<td>").append(renderPdfCell(field, row.get(field), row)).append("</td>"));
            if (StringUtils.hasText(linkField)) {
                String link = stringValue(row.get(linkField));
                html.append("<td>");
                if (StringUtils.hasText(link)) {
                    html.append("<a class=\"link\" href=\"").append(escapeHtml(link)).append("\">Open in CxAlloy</a>");
                } else {
                    html.append("-");
                }
                html.append("</td>");
            }
            html.append("</tr>");
        });
        html.append("</tbody></table>");
    }

    private String renderCurveSvg(List<Map<String, Object>> points) {
        if (points == null || points.isEmpty()) {
            return "<div class=\"note\">No plan or actual curve data available.</div>";
        }
        int width = 680;
        int height = 220;
        int left = 28;
        int right = 16;
        int top = 20;
        int bottom = 34;
        double maxValue = points.stream()
                .mapToDouble(point -> Math.max(number(point.get("cumPlanned")), number(point.get("cumActual"))))
                .max()
                .orElse(1);
        double usableWidth = width - left - right;
        double usableHeight = height - top - bottom;

        StringBuilder plannedPoints = new StringBuilder();
        StringBuilder actualPoints = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> point = points.get(i);
            double x = left + (points.size() == 1 ? usableWidth / 2 : (usableWidth * i / (points.size() - 1)));
            double plannedY = top + usableHeight - ((number(point.get("cumPlanned")) / maxValue) * usableHeight);
            double actualY = top + usableHeight - ((number(point.get("cumActual")) / maxValue) * usableHeight);
            plannedPoints.append(i == 0 ? "" : " ").append(formatSvg(x)).append(",").append(formatSvg(plannedY));
            actualPoints.append(i == 0 ? "" : " ").append(formatSvg(x)).append(",").append(formatSvg(actualY));
        }

        StringBuilder svg = new StringBuilder();
        svg.append("<div class=\"curve-wrap\"><svg viewBox=\"0 0 ").append(width).append(" ").append(height).append("\" width=\"100%\" height=\"220\" xmlns=\"http://www.w3.org/2000/svg\">");
        for (int i = 0; i < 4; i++) {
            double y = top + (usableHeight * i / 3.0);
            svg.append("<line x1=\"").append(left).append("\" x2=\"").append(width - right).append("\" y1=\"").append(formatSvg(y)).append("\" y2=\"").append(formatSvg(y)).append("\" stroke=\"#dbe7f5\" stroke-dasharray=\"4 4\" stroke-width=\"1\" />");
        }
        svg.append("<polyline fill=\"none\" stroke=\"#94a3b8\" stroke-width=\"3\" stroke-dasharray=\"6 6\" points=\"").append(plannedPoints).append("\" />");
        svg.append("<polyline fill=\"none\" stroke=\"#22c55e\" stroke-width=\"3.5\" points=\"").append(actualPoints).append("\" />");
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> point = points.get(i);
            double x = left + (points.size() == 1 ? usableWidth / 2 : (usableWidth * i / (points.size() - 1)));
            double plannedY = top + usableHeight - ((number(point.get("cumPlanned")) / maxValue) * usableHeight);
            double actualY = top + usableHeight - ((number(point.get("cumActual")) / maxValue) * usableHeight);
            svg.append("<circle cx=\"").append(formatSvg(x)).append("\" cy=\"").append(formatSvg(plannedY)).append("\" r=\"4.5\" fill=\"#0f172a\" stroke=\"#cbd5e1\" stroke-width=\"2\" />");
            svg.append("<circle cx=\"").append(formatSvg(x)).append("\" cy=\"").append(formatSvg(actualY)).append("\" r=\"4.5\" fill=\"#0f172a\" stroke=\"#22c55e\" stroke-width=\"2.5\" />");
            if (i < 10) {
                svg.append("<text x=\"").append(formatSvg(x)).append("\" y=\"").append(height - 12).append("\" text-anchor=\"middle\" font-size=\"9\" fill=\"#64748b\">")
                        .append(escapeHtml(stringValue(point.get("label"))))
                        .append("</text>");
            }
        }
        svg.append("</svg></div>");
        return svg.toString();
    }

    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private byte[] buildPdfWithPdfBox(SavedReport report) throws IOException {
        Map<String, Object> reportData = readMap(report.getReportJson());
        Map<String, Object> project = asMap(reportData.get("project"));
        Map<String, Object> summary = asMap(reportData.get("summary"));
        Map<String, Object> executive = asMap(reportData.get("executive"));
        Map<String, Object> projectDetails = asMap(executive.get("projectDetails"));
        Map<String, Object> executiveSummary = asMap(executive.get("executiveSummary"));
        Map<String, Object> periodInsights = asMap(executive.get("periodInsights"));
        List<Map<String, Object>> checklistStatusTable = asListOfMaps(executive.get("checklistStatusTable"));
        List<Map<String, Object>> issueStatusTable = asListOfMaps(executive.get("issueStatusTable"));
        List<Map<String, Object>> tagSummary = asListOfMaps(executive.get("tagSummary"));
        List<Map<String, Object>> trend4Weeks = asListOfMaps(executive.get("trend4Weeks"));
        List<Map<String, Object>> peopleOnSite = asListOfMaps(executive.get("peopleOnSite"));
        List<Map<String, Object>> equipmentMatrixRows = asListOfMaps(asMap(executive.get("equipmentMatrix")).get("rows"));
        List<Map<String, Object>> overdueItems = asListOfMaps(executive.get("overdueItems"));
        List<Map<String, Object>> staleChecklists = asListOfMaps(executive.get("staleChecklists"));
        List<Map<String, Object>> openLongerThan30 = asListOfMaps(executive.get("openLongerThan30"));
        List<Map<String, Object>> topIssueEquipment = asListOfMaps(executive.get("topIssueEquipment"));

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(document);

            writer.header(report.getTitle(), report.getSubtitle());
            writer.section("Project Details");
            writer.keyValue("Project", firstNonBlank((String) project.get("projectName"), report.getProjectName(), report.getProjectId()));
            writer.keyValue("Client", stringValue(projectDetails.get("client")));
            writer.keyValue("Location", stringValue(projectDetails.get("location")));
            writer.keyValue("Project ID", stringValue(projectDetails.get("projectCode")));
            writer.keyValue("Window", report.getDateFrom() + " to " + report.getDateTo());
            writer.keyValue("Author", stringValue(projectDetails.get("author")));

            writer.section("Executive Summary");
            writer.paragraph(stringValue(executiveSummary.get("narrative")));
            writer.metricRow(List.of(
                    new PdfMetric("Checklists", metricValue(summary, "checklists", "total"), new Color(34, 197, 94)),
                    new PdfMetric("Issues", metricValue(summary, "issues", "total"), new Color(245, 158, 11)),
                    new PdfMetric("Tasks", metricValue(summary, "tasks", "total"), new Color(59, 130, 246)),
                    new PdfMetric("Equipment", metricValue(summary, "equipment", "total"), new Color(148, 163, 184))
            ));
            writer.metricRow(List.of(
                    new PdfMetric("Overall", stringValue(executiveSummary.get("overallCompletionPct")), new Color(34, 197, 94)),
                    new PdfMetric("Plan", stringValue(executiveSummary.get("plannedCompletionPct")), new Color(59, 130, 246)),
                    new PdfMetric("Forecast", stringValue(executiveSummary.get("forecastCompletion")), new Color(245, 158, 11)),
                    new PdfMetric("Daily Pace", stringValue(executiveSummary.get("dailyRunRate")), new Color(239, 68, 68))
            ));

            writer.section("Period Insights");
            writer.bullet("Tags granted: " + stringValue(periodInsights.get("tagsGranted")));
            writer.bullet("Issues closed: " + stringValue(periodInsights.get("issuesClosed")));
            writer.bullet("Tests done: " + stringValue(periodInsights.get("testsDone")));
            writer.bullet("Started: " + stringValue(periodInsights.get("started")));

            writer.section("Checklist Status");
            writeSimpleRows(writer, checklistStatusTable, List.of("status", "count", "percentage"));
            writer.section("Issue Status");
            writeSimpleRows(writer, issueStatusTable, List.of("status", "count", "percentage"));
            writer.section("Tag Summary");
            writeSimpleRows(writer, tagSummary, List.of("tagLevel", "planned", "complete", "percentage"));

            writer.newPage();
            writer.section("People On Site");
            writeSimpleRows(writer, peopleOnSite, List.of("name", "role", "onsiteOffsite", "task", "manDays"));
            writer.section("Week on Week Trend");
            writeSimpleRows(writer, trend4Weeks, List.of("week", "tagsGranted", "issuesClosed", "testsDone", "started"));
            writer.section("Equipment Matrix - High Level");
            writeSimpleRows(writer, equipmentMatrixRows, List.of("discipline", "equipmentType", "L1", "L2", "L3", "L4"));
            writer.section("Checklist Watchlist");
            writeSimpleRows(writer, overdueItems, List.of("name", "status", "dueDate", "daysLate"));
            writeSimpleRows(writer, staleChecklists, List.of("name", "status", "ageDays"));
            writer.section("Open Checklists > 30 Days");
            writeSimpleRows(writer, openLongerThan30, List.of("name", "status", "openDays"));
            writer.section("Top 5 Equipment With Max Issues");
            writeSimpleRows(writer, topIssueEquipment, List.of("equipment", "type", "total", "open", "closed", "checklists"));

            writer.close();
            document.save(output);
            return output.toByteArray();
        }
    }

    private String formatSvg(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private void writeSimpleRows(PdfWriter writer, List<Map<String, Object>> rows, List<String> fields) throws IOException {
        if (rows == null || rows.isEmpty()) {
            writer.paragraph("No data available.");
            return;
        }
        int limit = Math.min(rows.size(), 8);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = rows.get(i);
            String line = fields.stream()
                    .map(field -> readableLabel(field) + ": " + stringValue(row.get(field)))
                    .collect(Collectors.joining(" | "));
            writer.bullet(line);
        }
    }

    private void appendCsvRow(StringBuilder csv, String... cells) {
        csv.append(Arrays.stream(cells)
                        .map(cell -> "\"" + String.valueOf(cell == null ? "" : cell).replace("\"", "\"\"") + "\"")
                        .collect(Collectors.joining(",")))
                .append('\n');
    }

    private String findBrowserBinary() {
        String explicit = System.getenv("BROWSER_BIN");
        if (StringUtils.hasText(explicit) && Files.exists(Path.of(explicit))) {
            return explicit;
        }
        return BROWSER_PATHS.stream()
                .filter(path -> Files.exists(Path.of(path)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No local Edge or Chrome installation found for PDF export"));
    }

    private String heroCard(String label, String value) {
        return "<div class=\"hero-card\"><div class=\"hero-card-label\">" + escapeHtml(label) + "</div><div class=\"hero-card-value\">" + escapeHtml(value) + "</div></div>";
    }

    private String statCard(String label, Object value, String sub) {
        return "<div class=\"stat " + statTone(label) + "\"><div class=\"stat-label\">" + escapeHtml(label) + "</div><div class=\"stat-value\">" + escapeHtml(String.valueOf(value)) + "</div><div class=\"stat-sub\">" + escapeHtml(sub) + "</div></div>";
    }

    private String renderPdfCell(String field, Object value, Map<String, Object> row) {
        String text = escapeHtml(stringValue(value));
        String normalizedField = normalize(field);
        if ("status".equals(normalizedField)) {
            return "<span class=\"badge " + statusBadgeTone(stringValue(value)) + "\">" + text + "</span>";
        }
        if (List.of("percentage", "l1", "l2", "l3", "l4").contains(field)) {
            return "<span class=\"metric-chip " + percentageTone(field, stringValue(value), row) + "\">" + text + "</span>";
        }
        if (List.of("count", "total", "open", "closed", "planned", "complete", "dayslate", "agedays", "opendays").contains(normalizedField)) {
            return "<span class=\"metric-chip " + numericTone(normalizedField, stringValue(value)) + "\">" + text + "</span>";
        }
        return text;
    }

    private String statTone(String label) {
        String normalized = normalize(label);
        if (normalized.contains("checklist")) return "stat--green";
        if (normalized.contains("issue")) return "stat--amber";
        if (normalized.contains("task")) return "stat--blue";
        return "stat--red";
    }

    private String miniStatTone(String label, String value) {
        String normalized = normalize(label);
        if (normalized.contains("overall") || normalized.contains("plan") || normalized.contains("forecast")) {
            return "mini-stat--green";
        }
        if (normalized.contains("pace") || normalized.contains("tag")) {
            return "mini-stat--amber";
        }
        if (normalized.contains("issue")) {
            return "mini-stat--red";
        }
        if (normalized.contains("test") || normalized.contains("started")) {
            return "mini-stat--blue";
        }
        if (normalize(value).contains("0")) {
            return "mini-stat--slate";
        }
        return "";
    }

    private String statusBadgeTone(String status) {
        String normalized = normalize(status);
        if (normalized.contains("finish") || normalized.contains("closed") || normalized.contains("pass")) return "badge--green";
        if (normalized.contains("progress") || normalized.contains("started") || normalized.contains("verify")) return "badge--amber";
        if (normalized.contains("open") || normalized.contains("fail") || normalized.contains("return")) return "badge--red";
        if (normalized.contains("owner") || normalized.contains("recommend")) return "badge--blue";
        return "badge--slate";
    }

    private String percentageTone(String field, String value, Map<String, Object> row) {
        String normalizedField = normalize(field);
        if ("l1".equals(normalizedField) || normalize(stringValue(row.get("tagLevel"))).contains("red")) return numericPercentageTone(value, "red");
        if ("l2".equals(normalizedField) || normalize(stringValue(row.get("tagLevel"))).contains("yellow")) return numericPercentageTone(value, "yellow");
        if ("l3".equals(normalizedField) || normalize(stringValue(row.get("tagLevel"))).contains("green")) return numericPercentageTone(value, "green");
        if ("l4".equals(normalizedField) || normalize(stringValue(row.get("tagLevel"))).contains("blue")) return numericPercentageTone(value, "blue");
        return numericPercentageTone(value, "");
    }

    private String numericPercentageTone(String value, String preferred) {
        Double number = parseNumeric(value);
        if ("red".equals(preferred)) return "metric-chip--red";
        if ("yellow".equals(preferred)) return "metric-chip--amber";
        if ("green".equals(preferred)) return "metric-chip--green";
        if ("blue".equals(preferred)) return "metric-chip--blue";
        if (number == null) return "metric-chip--slate";
        if (number >= 90) return "metric-chip--green";
        if (number >= 50) return "metric-chip--amber";
        if (number > 0) return "metric-chip--red";
        return "metric-chip--slate";
    }

    private String numericTone(String field, String value) {
        Double number = parseNumeric(value);
        if (number == null) return "metric-chip--slate";
        if ("closed".equals(field) || "complete".equals(field)) return number > 0 ? "metric-chip--green" : "metric-chip--slate";
        if ("open".equals(field) || "dayslate".equals(field) || "agedays".equals(field) || "opendays".equals(field)) return number > 0 ? "metric-chip--red" : "metric-chip--slate";
        if ("planned".equals(field) || "total".equals(field) || "count".equals(field)) return "metric-chip--blue";
        return "metric-chip--amber";
    }

    private Double parseNumeric(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String cleaned = value.replace("%", "").replaceAll("[^0-9.\\-]", "");
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (Exception ignored) {
            return null;
        }
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

    private List<Map<String, Object>> extractTests(List<Equipment> equipmentList) {
        List<Map<String, Object>> tests = new ArrayList<>();
        for (Equipment equipment : equipmentList) {
            if (!StringUtils.hasText(equipment.getRawJson())) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(equipment.getRawJson());
                JsonNode testNodes = root.get("tests");
                if (testNodes == null || !testNodes.isArray()) {
                    continue;
                }
                for (JsonNode test : testNodes) {
                    tests.add(row(
                            "status", firstNonBlank(text(test, "status"), text(test, "state"), "unknown"),
                            "actualFinishDate", firstNonBlank(text(test, "actual_finish_date"), text(test, "completed_date"), text(test, "date_completed"), text(test, "closed_at"), text(test, "finished_at")),
                            "updatedAt", firstNonBlank(text(test, "last_updated_at"), text(test, "updated_at"), text(test, "date_updated")),
                            "createdAt", firstNonBlank(text(test, "date_created"), text(test, "created_at"))
                    ));
                }
            } catch (Exception ignored) {
                // Ignore malformed raw payloads.
            }
        }
        return tests;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private boolean isClosedTest(Map<String, Object> test) {
        String status = normalize(stringValue(test.get("status")));
        return status.contains("pass") || status.contains("closed") || status.contains("complete") || status.contains("finished");
    }

    private LocalDate checklistFinishedDate(Checklist checklist) {
        return firstNonNull(
                checklist.getLatestFinishedDate(),
                parseDate(checklist.getUpdatedAt()),
                parseDate(checklist.getActualFinishDate()),
                parseDate(checklist.getCompletedDate()),
                parseDate(checklist.getCreatedAt())
        );
    }

    private LocalDate checklistStartedDate(Checklist checklist) {
        return firstNonNull(
                checklist.getLatestInProgressDate(),
                checklist.getLatestOpenDate(),
                parseDate(checklist.getCreatedAt())
        );
    }

    private LocalDate issueClosedDate(Issue issue) {
        return firstDate(issue.getActualFinishDate(), issue.getUpdatedAt(), issue.getCreatedAt());
    }

    private LocalDate testClosedDate(Map<String, Object> test) {
        return firstDate(
                stringValue(test.get("actualFinishDate")),
                stringValue(test.get("updatedAt")),
                stringValue(test.get("createdAt"))
        );
    }

    private double calculatePlannedCompletion(List<Checklist> checklists) {
        LocalDate today = LocalDate.now();
        long planned = checklists.stream()
                .filter(checklist -> {
                    LocalDate due = parseDate(checklist.getDueDate());
                    return due != null && !due.isAfter(today);
                })
                .count();
        if (planned == 0) {
            return percent(checklists.stream().filter(this::isClosedChecklist).count(), checklists.size());
        }
        long closed = checklists.stream()
                .filter(this::isClosedChecklist)
                .filter(checklist -> {
                    LocalDate due = parseDate(checklist.getDueDate());
                    return due != null && !due.isAfter(today);
                })
                .count();
        return percent(closed, planned);
    }

    private double calculateDailyRunRate(List<Checklist> checklists, int trailingDays) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(1, trailingDays - 1L));
        long closed = checklists.stream()
                .filter(this::isClosedChecklist)
                .filter(checklist -> {
                    LocalDate finished = checklistFinishedDate(checklist);
                    return finished != null && !finished.isBefore(start) && !finished.isAfter(end);
                })
                .count();
        return closed / (double) Math.max(1, trailingDays);
    }

    private LocalDate estimateForecastDate(List<Checklist> checklists, double dailyRunRate) {
        if (dailyRunRate <= 0) {
            return null;
        }
        long remaining = checklists.stream().filter(checklist -> !isClosedChecklist(checklist)).count();
        if (remaining == 0) {
            LocalDate latest = checklists.stream()
                    .map(this::checklistFinishedDate)
                    .filter(Objects::nonNull)
                    .max(LocalDate::compareTo)
                    .orElse(LocalDate.now());
            return latest;
        }
        long daysNeeded = (long) Math.ceil(remaining / dailyRunRate);
        return LocalDate.now().plusDays(Math.max(1, daysNeeded));
    }

    private LocalDate firstNonNull(LocalDate... values) {
        for (LocalDate value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private long daysBetween(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.max(0, Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays());
    }

    private double percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return (numerator * 100.0) / denominator;
    }

    private String formatPercent(double value) {
        return String.format(Locale.US, "%.1f%%", value);
    }

    private String formatOneDecimal(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String buildChecklistLink(String projectId, String checklistExternalId) {
        if (!StringUtils.hasText(projectId) || !StringUtils.hasText(checklistExternalId)) {
            return "";
        }
        return "https://tq.cxalloy.com/project/" + projectId + "/checklists/" + checklistExternalId;
    }

    private String buildEquipmentLink(String projectId, String equipmentExternalId) {
        if (!StringUtils.hasText(projectId) || !StringUtils.hasText(equipmentExternalId)) {
            return "";
        }
        return "https://tq.cxalloy.com/project/" + projectId + "/equipment/" + equipmentExternalId;
    }

    private String checklistLevel(Checklist checklist) {
        String source = Stream.of(checklist.getTagLevel(), checklist.getChecklistType(), checklist.getName())
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .collect(Collectors.joining(" "));
        if (source.contains("red") || source.contains("l1") || source.contains("level-1") || source.contains("level 1")) return "L1";
        if (source.contains("yellow") || source.contains("l2") || source.contains("level-2") || source.contains("level 2")) return "L2";
        if (source.contains("green") || source.contains("l3") || source.contains("level-3") || source.contains("level 3")) return "L3";
        if (source.contains("blue") || source.contains("l4") || source.contains("level-4") || source.contains("level 4")) return "L4";
        return null;
    }

    private Equipment resolveChecklistEquipment(Checklist checklist, Map<String, Equipment> lookup) {
        String assetId = normalize(checklist.getAssetId());
        if (StringUtils.hasText(assetId) && lookup.containsKey(assetId)) {
            return lookup.get(assetId);
        }
        String name = normalize(checklist.getName());
        if (StringUtils.hasText(name) && lookup.containsKey(name)) {
            return lookup.get(name);
        }
        String prefix = StringUtils.hasText(name) ? name.split(" - ")[0] : "";
        return StringUtils.hasText(prefix) ? lookup.get(prefix) : null;
    }

    private String normalizeDiscipline(String value) {
        String raw = normalize(value);
        if (!StringUtils.hasText(raw)) {
            return "Other";
        }
        if (raw.contains("elec") || raw.contains("power") || raw.contains("control") || raw.contains("epms") || raw.contains("busway")) {
            return "Electrical";
        }
        if (raw.contains("mech") || raw.contains("hvac") || raw.contains("pipe") || raw.contains("water") || raw.contains("fuel") || raw.contains("cool")) {
            return "Mechanical";
        }
        return "Other";
    }

    private List<String> LEVEL_ORDER() {
        return List.of("L1", "L2", "L3", "L4");
    }

    private LocalDate startOfWeek(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    private String weekLabel(LocalDate date) {
        WeekFields wf = WeekFields.ISO;
        return date.getYear() + "-W" + String.format(Locale.US, "%02d", date.get(wf.weekOfWeekBasedYear()));
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

    private record PdfMetric(String label, String value, Color color) {}

    private static final class PdfWriter {
        private static final PDFont FONT_REGULAR = PDType1Font.HELVETICA;
        private static final PDFont FONT_BOLD = PDType1Font.HELVETICA_BOLD;

        private final PDDocument document;
        private PDPage page;
        private PDPageContentStream content;
        private float pageWidth;
        private float pageHeight;
        private float cursorY;

        private PdfWriter(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        private void newPage() throws IOException {
            closeCurrent();
            this.page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            this.document.addPage(page);
            this.pageWidth = page.getMediaBox().getWidth();
            this.pageHeight = page.getMediaBox().getHeight();
            this.content = new PDPageContentStream(document, page);
            this.cursorY = pageHeight - 30;
        }

        private void header(String title, String subtitle) throws IOException {
            writeWrapped(title, FONT_BOLD, 20, new Color(15, 23, 42), 30, pageWidth - 60, 24);
            writeWrapped(subtitle, FONT_REGULAR, 10, new Color(71, 85, 105), 30, pageWidth - 60, 14);
            cursorY -= 6;
        }

        private void section(String title) throws IOException {
            ensureSpace(26);
            cursorY -= 4;
            writeLine(title, FONT_BOLD, 14, new Color(29, 78, 216), 30, cursorY);
            cursorY -= 14;
            drawDivider();
            cursorY -= 8;
        }

        private void keyValue(String key, String value) throws IOException {
            ensureSpace(14);
            writeLine(key + ": " + value, FONT_REGULAR, 10, new Color(15, 23, 42), 34, cursorY);
            cursorY -= 12;
        }

        private void paragraph(String text) throws IOException {
            writeWrapped(text, FONT_REGULAR, 10, new Color(51, 65, 85), 34, pageWidth - 68, 13);
            cursorY -= 4;
        }

        private void bullet(String text) throws IOException {
            ensureSpace(14);
            writeWrapped("- " + text, FONT_REGULAR, 10, new Color(30, 41, 59), 38, pageWidth - 76, 13);
        }

        private void metricRow(List<PdfMetric> metrics) throws IOException {
            float x = 30;
            float width = (pageWidth - 75) / 4f;
            float height = 42;
            ensureSpace(height + 8);
            for (PdfMetric metric : metrics) {
                fillRect(x, cursorY - height + 4, width, height, lighten(metric.color));
                strokeRect(x, cursorY - height + 4, width, height, metric.color);
                writeLine(metric.label, FONT_BOLD, 9, new Color(71, 85, 105), x + 8, cursorY - 10);
                writeLine(metric.value, FONT_BOLD, 16, metric.color, x + 8, cursorY - 27);
                x += width + 5;
            }
            cursorY -= height + 10;
        }

        private void ensureSpace(float needed) throws IOException {
            if (cursorY - needed < 26) {
                newPage();
            }
        }

        private void drawDivider() throws IOException {
            content.setStrokingColor(new Color(219, 234, 254));
            content.moveTo(30, cursorY);
            content.lineTo(pageWidth - 30, cursorY);
            content.stroke();
        }

        private void writeWrapped(String text, PDFont font, float fontSize, Color color, float x, float width, float lineHeight) throws IOException {
            if (!StringUtils.hasText(text)) {
                return;
            }
            for (String line : wrapText(text, font, fontSize, width)) {
                ensureSpace(lineHeight);
                writeLine(line, font, fontSize, color, x, cursorY);
                cursorY -= lineHeight;
            }
        }

        private List<String> wrapText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
            String sanitizedText = sanitizePdfText(text);
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : sanitizedText.split("\\s+")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                float width = font.getStringWidth(candidate) / 1000f * fontSize;
                if (width > maxWidth && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }

        private void writeLine(String text, PDFont font, float fontSize, Color color, float x, float y) throws IOException {
            content.beginText();
            content.setFont(font, fontSize);
            content.setNonStrokingColor(color);
            content.newLineAtOffset(x, y);
            content.showText(sanitizePdfText(text));
            content.endText();
        }

        private void fillRect(float x, float y, float width, float height, Color color) throws IOException {
            content.setNonStrokingColor(color);
            content.addRect(x, y, width, height);
            content.fill();
        }

        private void strokeRect(float x, float y, float width, float height, Color color) throws IOException {
            content.setStrokingColor(color);
            content.addRect(x, y, width, height);
            content.stroke();
        }

        private Color lighten(Color color) {
            return new Color(
                    Math.min(255, (int) (color.getRed() * 0.15 + 220)),
                    Math.min(255, (int) (color.getGreen() * 0.15 + 220)),
                    Math.min(255, (int) (color.getBlue() * 0.15 + 220))
            );
        }

        private void closeCurrent() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }

        private void close() throws IOException {
            closeCurrent();
        }
    }

    private static String sanitizePdfText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u2022', '-')
                .replace("\u2026", "...")
                .replace('\u00A0', ' ');
    }

    private record Range(LocalDate from, LocalDate to) {}
}
