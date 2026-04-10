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
    private static final List<String> DELIVERY_TAG_ORDER = List.of("red", "yellow", "green", "blue", "white");
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
    private final ProviderContextService providerContextService;
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
                              ProviderContextService providerContextService,
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
        this.providerContextService = providerContextService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getReports(String projectId) {
        projectAccessService.requireProjectAccess(projectId);
        return savedReportRepository.findByProjectIdAndProviderOrderByGeneratedAtDesc(
                        projectId, providerContextService.currentProviderKey()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReport(Long id) {
        SavedReport report = savedReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + id));
        requireCurrentProvider(report.getProvider(), "Report not found: " + id);
        projectAccessService.requireProjectAccess(report.getProjectId());
        return toResponse(report);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getFilterOptions(String projectId) {
        projectAccessService.requireProjectAccess(projectId);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("issueStatuses", issueRepository.findByProjectId(projectId).stream()
                .filter(issue -> providerContextService.matchesCurrentProvider(issue.getProvider()))
                .map(Issue::getStatus)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        options.put("checklistStatuses", checklistRepository.findByProjectId(projectId).stream()
                .filter(checklist -> providerContextService.matchesCurrentProvider(checklist.getProvider()))
                .map(Checklist::getStatus)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        options.put("equipmentTypes", equipmentRepository.findByProjectId(projectId).stream()
                .filter(equipment -> providerContextService.matchesCurrentProvider(equipment.getProvider()))
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

        Project project = projectRepository.findByExternalIdAndProvider(projectId, providerContextService.currentProviderKey())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        String normalizedReportType = normalizeReportType(request.getReportType());
        Range range = resolveRange(normalizedReportType, request.getDateFrom(), request.getDateTo());
        List<String> sections = normalizeSections(request.getSections());
        List<Map<String, Object>> sectionSettings = normalizeSectionSettings(sections, request.getSectionSettings());

        List<Checklist> allChecklists = hydrateChecklistStatusDates(projectId, checklistRepository.findByProjectId(projectId).stream()
                .filter(checklist -> providerContextService.matchesCurrentProvider(checklist.getProvider()))
                .toList());
        List<Issue> allIssues = issueRepository.findByProjectId(projectId).stream()
                .filter(issue -> providerContextService.matchesCurrentProvider(issue.getProvider()))
                .toList();
        List<Equipment> allEquipment = equipmentRepository.findByProjectId(projectId).stream()
                .filter(equipment -> providerContextService.matchesCurrentProvider(equipment.getProvider()))
                .toList();
        List<CxTask> allTasks = taskRepository.findByProjectId(projectId).stream()
                .filter(task -> providerContextService.matchesCurrentProvider(task.getProvider()))
                .toList();
        List<Person> persons = personRepository.findByProjectId(projectId);

        List<Checklist> checklists = applyChecklistFilters(allChecklists, request, range);
        List<Issue> issues = applyIssueFilters(allIssues, request, range);
        List<Equipment> equipment = applyEquipmentFilters(allEquipment, request);
        List<CxTask> tasks = allTasks;

        Map<String, Object> filters = buildFilters(request, range);
        Map<String, Object> manualContent = buildManualContent(request);
        Map<String, Object> reportData = buildReportData(
                normalizedReportType,
                project,
                range,
                sections,
                sectionSettings,
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
        report.setProvider(providerContextService.currentProviderKey());
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
        requireCurrentProvider(report.getProvider(), "Report not found: " + id);
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
        requireCurrentProvider(report.getProvider(), "Report not found: " + id);
        String base = slugify(StringUtils.hasText(report.getTitle()) ? report.getTitle() : "saved-report-" + id);
        String normalizedFormat = normalizeDownloadFormat(format);
        return base + ("csv".equals(normalizedFormat) ? ".csv" : "pdf".equals(normalizedFormat) ? ".pdf" : ".json");
    }

    private Map<String, Object> buildReportData(String reportType,
                                                Project project,
                                                Range range,
                                                List<String> sections,
                                                List<Map<String, Object>> sectionSettings,
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
        data.put("sectionSettings", sectionSettings);

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
        data.put("sectionData", buildSectionData(reportType, range, sections, checklists, issues, equipment, tasks, persons, manualContent));
        data.put("executive", buildExecutiveData(project, range, allChecklists, allIssues, allEquipment, allTasks, persons, manualContent));
        return data;
    }

    private void requireCurrentProvider(String provider, String message) {
        if (!providerContextService.matchesCurrentProvider(provider)) {
            throw new IllegalArgumentException(message);
        }
    }

    private Map<String, Object> buildSectionData(String reportType,
                                                 Range range,
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
            List<Map<String, Object>> completionByCategory = buildChecklistCompletionByCategory(checklists);
            List<Map<String, Object>> weekOverWeek = buildChecklistWeekOverWeek(checklists, range, 8);
            List<Map<String, Object>> checklistRows = checklists.stream()
                    .sorted(Comparator.comparing(checklist -> isClosedChecklist(checklist) ? 1 : 0))
                    .limit(rowLimit)
                    .map(checklist -> row(
                            "name", valueOrEmpty(checklist.getName()),
                            "status", defaultLabel(checklist.getStatus(), "Unknown"),
                            "category", firstNonBlank(tagDisplayLabel(defaultLabel(checklist.getTagLevel(), "")), defaultLabel(checklist.getChecklistType(), "Unknown")),
                            "tagLevel", defaultLabel(checklist.getTagLevel(), "unknown"),
                            "assignedTo", valueOrEmpty(checklist.getAssignedTo()),
                            "dueDate", valueOrEmpty(checklist.getDueDate()),
                            "openDays", checklistOpenDays(checklist)
                    ))
                    .toList();
            sectionData.put("checklists", Map.of(
                    "byTag", byTag,
                    "byStatus", byStatus,
                    "progressByCategory", checklistProgressByTag(checklists),
                    "completionByCategory", completionByCategory,
                    "openByCategory", completionByCategory.stream().collect(Collectors.toMap(
                            row -> stringValue(row.get("category")),
                            row -> longValue(row.get("total")) - longValue(row.get("closed")),
                            (left, right) -> left,
                            LinkedHashMap::new
                    )),
                    "weekOverWeek", weekOverWeek,
                    "outlierRows", buildChecklistOutlierRows(checklists),
                    "rows", checklistRows,
                    "totalRows", checklists.size()
            ));
        }

        if (sections.contains("issues")) {
            Map<String, String> personCompanies = buildPersonCompanyLookup(persons);
            Map<String, Object> companyBreakdown = buildIssueCompanyBreakdown(issues, persons);
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
                            "company", resolveIssueCompany(issue, personCompanies),
                            "assignee", valueOrEmpty(issue.getAssignee()),
                            "location", valueOrEmpty(issue.getLocation())
                    ))
                    .toList();
            Map<String, Object> issueSection = new LinkedHashMap<>();
            issueSection.put("byStatus", byStatus);
            issueSection.put("byPriority", byPriority);
            issueSection.put("topLocations", topIssueLocations(issues));
            issueSection.put("progressByCategory", issueProgressByPriority(issues));
            issueSection.put("rows", issueRows);
            issueSection.put("totalRows", issues.size());
            issueSection.putAll(companyBreakdown);
            sectionData.put("issues", issueSection);
        }

        if (sections.contains("tests")) {
            int totalTests = equipment.stream().mapToInt(item -> item.getTestCount() == null ? 0 : item.getTestCount()).sum();
            Map<String, Long> byType = orderedCountMap(
                    equipment.stream()
                            .filter(item -> item.getTestCount() != null && item.getTestCount() > 0)
                            .collect(Collectors.groupingBy(item -> defaultLabel(item.getEquipmentType(), "Unknown"), Collectors.counting())),
                    List.of()
            );
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
            sectionData.put("tests", Map.of(
                    "totalTests", totalTests,
                    "byType", byType,
                    "rows", testRows,
                    "totalRows", testRows.size()
            ));
        }

        if (sections.contains("equipment")) {
            Map<String, Long> byType = orderedCountMap(
                    equipment.stream().collect(Collectors.groupingBy(item -> defaultLabel(item.getEquipmentType(), "Unknown"), Collectors.counting())),
                    List.of()
            );
            Map<String, Long> byStatus = orderedCountMap(
                    equipment.stream().collect(Collectors.groupingBy(item -> defaultLabel(item.getStatus(), "Unknown"), Collectors.counting())),
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
            sectionData.put("equipment", Map.of(
                    "byType", byType,
                    "byStatus", byStatus,
                    "rows", equipmentRows,
                    "totalRows", equipment.size()
            ));
        }

        if (sections.contains("summary")) {
            sectionData.put("summary", Map.of("text", valueOrEmpty((String) manualContent.get("summaryText"))));
        }
        if (sections.contains("custom")) {
            sectionData.put("custom", Map.of("text", valueOrEmpty((String) manualContent.get("customSectionText"))));
        }
        if (sections.contains("progressphotos")) {
            sectionData.put("progressphotos", Map.of("text", valueOrEmpty((String) manualContent.get("progressPhotosText"))));
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
        Map<String, ChecklistStatusDate> byChecklist = checklistStatusDateRepository
                .findByProjectIdAndProvider(projectId, providerContextService.currentProviderKey()).stream()
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
        Map<String, Object> tagVelocity = buildTagVelocity(checklists, range);
        List<Map<String, Object>> keyProjectDelivery = buildKeyProjectDeliveryRows(checklists, range);

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
                "keyProjectDelivery", keyProjectDelivery,
                "tagVelocity", tagVelocity,
                "issueStatusTable", buildIssueStatusTable(issues),
                "checklistStatusTable", buildChecklistStatusTable(checklists),
                "trend4Weeks", buildFourWeekTrend(checklists, issues, tests),
                "planVsActual", buildPlanVsActualCurve(checklists),
                "equipmentMatrix", buildEquipmentMatrixSummary(equipment, checklists),
                "overdueItems", buildOverdueChecklistRows(checklists),
                "staleChecklists", buildStaleChecklistRows(checklists),
                "openLongerThan30", buildOpenChecklistRows(checklists, 30),
                "topIssueEquipment", buildTopIssueEquipmentRows(equipment, checklists, issues),
                "generalComment", buildGeneralComment(manualContent, executiveSummary, tagVelocity, keyProjectDelivery)
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
        return DELIVERY_TAG_ORDER.stream()
                .map(tag -> {
                    List<Checklist> bucket = checklists.stream()
                            .filter(checklist -> tag.equalsIgnoreCase(defaultLabel(checklist.getTagLevel(), "unknown")))
                            .toList();
                    long total = bucket.size();
                    long closed = bucket.stream().filter(this::isClosedChecklist).count();
                    return row(
                            "tagLevel", tagDisplayLabel(tag),
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

    private List<Map<String, Object>> buildChecklistCompletionByCategory(List<Checklist> checklists) {
        return DELIVERY_TAG_ORDER.stream()
                .map(tag -> {
                    List<Checklist> bucket = checklists.stream()
                            .filter(checklist -> tag.equalsIgnoreCase(defaultLabel(checklist.getTagLevel(), "unknown")))
                            .toList();
                    long total = bucket.size();
                    long closed = bucket.stream().filter(this::isClosedChecklist).count();
                    double pct = percent(closed, total);
                    return row(
                            "category", tagDisplayLabel(tag),
                            "closed", closed,
                            "total", total,
                            "percentageValue", pct,
                            "percentage", formatPercent(pct)
                    );
                })
                .filter(entry -> longValue(entry.get("total")) > 0)
                .toList();
    }

    private List<Map<String, Object>> buildChecklistWeekOverWeek(List<Checklist> checklists, Range range, int weeks) {
        LocalDate anchor = startOfWeek(range == null ? LocalDate.now() : range.to());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int offset = weeks - 1; offset >= 0; offset--) {
            LocalDate start = anchor.minusWeeks(offset);
            LocalDate end = start.plusDays(6);
            long closed = checklists.stream()
                    .filter(this::isClosedChecklist)
                    .filter(checklist -> {
                        LocalDate finished = checklistFinishedDate(checklist);
                        return finished != null && !finished.isBefore(start) && !finished.isAfter(end);
                    })
                    .count();
            rows.add(row(
                    "week", start.format(DateTimeFormatter.ofPattern("d-MMM")),
                    "closed", closed
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> buildChecklistOutlierRows(List<Checklist> checklists) {
        return checklists.stream()
                .filter(checklist -> !isClosedChecklist(checklist))
                .map(checklist -> row(
                        "name", valueOrEmpty(checklist.getName()),
                        "status", readableLabel(defaultLabel(checklist.getStatus(), "unknown")),
                        "category", firstNonBlank(tagDisplayLabel(defaultLabel(checklist.getTagLevel(), "")), defaultLabel(checklist.getChecklistType(), "Unknown")),
                        "openDays", checklistOpenDays(checklist)
                ))
                .sorted(Comparator.comparing((Map<String, Object> row) -> longValue(row.get("openDays"))).reversed())
                .limit(6)
                .toList();
    }

    private Map<String, Object> buildTagVelocity(List<Checklist> checklists, Range range) {
        List<Checklist> closed = checklists.stream()
                .filter(this::isClosedChecklist)
                .filter(checklist -> checklistFinishedDate(checklist) != null)
                .toList();
        LocalDate firstClosed = closed.stream()
                .map(this::checklistFinishedDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        LocalDate lastClosed = closed.stream()
                .map(this::checklistFinishedDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        long activeWeeks = 0;
        long activeBusinessDays = 0;
        if (firstClosed != null && lastClosed != null) {
            activeWeeks = Math.max(1, (daysBetween(firstClosed, lastClosed) / 7) + 1);
            LocalDate cursor = firstClosed;
            while (!cursor.isAfter(lastClosed)) {
                if (cursor.getDayOfWeek().getValue() <= 5) {
                    activeBusinessDays++;
                }
                cursor = cursor.plusDays(1);
            }
        }
        long closedInWindow = closed.stream()
                .filter(checklist -> withinRange(checklistFinishedDate(checklist), range))
                .count();
        long startedInWindow = checklists.stream()
                .filter(checklist -> withinRange(checklistStartedDate(checklist), range))
                .count();
        return row(
                "avgPerWeek", formatOneDecimal(closed.size() / (double) Math.max(1, activeWeeks)),
                "avgPerDay", formatOneDecimal(closed.size() / (double) Math.max(1, activeBusinessDays)),
                "closedInWindow", closedInWindow,
                "startedInWindow", startedInWindow,
                "weekOverWeek", buildChecklistWeekOverWeek(checklists, range, 8)
        );
    }

    private List<Map<String, Object>> buildKeyProjectDeliveryRows(List<Checklist> checklists, Range range) {
        return DELIVERY_TAG_ORDER.stream()
                .map(tag -> {
                    List<Checklist> bucket = checklists.stream()
                            .filter(checklist -> tag.equalsIgnoreCase(defaultLabel(checklist.getTagLevel(), "unknown")))
                            .toList();
                    long total = bucket.size();
                    if (total == 0) {
                        return null;
                    }
                    long closed = bucket.stream().filter(this::isClosedChecklist).count();
                    long closedInWindow = bucket.stream()
                            .filter(this::isClosedChecklist)
                            .filter(checklist -> withinRange(checklistFinishedDate(checklist), range))
                            .count();
                    long open = total - closed;
                    double pct = percent(closed, total);
                    return row(
                            "tagLevel", tagDisplayLabel(tag),
                            "planned", total,
                            "complete", closed,
                            "open", open,
                            "percentageValue", pct,
                            "percentage", formatPercent(pct),
                            "closedInWindow", closedInWindow,
                            "comment", buildTagDeliveryComment(tag, pct, open, closedInWindow)
                    );
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private String buildTagDeliveryComment(String tag, double pct, long open, long closedInWindow) {
        StringBuilder builder = new StringBuilder();
        builder.append(tagDisplayLabel(tag)).append(" is ").append(formatPercent(pct)).append(" complete.");
        if (open == 0) {
            builder.append(" All deliverables are closed out.");
        } else if (open == 1) {
            builder.append(" 1 item remains open.");
        } else {
            builder.append(" ").append(open).append(" items remain open.");
        }
        if (closedInWindow > 0) {
            builder.append(" ").append(closedInWindow).append(" were completed in the selected window.");
        }
        return builder.toString();
    }

    private String buildGeneralComment(Map<String, Object> manualContent,
                                       Map<String, Object> executiveSummary,
                                       Map<String, Object> tagVelocity,
                                       List<Map<String, Object>> keyProjectDelivery) {
        String manual = stringValue(manualContent.get("summaryText"));
        if (StringUtils.hasText(manual)) {
            return manual;
        }
        String headline = stringValue(executiveSummary.get("narrative"));
        String strongestTag = keyProjectDelivery.stream()
                .map(row -> stringValue(row.get("comment")))
                .findFirst()
                .orElse("");
        return Stream.of(
                        headline,
                        "Average tag pace is " + stringValue(tagVelocity.get("avgPerWeek")) + " per week and " + stringValue(tagVelocity.get("avgPerDay")) + " per day.",
                        strongestTag
                )
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" "));
    }

    private Map<String, Object> buildIssueCompanyBreakdown(List<Issue> issues, List<Person> persons) {
        Map<String, String> personCompanies = buildPersonCompanyLookup(persons);
        List<Map<String, Object>> companyRows = issues.stream()
                .collect(Collectors.groupingBy(issue -> resolveIssueCompany(issue, personCompanies), LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> {
                    long open = entry.getValue().stream().filter(issue -> !isClosedIssue(issue)).count();
                    long closed = entry.getValue().stream().filter(this::isClosedIssue).count();
                    double avgClosureDays = averageIssueClosureDays(entry.getValue());
                    return row(
                            "company", entry.getKey(),
                            "open", open,
                            "closed", closed,
                            "total", entry.getValue().size(),
                            "avgClosureDaysValue", avgClosureDays,
                            "avgClosureDays", formatOneDecimal(avgClosureDays)
                    );
                })
                .sorted(Comparator
                        .comparingLong((Map<String, Object> row) -> longValue(row.get("open"))).reversed()
                        .thenComparing(Comparator.comparingLong((Map<String, Object> row) -> longValue(row.get("total"))).reversed())
                        .thenComparing(row -> stringValue(row.get("company")), String.CASE_INSENSITIVE_ORDER))
                .limit(8)
                .toList();

        LinkedHashMap<String, Long> openByCompany = companyRows.stream()
                .filter(row -> longValue(row.get("open")) > 0)
                .collect(Collectors.toMap(
                        row -> stringValue(row.get("company")),
                        row -> longValue(row.get("open")),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        if (openByCompany.isEmpty()) {
            openByCompany.put("Overall", 0L);
        }

        LinkedHashMap<String, Double> avgClosureByCompany = companyRows.stream()
                .filter(row -> number(row.get("avgClosureDaysValue")) > 0)
                .collect(Collectors.toMap(
                        row -> stringValue(row.get("company")),
                        row -> number(row.get("avgClosureDaysValue")),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        if (avgClosureByCompany.isEmpty()) {
            avgClosureByCompany.put("Overall", averageIssueClosureDays(issues));
        }

        List<Map<String, Object>> openRows = issues.stream()
                .filter(issue -> !isClosedIssue(issue))
                .sorted(Comparator.comparing((Issue issue) -> normalize(issue.getPriority()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(issue -> valueOrEmpty(issue.getTitle()), String.CASE_INSENSITIVE_ORDER))
                .limit(6)
                .map(issue -> row(
                        "title", valueOrEmpty(issue.getTitle()),
                        "company", resolveIssueCompany(issue, personCompanies),
                        "status", readableLabel(defaultLabel(issue.getStatus(), "unknown")),
                        "priority", valueOrEmpty(issue.getPriority()),
                        "location", valueOrEmpty(issue.getLocation())
                ))
                .toList();

        return row(
                "byCompany", openByCompany,
                "openByCompany", openByCompany,
                "avgClosureByCompany", avgClosureByCompany,
                "companyRows", companyRows,
                "openRows", openRows,
                "averageClosureDays", formatOneDecimal(averageIssueClosureDays(issues))
        );
    }

    private double averageIssueClosureDays(List<Issue> issues) {
        List<Long> durations = issues.stream()
                .filter(this::isClosedIssue)
                .map(issue -> {
                    LocalDate created = parseDate(issue.getCreatedAt());
                    LocalDate closed = issueClosedDate(issue);
                    return created == null || closed == null ? null : daysBetween(created, closed);
                })
                .filter(Objects::nonNull)
                .toList();
        if (durations.isEmpty()) {
            return 0;
        }
        return durations.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private Map<String, String> buildPersonCompanyLookup(List<Person> persons) {
        LinkedHashMap<String, String> lookup = new LinkedHashMap<>();
        for (Person person : persons) {
            String company = firstNonBlank(person.getCompany(), "Unknown");
            rememberCompanyAlias(lookup, personName(person), company);
            rememberCompanyAlias(lookup, person.getEmail(), company);
            rememberCompanyAlias(lookup, emailLocalPart(person.getEmail()), company);
            rememberCompanyAlias(lookup, person.getExternalId(), company);
            rememberCompanyAlias(lookup, firstNonBlank(person.getFirstName(), "") + " " + firstNonBlank(person.getLastName(), ""), company);
        }
        return lookup;
    }

    private void rememberCompanyAlias(Map<String, String> lookup, String alias, String company) {
        String normalized = normalize(alias);
        if (StringUtils.hasText(normalized) && !lookup.containsKey(normalized)) {
            lookup.put(normalized, company);
        }
    }

    private String emailLocalPart(String email) {
        if (!StringUtils.hasText(email) || !email.contains("@")) {
            return "";
        }
        return email.substring(0, email.indexOf('@'));
    }

    private String resolveIssueCompany(Issue issue, Map<String, String> personCompanies) {
        for (String candidate : Stream.of(issue.getAssignee(), issue.getReporter(), issue.getCreatedBy()).toList()) {
            String resolved = resolveCompanyAlias(personCompanies, candidate);
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }
        String fromRaw = extractIssueCompanyFromRaw(issue);
        return StringUtils.hasText(fromRaw) ? fromRaw : "Unknown";
    }

    private String resolveCompanyAlias(Map<String, String> personCompanies, String candidate) {
        String normalized = normalize(candidate);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        if (personCompanies.containsKey(normalized)) {
            return personCompanies.get(normalized);
        }
        String localPart = emailLocalPart(candidate);
        if (StringUtils.hasText(localPart) && personCompanies.containsKey(normalize(localPart))) {
            return personCompanies.get(normalize(localPart));
        }
        return personCompanies.entrySet().stream()
                .filter(entry -> normalized.contains(entry.getKey()) || entry.getKey().contains(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private String extractIssueCompanyFromRaw(Issue issue) {
        if (!StringUtils.hasText(issue.getRawJson())) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(issue.getRawJson());
            return Stream.of("company", "company_name", "vendor", "vendor_name", "contractor", "contractor_name", "assigned_company")
                    .map(field -> text(root, field))
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse("");
        } catch (Exception ignored) {
            return "";
        }
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
                .toList();
    }

    private List<Issue> applyIssueFilters(List<Issue> source, SavedReportRequest request, Range range) {
        Set<String> statuses = normalizeValues(request.getIssueStatuses());
        return source.stream()
                .filter(issue -> statuses.isEmpty() || statuses.contains(normalize(issue.getStatus())))
                .toList();
    }

    private List<Equipment> applyEquipmentFilters(List<Equipment> source, SavedReportRequest request) {
        Set<String> types = normalizeValues(request.getEquipmentTypes());
        return source.stream()
                .filter(item -> types.isEmpty() || types.contains(normalize(item.getEquipmentType())))
                .toList();
    }

    private List<CxTask> applyTaskFilters(List<CxTask> source, Range range) {
        return source;
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
        manualContent.put("customSectionText", valueOrEmpty(request.getCustomSectionText()));
        manualContent.put("progressPhotosText", valueOrEmpty(request.getProgressPhotosText()));
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
        Path browserProfileDir = null;
        Path browserRuntimeDir = null;
        try {
            htmlPath = Files.createTempFile("saved-report-", ".html");
            pdfPath = Files.createTempFile("saved-report-", ".pdf");
            browserProfileDir = Files.createTempDirectory("saved-report-browser-profile-");
            browserRuntimeDir = Files.createTempDirectory("saved-report-browser-runtime-");
            Files.writeString(htmlPath, buildPdfHtml(report), StandardCharsets.UTF_8);

            String browserPath = findBrowserBinary();
            ProcessBuilder processBuilder = new ProcessBuilder(
                    browserPath,
                    "--headless",
                    "--disable-gpu",
                    "--no-sandbox",
                    "--disable-setuid-sandbox",
                    "--disable-dev-shm-usage",
                    "--no-zygote",
                    "--allow-file-access-from-files",
                    "--user-data-dir=" + browserProfileDir,
                    "--no-pdf-header-footer",
                    "--print-to-pdf=" + pdfPath.toString(),
                    htmlPath.toUri().toString()
            ).redirectErrorStream(true);
            processBuilder.environment().put("HOME", browserProfileDir.toString());
            processBuilder.environment().put("XDG_RUNTIME_DIR", browserRuntimeDir.toString());
            Process process = processBuilder.start();

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
            try {
                if (browserProfileDir != null) Files.walk(browserProfileDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
            try {
                if (browserRuntimeDir != null) Files.walk(browserRuntimeDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
        }
    }

    private String buildPdfHtml(SavedReport report) {
        Map<String, Object> reportData = readMap(report.getReportJson());
        Map<String, Object> project = asMap(reportData.get("project"));
        Map<String, Object> summary = asMap(reportData.get("summary"));
        Map<String, Object> executive = asMap(reportData.get("executive"));
        return buildSectionAwarePdfHtml(report, reportData, project, summary, executive);
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
        return buildSectionAwarePdfWithPdfBox(report, reportData, project, summary, executive);
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

    private String buildSectionAwarePdfHtml(SavedReport report,
                                            Map<String, Object> reportData,
                                            Map<String, Object> project,
                                            Map<String, Object> summary,
                                            Map<String, Object> executive) {
        Map<String, Object> projectDetails = asMap(executive.get("projectDetails"));
        List<String> sections = normalizeSections(asStringList(reportData.get("sections")));
        List<Map<String, Object>> sectionSettings = normalizeSectionSettings(sections, asListOfMaps(reportData.get("sectionSettings")));

        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <title>Saved Report</title>
                  <style>
                    @page { size: A4 portrait; margin: 0; }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: 'Segoe UI', Arial, sans-serif; background: #fff; color: #0f172a; }
                    .pdf-page { width: 210mm; min-height: 297mm; position: relative; page-break-before: always; page-break-after: always; overflow: hidden; }
                    .pdf-page:first-child { page-break-before: avoid; }
                    .page-banner { background: #1a2744; color: #fff; padding: 14mm 12mm 8mm; }
                    .page-banner__title { font-size: 22pt; font-weight: 800; letter-spacing: 0.01em; line-height: 1.1; }
                    .page-banner__subtitle { font-size: 9pt; color: rgba(255,255,255,0.72); margin-top: 4px; }
                    .cover-meta-row { display: flex; gap: 0; margin-top: 10px; border-top: 2px solid #c9a227; padding-top: 8px; }
                    .cover-meta-cell { flex: 1; padding: 4px 8px; }
                    .cover-meta-cell:first-child { padding-left: 0; }
                    .cover-meta-cell__label { font-size: 7pt; color: #c9a227; text-transform: uppercase; letter-spacing: 0.1em; font-weight: 700; }
                    .cover-meta-cell__value { font-size: 9pt; color: #fff; margin-top: 2px; font-weight: 600; }
                    .gold-divider { border: none; border-top: 2px solid #c9a227; margin: 0 12mm; }
                    .page-content { padding: 5mm 12mm 20mm; }
                    .section-heading { font-size: 8.5pt; font-weight: 800; text-transform: uppercase; letter-spacing: 0.12em; color: #1a2744; border-bottom: 2px solid #1a2744; padding-bottom: 3px; margin: 9px 0 7px; }
                    .kpi-grid { display: grid; gap: 0; margin: 7px 0; border: 1px solid #e2e8f0; }
                    .kpi-grid--6 { grid-template-columns: repeat(6, 1fr); }
                    .kpi-grid--5 { grid-template-columns: repeat(5, 1fr); }
                    .kpi-card { padding: 8px 6px 6px; text-align: center; border-right: 1px solid #e2e8f0; }
                    .kpi-card:last-child { border-right: none; }
                    .kpi-card__number { font-size: 20pt; font-weight: 800; color: #0f172a; line-height: 1; }
                    .kpi-card__label { font-size: 6.5pt; color: #64748b; margin-top: 3px; text-transform: uppercase; letter-spacing: 0.04em; }
                    .kpi-card__sub { font-size: 7pt; color: #1a2744; font-weight: 700; margin-top: 2px; }
                    .insight-box { background: #eff6ff; border-left: 4px solid #2563eb; padding: 7px 10px; border-radius: 3px; margin: 7px 0; }
                    .insight-box ul { margin: 0; padding-left: 14px; font-size: 8pt; line-height: 1.6; color: #1e293b; }
                    .insight-box li + li { margin-top: 2px; }
                    .progress-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: 7px 0; }
                    .progress-panel { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 5px; padding: 7px 9px; }
                    .progress-panel__title { font-size: 7.5pt; font-weight: 800; color: #1a2744; margin-bottom: 5px; text-transform: uppercase; letter-spacing: 0.06em; }
                    .hbar-list { display: grid; gap: 5px; }
                    .hbar-row { display: grid; grid-template-columns: 100px 1fr 42px; gap: 6px; align-items: center; }
                    .hbar-label { font-size: 7.5pt; color: #334155; font-weight: 700; }
                    .hbar-track { height: 10px; background: #e2e8f0; border-radius: 99px; overflow: hidden; position: relative; }
                    .hbar-fill { position: absolute; inset: 0 auto 0 0; border-radius: 99px; background: #1a2744; }
                    .hbar-val { font-size: 8pt; color: #1a2744; font-weight: 700; text-align: right; }
                    .chart-panels { display: grid; grid-template-columns: repeat(3, 1fr); gap: 7px; margin: 7px 0; }
                    .chart-panel { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 5px; padding: 7px; }
                    .chart-panel__title { font-size: 7pt; font-weight: 800; color: #1a2744; margin-bottom: 5px; text-transform: uppercase; letter-spacing: 0.06em; }
                    .bubble-list { display: grid; gap: 5px; }
                    .bubble-row { display: flex; align-items: center; gap: 7px; font-size: 8pt; color: #334155; }
                    .bubble-dot { width: 12px; height: 12px; border-radius: 50%; flex-shrink: 0; }
                    .bubble-count { font-weight: 800; color: #0f172a; margin-left: auto; font-size: 8pt; }
                    table { width: 100%; border-collapse: collapse; margin: 5px 0; font-size: 7.5pt; }
                    thead tr { background: #1a2744; color: #fff; }
                    thead th { padding: 5px 7px; font-size: 7pt; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; text-align: left; color: #fff; }
                    tbody td { padding: 4px 7px; color: #1e293b; border-bottom: 1px solid #eef2f7; vertical-align: top; }
                    tbody tr:nth-child(even) td { background: #f8fafc; }
                    tbody tr:last-child td { border-bottom: none; }
                    tfoot tr { background: #1a2744; color: #fff; font-weight: 700; }
                    tfoot td { padding: 4px 7px; font-size: 7pt; color: #fff; }
                    .badge { display: inline-block; padding: 2px 6px; border-radius: 999px; font-size: 7pt; font-weight: 800; }
                    .badge--green { background: #dcfce7; color: #166534; }
                    .badge--amber { background: #fef3c7; color: #92400e; }
                    .badge--red { background: #fee2e2; color: #991b1b; }
                    .badge--blue { background: #dbeafe; color: #1d4ed8; }
                    .badge--slate { background: #e2e8f0; color: #334155; }
                    .page-footer { position: absolute; bottom: 8mm; left: 12mm; right: 0; display: flex; align-items: center; font-size: 6.5pt; color: #94a3b8; }
                    .page-footer__text { flex: 1; }
                    .page-footer__tab { background: #c9a227; color: #fff; font-weight: 800; font-size: 6.5pt; padding: 4px 11px; text-transform: uppercase; letter-spacing: 0.1em; }
                    .note { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 4px; padding: 7px 9px; color: #334155; font-size: 8pt; line-height: 1.5; white-space: pre-wrap; margin: 6px 0; }
                    .subheading { font-size: 8pt; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; color: #64748b; margin: 8px 0 5px; }
                    .insight-list { margin: 0; padding-left: 16px; color: #1e293b; font-size: 8pt; line-height: 1.6; }
                    .insight-list li + li { margin-top: 3px; }
                    .bar-list { display: grid; gap: 7px; margin-top: 7px; }
                    .bar-row { display: grid; grid-template-columns: 110px 1fr 44px; gap: 9px; align-items: center; }
                    .bar-label { font-size: 8pt; color: #334155; font-weight: 700; }
                    .bar-track { position: relative; height: 9px; background: #e2e8f0; border-radius: 999px; overflow: hidden; }
                    .bar-fill { position: absolute; inset: 0 auto 0 0; border-radius: 999px; background: linear-gradient(90deg, #1a2744 0%, #3b6cb7 100%); }
                    .bar-value { font-size: 8pt; color: #475569; font-weight: 800; text-align: right; }
                    .photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 8px; }
                    .photo-box { height: 80px; border-radius: 8px; background: #f1f5f9; border: 1px dashed #94a3b8; display: flex; align-items: center; justify-content: center; color: #64748b; font-size: 8pt; font-weight: 700; text-transform: uppercase; }
                    .section { background: #fff; border: 1px solid #dbe7f5; border-radius: 10px; overflow: hidden; break-inside: avoid; margin-bottom: 10px; }
                    .section-head { padding: 8px 11px; background: #f8fbff; border-bottom: 1px solid #e2ebf7; display: flex; align-items: center; justify-content: space-between; gap: 8px; }
                    .section-title { font-size: 10pt; font-weight: 800; color: #0f172a; }
                    .section-meta { font-size: 7pt; text-transform: uppercase; letter-spacing: 0.1em; color: #64748b; font-weight: 800; }
                    .section-body { padding: 9px 11px 11px; }
                    .mini-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 7px; margin-top: 8px; }
                    .mini-stat { border-radius: 8px; padding: 8px 9px; color: #0f172a; background: #f8fbff; border: 1px solid #dbe7f5; border-left: 3px solid #94a3b8; }
                    .mini-stat--green { border-left-color: #22c55e; background: #f0fdf4; }
                    .mini-stat--amber { border-left-color: #f59e0b; background: #fffbeb; }
                    .mini-stat--red { border-left-color: #ef4444; background: #fef2f2; }
                    .mini-stat--blue { border-left-color: #3b82f6; background: #eff6ff; }
                    .mini-label { font-size: 7.5pt; text-transform: uppercase; letter-spacing: 0.07em; color: #64748b; font-weight: 800; }
                    .mini-value { font-size: 14pt; font-weight: 800; margin-top: 4px; }
                    .mini-sub { font-size: 8pt; color: #64748b; margin-top: 3px; }
                    .metric-chip { display: inline-block; min-width: 48px; text-align: center; padding: 3px 6px; border-radius: 8px; font-weight: 800; font-size: 8pt; }
                    .metric-chip--green { background: #dcfce7; color: #166534; }
                    .metric-chip--amber { background: #fef3c7; color: #92400e; }
                    .metric-chip--red { background: #fee2e2; color: #991b1b; }
                    .metric-chip--blue { background: #dbeafe; color: #1d4ed8; }
                    .metric-chip--slate { background: #e2e8f0; color: #334155; }
                  </style>
                </head>
                <body>
                """);

        // Page 1: Summary cover page (always emitted)
        html.append("<div class=\"pdf-page\">");
        html.append(renderSummaryCoverPageHtml(report, reportData, project, summary, executive));
        html.append("</div>");

        // One page per section
        for (int index = 0; index < sections.size(); index++) {
            String sectionId = sections.get(index);
            Map<String, Object> settings = sectionSettings.get(index);
            if ("summary".equals(sectionId)) continue; // already on cover

            html.append("<div class=\"pdf-page\">");
            if ("checklists".equals(sectionId)) {
                html.append(renderChecklistPageHtml(settings, reportData, summary, executive, report, project));
            } else if ("issues".equals(sectionId)) {
                html.append(renderIssuesPageHtml(settings, reportData, summary, executive, report, project));
            } else {
                html.append("<div class=\"page-banner\"><div class=\"page-banner__title\">")
                        .append(escapeHtml(resolveSectionTitle(sectionId, settings)))
                        .append("</div></div>");
                html.append("<div class=\"page-content\">");
                html.append(renderSelectedPdfSectionHtml(sectionId, settings, reportData, summary, executive));
                html.append("</div>");
                html.append(buildPageFooterHtml(report, executive, sectionId.toUpperCase(Locale.ROOT)));
            }
            html.append("</div>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    // ─── NEW HTML PAGE RENDERERS ──────────────────────────────────────────────

    private String renderSummaryCoverPageHtml(SavedReport report,
                                               Map<String, Object> reportData,
                                               Map<String, Object> project,
                                               Map<String, Object> summary,
                                               Map<String, Object> executive) {
        Map<String, Object> projectDetails = asMap(executive.get("projectDetails"));
        Map<String, Object> executiveSummary = asMap(executive.get("executiveSummary"));
        Map<String, Object> tagVelocity = asMap(executive.get("tagVelocity"));
        List<Map<String, Object>> deliveryRows = asListOfMaps(executive.get("keyProjectDelivery"));
        List<Map<String, Object>> issueCompanyRows = asListOfMaps(asMap(asMap(reportData.get("sectionData")).get("issues")).get("companyRows"));
        String projectName = firstNonBlank((String) project.get("projectName"), report.getProjectName(), report.getProjectId());
        String client = firstNonBlank(stringValue(projectDetails.get("client")), "");
        String location = firstNonBlank(stringValue(projectDetails.get("location")), "");
        String author = firstNonBlank(stringValue(projectDetails.get("author")), report.getGeneratedBy());
        long checklistTotal = longValue(asMap(summary.get("checklists")).get("total"));
        long checklistClosed = longValue(asMap(summary.get("checklists")).get("closed"));
        long checklistOpen = longValue(asMap(summary.get("checklists")).get("open"));
        long issueTotal = longValue(asMap(summary.get("issues")).get("total"));
        long issueOpen = longValue(asMap(summary.get("issues")).get("open"));
        long issueClosed = longValue(asMap(summary.get("issues")).get("closed"));
        String avgIssueClosure = stringValue(asMap(asMap(reportData.get("sectionData")).get("issues")).get("averageClosureDays"));
        Double overallPctValue = parseNumeric(stringValue(executiveSummary.get("overallCompletionPct")));
        double overallCompletion = overallPctValue == null ? 0 : overallPctValue;
        List<String> summaryInsights = buildSectionInsights("summary", reportData, summary, executive);
        String narrative = firstNonBlank(stringValue(executiveSummary.get("narrative")), "");

        StringBuilder sb = new StringBuilder();

        // Banner
        sb.append("<div class=\"page-banner\">");
        sb.append("<div class=\"page-banner__title\">").append(escapeHtml(report.getTitle())).append("</div>");
        sb.append("<div class=\"page-banner__subtitle\">").append(escapeHtml(report.getSubtitle())).append("</div>");
        sb.append("<div class=\"cover-meta-row\">");
        sb.append(coverMetaCell("Client", client));
        sb.append(coverMetaCell("Location", location));
        sb.append(coverMetaCell("Window", report.getDateFrom() + " \u2013 " + report.getDateTo()));
        sb.append(coverMetaCell("Project", projectName));
        sb.append(coverMetaCell("Author", author));
        sb.append("</div></div>");
        sb.append("<hr class=\"gold-divider\" />");

        sb.append("<div class=\"page-content\">");

        // KPI row 1 (6 cards)
        sb.append("<div class=\"section-heading\">KEY PERFORMANCE INDICATORS</div>");
        sb.append("<div class=\"kpi-grid kpi-grid--6\">");
        sb.append(kpiCard(String.valueOf(checklistTotal), "Total Checklists", ""));
        sb.append(kpiCard(String.valueOf(checklistClosed), "Checklists Closed", formatPercent(percent(checklistClosed, checklistTotal))));
        sb.append(kpiCard(String.valueOf(checklistOpen), "Checklists Open", formatPercent(percent(checklistOpen, checklistTotal))));
        sb.append(kpiCard(String.valueOf(issueTotal), "Total Issues", ""));
        sb.append(kpiCard(String.valueOf(issueOpen), "Open Issues", issueOpen == 0 ? "All Resolved" : "Needs Action"));
        sb.append(kpiCard(metricValue(summary, "equipment", "total"), "Equipment Units", ""));
        sb.append("</div>");
        // KPI row 2 (6 cards)
        sb.append("<div class=\"kpi-grid kpi-grid--6\">");
        sb.append(kpiCard(stringValue(tagVelocity.get("avgPerWeek")), "Avg Tags / Week", "Delivery Pace"));
        sb.append(kpiCard(stringValue(tagVelocity.get("avgPerDay")), "Avg Tags / Day", "Delivery Pace"));
        sb.append(kpiCard(stringValue(tagVelocity.get("closedInWindow")), "Closed This Window", report.getDateFrom() + " \u2013 " + report.getDateTo()));
        sb.append(kpiCard(avgIssueClosure + "d", "Avg Issue Closure", "Mean Closure Time"));
        sb.append(kpiCard(String.valueOf(issueCompanyRows.size()), "Companies", "Issue Ownership"));
        sb.append(kpiCard(firstNonBlank(stringValue(executiveSummary.get("overallCompletionPct")), "0%"), "Overall Completion", "Current Project Closeout"));
        sb.append("</div>");

        // Executive summary
        sb.append("<div class=\"section-heading\">EXECUTIVE SUMMARY</div>");
        sb.append("<div class=\"insight-box\"><ul>");
        if (StringUtils.hasText(narrative)) {
            for (String line : narrative.split("[\r\n]+")) {
                if (StringUtils.hasText(line.trim())) {
                    sb.append("<li>").append(escapeHtml(line.trim())).append("</li>");
                }
            }
        } else {
            sb.append("<li>").append(escapeHtml(formatPercent(overallCompletion) + " checklist completion — "
                    + checklistClosed + " of " + checklistTotal + " items closed.")).append("</li>");
            sb.append("<li>All ").append(issueClosed).append(" raised issues ").append(issueOpen == 0 ? "fully resolved. 0 open issues remain." : (issueOpen + " issues remain open.")).append("</li>");
            sb.append("<li>Average issue closure time: ").append(escapeHtml(avgIssueClosure)).append(" days.</li>");
        }
        sb.append("</ul></div>");

        // Overall delivery progress
        sb.append("<div class=\"section-heading\">OVERALL DELIVERY PROGRESS</div>");
        sb.append("<div class=\"progress-row\">");
        // Left: donut
        sb.append("<div class=\"progress-panel\">");
        sb.append("<div class=\"progress-panel__title\">Checklist Completion</div>");
        sb.append(buildDonutSvgHtml(checklistClosed, checklistOpen,
                firstNonBlank(stringValue(executiveSummary.get("overallCompletionPct")), formatPercent(overallCompletion)),
                "Closed: " + checklistClosed, "Open: " + checklistOpen));
        sb.append("</div>");
        // Right: tag completion bars
        sb.append("<div class=\"progress-panel\">");
        sb.append("<div class=\"progress-panel__title\">Tag Category Completion (%)</div>");
        sb.append("<div class=\"hbar-list\">");
        for (Map<String, Object> row : deliveryRows) {
            String tagLabel = stringValue(row.get("tagLevel"));
            if (!StringUtils.hasText(tagLabel)) continue;
            double pctVal = number(row.get("percentageValue"));
            String pctText = firstNonBlank(stringValue(row.get("percentage")), formatPercent(pctVal));
            sb.append("<div class=\"hbar-row\">")
              .append("<div class=\"hbar-label\">").append(escapeHtml(tagLabel)).append("</div>")
              .append("<div class=\"hbar-track\"><div class=\"hbar-fill\" style=\"width:").append(String.format(Locale.US, "%.1f", Math.min(100.0, pctVal))).append("%\"></div></div>")
              .append("<div class=\"hbar-val\">").append(escapeHtml(pctText)).append("</div>")
              .append("</div>");
        }
        sb.append("</div></div></div>");

        // Summary insights
        if (!summaryInsights.isEmpty()) {
            sb.append("<div class=\"section-heading\">SUMMARY INSIGHTS</div>");
            sb.append("<div class=\"insight-box\"><ul>");
            summaryInsights.forEach(ins -> sb.append("<li>").append(escapeHtml(ins)).append("</li>"));
            sb.append("</ul></div>");
        }

        sb.append("</div>"); // page-content
        sb.append(buildPageFooterHtml(report, executive, "SUMMARY"));
        return sb.toString();
    }

    private String renderChecklistPageHtml(Map<String, Object> settings,
                                            Map<String, Object> reportData,
                                            Map<String, Object> summary,
                                            Map<String, Object> executive,
                                            SavedReport report,
                                            Map<String, Object> project) {
        Map<String, Object> sectionEntry = asMap(asMap(reportData.get("sectionData")).get("checklists"));
        Map<String, Object> tagVelocity = asMap(executive.get("tagVelocity"));
        List<Map<String, Object>> completionRows = asListOfMaps(sectionEntry.get("completionByCategory"));
        List<Map<String, Object>> weekRows = asListOfMaps(sectionEntry.get("weekOverWeek"));
        List<Map<String, Object>> outlierRows = asListOfMaps(sectionEntry.get("outlierRows"));
        List<Map<String, Object>> deliveryRows = asListOfMaps(executive.get("keyProjectDelivery"));
        Map<String, Long> activeTags = toCountMap(sectionEntry.get("byTag"), 6);
        String mostActiveTag = activeTags.isEmpty() ? "N/A"
                : activeTags.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("N/A");
        Map<String, Long> closedInWindowByTag = deliveryRows.stream()
                .collect(Collectors.toMap(
                        row -> stringValue(row.get("tagLevel")),
                        row -> longValue(row.get("closedInWindow")),
                        (l, r) -> l, LinkedHashMap::new));
        List<Map<String, Object>> matrixRows = completionRows.stream()
                .map(row -> row(
                        "category", stringValue(row.get("category")),
                        "percentage", stringValue(row.get("percentage")),
                        "closed", row.get("closed"),
                        "total", row.get("total"),
                        "open", Math.max(0L, longValue(row.get("total")) - longValue(row.get("closed"))),
                        "thisWindow", closedInWindowByTag.getOrDefault(stringValue(row.get("category")), 0L)
                )).toList();
        List<String> insights = buildSectionInsights("checklists", reportData, summary, executive);
        boolean includeChart = booleanValue(settings.get("includeChart"), true);
        boolean includeTable = booleanValue(settings.get("includeTable"), true);
        boolean includeInsights = booleanValue(settings.get("includeInsights"), true);
        long checklistTotal = longValue(asMap(summary.get("checklists")).get("total"));
        long checklistClosed = longValue(asMap(summary.get("checklists")).get("closed"));
        long checklistOpen = longValue(asMap(summary.get("checklists")).get("open"));
        String projectName = firstNonBlank((String) project.get("projectName"), report.getProjectName(), report.getProjectId());

        StringBuilder sb = new StringBuilder();

        // Banner
        sb.append("<div class=\"page-banner\">");
        sb.append("<div class=\"page-banner__title\">CHECKLISTS</div>");
        sb.append("<div class=\"page-banner__subtitle\">Checklist analysis \u00b7 ").append(escapeHtml(projectName))
          .append(" \u00b7 Snapshot: ").append(escapeHtml(report.getDateFrom())).append(" \u2013 ").append(escapeHtml(report.getDateTo())).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"page-content\">");

        // KPI overview (5 cards)
        sb.append("<div class=\"section-heading\">CHECKLIST OVERVIEW</div>");
        sb.append("<div class=\"kpi-grid kpi-grid--5\">");
        sb.append(kpiCard(String.valueOf(checklistTotal), "Total Checklists", ""));
        sb.append(kpiCard(String.valueOf(checklistClosed), "Closed", formatPercent(percent(checklistClosed, checklistTotal))));
        sb.append(kpiCard(String.valueOf(checklistOpen), "Open", formatPercent(percent(checklistOpen, checklistTotal))));
        sb.append(kpiCard(stringValue(tagVelocity.get("avgPerWeek")), "Avg Tags / Week", "Delivery Pace"));
        sb.append(kpiCard(mostActiveTag, "Most Active Tag", stringValue(tagVelocity.get("closedInWindow")) + " moved this window"));
        sb.append("</div>");

        // Charts
        if (includeChart) {
            sb.append("<div class=\"section-heading\">CHARTS &amp; ANALYSIS</div>");
            sb.append("<div class=\"chart-panels\">");
            // Panel 1: WoW bar
            sb.append("<div class=\"chart-panel\">");
            sb.append("<div class=\"chart-panel__title\">Week-over-Week Checklist Closure</div>");
            sb.append(buildWoWSvgHtml(weekRows));
            sb.append("</div>");
            // Panel 2: Open vs Closed donut
            sb.append("<div class=\"chart-panel\">");
            sb.append("<div class=\"chart-panel__title\">Open vs Closed</div>");
            sb.append(buildDonutSvgHtml(checklistClosed, checklistOpen,
                    formatPercent(percent(checklistClosed, checklistTotal)),
                    "Closed: " + checklistClosed, "Open: " + checklistOpen));
            sb.append("</div>");
            // Panel 3: bubble list
            sb.append("<div class=\"chart-panel\">");
            sb.append("<div class=\"chart-panel__title\">Items Closed vs Total</div>");
            sb.append(buildBubbleListHtml(completionRows, "category", "closed", "total"));
            sb.append("</div>");
            sb.append("</div>"); // chart-panels
        }

        // Category completion matrix
        if (includeTable) {
            sb.append("<div class=\"section-heading\">CATEGORY COMPLETION MATRIX</div>");
            sb.append("<table><thead><tr>");
            sb.append("<th>Category</th><th>% Complete</th><th>Closed</th><th>Total</th><th>Open</th><th>This Window</th>");
            sb.append("</tr></thead><tbody>");
            for (Map<String, Object> row : matrixRows) {
                sb.append("<tr>")
                  .append("<td>").append(escapeHtml(stringValue(row.get("category")))).append("</td>")
                  .append("<td>").append(escapeHtml(stringValue(row.get("percentage")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("closed")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("total")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("open")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("thisWindow")))).append("</td>")
                  .append("</tr>");
            }
            // Totals footer
            long sumClosed = matrixRows.stream().mapToLong(r -> longValue(r.get("closed"))).sum();
            long sumTotal = matrixRows.stream().mapToLong(r -> longValue(r.get("total"))).sum();
            long sumOpen = matrixRows.stream().mapToLong(r -> longValue(r.get("open"))).sum();
            long sumWindow = matrixRows.stream().mapToLong(r -> longValue(r.get("thisWindow"))).sum();
            sb.append("</tbody><tfoot><tr>")
              .append("<td>Total</td>")
              .append("<td>").append(formatPercent(percent(sumClosed, sumTotal))).append("</td>")
              .append("<td>").append(sumClosed).append("</td>")
              .append("<td>").append(sumTotal).append("</td>")
              .append("<td>").append(sumOpen).append("</td>")
              .append("<td>").append(sumWindow).append("</td>")
              .append("</tr></tfoot></table>");

            // Outliers
            if (!outlierRows.isEmpty()) {
                sb.append("<div class=\"section-heading\">CHECKLIST OUTLIERS \u2014 ITEMS EXCEEDING 30 DAYS OPEN</div>");
                sb.append("<table><thead><tr>");
                sb.append("<th>Checklist Reference</th><th>Status</th><th>Category</th><th>Days Open</th>");
                sb.append("</tr></thead><tbody>");
                for (Map<String, Object> row : outlierRows) {
                    sb.append("<tr>")
                      .append("<td>").append(escapeHtml(stringValue(row.get("name")))).append("</td>")
                      .append("<td>").append(escapeHtml(stringValue(row.get("status")))).append("</td>")
                      .append("<td>").append(escapeHtml(stringValue(row.get("category")))).append("</td>")
                      .append("<td>").append(escapeHtml(String.valueOf(row.get("openDays")))).append("</td>")
                      .append("</tr>");
                }
                sb.append("</tbody></table>");
            }
        }

        // Insights
        if (includeInsights && !insights.isEmpty()) {
            sb.append("<div class=\"insight-box\"><ul>");
            insights.forEach(ins -> sb.append("<li>").append(escapeHtml(ins)).append("</li>"));
            sb.append("</ul></div>");
        }

        sb.append("</div>"); // page-content
        sb.append(buildPageFooterHtml(report, executive, "CHECKLISTS"));
        return sb.toString();
    }

    private String renderIssuesPageHtml(Map<String, Object> settings,
                                         Map<String, Object> reportData,
                                         Map<String, Object> summary,
                                         Map<String, Object> executive,
                                         SavedReport report,
                                         Map<String, Object> project) {
        Map<String, Object> sectionEntry = asMap(asMap(reportData.get("sectionData")).get("issues"));
        List<Map<String, Object>> companyRows = asListOfMaps(sectionEntry.get("companyRows"));
        List<Map<String, Object>> priorityRows = buildIssuePriorityBreakdownRows(sectionEntry, summary);
        long totalIssues = Math.max(1, longValue(asMap(summary.get("issues")).get("total")));
        List<Map<String, Object>> companySummaryRows = companyRows.stream()
                .map(row -> row(
                        "company", stringValue(row.get("company")),
                        "open", row.get("open"),
                        "closed", row.get("closed"),
                        "total", row.get("total"),
                        "avgClosureDays", row.get("avgClosureDays"),
                        "shareOfTotal", formatPercent(percent(longValue(row.get("total")), totalIssues))
                )).toList();
        List<String> insights = buildSectionInsights("issues", reportData, summary, executive);
        boolean includeChart = booleanValue(settings.get("includeChart"), true);
        boolean includeTable = booleanValue(settings.get("includeTable"), true);
        boolean includeInsights = booleanValue(settings.get("includeInsights"), true);
        String projectName = firstNonBlank((String) project.get("projectName"), report.getProjectName(), report.getProjectId());

        List<PdfBarValue> avgClosureBars = toNumericBarValuesFromMap(sectionEntry.get("avgClosureByCompany"), 6, true);
        Map<String, Long> byPriority = toCountMap(sectionEntry.get("byPriority"), 8);
        List<Map<String, Object>> priorityBarRows = byPriority.entrySet().stream()
                .map(e -> row("week", e.getKey(), "closed", e.getValue()))
                .toList();

        StringBuilder sb = new StringBuilder();

        // Banner
        sb.append("<div class=\"page-banner\">");
        sb.append("<div class=\"page-banner__title\">ISSUES</div>");
        sb.append("<div class=\"page-banner__subtitle\">Issues analysis \u00b7 ").append(escapeHtml(projectName))
          .append(" \u00b7 Snapshot: ").append(escapeHtml(report.getDateFrom())).append(" \u2013 ").append(escapeHtml(report.getDateTo())).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"page-content\">");

        // KPI overview (5 cards)
        sb.append("<div class=\"section-heading\">ISSUES OVERVIEW</div>");
        sb.append("<div class=\"kpi-grid kpi-grid--5\">");
        sb.append(kpiCard(metricValue(summary, "issues", "total"), "Total Issues", "Logged Issues"));
        sb.append(kpiCard(metricValue(summary, "issues", "open"), "Open Issues", longValue(asMap(summary.get("issues")).get("open")) == 0 ? "All Resolved" : "Currently Open"));
        sb.append(kpiCard(metricValue(summary, "issues", "closed"), "Closed Issues", "100%"));
        sb.append(kpiCard(firstNonBlank(stringValue(sectionEntry.get("averageClosureDays")), "0"), "Avg Closure (Days)", "Mean Closure Time"));
        sb.append(kpiCard(String.valueOf(companyRows.size()), "Companies", "Issue Ownership"));
        sb.append("</div>");

        // Charts
        if (includeChart) {
            sb.append("<div class=\"section-heading\">CHARTS &amp; ANALYSIS</div>");
            sb.append("<div class=\"chart-panels\">");
            // Panel 1: avg closure time horizontal bars
            sb.append("<div class=\"chart-panel\">");
            sb.append("<div class=\"chart-panel__title\">Average Issue Closure Time by Company (Days)</div>");
            sb.append(buildHBarListHtml(avgClosureBars));
            sb.append("</div>");
            // Panel 2: Issues by company donut
            sb.append("<div class=\"chart-panel\">");
            sb.append("<div class=\"chart-panel__title\">Issues by Company</div>");
            sb.append(buildDonutSvgHtml(totalIssues - longValue(asMap(summary.get("issues")).get("open")),
                    longValue(asMap(summary.get("issues")).get("open")),
                    formatPercent(percent(totalIssues - longValue(asMap(summary.get("issues")).get("open")), totalIssues)),
                    "Closed: " + (totalIssues - longValue(asMap(summary.get("issues")).get("open"))),
                    "Open: " + longValue(asMap(summary.get("issues")).get("open"))));
            sb.append("</div>");
            // Panel 3: priority bucket bars
            sb.append("<div class=\"chart-panel\">");
            sb.append("<div class=\"chart-panel__title\">Issues by Priority Bucket</div>");
            sb.append(buildWoWSvgHtml(priorityBarRows));
            sb.append("</div>");
            sb.append("</div>"); // chart-panels
        }

        // Issue company summary table
        if (includeTable) {
            sb.append("<div class=\"section-heading\">ISSUE COMPANY SUMMARY</div>");
            sb.append("<table><thead><tr>");
            sb.append("<th>Company</th><th>Open</th><th>Closed</th><th>Total</th><th>Avg Closure (Days)</th><th>Share of Total</th>");
            sb.append("</tr></thead><tbody>");
            long grandTotal = 0, grandOpen = 0, grandClosed = 0;
            for (Map<String, Object> row : companySummaryRows) {
                sb.append("<tr>")
                  .append("<td>").append(escapeHtml(stringValue(row.get("company")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("open")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("closed")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("total")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("avgClosureDays")))).append("</td>")
                  .append("<td>").append(escapeHtml(stringValue(row.get("shareOfTotal")))).append("</td>")
                  .append("</tr>");
                grandTotal += longValue(row.get("total"));
                grandOpen += longValue(row.get("open"));
                grandClosed += longValue(row.get("closed"));
            }
            sb.append("</tbody><tfoot><tr>")
              .append("<td>Total</td>")
              .append("<td>").append(grandOpen).append("</td>")
              .append("<td>").append(grandClosed).append("</td>")
              .append("<td>").append(grandTotal).append("</td>")
              .append("<td>\u2014</td>")
              .append("<td>100%</td>")
              .append("</tr></tfoot></table>");

            // Priority breakdown table
            sb.append("<div class=\"section-heading\">PRIORITY BUCKET BREAKDOWN</div>");
            sb.append("<table><thead><tr>");
            sb.append("<th>Priority Bucket</th><th>Count</th><th>% of Total</th><th>Resolution Status</th>");
            sb.append("</tr></thead><tbody>");
            long prioGrandCount = 0;
            for (Map<String, Object> row : priorityRows) {
                sb.append("<tr>")
                  .append("<td>").append(escapeHtml(stringValue(row.get("priority")))).append("</td>")
                  .append("<td>").append(escapeHtml(String.valueOf(row.get("count")))).append("</td>")
                  .append("<td>").append(escapeHtml(stringValue(row.get("percentage")))).append("</td>")
                  .append("<td>").append(escapeHtml(stringValue(row.get("resolutionStatus")))).append("</td>")
                  .append("</tr>");
                prioGrandCount += longValue(row.get("count"));
            }
            sb.append("</tbody><tfoot><tr>")
              .append("<td>Total</td><td>").append(prioGrandCount).append("</td><td>100%</td><td>0 Open</td>")
              .append("</tr></tfoot></table>");
        }

        // Insights
        if (includeInsights && !insights.isEmpty()) {
            sb.append("<div class=\"insight-box\"><ul>");
            insights.forEach(ins -> sb.append("<li>").append(escapeHtml(ins)).append("</li>"));
            sb.append("</ul></div>");
        }

        sb.append("</div>"); // page-content
        sb.append(buildPageFooterHtml(report, executive, "ISSUES"));
        return sb.toString();
    }

    // ─── SVG & HTML HELPERS ───────────────────────────────────────────────────

    private String kpiCard(String number, String label, String sub) {
        StringBuilder s = new StringBuilder("<div class=\"kpi-card\">");
        s.append("<div class=\"kpi-card__number\">").append(escapeHtml(number)).append("</div>");
        s.append("<div class=\"kpi-card__label\">").append(escapeHtml(label)).append("</div>");
        if (StringUtils.hasText(sub)) {
            s.append("<div class=\"kpi-card__sub\">").append(escapeHtml(sub)).append("</div>");
        }
        s.append("</div>");
        return s.toString();
    }

    private String coverMetaCell(String label, String value) {
        return "<div class=\"cover-meta-cell\">"
                + "<div class=\"cover-meta-cell__label\">" + escapeHtml(label) + "</div>"
                + "<div class=\"cover-meta-cell__value\">" + escapeHtml(value) + "</div>"
                + "</div>";
    }

    private String buildDonutSvgHtml(long primary, long secondary, String centerText, String label1, String label2) {
        double total = Math.max(1, primary + secondary);
        double circumference = 2 * Math.PI * 55; // r=55
        double filledLen = (primary / total) * circumference;
        String filled = String.format(Locale.US, "%.2f", filledLen);
        String circ = String.format(Locale.US, "%.2f", circumference);
        return "<svg viewBox=\"0 0 160 190\" width=\"100%\" height=\"160\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<circle cx=\"80\" cy=\"80\" r=\"55\" fill=\"none\" stroke=\"#e2e8f0\" stroke-width=\"22\"/>"
                + "<circle cx=\"80\" cy=\"80\" r=\"55\" fill=\"none\" stroke=\"#1a2744\" stroke-width=\"22\""
                + " stroke-dasharray=\"" + filled + " " + circ + "\""
                + " stroke-dashoffset=\"" + String.format(Locale.US, "%.2f", -circumference * 0.25) + "\""
                + " transform=\"rotate(-90 80 80)\"/>"
                + "<text x=\"80\" y=\"75\" text-anchor=\"middle\" font-size=\"16\" font-weight=\"800\" fill=\"#0f172a\" font-family=\"'Segoe UI',Arial,sans-serif\">"
                + escapeHtml(centerText) + "</text>"
                + "<text x=\"80\" y=\"90\" text-anchor=\"middle\" font-size=\"8\" fill=\"#64748b\" font-family=\"'Segoe UI',Arial,sans-serif\">complete</text>"
                + "<rect x=\"20\" y=\"148\" width=\"10\" height=\"10\" fill=\"#1a2744\"/>"
                + "<text x=\"34\" y=\"157\" font-size=\"8\" fill=\"#334155\" font-family=\"'Segoe UI',Arial,sans-serif\">" + escapeHtml(label1) + "</text>"
                + "<rect x=\"90\" y=\"148\" width=\"10\" height=\"10\" fill=\"#e2e8f0\"/>"
                + "<text x=\"104\" y=\"157\" font-size=\"8\" fill=\"#334155\" font-family=\"'Segoe UI',Arial,sans-serif\">" + escapeHtml(label2) + "</text>"
                + "</svg>";
    }

    private String buildWoWSvgHtml(List<Map<String, Object>> weekRows) {
        if (weekRows == null || weekRows.isEmpty()) {
            return "<div style=\"font-size:7.5pt;color:#94a3b8;padding:10px 0;\">No weekly data available.</div>";
        }
        int svgW = 240, svgH = 120;
        int padL = 20, padB = 22, padT = 14, padR = 8;
        int chartW = svgW - padL - padR;
        int chartH = svgH - padT - padB;
        long maxVal = weekRows.stream().mapToLong(r -> longValue(r.get("closed"))).max().orElse(1);
        if (maxVal == 0) maxVal = 1;
        int n = weekRows.size();
        double barW = Math.max(4, (double) chartW / n * 0.55);
        double gap = n <= 1 ? 0 : (double) chartW / n;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 ").append(svgW).append(" ").append(svgH)
           .append("\" width=\"100%\" height=\"").append(svgH).append("\" xmlns=\"http://www.w3.org/2000/svg\">");
        // Grid lines
        for (int g = 0; g <= 3; g++) {
            double gy = padT + chartH * g / 3.0;
            svg.append("<line x1=\"").append(padL).append("\" y1=\"").append(String.format(Locale.US, "%.1f", gy))
               .append("\" x2=\"").append(svgW - padR).append("\" y2=\"").append(String.format(Locale.US, "%.1f", gy))
               .append("\" stroke=\"#e2e8f0\" stroke-width=\"0.5\"/>");
        }
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = weekRows.get(i);
            long val = longValue(row.get("closed"));
            double bh = (double) val / maxVal * chartH;
            double bx = padL + gap * i + (gap - barW) / 2.0;
            double by = padT + chartH - bh;
            svg.append("<rect x=\"").append(String.format(Locale.US, "%.1f", bx))
               .append("\" y=\"").append(String.format(Locale.US, "%.1f", by))
               .append("\" width=\"").append(String.format(Locale.US, "%.1f", barW))
               .append("\" height=\"").append(String.format(Locale.US, "%.1f", Math.max(1, bh)))
               .append("\" fill=\"#1a2744\" rx=\"1\"/>");
            if (val > 0) {
                svg.append("<text x=\"").append(String.format(Locale.US, "%.1f", bx + barW / 2))
                   .append("\" y=\"").append(String.format(Locale.US, "%.1f", by - 2))
                   .append("\" text-anchor=\"middle\" font-size=\"7\" fill=\"#0f172a\" font-family=\"'Segoe UI',Arial,sans-serif\">")
                   .append(val).append("</text>");
            }
            String weekLabel = stringValue(row.get("week"));
            if (weekLabel.length() > 5) weekLabel = weekLabel.substring(weekLabel.length() - 5);
            svg.append("<text x=\"").append(String.format(Locale.US, "%.1f", bx + barW / 2))
               .append("\" y=\"").append(svgH - 6)
               .append("\" text-anchor=\"middle\" font-size=\"6.5\" fill=\"#64748b\" font-family=\"'Segoe UI',Arial,sans-serif\">")
               .append(escapeHtml(weekLabel)).append("</text>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private String buildBubbleListHtml(List<Map<String, Object>> rows, String labelField,
                                        String closedField, String totalField) {
        StringBuilder sb = new StringBuilder("<div class=\"bubble-list\">");
        for (Map<String, Object> row : rows) {
            long closed = longValue(row.get(closedField));
            long total = Math.max(1, longValue(row.get(totalField)));
            double ratio = (double) closed / total;
            String dotColor = ratio >= 0.8 ? "#22c55e" : ratio > 0 ? "#f59e0b" : "#94a3b8";
            sb.append("<div class=\"bubble-row\">")
              .append("<div class=\"bubble-dot\" style=\"background:").append(dotColor).append("\"></div>")
              .append("<span>").append(escapeHtml(stringValue(row.get(labelField)))).append("</span>")
              .append("<span class=\"bubble-count\">").append(closed).append("</span>")
              .append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String buildHBarListHtml(List<PdfBarValue> values) {
        if (values == null || values.isEmpty()) {
            return "<div style=\"font-size:7.5pt;color:#94a3b8;padding:5px 0;\">No data available.</div>";
        }
        double maxVal = values.stream().mapToDouble(PdfBarValue::value).max().orElse(1);
        if (maxVal == 0) maxVal = 1;
        StringBuilder sb = new StringBuilder("<div class=\"hbar-list\">");
        for (PdfBarValue v : values) {
            double w = Math.min(100.0, v.value() / maxVal * 100.0);
            sb.append("<div class=\"hbar-row\">")
              .append("<div class=\"hbar-label\">").append(escapeHtml(v.label())).append("</div>")
              .append("<div class=\"hbar-track\"><div class=\"hbar-fill\" style=\"width:")
              .append(String.format(Locale.US, "%.1f", w)).append("%\"></div></div>")
              .append("<div class=\"hbar-val\">").append(escapeHtml(v.displayValue())).append("</div>")
              .append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String buildPageFooterHtml(SavedReport report, Map<String, Object> executive, String tabLabel) {
        Map<String, Object> projectDetails = asMap(executive.get("projectDetails"));
        String projectName = firstNonBlank(report.getProjectName(), "");
        String client = firstNonBlank(stringValue(projectDetails.get("client")), "");
        String location = firstNonBlank(stringValue(projectDetails.get("location")), "");
        String text = Stream.of(projectName, client, location,
                "Weekly Report: " + report.getDateFrom() + " \u2013 " + report.getDateTo(),
                "CONFIDENTIAL")
                .filter(StringUtils::hasText).collect(Collectors.joining(" \u00b7 "));
        return "<div class=\"page-footer\">"
                + "<div class=\"page-footer__text\">" + escapeHtml(text) + "</div>"
                + "<div class=\"page-footer__tab\">" + escapeHtml(tabLabel) + "</div>"
                + "</div>";
    }

    // ─────────────────────────────────────────────────────────────────────────

    private byte[] buildSectionAwarePdfWithPdfBox(SavedReport report,
                                                  Map<String, Object> reportData,
                                                  Map<String, Object> project,
                                                  Map<String, Object> summary,
                                                  Map<String, Object> executive) throws IOException {
        List<String> sections = normalizeSections(asStringList(reportData.get("sections")));
        List<Map<String, Object>> sectionSettings = normalizeSectionSettings(sections, asListOfMaps(reportData.get("sectionSettings")));
        Map<String, Object> projectDetails = asMap(executive.get("projectDetails"));

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(document);

            writer.header(report.getTitle(), report.getSubtitle());
            writer.section("Report Overview", new Color(37, 99, 235), "PROJECT SNAPSHOT");
            writer.keyValue("Project", firstNonBlank((String) project.get("projectName"), report.getProjectName(), report.getProjectId()));
            writer.keyValue("Client", stringValue(projectDetails.get("client")));
            writer.keyValue("Location", stringValue(projectDetails.get("location")));
            writer.keyValue("Project ID", firstNonBlank(stringValue(projectDetails.get("projectCode")), report.getProjectId()));
            writer.keyValue("Window", report.getDateFrom() + " to " + report.getDateTo());
            writer.keyValue("Author", stringValue(projectDetails.get("author")));
            writer.metricRow(List.of(
                    new PdfMetric("Checklists", metricValue(summary, "checklists", "total"), new Color(34, 197, 94)),
                    new PdfMetric("Issues", metricValue(summary, "issues", "total"), new Color(245, 158, 11)),
                    new PdfMetric("Tasks", metricValue(summary, "tasks", "total"), new Color(59, 130, 246)),
                    new PdfMetric("Equipment", metricValue(summary, "equipment", "total"), new Color(148, 163, 184))
            ));

            for (int index = 0; index < sections.size(); index++) {
                String sectionId = sections.get(index);
                Map<String, Object> settings = sectionSettings.get(index);
                Color accent = sectionColor(sectionId);
                if (index > 0) {
                    writer.newPage();
                }

                if ("summary".equals(sectionId)) {
                    renderSummaryPdfSection(writer, settings, reportData, summary, executive, accent);
                    continue;
                }
                if ("checklists".equals(sectionId)) {
                    renderChecklistPdfSection(writer, settings, reportData, summary, executive, accent);
                    continue;
                }
                if ("issues".equals(sectionId)) {
                    renderIssuesPdfSection(writer, settings, reportData, summary, executive, accent);
                    continue;
                }

                writer.section(resolveSectionTitle(sectionId, settings), accent, sectionBlocksLabel(sectionId, settings));

                String narrative = resolveSectionNarrative(sectionId, settings, reportData, executive);
                List<String> insights = buildSectionInsights(sectionId, reportData, summary, executive);
                boolean includeInsights = booleanValue(settings.get("includeInsights"), true);
                boolean includeChart = booleanValue(settings.get("includeChart"), isDataSection(sectionId));
                boolean includeTable = booleanValue(settings.get("includeTable"), isDataSection(sectionId));
                boolean rendered = false;

                if (includeInsights && StringUtils.hasText(narrative)) {
                    writer.noteBox(narrative);
                    rendered = true;
                }
                if (includeInsights && !insights.isEmpty()) {
                    writer.subheading("Insights");
                    for (String insight : insights) {
                        writer.bullet(insight);
                    }
                    writer.spacer(4);
                    rendered = true;
                }
                if (includeChart) {
                    Map<String, Long> chartSeries = chartSeriesForSection(sectionId, reportData, summary, executive);
                    if (!chartSeries.isEmpty()) {
                        writer.subheading("Chart");
                        writer.barChart(chartSeries, accent);
                        rendered = true;
                    } else if ("progressphotos".equals(sectionId)) {
                        writer.subheading("Progress Photos");
                        writer.photoPlaceholders();
                        rendered = true;
                    }
                }
                if (includeTable) {
                    List<Map<String, Object>> rows = tableRowsForSection(sectionId, reportData, summary, executive);
                    if (!rows.isEmpty()) {
                        writer.subheading("Data Table");
                        writer.table(rows, tableFieldsForSection(sectionId), tableLabelsForSection(sectionId), 6);
                        rendered = true;
                    }
                }
                if (!rendered) {
                    writer.noteBox("No output blocks are enabled for this section.");
                }
            }

            writer.close();
            document.save(output);
            return output.toByteArray();
        }
    }

    private void renderSummaryPdfSection(PdfWriter writer,
                                         Map<String, Object> settings,
                                         Map<String, Object> reportData,
                                         Map<String, Object> summary,
                                         Map<String, Object> executive,
                                         Color accent) throws IOException {
        Map<String, Object> executiveSummary = asMap(executive.get("executiveSummary"));
        Map<String, Object> tagVelocity = asMap(executive.get("tagVelocity"));
        List<Map<String, Object>> deliveryRows = asListOfMaps(executive.get("keyProjectDelivery"));
        List<Map<String, Object>> issueCompanyRows = asListOfMaps(asMap(asMap(reportData.get("sectionData")).get("issues")).get("companyRows"));
        Map<String, Object> range = asMap(reportData.get("range"));
        String comment = firstNonBlank(
                stringValue(executive.get("generalComment")),
                resolveSectionNarrative("summary", settings, reportData, executive)
        );
        Double overallCompletionValue = parseNumeric(stringValue(executiveSummary.get("overallCompletionPct")));
        double overallCompletion = overallCompletionValue == null ? 0 : overallCompletionValue;
        long checklistTotal = longValue(asMap(summary.get("checklists")).get("total"));
        long checklistClosed = longValue(asMap(summary.get("checklists")).get("closed"));
        long checklistOpen = longValue(asMap(summary.get("checklists")).get("open"));
        long issueTotal = longValue(asMap(summary.get("issues")).get("total"));
        long issueOpen = longValue(asMap(summary.get("issues")).get("open"));
        String avgIssueClosure = stringValue(asMap(asMap(reportData.get("sectionData")).get("issues")).get("averageClosureDays"));
        List<String> summaryInsights = buildSectionInsights("summary", reportData, summary, executive);
        List<String> deliveryComments = deliveryRows.stream()
                .map(row -> stringValue(row.get("comment")))
                .filter(StringUtils::hasText)
                .toList();
        List<Map<String, Object>> deliveryMatrixRows = deliveryRows.stream()
                .map(row -> row(
                        "tagLevel", stringValue(row.get("tagLevel")),
                        "percentage", stringValue(row.get("percentage")),
                        "complete", row.get("complete"),
                        "planned", row.get("planned"),
                        "closedInWindow", row.get("closedInWindow"),
                        "comment", stringValue(row.get("comment"))
                ))
                .toList();

        writer.headingRule("KEY PERFORMANCE INDICATORS");
        writer.metricGrid(List.of(
                new PdfMetric("Total Checklists", String.valueOf(checklistTotal), "project scope", new Color(34, 197, 94)),
                new PdfMetric("Checklists Closed", String.valueOf(checklistClosed), formatPercent(percent(checklistClosed, checklistTotal)), new Color(22, 163, 74)),
                new PdfMetric("Checklists Open", String.valueOf(checklistOpen), formatPercent(percent(checklistOpen, checklistTotal)), new Color(239, 68, 68)),
                new PdfMetric("Total Issues", String.valueOf(issueTotal), "logged issues", new Color(245, 158, 11)),
                new PdfMetric("Open Issues", String.valueOf(issueOpen), issueOpen == 0 ? "all resolved" : "needs action", new Color(249, 115, 22)),
                new PdfMetric("Equipment Units", metricValue(summary, "equipment", "total"), "equipment in scope", new Color(100, 116, 139)),
                new PdfMetric("Avg Tags / Week", stringValue(tagVelocity.get("avgPerWeek")), "delivery pace", accent),
                new PdfMetric("Avg Tags / Day", stringValue(tagVelocity.get("avgPerDay")), "5-day week pace", accent),
                new PdfMetric("Closed This Window", stringValue(tagVelocity.get("closedInWindow")), firstNonBlank(stringValue(range.get("label")), stringValue(range.get("from")) + " to " + stringValue(range.get("to"))), accent),
                new PdfMetric("Avg Issue Closure", avgIssueClosure + " d", "mean closure time", new Color(99, 102, 241)),
                new PdfMetric("Companies", String.valueOf(issueCompanyRows.size()), "issue ownership", new Color(99, 102, 241)),
                new PdfMetric("Overall Completion", stringValue(executiveSummary.get("overallCompletionPct")), "current project closeout", new Color(34, 197, 94))
        ), 6, 48f);

        writer.headingRule("EXECUTIVE SUMMARY");
        writer.noteBox(firstNonBlank(stringValue(executiveSummary.get("narrative")), comment));

        writer.headingRule("KEY PROJECT DELIVERY STATUS");
        writer.table(
                deliveryMatrixRows,
                List.of("tagLevel", "percentage", "complete", "planned", "closedInWindow", "comment"),
                Map.of(
                        "tagLevel", "Deliverable",
                        "percentage", "% Complete",
                        "complete", "Closed",
                        "planned", "Total",
                        "closedInWindow", "This Week",
                        "comment", "Comment"
                ),
                new float[]{0.16f, 0.11f, 0.09f, 0.09f, 0.10f, 0.45f},
                Math.max(4, deliveryMatrixRows.size())
        );

        writer.headingRule("OVERALL DELIVERY PROGRESS");
        writer.chartPanels(List.of(
                new PdfChartBlock(
                        "Overall Completion",
                        List.of(
                                new PdfBarValue("Complete", overallCompletion, stringValue(executiveSummary.get("overallCompletionPct"))),
                                new PdfBarValue("Remaining", Math.max(0, 100d - overallCompletion), formatPercent(Math.max(0, 100d - overallCompletion)))
                        ),
                        accent,
                        "No completion data is available.",
                        PdfChartStyle.DONUT,
                        stringValue(executiveSummary.get("overallCompletionPct")),
                        "Complete"
                ),
                new PdfChartBlock(
                        "Tag Percentage Completion",
                        toPercentBarValues(deliveryRows, "tagLevel", "percentageValue", "percentage"),
                        accent,
                        "No key deliverable progress is available.",
                        PdfChartStyle.HORIZONTAL_BAR
                )
        ));

        writer.textPanels(List.of(
                new PdfTextPanel(
                        "Current Week Progress",
                        deliveryComments.isEmpty() ? List.of("No tag movements were recorded in the selected reporting window.") : deliveryComments,
                        accent
                ),
                new PdfTextPanel(
                        "General Comment",
                        StringUtils.hasText(comment) ? List.of(comment) : List.of("No additional general comment was entered for this reporting window."),
                        accent
                )
        ));

        if (booleanValue(settings.get("includeInsights"), true) && !summaryInsights.isEmpty()) {
            writer.headingRule("SUMMARY INSIGHTS");
            writer.insightPanel("Summary Insights", summaryInsights, accent);
        }
    }

    private void renderChecklistPdfSection(PdfWriter writer,
                                           Map<String, Object> settings,
                                           Map<String, Object> reportData,
                                           Map<String, Object> summary,
                                           Map<String, Object> executive,
                                           Color accent) throws IOException {
        Map<String, Object> sectionEntry = asMap(asMap(reportData.get("sectionData")).get("checklists"));
        Map<String, Object> tagVelocity = asMap(executive.get("tagVelocity"));
        List<Map<String, Object>> completionRows = asListOfMaps(sectionEntry.get("completionByCategory"));
        List<Map<String, Object>> weekRows = asListOfMaps(sectionEntry.get("weekOverWeek"));
        List<Map<String, Object>> outlierRows = asListOfMaps(sectionEntry.get("outlierRows"));
        List<Map<String, Object>> deliveryRows = asListOfMaps(executive.get("keyProjectDelivery"));
        Map<String, Long> activeTags = toCountMap(sectionEntry.get("byTag"), 6);
        String mostActiveTag = activeTags.isEmpty() ? "N/A" : activeTags.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("N/A");
        Map<String, Long> closedInWindowByTag = deliveryRows.stream()
                .collect(Collectors.toMap(
                        row -> stringValue(row.get("tagLevel")),
                        row -> longValue(row.get("closedInWindow")),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<Map<String, Object>> matrixRows = completionRows.stream()
                .map(row -> row(
                        "category", stringValue(row.get("category")),
                        "percentage", stringValue(row.get("percentage")),
                        "closed", row.get("closed"),
                        "total", row.get("total"),
                        "open", Math.max(0, longValue(row.get("total")) - longValue(row.get("closed"))),
                        "thisWindow", closedInWindowByTag.getOrDefault(stringValue(row.get("category")), 0L)
                ))
                .toList();
        List<String> insights = buildSectionInsights("checklists", reportData, summary, executive);

        writer.sectionBanner("CHECKLISTS", "Checklist performance, closure velocity, and open outliers");
        writer.headingRule("CHECKLIST OVERVIEW");
        writer.metricGrid(List.of(
                new PdfMetric("Total Checklists", metricValue(summary, "checklists", "total"), "project scope", accent),
                new PdfMetric("Closed", metricValue(summary, "checklists", "closed"), "completed", new Color(22, 163, 74)),
                new PdfMetric("Open", metricValue(summary, "checklists", "open"), "remaining", new Color(239, 68, 68)),
                new PdfMetric("Avg Tags / Week", stringValue(tagVelocity.get("avgPerWeek")), "delivery pace", new Color(59, 130, 246)),
                new PdfMetric("Most Active Tag", mostActiveTag, stringValue(tagVelocity.get("closedInWindow")) + " moved this window", new Color(14, 165, 233))
        ), 5, 50f);

        if (booleanValue(settings.get("includeChart"), true)) {
            writer.headingRule("CHARTS & ANALYSIS");
            writer.chartPanels(List.of(
                    new PdfChartBlock(
                            "Checklist Completion by Category",
                            toPercentBarValues(completionRows, "category", "percentageValue", "percentage"),
                            accent,
                            "No checklist category progress data is available.",
                            PdfChartStyle.HORIZONTAL_BAR
                    ),
                    new PdfChartBlock(
                            "WoW Checklist Completion",
                            toCountBarValues(weekRows, "week", "closed"),
                            new Color(59, 130, 246),
                            "No weekly checklist completions were recorded.",
                            PdfChartStyle.VERTICAL_BAR
                    )
            ));
        }

        if (booleanValue(settings.get("includeTable"), true)) {
            writer.headingRule("CATEGORY COMPLETION MATRIX");
            writer.table(
                    matrixRows,
                    List.of("category", "percentage", "closed", "total", "open", "thisWindow"),
                    Map.of(
                            "category", "Category",
                            "percentage", "% Complete",
                            "closed", "Closed",
                            "total", "Total",
                            "open", "Open",
                            "thisWindow", "This Window"
                    ),
                    new float[]{0.30f, 0.13f, 0.12f, 0.12f, 0.11f, 0.22f},
                    Math.max(6, matrixRows.size())
            );
            if (!outlierRows.isEmpty()) {
                writer.headingRule("CHECKLIST OUTLIERS - ITEMS EXCEEDING 30 DAYS OPEN");
                writer.table(
                        outlierRows,
                        List.of("name", "status", "category", "openDays"),
                        Map.of("name", "Checklist", "status", "Status", "category", "Category", "openDays", "Days Open"),
                        new float[]{0.48f, 0.14f, 0.22f, 0.16f},
                        Math.max(6, outlierRows.size())
                );
            }
        }

        if (booleanValue(settings.get("includeInsights"), true) && !insights.isEmpty()) {
            writer.insightPanel("Checklist Insights", insights, accent);
        }
    }

    private void renderIssuesPdfSection(PdfWriter writer,
                                        Map<String, Object> settings,
                                        Map<String, Object> reportData,
                                        Map<String, Object> summary,
                                        Map<String, Object> executive,
                                        Color accent) throws IOException {
        Map<String, Object> sectionEntry = asMap(asMap(reportData.get("sectionData")).get("issues"));
        List<Map<String, Object>> companyRows = asListOfMaps(sectionEntry.get("companyRows"));
        List<Map<String, Object>> priorityRows = buildIssuePriorityBreakdownRows(sectionEntry, summary);
        long totalIssues = Math.max(1, longValue(asMap(summary.get("issues")).get("total")));
        List<Map<String, Object>> companySummaryRows = companyRows.stream()
                .map(row -> row(
                        "company", stringValue(row.get("company")),
                        "open", row.get("open"),
                        "closed", row.get("closed"),
                        "total", row.get("total"),
                        "avgClosureDays", row.get("avgClosureDays"),
                        "shareOfTotal", formatPercent(percent(longValue(row.get("total")), totalIssues))
                ))
                .toList();
        List<String> insights = buildSectionInsights("issues", reportData, summary, executive);

        writer.sectionBanner("ISSUES", "Issue ownership, closure timing, and priority distribution");
        writer.headingRule("ISSUES OVERVIEW");
        writer.metricGrid(List.of(
                new PdfMetric("Total Issues", metricValue(summary, "issues", "total"), "logged issues", accent),
                new PdfMetric("Open Issues", metricValue(summary, "issues", "open"), "currently open", new Color(239, 68, 68)),
                new PdfMetric("Closed Issues", metricValue(summary, "issues", "closed"), "closed out", new Color(22, 163, 74)),
                new PdfMetric("Avg Closure (Days)", stringValue(sectionEntry.get("averageClosureDays")), "mean closure time", new Color(99, 102, 241)),
                new PdfMetric("Companies", String.valueOf(companyRows.size()), "issue ownership", new Color(14, 165, 233))
        ), 5, 50f);

        if (booleanValue(settings.get("includeChart"), true)) {
            writer.headingRule("CHARTS & ANALYSIS");
            writer.chartPanels(List.of(
                    new PdfChartBlock(
                            "Open Issues by Company",
                            toCountBarValuesFromMap(sectionEntry.get("openByCompany"), 6),
                            accent,
                            "There are no open issues in the current snapshot.",
                            PdfChartStyle.HORIZONTAL_BAR
                    ),
                    new PdfChartBlock(
                            "Average Issue Closure (Days)",
                            toNumericBarValuesFromMap(sectionEntry.get("avgClosureByCompany"), 6, true),
                            new Color(99, 102, 241),
                            "Closure timing is not available for this issue set.",
                            PdfChartStyle.HORIZONTAL_BAR
                    )
            ));
        }

        if (booleanValue(settings.get("includeTable"), true)) {
            writer.headingRule("ISSUE COMPANY SUMMARY");
            writer.table(
                    companySummaryRows,
                    List.of("company", "open", "closed", "total", "avgClosureDays", "shareOfTotal"),
                    Map.of(
                            "company", "Company",
                            "open", "Open",
                            "closed", "Closed",
                            "total", "Total",
                            "avgClosureDays", "Avg Closure (Days)",
                            "shareOfTotal", "Share of Total"
                    ),
                    new float[]{0.28f, 0.10f, 0.10f, 0.10f, 0.18f, 0.24f},
                    Math.max(6, companySummaryRows.size())
            );
            writer.headingRule("PRIORITY BUCKET BREAKDOWN");
            writer.table(
                    priorityRows,
                    List.of("priority", "count", "percentage", "resolutionStatus"),
                    Map.of(
                            "priority", "Priority Bucket",
                            "count", "Count",
                            "percentage", "% of Total",
                            "resolutionStatus", "Resolution Status"
                    ),
                    new float[]{0.38f, 0.12f, 0.16f, 0.34f},
                    Math.max(5, priorityRows.size())
            );
        }

        if (booleanValue(settings.get("includeInsights"), true) && !insights.isEmpty()) {
            writer.insightPanel("Issue Insights", insights, accent);
        }
    }

    private List<Map<String, Object>> buildIssuePriorityBreakdownRows(Map<String, Object> sectionEntry,
                                                                      Map<String, Object> summary) {
        long totalIssues = Math.max(1, longValue(asMap(summary.get("issues")).get("total")));
        return asListOfMaps(sectionEntry.get("progressByCategory")).stream()
                .map(row -> {
                    long open = longValue(row.get("open"));
                    long closed = longValue(row.get("closed"));
                    long total = longValue(row.get("total"));
                    String resolutionStatus;
                    if (open == 0 && closed > 0) {
                        resolutionStatus = "Closed";
                    } else if (open > 0 && closed == 0) {
                        resolutionStatus = "Open";
                    } else if (open > 0) {
                        resolutionStatus = "Mixed";
                    } else {
                        resolutionStatus = "No activity";
                    }
                    return row(
                            "priority", stringValue(row.get("category")),
                            "count", total,
                            "percentage", formatPercent(percent(total, totalIssues)),
                            "resolutionStatus", resolutionStatus
                    );
                })
                .filter(row -> longValue(row.get("count")) > 0)
                .toList();
    }

    private String renderSelectedPdfSectionHtml(String sectionId,
                                                Map<String, Object> settings,
                                                Map<String, Object> reportData,
                                                Map<String, Object> summary,
                                                Map<String, Object> executive) {
        String title = resolveSectionTitle(sectionId, settings);
        boolean includeInsights = booleanValue(settings.get("includeInsights"), true);
        boolean includeChart = booleanValue(settings.get("includeChart"), isDataSection(sectionId));
        boolean includeTable = booleanValue(settings.get("includeTable"), isDataSection(sectionId));
        String narrative = resolveSectionNarrative(sectionId, settings, reportData, executive);
        List<String> insights = buildSectionInsights(sectionId, reportData, summary, executive);
        Map<String, Long> chartSeries = includeChart ? chartSeriesForSection(sectionId, reportData, summary, executive) : Map.of();
        List<Map<String, Object>> rows = includeTable ? tableRowsForSection(sectionId, reportData, summary, executive) : List.of();

        StringBuilder html = new StringBuilder();
        html.append("<section class=\"section\"><div class=\"section-head\"><div class=\"section-title\">")
                .append(escapeHtml(title))
                .append("</div><div class=\"section-meta\">");
        html.append(includeInsights ? "Insights" : "");
        if (includeChart) html.append(includeInsights ? " • Chart" : "Chart");
        if (includeTable) html.append((includeInsights || includeChart) ? " • Table" : "Table");
        html.append("</div></div><div class=\"section-body\">");

        boolean rendered = false;
        if (includeInsights && StringUtils.hasText(narrative)) {
            html.append("<div class=\"note\">").append(escapeHtml(narrative)).append("</div>");
            rendered = true;
        }
        if (includeInsights && !insights.isEmpty()) {
            html.append("<div class=\"subheading\">Insights</div><ul class=\"insight-list\">");
            insights.forEach(line -> html.append("<li>").append(escapeHtml(line)).append("</li>"));
            html.append("</ul>");
            rendered = true;
        }
        if (includeChart) {
            String chartHtml = renderSectionChartHtml(sectionId, reportData, summary, executive, chartSeries);
            if (StringUtils.hasText(chartHtml)) {
                html.append(chartHtml);
                rendered = true;
            }
        }
        if (includeTable) {
            html.append("<div class=\"subheading\">Data table</div>");
            appendSimpleTable(html, rows, tableFieldsForSection(sectionId), tableLabelsForSection(sectionId), null);
            rendered = true;
        }
        if (!rendered) {
            html.append("<div class=\"note\">No output blocks are enabled for this section.</div>");
        }
        html.append("</div></section>");
        return html.toString();
    }

    private String renderSectionChartHtml(String sectionId,
                                          Map<String, Object> reportData,
                                          Map<String, Object> summary,
                                          Map<String, Object> executive,
                                          Map<String, Long> chartSeries) {
        if ("summary".equals(sectionId)) {
            Map<String, Object> executiveSummary = asMap(executive.get("executiveSummary"));
            Map<String, Object> periodInsights = asMap(executive.get("periodInsights"));
            StringBuilder html = new StringBuilder();
            html.append("<div class=\"subheading\">Headline metrics</div><div class=\"mini-grid\">");
            html.append(miniStat("Overall Completion", stringValue(executiveSummary.get("overallCompletionPct")), metricValue(summary, "checklists", "closed") + "/" + metricValue(summary, "checklists", "total") + " closed"));
            html.append(miniStat("Plan Completion", stringValue(executiveSummary.get("plannedCompletionPct")), "Due-date baseline"));
            html.append(miniStat("Forecast", stringValue(executiveSummary.get("forecastCompletion")), "Projected finish"));
            html.append(miniStat("Issues Closed", stringValue(periodInsights.get("issuesClosed")), "Within window"));
            html.append("</div>");
            return html.toString();
        }
        if ("progressphotos".equals(sectionId)) {
            StringBuilder html = new StringBuilder();
            html.append("<div class=\"subheading\">Photo placeholders</div><div class=\"photo-grid\">");
            for (int index = 1; index <= 3; index++) {
                html.append("<div class=\"photo-box\">Photo ").append(index).append("</div>");
            }
            html.append("</div>");
            return html.toString();
        }
        if (chartSeries.isEmpty()) {
            return "";
        }

        long max = chartSeries.values().stream().mapToLong(Long::longValue).max().orElse(1);
        StringBuilder html = new StringBuilder("<div class=\"subheading\">Chart</div><div class=\"bar-list\">");
        chartSeries.entrySet().stream().limit(6).forEach(entry -> {
            long count = entry.getValue();
            double width = max == 0 ? 0 : (count * 100.0 / max);
            html.append("<div class=\"bar-row\"><div class=\"bar-label\">")
                    .append(escapeHtml(entry.getKey()))
                    .append("</div><div class=\"bar-track\"><div class=\"bar-fill\" style=\"width:")
                    .append(String.format(Locale.US, "%.2f", width))
                    .append("%\"></div></div><div class=\"bar-value\">")
                    .append(escapeHtml(String.valueOf(count)))
                    .append("</div></div>");
        });
        html.append("</div>");
        return html.toString();
    }

    private String resolveSectionTitle(String sectionId, Map<String, Object> settings) {
        return firstNonBlank(stringValue(settings.get("title")), sectionTitle(sectionId));
    }

    private String resolveSectionNarrative(String sectionId,
                                           Map<String, Object> settings,
                                           Map<String, Object> reportData,
                                           Map<String, Object> executive) {
        String configured = stringValue(settings.get("narrative"));
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        Map<String, Object> sectionEntry = asMap(asMap(reportData.get("sectionData")).get(sectionId));
        String text = stringValue(sectionEntry.get("text"));
        if (StringUtils.hasText(text)) {
            return text;
        }
        if ("summary".equals(sectionId)) {
            return stringValue(asMap(executive.get("executiveSummary")).get("narrative"));
        }
        if ("progressphotos".equals(sectionId)) {
            return "Add a concise progress-photo narrative and attach the selected image set in a follow-up pass.";
        }
        return "";
    }

    private List<String> buildSectionInsights(String sectionId,
                                              Map<String, Object> reportData,
                                              Map<String, Object> summary,
                                              Map<String, Object> executive) {
        Map<String, Object> sectionEntry = asMap(asMap(reportData.get("sectionData")).get(sectionId));
        return switch (normalize(sectionId)) {
            case "summary" -> {
                Map<String, Object> executiveSummary = asMap(executive.get("executiveSummary"));
                Map<String, Object> periodInsights = asMap(executive.get("periodInsights"));
                Map<String, Object> tagVelocity = asMap(executive.get("tagVelocity"));
                yield Stream.of(
                                "Overall checklist completion is " + stringValue(executiveSummary.get("overallCompletionPct")) + " with " + metricValue(summary, "checklists", "closed") + " closed items in the project snapshot.",
                                stringValue(periodInsights.get("issuesClosed")) + " issues were closed during the selected reporting window.",
                                "Forecast completion is currently " + stringValue(executiveSummary.get("forecastCompletion")) + ".",
                                "Average delivery pace is " + stringValue(tagVelocity.get("avgPerWeek")) + " tags per week and " + stringValue(tagVelocity.get("avgPerDay")) + " tags per day."
                        )
                        .filter(StringUtils::hasText)
                        .toList();
            }
            case "checklists" -> Stream.of(
                            metricValue(summary, "checklists", "closed") + " checklists are closed and " + metricValue(summary, "checklists", "open") + " remain open.",
                            topCountLine(toCountMap(sectionEntry.get("byStatus"), 8), "Largest checklist status bucket"),
                            topCountLine(toCountMap(sectionEntry.get("byTag"), 6), "Most active tag"),
                            topCountLine(toCountMap(sectionEntry.get("openByCategory"), 6), "Biggest open tag category")
                    )
                    .filter(StringUtils::hasText)
                    .toList();
            case "issues" -> Stream.of(
                            metricValue(summary, "issues", "open") + " issues remain open in the filtered window.",
                            topCountLine(toCountMap(sectionEntry.get("byPriority"), 8), "Highest issue priority bucket"),
                            topCountLine(toCountMap(sectionEntry.get("topLocations"), 5), "Most affected location"),
                            topCountLine(toCountMap(sectionEntry.get("openByCompany"), 6), "Most affected company")
                    )
                    .filter(StringUtils::hasText)
                    .toList();
            case "equipment" -> Stream.of(
                            stringValue(sectionEntry.get("totalRows")) + " equipment rows are in the selected report scope.",
                            topCountLine(toCountMap(sectionEntry.get("byType"), 8), "Largest equipment type"),
                            topCountLine(toCountMap(sectionEntry.get("byStatus"), 8), "Most common equipment status")
                    )
                    .filter(StringUtils::hasText)
                    .toList();
            case "tests" -> Stream.of(
                            stringValue(sectionEntry.get("totalTests")) + " tests are represented in this report window.",
                            topCountLine(toCountMap(sectionEntry.get("byType"), 8), "Test-heavy equipment type")
                    )
                    .filter(StringUtils::hasText)
                    .toList();
            case "personnel" -> Stream.of(
                            stringValue(sectionEntry.get("totalRows")) + " personnel rows are available for the selected project scope.",
                            topCountLine(toCountMap(sectionEntry.get("byCompany"), 8), "Largest company allocation")
                    )
                    .filter(StringUtils::hasText)
                    .toList();
            case "activities" -> List.of(stringValue(sectionEntry.get("totalRows")) + " active delivery tasks are included in this section.");
            case "upcoming" -> List.of(stringValue(sectionEntry.get("totalRows")) + " upcoming tasks fall within the near-term planning window.");
            case "progressphotos" -> List.of("Use this section to call out visible site milestones and attach the curated image set for the same period.");
            default -> List.of();
        };
    }

    private Map<String, Long> chartSeriesForSection(String sectionId,
                                                    Map<String, Object> reportData,
                                                    Map<String, Object> summary,
                                                    Map<String, Object> executive) {
        Map<String, Object> sectionEntry = asMap(asMap(reportData.get("sectionData")).get(sectionId));
        return switch (normalize(sectionId)) {
            case "summary" -> {
                LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
                counts.put("Closed checklists", longValue(asMap(summary.get("checklists")).get("closed")));
                counts.put("Open issues", longValue(asMap(summary.get("issues")).get("open")));
                counts.put("Open tasks", longValue(asMap(summary.get("tasks")).get("open")));
                counts.put("Equipment", longValue(asMap(summary.get("equipment")).get("total")));
                yield counts;
            }
            case "checklists" -> toCountMap(sectionEntry.get("byStatus"), 8);
            case "issues" -> toCountMap(sectionEntry.get("byPriority"), 8);
            case "equipment" -> toCountMap(sectionEntry.get("byType"), 8);
            case "tests" -> toCountMap(sectionEntry.get("byType"), 8);
            case "personnel" -> toCountMap(sectionEntry.get("byCompany"), 8);
            case "activities", "upcoming" -> rowsGroupedCounts(asListOfMaps(sectionEntry.get("rows")), "status", 8);
            default -> Map.of();
        };
    }

    private List<Map<String, Object>> tableRowsForSection(String sectionId,
                                                          Map<String, Object> reportData,
                                                          Map<String, Object> summary,
                                                          Map<String, Object> executive) {
        Map<String, Object> sectionEntry = asMap(asMap(reportData.get("sectionData")).get(sectionId));
        return switch (normalize(sectionId)) {
            case "summary" -> buildSummaryTableRows(summary, executive);
            case "checklists", "issues", "equipment", "tests", "personnel", "activities", "upcoming" -> asListOfMaps(sectionEntry.get("rows"));
            default -> List.of();
        };
    }

    private List<String> tableFieldsForSection(String sectionId) {
        return switch (normalize(sectionId)) {
            case "summary" -> List.of("metric", "value");
            case "checklists" -> List.of("name", "status", "category", "tagLevel", "assignedTo", "dueDate");
            case "issues" -> List.of("title", "company", "status", "priority", "assignee", "location");
            case "equipment" -> List.of("name", "type", "status", "system", "tests");
            case "tests" -> List.of("equipment", "type", "tests");
            case "personnel" -> List.of("name", "email", "company", "role");
            case "activities" -> List.of("title", "status", "priority", "dueDate", "assignedTo");
            case "upcoming" -> List.of("title", "dueDate", "status", "assignedTo");
            default -> List.of("label", "value");
        };
    }

    private Map<String, String> tableLabelsForSection(String sectionId) {
        return switch (normalize(sectionId)) {
            case "summary" -> Map.of("metric", "Metric", "value", "Value");
            case "checklists" -> Map.of("name", "Checklist", "status", "Status", "category", "Category", "tagLevel", "Tag", "assignedTo", "Assigned To", "dueDate", "Due Date");
            case "issues" -> Map.of("title", "Issue", "company", "Company", "status", "Status", "priority", "Priority", "assignee", "Assignee", "location", "Location");
            case "equipment" -> Map.of("name", "Equipment", "type", "Type", "status", "Status", "system", "System", "tests", "Tests");
            case "tests" -> Map.of("equipment", "Equipment", "type", "Type", "tests", "Tests");
            case "personnel" -> Map.of("name", "Name", "email", "Email", "company", "Company", "role", "Role");
            case "activities" -> Map.of("title", "Activity", "status", "Status", "priority", "Priority", "dueDate", "Due Date", "assignedTo", "Assigned To");
            case "upcoming" -> Map.of("title", "Task", "dueDate", "Due Date", "status", "Status", "assignedTo", "Assigned To");
            default -> Map.of("label", "Label", "value", "Value");
        };
    }

    private List<Map<String, Object>> buildSummaryTableRows(Map<String, Object> summary, Map<String, Object> executive) {
        Map<String, Object> executiveSummary = asMap(executive.get("executiveSummary"));
        return List.of(
                row("metric", "Closed checklists", "value", metricValue(summary, "checklists", "closed")),
                row("metric", "Open issues", "value", metricValue(summary, "issues", "open")),
                row("metric", "Open tasks", "value", metricValue(summary, "tasks", "open")),
                row("metric", "Total equipment", "value", metricValue(summary, "equipment", "total")),
                row("metric", "Overall completion", "value", stringValue(executiveSummary.get("overallCompletionPct"))),
                row("metric", "Forecast finish", "value", stringValue(executiveSummary.get("forecastCompletion")))
        );
    }

    private List<PdfBarValue> toPercentBarValues(List<Map<String, Object>> rows,
                                                 String labelField,
                                                 String valueField,
                                                 String displayField) {
        return rows.stream()
                .filter(row -> StringUtils.hasText(stringValue(row.get(labelField))))
                .map(row -> new PdfBarValue(
                        stringValue(row.get(labelField)),
                        number(row.get(valueField)),
                        firstNonBlank(stringValue(row.get(displayField)), formatOneDecimal(number(row.get(valueField))))
                ))
                .limit(6)
                .toList();
    }

    private List<PdfBarValue> toCountBarValues(List<Map<String, Object>> rows, String labelField, String valueField) {
        return rows.stream()
                .filter(row -> StringUtils.hasText(stringValue(row.get(labelField))))
                .map(row -> new PdfBarValue(
                        stringValue(row.get(labelField)),
                        number(row.get(valueField)),
                        String.valueOf(longValue(row.get(valueField)))
                ))
                .limit(8)
                .toList();
    }

    private List<PdfBarValue> toCountBarValuesFromMap(Object rawMap, int limit) {
        if (!(rawMap instanceof Map<?, ?> map)) {
            return List.of();
        }
        return map.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .map(entry -> new PdfBarValue(
                        readableLabel(String.valueOf(entry.getKey())),
                        number(entry.getValue()),
                        String.valueOf(longValue(entry.getValue()))
                ))
                .limit(limit)
                .toList();
    }

    private List<PdfBarValue> toNumericBarValuesFromMap(Object rawMap, int limit, boolean oneDecimal) {
        if (!(rawMap instanceof Map<?, ?> map)) {
            return List.of();
        }
        return map.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .map(entry -> new PdfBarValue(
                        readableLabel(String.valueOf(entry.getKey())),
                        number(entry.getValue()),
                        oneDecimal ? formatOneDecimal(number(entry.getValue())) : String.valueOf(longValue(entry.getValue()))
                ))
                .limit(limit)
                .toList();
    }

    private Map<String, Long> toCountMap(Object value, int limit) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        return rawMap.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(
                        entry -> readableLabel(String.valueOf(entry.getKey())),
                        entry -> longValue(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .entrySet().stream()
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, Long> rowsGroupedCounts(List<Map<String, Object>> rows, String field, int limit) {
        return rows.stream()
                .map(row -> defaultLabel(stringValue(row.get(field)), "Unknown"))
                .collect(Collectors.groupingBy(this::readableLabel, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private String topCountLine(Map<String, Long> counts, String prefix) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> prefix + ": " + entry.getKey() + " (" + entry.getValue() + ")")
                .orElse("");
    }

    private String sectionBlocksLabel(String sectionId, Map<String, Object> settings) {
        List<String> labels = new ArrayList<>();
        if (booleanValue(settings.get("includeInsights"), true)) {
            labels.add("Insights");
        }
        if (booleanValue(settings.get("includeChart"), isDataSection(sectionId))) {
            labels.add("Chart");
        }
        if (booleanValue(settings.get("includeTable"), isDataSection(sectionId))) {
            labels.add("Table");
        }
        return String.join(" • ", labels);
    }

    private Color sectionColor(String sectionId) {
        return switch (normalize(sectionId)) {
            case "summary" -> new Color(59, 130, 246);
            case "checklists" -> new Color(16, 185, 129);
            case "issues" -> new Color(249, 115, 22);
            case "equipment" -> new Color(34, 197, 94);
            case "tests" -> new Color(14, 165, 233);
            case "custom" -> new Color(139, 92, 246);
            case "progressphotos" -> new Color(245, 158, 11);
            case "personnel" -> new Color(99, 102, 241);
            case "activities" -> new Color(45, 212, 191);
            case "upcoming" -> new Color(168, 85, 247);
            case "safety" -> new Color(244, 63, 94);
            case "commercials" -> new Color(234, 179, 8);
            default -> new Color(37, 99, 235);
        };
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

    private long checklistOpenDays(Checklist checklist) {
        LocalDate today = LocalDate.now();
        LocalDate anchor = firstNonNull(checklist.getLatestOpenDate(), checklist.getLatestInProgressDate(), parseDate(checklist.getCreatedAt()));
        return daysBetween(anchor, today);
    }

    private String tagDisplayLabel(String rawTag) {
        String tag = normalize(rawTag);
        return switch (tag) {
            case "red" -> "L1 Red Tag";
            case "yellow" -> "L2 Yellow Tag";
            case "green" -> "L3 Green Tag";
            case "blue" -> "L4 Blue Tag";
            case "white" -> "L5 White Tag";
            default -> "";
        };
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
            case "custom" -> "Custom Section";
            case "personnel" -> "Personnel";
            case "activities" -> "Activities";
            case "upcoming" -> "Upcoming";
            case "safety" -> "Safety Notes";
            case "checklists" -> "Checklist Progress";
            case "issues" -> "Issue Register";
            case "tests" -> "Test Snapshot";
            case "equipment" -> "Equipment Overview";
            case "progressphotos" -> "Progress Photos";
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
        List<String> normalized = normalizeOrderedList(sections);
        return normalized.isEmpty() ? DEFAULT_SECTIONS : normalized;
    }

    private List<Map<String, Object>> normalizeSectionSettings(List<String> sections, List<Map<String, Object>> sectionSettings) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            String sectionId = sections.get(index);
            Map<String, Object> source = sectionSettings != null && index < sectionSettings.size() && sectionSettings.get(index) != null
                    ? new LinkedHashMap<>(sectionSettings.get(index))
                    : new LinkedHashMap<>();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", firstNonBlank(stringValue(source.get("title")), sectionTitle(sectionId)));
            item.put("narrative", stringValue(source.get("narrative")));
            item.put("includeInsights", booleanValue(source.get("includeInsights"), true));
            item.put("includeChart", booleanValue(source.get("includeChart"), isDataSection(sectionId)));
            item.put("includeTable", booleanValue(source.get("includeTable"), isDataSection(sectionId)));
            item.put("chartType", firstNonBlank(stringValue(source.get("chartType")), defaultChartType(sectionId)));
            item.put("issueStatuses", normalizeOrderedList(asStringList(source.get("issueStatuses"))));
            item.put("checklistStatuses", normalizeOrderedList(asStringList(source.get("checklistStatuses"))));
            item.put("equipmentTypes", normalizeOrderedList(asStringList(source.get("equipmentTypes"))));
            normalized.add(item);
        }
        return normalized;
    }

    private List<String> normalizeList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .distinct()
                .toList();
    }

    private List<String> normalizeOrderedList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .toList();
    }

    private Set<String> normalizeValues(List<String> values) {
        return new LinkedHashSet<>(normalizeList(values));
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = normalize(String.valueOf(value));
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private boolean isDataSection(String sectionId) {
        return Set.of("checklists", "issues", "equipment", "tests", "personnel", "activities", "upcoming").contains(normalize(sectionId));
    }

    private String defaultChartType(String sectionId) {
        String normalized = normalize(sectionId);
        if ("progressphotos".equals(normalized)) {
            return "gallery";
        }
        if ("summary".equals(normalized)) {
            return "headline";
        }
        return "bar";
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

    private record PdfMetric(String label, String value, String detail, Color color) {
        private PdfMetric(String label, String value, Color color) {
            this(label, value, "", color);
        }
    }

    private record PdfBarValue(String label, double value, String displayValue) {}

    private enum PdfChartStyle {
        HORIZONTAL_BAR,
        VERTICAL_BAR,
        DONUT
    }

    private record PdfChartBlock(String title,
                                 List<PdfBarValue> values,
                                 Color accent,
                                 String emptyState,
                                 PdfChartStyle style,
                                 String centerValue,
                                 String centerLabel) {
        private PdfChartBlock(String title, List<PdfBarValue> values, Color accent, String emptyState) {
            this(title, values, accent, emptyState, PdfChartStyle.HORIZONTAL_BAR, "", "");
        }

        private PdfChartBlock(String title, List<PdfBarValue> values, Color accent, String emptyState, PdfChartStyle style) {
            this(title, values, accent, emptyState, style, "", "");
        }
    }

    private record PdfTextPanel(String title, List<String> lines, Color accent) {}

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
            float x = 30;
            float width = pageWidth - 60;
            float height = 78;
            ensureSpace(height + 12);
            float y = cursorY - height;

            fillRect(x, y, width, height, new Color(15, 23, 42));
            fillRect(x, y + height - 8, width, 8, new Color(59, 130, 246));
            strokeRect(x, y, width, height, new Color(30, 41, 59));

            List<String> titleLines = wrapText(title, FONT_BOLD, 24, width - 36);
            List<String> subtitleLines = wrapText(subtitle, FONT_REGULAR, 11, width - 36);
            float lineY = y + height - 28;
            for (String line : titleLines.stream().limit(2).toList()) {
                writeLine(line, FONT_BOLD, 24, Color.WHITE, x + 18, lineY);
                lineY -= 24;
            }
            for (String line : subtitleLines.stream().limit(2).toList()) {
                writeLine(line, FONT_REGULAR, 11, new Color(191, 219, 254), x + 18, lineY);
                lineY -= 14;
            }

            cursorY = y - 12;
        }

        private void section(String title) throws IOException {
            section(title, new Color(37, 99, 235), null);
        }

        private void section(String title, Color accent, String meta) throws IOException {
            float x = 30;
            float width = pageWidth - 60;
            float height = 24;
            ensureSpace(height + 12);
            float y = cursorY - height;
            fillRect(x, y, width, height, lighten(accent));
            strokeRect(x, y, width, height, accent);
            writeLine(title, FONT_BOLD, 13, new Color(15, 23, 42), x + 10, y + 8);
            if (StringUtils.hasText(meta)) {
                float metaWidth = FONT_BOLD.getStringWidth(sanitizePdfText(meta)) / 1000f * 8f;
                writeLine(meta, FONT_BOLD, 8, new Color(71, 85, 105), x + width - metaWidth - 10, y + 9);
            }
            cursorY = y - 10;
        }

        private void sectionBanner(String title, String subtitle) throws IOException {
            float x = 30;
            float width = pageWidth - 60;
            float height = StringUtils.hasText(subtitle) ? 44 : 34;
            ensureSpace(height + 12);
            float y = cursorY - height;
            fillRect(x, y, width, height, new Color(15, 23, 42));
            fillRect(x, y + height - 4, width, 4, new Color(212, 175, 55));
            strokeRect(x, y, width, height, new Color(30, 41, 59));
            writeLine(title, FONT_BOLD, 18, Color.WHITE, x + 12, y + height - 18);
            if (StringUtils.hasText(subtitle)) {
                writeLine(fitText(subtitle, FONT_REGULAR, 9.5f, width - 24), FONT_REGULAR, 9.5f, new Color(191, 219, 254), x + 12, y + 10);
            }
            cursorY = y - 12;
        }

        private void headingRule(String text) throws IOException {
            ensureSpace(18);
            float x = 36;
            float y = cursorY;
            String title = text.toUpperCase(Locale.ROOT);
            writeLine(title, FONT_BOLD, 11, new Color(15, 23, 42), x, y);
            float titleWidth = FONT_BOLD.getStringWidth(sanitizePdfText(title)) / 1000f * 11f;
            strokeLine(x + titleWidth + 10, y - 1, pageWidth - 36, y - 1, new Color(212, 175, 55), 1.4f);
            cursorY -= 16;
        }

        private void keyValue(String key, String value) throws IOException {
            ensureSpace(14);
            writeLine(key + ":", FONT_BOLD, 10, new Color(15, 23, 42), 36, cursorY);
            writeLine(value, FONT_REGULAR, 10, new Color(51, 65, 85), 112, cursorY);
            cursorY -= 12;
        }

        private void paragraph(String text) throws IOException {
            writeWrapped(text, FONT_REGULAR, 10, new Color(51, 65, 85), 38, pageWidth - 76, 14);
            cursorY -= 4;
        }

        private void bullet(String text) throws IOException {
            ensureSpace(14);
            writeWrapped("- " + text, FONT_REGULAR, 10, new Color(30, 41, 59), 42, pageWidth - 84, 14);
        }

        private void metricRow(List<PdfMetric> metrics) throws IOException {
            if (metrics == null || metrics.isEmpty()) {
                return;
            }
            float x = 30;
            int columns = Math.max(1, metrics.size());
            float width = (pageWidth - 60 - ((columns - 1) * 5f)) / columns;
            float height = 48;
            ensureSpace(height + 8);
            for (PdfMetric metric : metrics) {
                fillRect(x, cursorY - height + 4, width, height, lighten(metric.color));
                fillRect(x, cursorY - 2, width, 6, metric.color);
                strokeRect(x, cursorY - height + 4, width, height, metric.color);
                writeLine(metric.label, FONT_BOLD, 9, new Color(71, 85, 105), x + 10, cursorY - 14);
                writeLine(metric.value, FONT_BOLD, 18, metric.color, x + 10, cursorY - 33);
                if (StringUtils.hasText(metric.detail)) {
                    writeLine(fitText(metric.detail, FONT_REGULAR, 7.5f, width - 16), FONT_REGULAR, 7.5f, new Color(100, 116, 139), x + 10, cursorY - 42);
                }
                x += width + 5;
            }
            cursorY -= height + 10;
        }

        private void metricGrid(List<PdfMetric> metrics, int columns, float cellHeight) throws IOException {
            if (metrics == null || metrics.isEmpty()) {
                return;
            }
            int safeColumns = Math.max(1, columns);
            float x = 36;
            float gap = 6;
            float width = (pageWidth - 72 - ((safeColumns - 1) * gap)) / safeColumns;
            int rows = (int) Math.ceil(metrics.size() / (double) safeColumns);
            float totalHeight = (rows * cellHeight) + ((rows - 1) * gap);
            ensureSpace(totalHeight + 8);

            for (int index = 0; index < metrics.size(); index++) {
                PdfMetric metric = metrics.get(index);
                int rowIndex = index / safeColumns;
                int columnIndex = index % safeColumns;
                float boxX = x + columnIndex * (width + gap);
                float boxY = cursorY - (rowIndex * (cellHeight + gap)) - cellHeight;
                fillRect(boxX, boxY, width, cellHeight, new Color(248, 250, 252));
                fillRect(boxX, boxY + cellHeight - 5, width, 5, metric.color());
                strokeRect(boxX, boxY, width, cellHeight, new Color(203, 213, 225));
                writeLine(fitText(metric.label().toUpperCase(Locale.ROOT), FONT_BOLD, 7.5f, width - 16), FONT_BOLD, 7.5f, new Color(71, 85, 105), boxX + 8, boxY + cellHeight - 15);
                writeLine(fitText(metric.value(), FONT_BOLD, 17f, width - 16), FONT_BOLD, 17f, metric.color(), boxX + 8, boxY + cellHeight - 33);
                if (StringUtils.hasText(metric.detail())) {
                    writeLine(fitText(metric.detail(), FONT_REGULAR, 7.5f, width - 16), FONT_REGULAR, 7.5f, new Color(100, 116, 139), boxX + 8, boxY + 10);
                }
            }
            cursorY -= totalHeight + 10;
        }

        private void subheading(String text) throws IOException {
            ensureSpace(14);
            writeLine(text.toUpperCase(Locale.ROOT), FONT_BOLD, 9, new Color(100, 116, 139), 38, cursorY);
            cursorY -= 12;
        }

        private void noteBox(String text) throws IOException {
            if (!StringUtils.hasText(text)) {
                return;
            }
            float x = 36;
            float width = pageWidth - 72;
            List<String> lines = wrapText(text, FONT_REGULAR, 10, width - 20);
            float height = Math.max(36, lines.size() * 14f + 14f);
            ensureSpace(height + 8);
            float y = cursorY - height;
            fillRect(x, y, width, height, new Color(248, 250, 252));
            strokeRect(x, y, width, height, new Color(226, 232, 240));
            float lineY = y + height - 16;
            for (String line : lines) {
                writeLine(line, FONT_REGULAR, 10, new Color(51, 65, 85), x + 10, lineY);
                lineY -= 14;
            }
            cursorY = y - 8;
        }

        private void insightPanel(String title, List<String> lines, Color accent) throws IOException {
            List<String> safeLines = lines == null ? List.of() : lines.stream().filter(StringUtils::hasText).toList();
            if (safeLines.isEmpty()) {
                return;
            }
            float x = 36;
            float width = pageWidth - 72;
            float lineHeight = 13f;
            float height = 28 + (safeLines.size() * lineHeight) + 8;
            ensureSpace(height + 8);
            float y = cursorY - height;
            fillRect(x, y, width, height, new Color(245, 247, 250));
            fillRect(x, y, 5, height, accent);
            strokeRect(x, y, width, height, new Color(203, 213, 225));
            writeLine(title.toUpperCase(Locale.ROOT), FONT_BOLD, 9f, new Color(71, 85, 105), x + 12, y + height - 16);
            float lineY = y + height - 31;
            for (String line : safeLines) {
                for (String wrapped : wrapText("- " + line, FONT_REGULAR, 9.5f, width - 24)) {
                    if (lineY < y + 12) {
                        break;
                    }
                    writeLine(wrapped, FONT_REGULAR, 9.5f, new Color(30, 41, 59), x + 14, lineY);
                    lineY -= 13f;
                }
            }
            cursorY = y - 10;
        }

        private void textPanels(List<PdfTextPanel> panels) throws IOException {
            List<PdfTextPanel> safePanels = panels == null ? List.of() : panels.stream().filter(Objects::nonNull).toList();
            if (safePanels.isEmpty()) {
                return;
            }
            float x = 36;
            float gap = 12;
            float width = (pageWidth - 72 - ((safePanels.size() - 1) * gap)) / safePanels.size();
            float height = safePanels.stream().map(this::textPanelHeight).max(Float::compareTo).orElse(90f);
            ensureSpace(height + 8);
            float y = cursorY - height;
            for (int index = 0; index < safePanels.size(); index++) {
                drawTextPanel(safePanels.get(index), x + index * (width + gap), y, width, height);
            }
            cursorY = y - 10;
        }

        private float textPanelHeight(PdfTextPanel panel) {
            int lineCount = panel.lines() == null ? 1 : Math.max(1, panel.lines().size());
            return Math.max(84f, 38f + (lineCount * 16f));
        }

        private void drawTextPanel(PdfTextPanel panel, float x, float y, float width, float height) throws IOException {
            fillRect(x, y, width, height, new Color(248, 250, 252));
            strokeRect(x, y, width, height, new Color(203, 213, 225));
            fillRect(x, y + height - 22, width, 22, lighten(panel.accent()));
            strokeRect(x, y + height - 22, width, 22, panel.accent());
            writeLine(fitText(panel.title(), FONT_BOLD, 9.5f, width - 16), FONT_BOLD, 9.5f, new Color(15, 23, 42), x + 8, y + height - 14);
            float lineY = y + height - 38;
            List<String> panelLines = panel.lines() == null || panel.lines().isEmpty() ? List.of("No notes provided.") : panel.lines();
            for (String line : panelLines) {
                for (String wrapped : wrapText(line, FONT_REGULAR, 9.5f, width - 20)) {
                    if (lineY < y + 12) {
                        break;
                    }
                    writeLine(wrapped, FONT_REGULAR, 9.5f, new Color(51, 65, 85), x + 10, lineY);
                    lineY -= 12;
                }
                lineY -= 4;
            }
        }

        private void barChart(Map<String, Long> values, Color accent) throws IOException {
            if (values == null || values.isEmpty()) {
                return;
            }
            List<Map.Entry<String, Long>> entries = values.entrySet().stream().limit(6).toList();
            float rowHeight = 22f;
            float height = entries.size() * rowHeight + 8f;
            ensureSpace(height + 8);
            float x = 38;
            float labelWidth = 125;
            float valueWidth = 36;
            float trackWidth = pageWidth - 76 - labelWidth - valueWidth;
            float topY = cursorY - 8;
            long max = entries.stream().mapToLong(Map.Entry::getValue).max().orElse(1);

            for (int index = 0; index < entries.size(); index++) {
                Map.Entry<String, Long> entry = entries.get(index);
                float rowY = topY - (index * rowHeight);
                writeLine(fitText(entry.getKey(), FONT_BOLD, 9, labelWidth - 8), FONT_BOLD, 9, new Color(30, 41, 59), x, rowY);
                float trackX = x + labelWidth;
                fillRect(trackX, rowY - 6, trackWidth, 8, new Color(226, 232, 240));
                float filled = max == 0 ? 0 : (trackWidth * entry.getValue() / (float) max);
                fillRect(trackX, rowY - 6, filled, 8, accent);
                writeLine(String.valueOf(entry.getValue()), FONT_BOLD, 9, accent, trackX + trackWidth + 8, rowY);
            }
            cursorY = topY - (entries.size() * rowHeight) + 6;
        }

        private void dualBarPanels(PdfChartBlock left, PdfChartBlock right) throws IOException {
            chartPanels(List.of(left, right));
        }

        private void chartPanels(List<PdfChartBlock> blocks) throws IOException {
            List<PdfChartBlock> safeBlocks = blocks == null ? List.of() : blocks.stream().filter(Objects::nonNull).toList();
            if (safeBlocks.isEmpty()) {
                return;
            }
            float x = 36;
            float gap = 12;
            float width = (pageWidth - 72 - ((safeBlocks.size() - 1) * gap)) / safeBlocks.size();
            float height = safeBlocks.stream().map(this::chartPanelHeight).max(Float::compareTo).orElse(170f);
            ensureSpace(height + 10);
            float y = cursorY - height;
            for (int index = 0; index < safeBlocks.size(); index++) {
                drawChartPanel(safeBlocks.get(index), x + index * (width + gap), y, width, height);
            }
            cursorY = y - 10;
        }

        private float chartPanelHeight(PdfChartBlock block) {
            int rowCount = block == null || block.values() == null ? 0 : block.values().size();
            return switch (block == null ? PdfChartStyle.HORIZONTAL_BAR : block.style()) {
                case DONUT -> 180f;
                case VERTICAL_BAR -> 190f;
                case HORIZONTAL_BAR -> Math.max(150f, 54f + (rowCount * 18f));
            };
        }

        private void drawChartPanel(PdfChartBlock block, float x, float y, float width, float height) throws IOException {
            fillRect(x, y, width, height, new Color(248, 250, 252));
            strokeRect(x, y, width, height, new Color(203, 213, 225));
            fillRect(x, y + height - 22, width, 22, lighten(block.accent()));
            strokeRect(x, y + height - 22, width, 22, block.accent());
            writeLine(fitText(block.title(), FONT_BOLD, 9.5f, width - 16), FONT_BOLD, 9.5f, new Color(15, 23, 42), x + 8, y + height - 14);

            List<PdfBarValue> values = block.values() == null ? List.of() : block.values().stream().limit(6).toList();
            if (values.isEmpty() || values.stream().noneMatch(item -> item.value() > 0)) {
                float textY = y + height - 42;
                String message = StringUtils.hasText(block.emptyState()) ? block.emptyState() : "No chart data available.";
                for (String line : wrapText(message, FONT_REGULAR, 9.5f, width - 20).stream().limit(3).toList()) {
                    writeLine(line, FONT_REGULAR, 9.5f, new Color(100, 116, 139), x + 10, textY);
                    textY -= 13;
                }
                return;
            }

            if (block.style() == PdfChartStyle.DONUT) {
                drawDonutPanel(block, values, x, y, width, height);
                return;
            }
            if (block.style() == PdfChartStyle.VERTICAL_BAR) {
                drawVerticalBarPanel(block, values, x, y, width, height);
                return;
            }

            double max = values.stream().mapToDouble(PdfBarValue::value).max().orElse(1);
            float labelWidth = Math.min(116f, width * 0.42f);
            float valueWidth = 34f;
            float trackWidth = width - 20 - labelWidth - valueWidth;
            float rowHeight = 18f;
            float rowY = y + height - 42;
            for (PdfBarValue item : values) {
                writeLine(fitText(item.label(), FONT_BOLD, 8.5f, labelWidth - 6), FONT_BOLD, 8.5f, new Color(51, 65, 85), x + 8, rowY);
                float trackX = x + 8 + labelWidth;
                fillRect(trackX, rowY - 6, trackWidth, 8, new Color(226, 232, 240));
                float fillWidth = max == 0 ? 0 : (float) ((item.value() / max) * trackWidth);
                fillRect(trackX, rowY - 6, fillWidth, 8, block.accent());
                writeLine(item.displayValue(), FONT_BOLD, 8.5f, block.accent(), trackX + trackWidth + 6, rowY);
                rowY -= rowHeight;
            }
        }

        private void drawVerticalBarPanel(PdfChartBlock block,
                                          List<PdfBarValue> values,
                                          float x,
                                          float y,
                                          float width,
                                          float height) throws IOException {
            double max = values.stream().mapToDouble(PdfBarValue::value).max().orElse(1);
            float chartLeft = x + 18;
            float chartRight = x + width - 18;
            float chartBottom = y + 28;
            float chartTop = y + 42;
            float chartHeight = height - 74;
            float slotWidth = (chartRight - chartLeft) / values.size();

            strokeLine(chartLeft, chartBottom, chartRight, chartBottom, new Color(203, 213, 225), 1f);
            for (int index = 0; index < values.size(); index++) {
                PdfBarValue item = values.get(index);
                float barWidth = Math.min(26f, slotWidth * 0.55f);
                float barX = chartLeft + index * slotWidth + ((slotWidth - barWidth) / 2f);
                float barHeight = max == 0 ? 0 : (float) ((item.value() / max) * chartHeight);
                fillRect(barX, chartBottom, barWidth, barHeight, block.accent());
                writeCenteredLine(item.displayValue(), FONT_BOLD, 8.5f, new Color(51, 65, 85), barX + (barWidth / 2f), chartBottom + barHeight + 8);
                writeCenteredLine(fitText(item.label(), FONT_REGULAR, 7.5f, slotWidth - 4), FONT_REGULAR, 7.5f, new Color(71, 85, 105), chartLeft + index * slotWidth + (slotWidth / 2f), y + 12);
            }
        }

        private void drawDonutPanel(PdfChartBlock block,
                                    List<PdfBarValue> values,
                                    float x,
                                    float y,
                                    float width,
                                    float height) throws IOException {
            double total = values.stream().mapToDouble(PdfBarValue::value).sum();
            double primary = values.get(0).value();
            double ratio = total <= 0 ? 0 : primary / total;
            float centerX = x + (width * 0.32f);
            float centerY = y + (height * 0.46f);
            float radius = Math.min(width * 0.18f, height * 0.24f);
            strokeArc(centerX, centerY, radius, 0, 360, 12f, new Color(226, 232, 240));
            strokeArc(centerX, centerY, radius, 90, (float) (90 - (ratio * 360f)), 12f, block.accent());
            writeCenteredLine(block.centerValue(), FONT_BOLD, 16f, new Color(15, 23, 42), centerX, centerY + 4);
            writeCenteredLine(block.centerLabel(), FONT_REGULAR, 8.5f, new Color(100, 116, 139), centerX, centerY - 12);

            float legendX = x + width * 0.56f;
            float legendY = y + height - 48;
            for (int index = 0; index < values.size(); index++) {
                PdfBarValue item = values.get(index);
                Color chip = index == 0 ? block.accent() : new Color(148, 163, 184);
                fillRect(legendX, legendY - 6, 8, 8, chip);
                writeLine(fitText(item.label() + " - " + item.displayValue(), FONT_REGULAR, 8.5f, width * 0.34f), FONT_REGULAR, 8.5f, new Color(51, 65, 85), legendX + 14, legendY - 1);
                legendY -= 15;
            }
        }

        private void photoPlaceholders() throws IOException {
            float x = 38;
            float gap = 10;
            float width = (pageWidth - 76 - (gap * 2)) / 3f;
            float height = 72;
            ensureSpace(height + 10);
            float y = cursorY - height;
            for (int index = 0; index < 3; index++) {
                float boxX = x + index * (width + gap);
                fillRect(boxX, y, width, height, new Color(248, 250, 252));
                strokeRect(boxX, y, width, height, new Color(148, 163, 184));
                writeLine("PHOTO " + (index + 1), FONT_BOLD, 10, new Color(100, 116, 139), boxX + 20, y + height / 2);
            }
            cursorY = y - 10;
        }

        private void table(List<Map<String, Object>> rows,
                           List<String> fields,
                           Map<String, String> labels,
                           int limit) throws IOException {
            table(rows, fields, labels, null, limit);
        }

        private void table(List<Map<String, Object>> rows,
                           List<String> fields,
                           Map<String, String> labels,
                           float[] widthRatios,
                           int limit) throws IOException {
            if (rows == null || rows.isEmpty() || fields == null || fields.isEmpty()) {
                return;
            }
            List<Map<String, Object>> visibleRows = rows.stream().limit(limit).toList();
            float x = 36;
            float width = pageWidth - 72;
            float headerHeight = 18;
            float rowHeight = 18;
            float height = headerHeight + (visibleRows.size() * rowHeight);
            ensureSpace(height + 8);
            float y = cursorY - height;
            float[] widths = widthRatios == null ? columnWidths(fields, width) : columnWidths(widthRatios, width);

            fillRect(x, y + height - headerHeight, width, headerHeight, new Color(241, 245, 249));
            strokeRect(x, y, width, height, new Color(203, 213, 225));

            float currentX = x;
            for (int index = 0; index < fields.size(); index++) {
                String field = fields.get(index);
                float columnWidth = widths[index];
                strokeRect(currentX, y + height - headerHeight, columnWidth, headerHeight, new Color(203, 213, 225));
                writeLine(fitText(labels.getOrDefault(field, field), FONT_BOLD, 8, columnWidth - 8), FONT_BOLD, 8, new Color(71, 85, 105), currentX + 4, y + height - 12);
                currentX += columnWidth;
            }

            for (int rowIndex = 0; rowIndex < visibleRows.size(); rowIndex++) {
                Map<String, Object> row = visibleRows.get(rowIndex);
                float rowY = y + height - headerHeight - ((rowIndex + 1) * rowHeight);
                if (rowIndex % 2 == 0) {
                    fillRect(x, rowY, width, rowHeight, new Color(248, 250, 252));
                }
                currentX = x;
                for (int index = 0; index < fields.size(); index++) {
                    String field = fields.get(index);
                    float columnWidth = widths[index];
                    strokeRect(currentX, rowY, columnWidth, rowHeight, new Color(226, 232, 240));
                    writeLine(fitText(String.valueOf(row.get(field) == null ? "" : row.get(field)), FONT_REGULAR, 8.5f, columnWidth - 8), FONT_REGULAR, 8.5f, new Color(30, 41, 59), currentX + 4, rowY + 6);
                    currentX += columnWidth;
                }
            }

            cursorY = y - 10;
        }

        private float[] columnWidths(List<String> fields, float totalWidth) {
            float[] widths = new float[fields.size()];
            if (fields.size() == 1) {
                widths[0] = totalWidth;
                return widths;
            }
            if (fields.size() == 2) {
                widths[0] = totalWidth * 0.38f;
                widths[1] = totalWidth * 0.62f;
                return widths;
            }
            widths[0] = totalWidth * 0.28f;
            float remaining = totalWidth - widths[0];
            float other = remaining / (fields.size() - 1);
            for (int index = 1; index < fields.size(); index++) {
                widths[index] = other;
            }
            return widths;
        }

        private float[] columnWidths(float[] ratios, float totalWidth) {
            float totalRatio = 0f;
            for (float ratio : ratios) {
                totalRatio += ratio;
            }
            float[] widths = new float[ratios.length];
            float used = 0f;
            for (int index = 0; index < ratios.length; index++) {
                widths[index] = totalWidth * (ratios[index] / totalRatio);
                used += widths[index];
            }
            if (widths.length > 0) {
                widths[widths.length - 1] += totalWidth - used;
            }
            return widths;
        }

        private String fitText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
            String value = sanitizePdfText(text == null ? "" : text);
            if (font.getStringWidth(value) / 1000f * fontSize <= maxWidth) {
                return value;
            }
            String ellipsis = "...";
            String current = value;
            while (!current.isEmpty()) {
                current = current.substring(0, current.length() - 1);
                String candidate = current + ellipsis;
                if (font.getStringWidth(candidate) / 1000f * fontSize <= maxWidth) {
                    return candidate;
                }
            }
            return ellipsis;
        }

        private void spacer(float height) {
            cursorY -= height;
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

        private void writeCenteredLine(String text, PDFont font, float fontSize, Color color, float centerX, float y) throws IOException {
            String safeText = sanitizePdfText(text == null ? "" : text);
            float width = font.getStringWidth(safeText) / 1000f * fontSize;
            writeLine(safeText, font, fontSize, color, centerX - (width / 2f), y);
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

        private void strokeLine(float x1, float y1, float x2, float y2, Color color, float lineWidth) throws IOException {
            content.setStrokingColor(color);
            content.setLineWidth(lineWidth);
            content.moveTo(x1, y1);
            content.lineTo(x2, y2);
            content.stroke();
            content.setLineWidth(1f);
        }

        private void strokeArc(float centerX,
                               float centerY,
                               float radius,
                               float startDegrees,
                               float endDegrees,
                               float lineWidth,
                               Color color) throws IOException {
            float start = startDegrees;
            float end = endDegrees;
            if (end < start) {
                end += 360f;
            }
            int segments = Math.max(12, (int) Math.ceil(Math.abs(end - start) / 12f));
            content.setStrokingColor(color);
            content.setLineWidth(lineWidth);
            for (int index = 0; index <= segments; index++) {
                double angle = Math.toRadians(start + ((end - start) * index / segments));
                float px = centerX + (float) (Math.cos(angle) * radius);
                float py = centerY + (float) (Math.sin(angle) * radius);
                if (index == 0) {
                    content.moveTo(px, py);
                } else {
                    content.lineTo(px, py);
                }
            }
            content.stroke();
            content.setLineWidth(1f);
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
