package com.cxalloy.integration.service;

import com.cxalloy.integration.dto.CopilotChatRequest;
import com.cxalloy.integration.dto.CopilotMessage;
import com.cxalloy.integration.model.Checklist;
import com.cxalloy.integration.model.Company;
import com.cxalloy.integration.model.CxTask;
import com.cxalloy.integration.model.Equipment;
import com.cxalloy.integration.model.Issue;
import com.cxalloy.integration.model.Person;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.model.ProjectManagedFile;
import com.cxalloy.integration.model.ProjectedFile;
import com.cxalloy.integration.model.Role;
import com.cxalloy.integration.model.TrackerBrief;
import com.cxalloy.integration.repository.ChecklistRepository;
import com.cxalloy.integration.repository.CompanyRepository;
import com.cxalloy.integration.repository.CxTaskRepository;
import com.cxalloy.integration.repository.EquipmentRepository;
import com.cxalloy.integration.repository.FileRecordRepository;
import com.cxalloy.integration.repository.IssueRepository;
import com.cxalloy.integration.repository.PersonRepository;
import com.cxalloy.integration.repository.ProjectManagedFileRepository;
import com.cxalloy.integration.repository.ProjectRepository;
import com.cxalloy.integration.repository.RoleRepository;
import com.cxalloy.integration.repository.TrackerBriefRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CopilotService {

    private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final int CONTEXT_PREVIEW_LIMIT = 12;
    private static final int DATE_RECORD_LIMIT = 24;
    private static final int SEARCH_MATCH_LIMIT = 8;
    private static final int FILE_TEXT_LIMIT = 12000;
    private static final int HISTORY_LIMIT = 12;
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
    private static final Set<String> STOP_WORDS = Set.of(
            "what", "which", "where", "when", "with", "from", "that", "this", "into",
            "about", "there", "their", "project", "projects", "please", "show", "give",
            "have", "been", "were", "does", "your", "ours", "than", "then", "them",
            "open", "closed", "data", "the", "and", "for", "are", "but", "not", "you"
    );
    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);

    private final ProjectRepository projectRepository;
    private final IssueRepository issueRepository;
    private final ChecklistRepository checklistRepository;
    private final EquipmentRepository equipmentRepository;
    private final CxTaskRepository taskRepository;
    private final PersonRepository personRepository;
    private final CompanyRepository companyRepository;
    private final RoleRepository roleRepository;
    private final FileRecordRepository fileRecordRepository;
    private final ProjectManagedFileRepository projectManagedFileRepository;
    private final TrackerBriefRepository trackerBriefRepository;
    private final ProjectAccessService projectAccessService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${copilot.api-key:}")
    private String configuredApiKey;

    @Value("${copilot.default-model:gpt-5.4}")
    private String defaultModel;

    public CopilotService(ProjectRepository projectRepository,
                          IssueRepository issueRepository,
                          ChecklistRepository checklistRepository,
                          EquipmentRepository equipmentRepository,
                          CxTaskRepository taskRepository,
                          PersonRepository personRepository,
                          CompanyRepository companyRepository,
                          RoleRepository roleRepository,
                          FileRecordRepository fileRecordRepository,
                          ProjectManagedFileRepository projectManagedFileRepository,
                          TrackerBriefRepository trackerBriefRepository,
                          ProjectAccessService projectAccessService,
                          RestTemplate restTemplate,
                          ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.issueRepository = issueRepository;
        this.checklistRepository = checklistRepository;
        this.equipmentRepository = equipmentRepository;
        this.taskRepository = taskRepository;
        this.personRepository = personRepository;
        this.companyRepository = companyRepository;
        this.roleRepository = roleRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.projectManagedFileRepository = projectManagedFileRepository;
        this.trackerBriefRepository = trackerBriefRepository;
        this.projectAccessService = projectAccessService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getWorkspaceContext(List<String> requestedProjectIds, String query, boolean includeProjectFiles) {
        return buildWorkspaceContext(requestedProjectIds, query, List.of(), includeProjectFiles);
    }

    public Map<String, Object> getCopilotConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configured", StringUtils.hasText(configuredApiKey));
        config.put("defaultModel", defaultModel);
        return config;
    }

    public Map<String, Object> chat(CopilotChatRequest request, List<MultipartFile> files) {
        if (!StringUtils.hasText(request.getPrompt())) {
            throw new IllegalArgumentException("Prompt is required");
        }

        String resolvedApiKey = StringUtils.hasText(request.getApiKey())
                ? request.getApiKey().trim()
                : StringUtils.hasText(configuredApiKey) ? configuredApiKey.trim() : "";
        if (!StringUtils.hasText(resolvedApiKey)) {
            throw new IllegalArgumentException("OpenAI is not configured on the server");
        }

        List<Map<String, Object>> uploadedFiles = extractUploads(files);
        Map<String, Object> context = buildWorkspaceContext(
                request.getProjectIds(),
                request.getPrompt(),
                uploadedFiles,
                request.isIncludeProjectFiles()
        );

        List<Map<String, Object>> input = new ArrayList<>();

        List<CopilotMessage> history = request.getConversation() == null ? List.of() : request.getConversation();
        int historyStart = Math.max(0, history.size() - HISTORY_LIMIT);
        for (int i = historyStart; i < history.size(); i++) {
            CopilotMessage message = history.get(i);
            if (!StringUtils.hasText(message.getText())) {
                continue;
            }
            input.add(messagePart(normalizeRole(message.getRole()), message.getText().trim()));
        }
        input.add(messagePart("user", request.getPrompt().trim()));

        Map<String, Object> payload = new LinkedHashMap<>();
        String resolvedModel = StringUtils.hasText(request.getModel()) ? request.getModel().trim() : defaultModel;
        String resolvedInstructions = StringUtils.hasText(request.getInstructions())
                ? request.getInstructions().trim()
                : buildSystemPrompt(context);
        payload.put("model", resolvedModel);
        payload.put("instructions", resolvedInstructions);
        payload.put("input", input);
        payload.put("max_output_tokens", 900);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resolvedApiKey);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(
                    OPENAI_RESPONSES_URL,
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    String.class
            );
        } catch (HttpStatusCodeException ex) {
            throw new IllegalStateException(extractOpenAiError(ex));
        }

        Map<String, Object> responseBody = readJsonMap(response.getBody());
        String answer = extractOutputText(responseBody);
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("OpenAI returned an empty response");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer.trim());
        result.put("model", payload.get("model"));
        result.put("scope", context.get("scope"));
        result.put("totals", context.get("totals"));
        result.put("searchMatches", context.get("searchMatches"));
        result.put("uploads", uploadedFiles);
        result.put("projectLibraryFiles", context.get("projectLibraryFiles"));
        result.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return result;
    }

    private Map<String, Object> buildWorkspaceContext(List<String> requestedProjectIds,
                                                      String query,
                                                      List<Map<String, Object>> uploads,
                                                      boolean includeProjectFiles) {
        Set<String> requestedIds = requestedProjectIds == null
                ? new LinkedHashSet<>()
                : requestedProjectIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> allowedIds = projectAccessService.getAccessibleProjectIdsForCurrentUser();
        Set<String> selectedIds = requestedIds.isEmpty()
                ? new LinkedHashSet<>(allowedIds)
                : requestedIds.stream()
                    .filter(allowedIds::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Project> allProjects = projectRepository.findAll();
        List<Project> projects = allProjects.stream()
                .filter(project -> selectedIds.isEmpty() || selectedIds.contains(project.getExternalId()))
                .sorted(Comparator.comparing(Project::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        Set<String> activeProjectIds = projects.stream()
                .map(Project::getExternalId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Issue> issues = filterByProjects(issueRepository.findAll(), Issue::getProjectId, activeProjectIds);
        List<Checklist> checklists = filterByProjects(checklistRepository.findAll(), Checklist::getProjectId, activeProjectIds);
        List<Equipment> equipment = filterByProjects(equipmentRepository.findAll(), Equipment::getProjectId, activeProjectIds);
        List<CxTask> tasks = filterByProjects(taskRepository.findAll(), CxTask::getProjectId, activeProjectIds);
        List<Person> persons = filterByProjects(personRepository.findAll(), Person::getProjectId, activeProjectIds);
        List<Company> companies = filterByProjects(companyRepository.findAll(), Company::getProjectId, activeProjectIds);
        List<Role> roles = filterByProjects(roleRepository.findAll(), Role::getProjectId, activeProjectIds);
        List<ProjectedFile> files = filterByProjects(fileRecordRepository.findAll(), ProjectedFile::getProjectId, activeProjectIds);
        List<ProjectManagedFile> managedFiles = includeProjectFiles
                ? filterByProjects(projectManagedFileRepository.findAll(), ProjectManagedFile::getProjectId, activeProjectIds)
                : List.of();
        List<TrackerBrief> briefs = filterByProjects(trackerBriefRepository.findAll(), TrackerBrief::getProjectId, activeProjectIds);
        List<Map<String, Object>> tests = extractTests(equipment);
        List<Map<String, Object>> indexedProjectFiles = includeProjectFiles
                ? buildManagedFileContext(managedFiles)
                : List.of();

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("mode", selectedIds.isEmpty() ? "entire_workspace" : "selected_projects");
        scope.put("projectIds", new ArrayList<>(activeProjectIds));
        scope.put("projectNames", projects.stream().map(Project::getName).toList());
        scope.put("projectCount", projects.size());
        scope.put("workspaceProjectCount", allProjects.size());
        scope.put("query", query == null ? "" : query);
        scope.put("includeProjectFiles", includeProjectFiles);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("projects", projects.size());
        totals.put("issues", issues.size());
        totals.put("openIssues", issues.stream().filter(issue -> !isClosedStatus(issue.getStatus())).count());
        totals.put("closedIssues", issues.stream().filter(issue -> isClosedStatus(issue.getStatus())).count());
        totals.put("checklists", checklists.size());
        totals.put("equipment", equipment.size());
        totals.put("tasks", tasks.size());
        totals.put("persons", persons.size());
        totals.put("companies", companies.size());
        totals.put("roles", roles.size());
        totals.put("files", files.size());
        totals.put("projectLibraryFiles", managedFiles.size());
        totals.put("projectLibraryIndexedFiles", indexedProjectFiles.size());
        totals.put("briefs", briefs.size());
        totals.put("tests", tests.size());
        totals.put("fileBytes", files.stream()
                .map(ProjectedFile::getFileSize)
                .filter(size -> size != null && size > 0)
                .reduce(0L, Long::sum));
        totals.put("projectLibraryFileBytes", managedFiles.stream()
                .map(ProjectManagedFile::getSizeBytes)
                .filter(size -> size != null && size > 0)
                .reduce(0L, Long::sum));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("totals", totals);
        result.put("projects", projects.stream().limit(CONTEXT_PREVIEW_LIMIT).map(this::projectPreview).toList());
        result.put("issueOverview", buildIssueOverview(issues));
        result.put("checklistOverview", buildChecklistOverview(checklists));
        result.put("equipmentOverview", buildEquipmentOverview(equipment));
        result.put("testOverview", buildTestOverview(tests));
        result.put("taskOverview", buildTaskOverview(tasks));
        result.put("dateIndex", buildDateIndex(issues, checklists, equipment, tasks, tests, files, briefs, managedFiles));
        result.put("peopleOverview", buildPeopleOverview(persons));
        result.put("companyOverview", Map.of(
                "topCompanies", topCounts(companies, Company::getName, 10),
                "preview", companies.stream().limit(CONTEXT_PREVIEW_LIMIT).map(this::companyPreview).toList()
        ));
        result.put("roleOverview", Map.of(
                "topRoles", topCounts(roles, Role::getName, 10),
                "preview", roles.stream().limit(CONTEXT_PREVIEW_LIMIT).map(this::rolePreview).toList()
        ));
        result.put("fileOverview", buildFileOverview(files));
        result.put("projectLibraryFiles", Map.of(
                "enabled", includeProjectFiles,
                "indexedCount", indexedProjectFiles.size(),
                "preview", indexedProjectFiles.stream().limit(CONTEXT_PREVIEW_LIMIT).toList()
        ));
        result.put("briefOverview", Map.of(
                "recentBriefs", briefs.stream()
                        .sorted(Comparator.comparing(TrackerBrief::getExportedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(CONTEXT_PREVIEW_LIMIT)
                        .map(this::briefPreview)
                        .toList()
        ));
        result.put("uploads", uploads);
        result.put("searchMatches", buildSearchMatches(query, projects, issues, checklists, equipment, tasks, persons, companies, roles, files, indexedProjectFiles));
        result.put("workspaceCapabilities", List.of(
                "Tracker Pulse",
                "Planned vs Actual",
                "Checklist Flow",
                "Issue Radar",
                "Asset Readiness",
                "Tracker Briefs",
                "AI Copilot",
                includeProjectFiles ? "Project File Library Context" : "Project File Library Context Available"
        ));
        return result;
    }

    private Map<String, Object> buildIssueOverview(List<Issue> issues) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("byStatus", topCounts(issues, Issue::getStatus, 12));
        overview.put("byPriority", topCounts(issues, Issue::getPriority, 8));
        overview.put("byAssignee", topCounts(issues, Issue::getAssignee, 12));
        overview.put("byLocation", topCounts(issues, Issue::getLocation, 12));
        overview.put("dateCoverage", buildDateCoverage(issues, Issue::getCreatedAt, Issue::getUpdatedAt, Issue::getActualFinishDate));
        overview.put("recent", issues.stream()
                .sorted(Comparator.comparing((Issue issue) -> sortDateKey(issue.getActualFinishDate(), issue.getUpdatedAt(), issue.getCreatedAt())).reversed())
                .limit(CONTEXT_PREVIEW_LIMIT)
                .map(this::issuePreview)
                .toList());
        return overview;
    }

    private Map<String, Object> buildChecklistOverview(List<Checklist> checklists) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("byStatus", topCounts(checklists, Checklist::getStatus, 12));
        overview.put("byTagLevel", topCounts(checklists, Checklist::getTagLevel, 10));
        overview.put("byType", topCounts(checklists, Checklist::getChecklistType, 12));
        overview.put("dateCoverage", buildDateCoverage(checklists, Checklist::getCreatedAt, Checklist::getUpdatedAt, Checklist::getActualFinishDate));
        overview.put("recent", checklists.stream()
                .sorted(Comparator.comparing((Checklist checklist) -> sortDateKey(checklist.getActualFinishDate(), checklist.getUpdatedAt(), checklist.getCreatedAt())).reversed())
                .limit(CONTEXT_PREVIEW_LIMIT)
                .map(this::checklistPreview)
                .toList());
        return overview;
    }

    private Map<String, Object> buildEquipmentOverview(List<Equipment> equipment) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("byType", topCounts(equipment, Equipment::getEquipmentType, 12));
        overview.put("bySystem", topCounts(equipment, Equipment::getSystemName, 12));
        overview.put("byDiscipline", topCounts(equipment, Equipment::getDiscipline, 10));
        overview.put("byStatus", topCounts(equipment, Equipment::getStatus, 10));
        overview.put("dateCoverage", buildDateCoverage(equipment, Equipment::getCreatedAt, Equipment::getUpdatedAt, item -> null));
        overview.put("recent", equipment.stream()
                .sorted(Comparator.comparing(Equipment::getUpdatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(CONTEXT_PREVIEW_LIMIT)
                .map(this::equipmentPreview)
                .toList());
        return overview;
    }

    private Map<String, Object> buildTaskOverview(List<CxTask> tasks) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("byStatus", topCounts(tasks, CxTask::getStatus, 10));
        overview.put("byPriority", topCounts(tasks, CxTask::getPriority, 8));
        overview.put("byAssignee", topCounts(tasks, CxTask::getAssignedTo, 10));
        overview.put("dateCoverage", buildDateCoverage(tasks, CxTask::getCreatedAt, CxTask::getUpdatedAt, CxTask::getActualFinishDate));
        overview.put("recent", tasks.stream()
                .sorted(Comparator.comparing((CxTask task) -> sortDateKey(task.getActualFinishDate(), task.getUpdatedAt(), task.getCreatedAt())).reversed())
                .limit(CONTEXT_PREVIEW_LIMIT)
                .map(this::taskPreview)
                .toList());
        return overview;
    }

    private Map<String, Object> buildTestOverview(List<Map<String, Object>> tests) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("byStatus", topMapCounts(tests, row -> stringValue(row.get("status")), 12));
        overview.put("byEquipment", topMapCounts(tests, row -> stringValue(row.get("equipmentName")), 12));
        overview.put("dateCoverage", buildMapDateCoverage(tests, "createdAt", "updatedAt", "actualFinishDate"));
        overview.put("recent", tests.stream()
                .sorted(Comparator.comparing((Map<String, Object> test) -> sortDateKey(stringValue(test.get("actualFinishDate")), stringValue(test.get("updatedAt")), stringValue(test.get("createdAt")))).reversed())
                .limit(CONTEXT_PREVIEW_LIMIT)
                .toList());
        return overview;
    }

    private Map<String, Object> buildDateIndex(List<Issue> issues,
                                               List<Checklist> checklists,
                                               List<Equipment> equipment,
                                               List<CxTask> tasks,
                                               List<Map<String, Object>> tests,
                                               List<ProjectedFile> files,
                                               List<TrackerBrief> briefs,
                                               List<ProjectManagedFile> managedFiles) {
        Map<String, Object> dateIndex = new LinkedHashMap<>();
        dateIndex.put("issues", buildDateCoverage(issues, Issue::getCreatedAt, Issue::getUpdatedAt, Issue::getActualFinishDate));
        dateIndex.put("checklists", buildDateCoverage(checklists, Checklist::getCreatedAt, Checklist::getUpdatedAt, Checklist::getActualFinishDate));
        dateIndex.put("equipment", buildDateCoverage(equipment, Equipment::getCreatedAt, Equipment::getUpdatedAt, item -> null));
        dateIndex.put("tasks", buildDateCoverage(tasks, CxTask::getCreatedAt, CxTask::getUpdatedAt, CxTask::getActualFinishDate));
        dateIndex.put("tests", buildMapDateCoverage(tests, "createdAt", "updatedAt", "actualFinishDate"));
        dateIndex.put("files", Map.of(
                "created", summarizeDates(files.stream().map(ProjectedFile::getCreatedDate).toList()),
                "updated", summarizeDates(List.of()),
                "actualFinish", summarizeDates(List.of())
        ));
        dateIndex.put("projectLibraryFiles", Map.of(
                "created", summarizeDates(managedFiles.stream().map(file -> formatDateTime(file.getUploadedAt())).toList()),
                "updated", summarizeDates(managedFiles.stream().map(file -> formatDateTime(file.getUpdatedAt())).toList()),
                "actualFinish", summarizeDates(List.of())
        ));
        dateIndex.put("briefs", Map.of(
                "created", summarizeDates(briefs.stream().map(brief -> brief.getExportedAt() == null ? null : brief.getExportedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).toList()),
                "updated", summarizeDates(List.of()),
                "actualFinish", summarizeDates(List.of())
        ));
        dateIndex.put("recentActualFinish", buildRecentActualFinishRecords(issues, checklists, tasks, tests));
        dateIndex.put("recentUpdates", buildRecentUpdatedRecords(issues, checklists, equipment, tasks, tests));
        dateIndex.put("recentCreates", buildRecentCreatedRecords(issues, checklists, equipment, tasks, tests, files, briefs, managedFiles));
        return dateIndex;
    }

    private Map<String, Object> buildPeopleOverview(List<Person> persons) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("byCompany", topCounts(persons, Person::getCompany, 10));
        overview.put("byRole", topCounts(persons, Person::getRole, 10));
        overview.put("byStatus", topCounts(persons, Person::getStatus, 10));
        overview.put("preview", persons.stream().limit(CONTEXT_PREVIEW_LIMIT).map(this::personPreview).toList());
        return overview;
    }

    private Map<String, Object> buildFileOverview(List<ProjectedFile> files) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("byMimeType", topCounts(files, ProjectedFile::getMimeType, 10));
        overview.put("largest", files.stream()
                .sorted(Comparator.comparing(ProjectedFile::getFileSize, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(CONTEXT_PREVIEW_LIMIT)
                .map(this::filePreview)
                .toList());
        return overview;
    }

    private Map<String, Object> buildSearchMatches(String query,
                                                   List<Project> projects,
                                                   List<Issue> issues,
                                                   List<Checklist> checklists,
                                                   List<Equipment> equipment,
                                                   List<CxTask> tasks,
                                                   List<Person> persons,
                                                   List<Company> companies,
                                                   List<Role> roles,
                                                   List<ProjectedFile> files,
                                                   List<Map<String, Object>> projectLibraryFiles) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return Map.of(
                    "issues", List.of(),
                    "checklists", List.of(),
                    "equipment", List.of(),
                    "tasks", List.of(),
                    "persons", List.of(),
                    "companies", List.of(),
                    "roles", List.of(),
                    "files", List.of(),
                    "projectLibraryFiles", List.of(),
                    "projects", List.of()
            );
        }

        Map<String, Object> matches = new LinkedHashMap<>();
        matches.put("projects", selectMatches(projects, project -> joinFields(
                project.getExternalId(), project.getName(), project.getNumber(), project.getLocation(), project.getClient()
        ), this::projectPreview, tokens));
        matches.put("issues", selectMatches(issues, issue -> joinFields(
                issue.getExternalId(), issue.getTitle(), issue.getDescription(), issue.getStatus(), issue.getLocation(), issue.getAssignee()
        ), this::issuePreview, tokens));
        matches.put("checklists", selectMatches(checklists, checklist -> joinFields(
                checklist.getExternalId(), checklist.getName(), checklist.getDescription(), checklist.getChecklistType(), checklist.getStatus()
        ), this::checklistPreview, tokens));
        matches.put("equipment", selectMatches(equipment, asset -> joinFields(
                asset.getExternalId(), asset.getName(), asset.getTag(), asset.getEquipmentType(), asset.getSystemName(), asset.getDiscipline()
        ), this::equipmentPreview, tokens));
        matches.put("tasks", selectMatches(tasks, task -> joinFields(
                task.getExternalId(), task.getTitle(), task.getDescription(), task.getStatus(), task.getAssignedTo(), task.getIssueId()
        ), this::taskPreview, tokens));
        matches.put("persons", selectMatches(persons, person -> joinFields(
                person.getExternalId(), person.getFirstName(), person.getLastName(), person.getEmail(), person.getCompany(), person.getRole()
        ), this::personPreview, tokens));
        matches.put("companies", selectMatches(companies, company -> joinFields(
                company.getExternalId(), company.getName(), company.getAbbreviation(), company.getAddress()
        ), this::companyPreview, tokens));
        matches.put("roles", selectMatches(roles, role -> joinFields(
                role.getExternalId(), role.getName(), role.getAbbreviation(), role.getDescription()
        ), this::rolePreview, tokens));
        matches.put("files", selectMatches(files, file -> joinFields(
                file.getExternalId(), file.getName(), file.getMimeType(), file.getAssetType(), file.getAssetId()
        ), this::filePreview, tokens));
        matches.put("projectLibraryFiles", selectMatches(projectLibraryFiles, file -> joinFields(
                stringValue(file.get("name")),
                stringValue(file.get("folderName")),
                stringValue(file.get("contentType")),
                stringValue(file.get("status")),
                stringValue(file.get("extractedText"))
        ), Function.identity(), tokens));
        return matches;
    }

    private <T> List<Map<String, Object>> selectMatches(List<T> items,
                                                        Function<T, String> haystack,
                                                        Function<T, Map<String, Object>> preview,
                                                        List<String> tokens) {
        return items.stream()
                .map(item -> Map.entry(item, scoreMatch(haystack.apply(item), tokens)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(SEARCH_MATCH_LIMIT)
                .map(entry -> preview.apply(entry.getKey()))
                .toList();
    }

    private int scoreMatch(String haystack, List<String> tokens) {
        String normalized = safeLower(haystack);
        int score = 0;
        for (String token : tokens) {
            if (normalized.contains(token)) {
                score += token.length();
            }
        }
        return score;
    }

    private List<String> tokenize(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        return List.of(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).stream()
                .filter(token -> token.length() >= 3)
                .filter(token -> !STOP_WORDS.contains(token))
                .distinct()
                .limit(12)
                .toList();
    }

    private List<Map<String, Object>> extractUploads(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> uploads = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("name", file.getOriginalFilename());
            preview.put("contentType", file.getContentType());
            preview.put("sizeBytes", file.getSize());
            preview.put("extractedText", extractUploadText(file));
            uploads.add(preview);
        }
        return uploads;
    }

    private String extractUploadText(MultipartFile file) {
        try {
            return extractFileText(file.getOriginalFilename(), file.getContentType(), file.getInputStream());
        } catch (IOException ex) {
            return "Failed to read uploaded file: " + ex.getMessage();
        }
    }

    private String extractStoredFileText(ProjectManagedFile file) {
        if (file == null || !StringUtils.hasText(file.getStoragePath())) {
            return null;
        }
        Path storedPath = Path.of(file.getStoragePath());
        if (!Files.isReadable(storedPath) || Files.isDirectory(storedPath)) {
            log.warn("Skipping unreadable project library file {} at {}", file.getId(), storedPath);
            return null;
        }
        try (InputStream inputStream = Files.newInputStream(storedPath)) {
            return extractFileText(file.getOriginalName(), file.getContentType(), inputStream);
        } catch (IOException ex) {
            log.warn("Skipping project library file {} because stored content could not be read", file.getId(), ex);
            return null;
        }
    }

    private String extractFileText(String fileName, String contentType, InputStream inputStream) throws IOException {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        byte[] bytes = inputStream.readAllBytes();

        if (isTextLikeFile(lowerName, lowerType)) {
            return truncateText(new String(bytes, StandardCharsets.UTF_8).trim());
        }
        if (lowerName.endsWith(".pdf") || lowerType.contains("pdf")) {
            try (PDDocument document = PDDocument.load(bytes)) {
                return truncateText(new PDFTextStripper().getText(document).trim());
            }
        }
        if (lowerName.endsWith(".docx")
                || lowerName.endsWith(".xlsx")
                || lowerName.endsWith(".xlsm")
                || lowerType.contains("wordprocessingml")
                || lowerType.contains("spreadsheetml")) {
            return truncateText(extractOfficeText(bytes, lowerName));
        }
        return "Binary file uploaded. Only metadata was attached for this file in the current copilot implementation.";
    }

    private boolean isTextLikeFile(String fileName, String contentType) {
        return contentType.startsWith("text/")
                || contentType.contains("json")
                || contentType.contains("xml")
                || contentType.contains("csv")
                || fileName.endsWith(".txt")
                || fileName.endsWith(".csv")
                || fileName.endsWith(".json")
                || fileName.endsWith(".md")
                || fileName.endsWith(".log")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".yaml");
    }

    private String extractOfficeText(byte[] bytes, String fileName) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            if (fileName.endsWith(".docx")) {
                try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(input);
                     org.apache.poi.xwpf.extractor.XWPFWordExtractor extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
                    return extractor.getText();
                }
            }
            try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
                DataFormatter formatter = new DataFormatter();
                StringBuilder builder = new StringBuilder();
                for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                    XSSFSheet sheet = workbook.getSheetAt(s);
                    builder.append("Sheet: ").append(sheet.getSheetName()).append("\n");
                    sheet.forEach(row -> {
                        row.forEach(cell -> builder.append(formatter.formatCellValue(cell)).append('\t'));
                        builder.append('\n');
                    });
                }
                return builder.toString();
            }
        } catch (IOException ex) {
            return "Failed to extract Office text: " + ex.getMessage();
        }
    }

    private String truncateText(String text) {
        if (!StringUtils.hasText(text)) {
            return "No extractable text content found.";
        }
        return text.length() > FILE_TEXT_LIMIT
                ? text.substring(0, FILE_TEXT_LIMIT) + "\n...[truncated]"
                : text;
    }

    private boolean hasIndexableProjectFileText(String extractedText) {
        return StringUtils.hasText(extractedText)
                && !"No extractable text content found.".equals(extractedText)
                && !extractedText.startsWith("Failed to extract Office text:")
                && !extractedText.startsWith("Binary file uploaded.");
    }

    private String buildSystemPrompt(Map<String, Object> context) {
        return """
                You are MODEM IQ AI Copilot for a commissioning and construction data platform.

                You answer using the synced workspace database context, project file library context, uploaded file context, and prior conversation.
                Treat the provided JSON as the authoritative snapshot of the visible database scope.
                Be specific. Mention project names, IDs, counts, tags, checklist levels, issue statuses, equipment types, and dates when they are present.
                Dates are critical in this workspace. Prefer actual finish dates first, then updated dates, then created dates, and make that distinction explicit.
                Use the dateIndex and entity dateCoverage sections whenever the user asks about dates, timelines, recent changes, completions, delays, or progress windows.
                If the user asks for all data, answer from the full visible database scope in the context first, then note where the context is summarized versus record-level.
                If project library files are enabled, use their extracted text and metadata when relevant and mention the folder or file name in your answer.
                If the answer is not supported by the provided context, say that clearly instead of guessing.
                Keep the answer concise but useful, and format with short paragraphs or flat bullets when that helps.
                If uploaded files are binary and only metadata is available, mention that limitation.

                Workspace context JSON:
                """ + writeJson(context);
    }

    private Map<String, Object> messagePart(String role, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "assistant".equals(role) ? "output_text" : "input_text");
        content.put("text", text);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", List.of(content));
        return message;
    }

    private String extractOutputText(Map<String, Object> responseBody) {
        Object outputText = responseBody.get("output_text");
        if (outputText instanceof String text && StringUtils.hasText(text)) {
            return text;
        }

        Object output = responseBody.get("output");
        if (output instanceof List<?> outputList) {
            StringBuilder builder = new StringBuilder();
            for (Object item : outputList) {
                if (!(item instanceof Map<?, ?> node)) {
                    continue;
                }
                Object content = node.get("content");
                if (!(content instanceof List<?> parts)) {
                    continue;
                }
                for (Object part : parts) {
                    if (!(part instanceof Map<?, ?> partMap)) {
                        continue;
                    }
                    Object text = partMap.get("text");
                    if (text instanceof String value && StringUtils.hasText(value)) {
                        if (builder.length() > 0) {
                            builder.append("\n");
                        }
                        builder.append(value.trim());
                    }
                }
            }
            return builder.toString();
        }

        return "";
    }

    private Map<String, Object> readJsonMap(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse OpenAI response: " + e.getMessage(), e);
        }
    }

    private String extractOpenAiError(HttpStatusCodeException ex) {
        try {
            Map<String, Object> body = readJsonMap(ex.getResponseBodyAsString());
            Object error = body.get("error");
            if (error instanceof Map<?, ?> errorMap) {
                Object message = errorMap.get("message");
                if (message instanceof String text && StringUtils.hasText(text)) {
                    return text;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to status text below if the body is not JSON.
        }
        return "OpenAI request failed with status " + ex.getStatusCode().value();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize copilot context", e);
        }
    }

    private String normalizeRole(String role) {
        String normalized = safeLower(role);
        if ("assistant".equals(normalized) || "system".equals(normalized)) {
            return normalized;
        }
        return "user";
    }

    private boolean isClosedStatus(String status) {
        String normalized = safeLower(status);
        return normalized.contains("closed")
                || normalized.contains("accepted")
                || normalized.contains("finished")
                || normalized.contains("complete")
                || normalized.contains("done");
    }

    private <T> List<T> filterByProjects(List<T> items,
                                         Function<T, String> projectIdAccessor,
                                         Set<String> activeProjectIds) {
        if (activeProjectIds.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> activeProjectIds.contains(projectIdAccessor.apply(item)))
                .toList();
    }

    private <T> List<Map<String, Object>> topCounts(List<T> items,
                                                    Function<T, String> keyExtractor,
                                                    int limit) {
        return items.stream()
                .map(keyExtractor)
                .filter(StringUtils::hasText)
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()))
                .entrySet()
                .stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(entry -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("label", entry.getKey());
                    value.put("count", entry.getValue());
                    return value;
                })
                .toList();
    }

    private Map<String, Object> projectPreview(Project project) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("projectId", project.getExternalId());
        preview.put("name", project.getName());
        preview.put("number", project.getNumber());
        preview.put("status", project.getStatus());
        preview.put("phase", project.getPhase());
        preview.put("location", project.getLocation());
        preview.put("client", project.getClient());
        return preview;
    }

    private Map<String, Object> issuePreview(Issue issue) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("issueId", issue.getExternalId());
        preview.put("projectId", issue.getProjectId());
        preview.put("title", issue.getTitle());
        preview.put("status", issue.getStatus());
        preview.put("priority", issue.getPriority());
        preview.put("assignee", issue.getAssignee());
        preview.put("location", issue.getLocation());
        preview.put("assetId", issue.getAssetId());
        preview.put("actualFinishDate", issue.getActualFinishDate());
        preview.put("createdAt", issue.getCreatedAt());
        preview.put("updatedAt", issue.getUpdatedAt());
        return preview;
    }

    private Map<String, Object> checklistPreview(Checklist checklist) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("checklistId", checklist.getExternalId());
        preview.put("projectId", checklist.getProjectId());
        preview.put("name", checklist.getName());
        preview.put("status", checklist.getStatus());
        preview.put("checklistType", checklist.getChecklistType());
        preview.put("tagLevel", checklist.getTagLevel());
        preview.put("assetId", checklist.getAssetId());
        preview.put("actualFinishDate", checklist.getActualFinishDate());
        preview.put("createdAt", checklist.getCreatedAt());
        preview.put("updatedAt", checklist.getUpdatedAt());
        return preview;
    }

    private Map<String, Object> equipmentPreview(Equipment equipment) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("equipmentId", equipment.getExternalId());
        preview.put("projectId", equipment.getProjectId());
        preview.put("name", equipment.getName());
        preview.put("tag", equipment.getTag());
        preview.put("status", equipment.getStatus());
        preview.put("equipmentType", equipment.getEquipmentType());
        preview.put("systemName", equipment.getSystemName());
        preview.put("discipline", equipment.getDiscipline());
        preview.put("tests", equipment.getTestCount());
        preview.put("createdAt", equipment.getCreatedAt());
        preview.put("updatedAt", equipment.getUpdatedAt());
        return preview;
    }

    private Map<String, Object> taskPreview(CxTask task) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("taskId", task.getExternalId());
        preview.put("projectId", task.getProjectId());
        preview.put("title", task.getTitle());
        preview.put("status", task.getStatus());
        preview.put("priority", task.getPriority());
        preview.put("assignedTo", task.getAssignedTo());
        preview.put("issueId", task.getIssueId());
        preview.put("actualFinishDate", task.getActualFinishDate());
        preview.put("createdAt", task.getCreatedAt());
        preview.put("updatedAt", task.getUpdatedAt());
        return preview;
    }

    private Map<String, Object> personPreview(Person person) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("personId", person.getExternalId());
        preview.put("projectId", person.getProjectId());
        preview.put("name", joinFields(person.getFirstName(), person.getLastName()).trim());
        preview.put("email", person.getEmail());
        preview.put("company", person.getCompany());
        preview.put("role", person.getRole());
        preview.put("status", person.getStatus());
        return preview;
    }

    private Map<String, Object> companyPreview(Company company) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("companyId", company.getExternalId());
        preview.put("projectId", company.getProjectId());
        preview.put("name", company.getName());
        preview.put("abbreviation", company.getAbbreviation());
        preview.put("address", company.getAddress());
        return preview;
    }

    private Map<String, Object> rolePreview(Role role) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("roleId", role.getExternalId());
        preview.put("projectId", role.getProjectId());
        preview.put("name", role.getName());
        preview.put("abbreviation", role.getAbbreviation());
        preview.put("description", role.getDescription());
        return preview;
    }

    private Map<String, Object> filePreview(ProjectedFile file) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("fileId", file.getExternalId());
        preview.put("projectId", file.getProjectId());
        preview.put("name", file.getName());
        preview.put("mimeType", file.getMimeType());
        preview.put("sizeBytes", file.getFileSize());
        preview.put("assetType", file.getAssetType());
        preview.put("assetId", file.getAssetId());
        preview.put("createdAt", file.getCreatedDate());
        return preview;
    }

    private Map<String, Object> managedFilePreview(ProjectManagedFile file, String extractedText) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("fileId", file.getId());
        preview.put("projectId", file.getProjectId());
        preview.put("folderId", file.getFolder() == null ? null : file.getFolder().getId());
        preview.put("folderName", file.getFolder() == null ? null : file.getFolder().getName());
        preview.put("name", file.getOriginalName());
        preview.put("contentType", file.getContentType());
        preview.put("sizeBytes", file.getSizeBytes());
        preview.put("status", file.getStatus() == null ? null : file.getStatus().getApiValue());
        preview.put("createdAt", formatDateTime(file.getUploadedAt()));
        preview.put("updatedAt", formatDateTime(file.getUpdatedAt()));
        preview.put("extractedText", extractedText);
        return preview;
    }

    private Map<String, Object> briefPreview(TrackerBrief brief) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("briefId", brief.getId());
        preview.put("projectId", brief.getProjectId());
        preview.put("title", brief.getTitle());
        preview.put("subtitle", brief.getSubtitle());
        preview.put("period", brief.getPeriod());
        preview.put("items", brief.getItems());
        preview.put("issues", brief.getIssues());
        preview.put("exportedAt", brief.getExportedAt());
        return preview;
    }

    private List<Map<String, Object>> buildRecentActualFinishRecords(List<Issue> issues,
                                                                     List<Checklist> checklists,
                                                                     List<CxTask> tasks,
                                                                     List<Map<String, Object>> tests) {
        List<Map<String, Object>> rows = new ArrayList<>();
        issues.stream()
                .map(this::issuePreview)
                .filter(row -> StringUtils.hasText(stringValue(row.get("actualFinishDate"))))
                .forEach(row -> rows.add(withEntityType("issue", row)));
        checklists.stream()
                .map(this::checklistPreview)
                .filter(row -> StringUtils.hasText(stringValue(row.get("actualFinishDate"))))
                .forEach(row -> rows.add(withEntityType("checklist", row)));
        tasks.stream()
                .map(this::taskPreview)
                .filter(row -> StringUtils.hasText(stringValue(row.get("actualFinishDate"))))
                .forEach(row -> rows.add(withEntityType("task", row)));
        tests.stream()
                .filter(row -> StringUtils.hasText(stringValue(row.get("actualFinishDate"))))
                .forEach(row -> rows.add(withEntityType("test", new LinkedHashMap<>(row))));

        return rows.stream()
                .sorted(Comparator.comparing((Map<String, Object> row) ->
                        sortDateKey(stringValue(row.get("actualFinishDate")), stringValue(row.get("updatedAt")), stringValue(row.get("createdAt")))).reversed())
                .limit(DATE_RECORD_LIMIT)
                .toList();
    }

    private List<Map<String, Object>> buildRecentUpdatedRecords(List<Issue> issues,
                                                                List<Checklist> checklists,
                                                                List<Equipment> equipment,
                                                                List<CxTask> tasks,
                                                                List<Map<String, Object>> tests) {
        List<Map<String, Object>> rows = new ArrayList<>();
        issues.stream().map(this::issuePreview).forEach(row -> rows.add(withEntityType("issue", row)));
        checklists.stream().map(this::checklistPreview).forEach(row -> rows.add(withEntityType("checklist", row)));
        equipment.stream().map(this::equipmentPreview).forEach(row -> rows.add(withEntityType("equipment", row)));
        tasks.stream().map(this::taskPreview).forEach(row -> rows.add(withEntityType("task", row)));
        tests.forEach(row -> rows.add(withEntityType("test", new LinkedHashMap<>(row))));

        return rows.stream()
                .filter(row -> StringUtils.hasText(stringValue(row.get("updatedAt"))))
                .sorted(Comparator.comparing((Map<String, Object> row) ->
                        sortDateKey(stringValue(row.get("updatedAt")), stringValue(row.get("createdAt")), stringValue(row.get("actualFinishDate")))).reversed())
                .limit(DATE_RECORD_LIMIT)
                .toList();
    }

    private List<Map<String, Object>> buildRecentCreatedRecords(List<Issue> issues,
                                                                List<Checklist> checklists,
                                                                List<Equipment> equipment,
                                                                List<CxTask> tasks,
                                                                List<Map<String, Object>> tests,
                                                                List<ProjectedFile> files,
                                                                List<TrackerBrief> briefs,
                                                                List<ProjectManagedFile> managedFiles) {
        List<Map<String, Object>> rows = new ArrayList<>();
        issues.stream().map(this::issuePreview).forEach(row -> rows.add(withEntityType("issue", row)));
        checklists.stream().map(this::checklistPreview).forEach(row -> rows.add(withEntityType("checklist", row)));
        equipment.stream().map(this::equipmentPreview).forEach(row -> rows.add(withEntityType("equipment", row)));
        tasks.stream().map(this::taskPreview).forEach(row -> rows.add(withEntityType("task", row)));
        tests.forEach(row -> rows.add(withEntityType("test", new LinkedHashMap<>(row))));
        files.stream().map(this::filePreview).forEach(row -> rows.add(withEntityType("file", row)));
        managedFiles.stream().map(file -> managedFilePreview(file, null)).forEach(row -> rows.add(withEntityType("projectLibraryFile", row)));
        briefs.stream().map(this::briefPreview).forEach(row -> rows.add(withEntityType("brief", row)));

        return rows.stream()
                .filter(row -> StringUtils.hasText(stringValue(row.get("createdAt"))) || StringUtils.hasText(stringValue(row.get("exportedAt"))))
                .sorted(Comparator.comparing((Map<String, Object> row) ->
                        sortDateKey(stringValue(row.get("createdAt")), stringValue(row.get("exportedAt")), stringValue(row.get("updatedAt")))).reversed())
                .limit(DATE_RECORD_LIMIT)
                .toList();
    }

    private List<Map<String, Object>> buildManagedFileContext(List<ProjectManagedFile> managedFiles) {
        List<Map<String, Object>> indexedFiles = new ArrayList<>();
        managedFiles.stream()
                .sorted(Comparator.comparing(ProjectManagedFile::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(file -> {
                    if (indexedFiles.size() >= CONTEXT_PREVIEW_LIMIT) {
                        return;
                    }
                    String extractedText = extractStoredFileText(file);
                    if (hasIndexableProjectFileText(extractedText)) {
                        indexedFiles.add(managedFilePreview(file, extractedText));
                    }
                });
        return indexedFiles;
    }

    private Map<String, Object> withEntityType(String entityType, Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("entityType", entityType);
        return result;
    }

    private String joinFields(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        return java.util.Arrays.stream(values)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" "));
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private List<Map<String, Object>> extractTests(List<Equipment> equipmentList) {
        List<Map<String, Object>> tests = new ArrayList<>();
        for (Equipment equipment : equipmentList) {
            String rawJson = equipment.getRawJson();
            if (!StringUtils.hasText(rawJson)) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(rawJson);
                JsonNode testNodes = root.get("tests");
                if (testNodes == null || !testNodes.isArray()) {
                    continue;
                }
                for (JsonNode test : testNodes) {
                    Map<String, Object> preview = new LinkedHashMap<>();
                    preview.put("testId", firstNonBlank(
                            getText(test, "test_id"),
                            getText(test, "id"),
                            getText(test, "_id")
                    ));
                    preview.put("projectId", equipment.getProjectId());
                    preview.put("equipmentId", equipment.getExternalId());
                    preview.put("equipmentName", firstNonBlank(equipment.getName(), equipment.getTag(), equipment.getExternalId()));
                    preview.put("name", firstNonBlank(
                            getText(test, "name"),
                            getText(test, "title"),
                            getText(test, "description"),
                            "Unnamed Test"
                    ));
                    preview.put("status", firstNonBlank(getText(test, "status"), getText(test, "state"), "unknown"));
                    preview.put("actualFinishDate", firstNonBlank(
                            getText(test, "actual_finish_date"),
                            getText(test, "completed_date"),
                            getText(test, "date_completed"),
                            getText(test, "closed_at"),
                            getText(test, "finished_at")
                    ));
                    preview.put("createdAt", firstNonBlank(getText(test, "date_created"), getText(test, "created_at")));
                    preview.put("updatedAt", firstNonBlank(getText(test, "last_updated_at"), getText(test, "updated_at"), getText(test, "date_updated")));
                    tests.add(preview);
                }
            } catch (Exception ignored) {
                // Ignore malformed raw payloads and continue with the rest.
            }
        }
        return tests;
    }

    private <T> Map<String, Object> buildDateCoverage(List<T> items,
                                                      Function<T, String> createdAccessor,
                                                      Function<T, String> updatedAccessor,
                                                      Function<T, String> actualFinishAccessor) {
        return Map.of(
                "created", summarizeDates(items.stream().map(createdAccessor).toList()),
                "updated", summarizeDates(items.stream().map(updatedAccessor).toList()),
                "actualFinish", summarizeDates(items.stream().map(actualFinishAccessor).toList())
        );
    }

    private Map<String, Object> buildMapDateCoverage(List<Map<String, Object>> items,
                                                     String createdField,
                                                     String updatedField,
                                                     String actualFinishField) {
        return Map.of(
                "created", summarizeDates(items.stream().map(item -> stringValue(item.get(createdField))).toList()),
                "updated", summarizeDates(items.stream().map(item -> stringValue(item.get(updatedField))).toList()),
                "actualFinish", summarizeDates(items.stream().map(item -> stringValue(item.get(actualFinishField))).toList())
        );
    }

    private Map<String, Object> summarizeDates(List<String> rawDates) {
        List<LocalDateTime> parsed = rawDates.stream()
                .map(this::parseDateTime)
                .filter(value -> value != null)
                .sorted()
                .toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", parsed.size());
        summary.put("earliest", parsed.isEmpty() ? null : parsed.get(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        summary.put("latest", parsed.isEmpty() ? null : parsed.get(parsed.size() - 1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return summary;
    }

    private LocalDateTime parseDateTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                TemporalAccessor parsed = formatter.parseBest(value, LocalDateTime::from, LocalDate::from, OffsetDateTime::from);
                if (parsed instanceof LocalDateTime dateTime) {
                    return dateTime;
                }
                if (parsed instanceof LocalDate date) {
                    return date.atStartOfDay();
                }
                if (parsed instanceof OffsetDateTime offsetDateTime) {
                    return offsetDateTime.toLocalDateTime();
                }
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private String sortDateKey(String... rawValues) {
        for (String raw : rawValues) {
            LocalDateTime parsed = parseDateTime(raw);
            if (parsed != null) {
                return parsed.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
        }
        return "";
    }

    private List<Map<String, Object>> topMapCounts(List<Map<String, Object>> items,
                                                   Function<Map<String, Object>, String> keyExtractor,
                                                   int limit) {
        return items.stream()
                .map(keyExtractor)
                .filter(StringUtils::hasText)
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()))
                .entrySet()
                .stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(entry -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("label", entry.getKey());
                    value.put("count", entry.getValue());
                    return value;
                })
                .toList();
    }

    private String getText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String text = node.get(field).asText();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
