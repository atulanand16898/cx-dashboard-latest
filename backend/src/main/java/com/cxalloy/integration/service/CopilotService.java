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
import com.cxalloy.integration.repository.ProjectRepository;
import com.cxalloy.integration.repository.RoleRepository;
import com.cxalloy.integration.repository.TrackerBriefRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private static final int SEARCH_MATCH_LIMIT = 8;
    private static final int FILE_TEXT_LIMIT = 12000;
    private static final int HISTORY_LIMIT = 12;
    private static final Set<String> STOP_WORDS = Set.of(
            "what", "which", "where", "when", "with", "from", "that", "this", "into",
            "about", "there", "their", "project", "projects", "please", "show", "give",
            "have", "been", "were", "does", "your", "ours", "than", "then", "them",
            "open", "closed", "data", "the", "and", "for", "are", "but", "not", "you"
    );

    private final ProjectRepository projectRepository;
    private final IssueRepository issueRepository;
    private final ChecklistRepository checklistRepository;
    private final EquipmentRepository equipmentRepository;
    private final CxTaskRepository taskRepository;
    private final PersonRepository personRepository;
    private final CompanyRepository companyRepository;
    private final RoleRepository roleRepository;
    private final FileRecordRepository fileRecordRepository;
    private final TrackerBriefRepository trackerBriefRepository;
    private final ProjectAccessService projectAccessService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${copilot.default-model:gpt-4o-mini}")
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
        this.trackerBriefRepository = trackerBriefRepository;
        this.projectAccessService = projectAccessService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getWorkspaceContext(List<String> requestedProjectIds, String query) {
        return buildWorkspaceContext(requestedProjectIds, query, List.of());
    }

    public Map<String, Object> chat(CopilotChatRequest request, List<MultipartFile> files) {
        if (!StringUtils.hasText(request.getApiKey())) {
            throw new IllegalArgumentException("OpenAI API key is required");
        }
        if (!StringUtils.hasText(request.getPrompt())) {
            throw new IllegalArgumentException("Prompt is required");
        }

        List<Map<String, Object>> uploadedFiles = extractUploads(files);
        Map<String, Object> context = buildWorkspaceContext(
                request.getProjectIds(),
                request.getPrompt(),
                uploadedFiles
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
        payload.put("model", StringUtils.hasText(request.getModel()) ? request.getModel().trim() : defaultModel);
        payload.put("instructions", buildSystemPrompt(context));
        payload.put("input", input);
        payload.put("max_output_tokens", 900);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(request.getApiKey().trim());

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
        result.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return result;
    }

    private Map<String, Object> buildWorkspaceContext(List<String> requestedProjectIds,
                                                      String query,
                                                      List<Map<String, Object>> uploads) {
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
        List<TrackerBrief> briefs = filterByProjects(trackerBriefRepository.findAll(), TrackerBrief::getProjectId, activeProjectIds);

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("mode", selectedIds.isEmpty() ? "entire_workspace" : "selected_projects");
        scope.put("projectIds", new ArrayList<>(activeProjectIds));
        scope.put("projectNames", projects.stream().map(Project::getName).toList());
        scope.put("projectCount", projects.size());
        scope.put("workspaceProjectCount", allProjects.size());
        scope.put("query", query == null ? "" : query);

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
        totals.put("briefs", briefs.size());
        totals.put("fileBytes", files.stream()
                .map(ProjectedFile::getFileSize)
                .filter(size -> size != null && size > 0)
                .reduce(0L, Long::sum));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("totals", totals);
        result.put("projects", projects.stream().limit(CONTEXT_PREVIEW_LIMIT).map(this::projectPreview).toList());
        result.put("issueOverview", buildIssueOverview(issues));
        result.put("checklistOverview", buildChecklistOverview(checklists));
        result.put("equipmentOverview", buildEquipmentOverview(equipment));
        result.put("taskOverview", buildTaskOverview(tasks));
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
        result.put("briefOverview", Map.of(
                "recentBriefs", briefs.stream()
                        .sorted(Comparator.comparing(TrackerBrief::getExportedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(CONTEXT_PREVIEW_LIMIT)
                        .map(this::briefPreview)
                        .toList()
        ));
        result.put("uploads", uploads);
        result.put("searchMatches", buildSearchMatches(query, projects, issues, checklists, equipment, tasks, persons, companies, roles, files));
        result.put("workspaceCapabilities", List.of(
                "Tracker Pulse",
                "Planned vs Actual",
                "Checklist Flow",
                "Issue Radar",
                "Asset Readiness",
                "Tracker Briefs",
                "AI Copilot"
        ));
        return result;
    }

    private Map<String, Object> buildIssueOverview(List<Issue> issues) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("byStatus", topCounts(issues, Issue::getStatus, 12));
        overview.put("byPriority", topCounts(issues, Issue::getPriority, 8));
        overview.put("byAssignee", topCounts(issues, Issue::getAssignee, 12));
        overview.put("byLocation", topCounts(issues, Issue::getLocation, 12));
        overview.put("recent", issues.stream()
                .sorted(Comparator.comparing(Issue::getUpdatedAt, Comparator.nullsLast(String::compareTo)).reversed())
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
        overview.put("recent", checklists.stream()
                .sorted(Comparator.comparing(Checklist::getUpdatedAt, Comparator.nullsLast(String::compareTo)).reversed())
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
        overview.put("recent", tasks.stream()
                .sorted(Comparator.comparing(CxTask::getUpdatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(CONTEXT_PREVIEW_LIMIT)
                .map(this::taskPreview)
                .toList());
        return overview;
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
                                                   List<ProjectedFile> files) {
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
            preview.put("extractedText", extractFileText(file));
            uploads.add(preview);
        }
        return uploads;
    }

    private String extractFileText(MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
            String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
            boolean textLike = contentType.startsWith("text/")
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

            if (!textLike) {
                return "Binary file uploaded. Only metadata was attached for this file in the current copilot implementation.";
            }

            String text = new String(file.getBytes(), StandardCharsets.UTF_8).trim();
            if (text.length() > FILE_TEXT_LIMIT) {
                return text.substring(0, FILE_TEXT_LIMIT) + "\n...[truncated]";
            }
            return text;
        } catch (IOException ex) {
            return "Failed to read uploaded file: " + ex.getMessage();
        }
    }

    private String buildSystemPrompt(Map<String, Object> context) {
        return """
                You are MODEM IQ AI Copilot for a commissioning and construction data platform.

                You answer using the synced workspace database context, uploaded file context, and prior conversation.
                Be specific. Mention project names, IDs, counts, tags, checklist levels, issue statuses, and equipment types when they are present.
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
}
