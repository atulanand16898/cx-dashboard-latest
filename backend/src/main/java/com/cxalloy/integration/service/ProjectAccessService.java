package com.cxalloy.integration.service;

import com.cxalloy.integration.dto.ProjectAccessAssignmentRequest;
import com.cxalloy.integration.dto.ProjectVisibilityRequest;
import com.cxalloy.integration.model.Person;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.model.ProjectAccessAssignment;
import com.cxalloy.integration.model.ProjectVisibilityPreference;
import com.cxalloy.integration.repository.PersonRepository;
import com.cxalloy.integration.repository.ProjectAccessAssignmentRepository;
import com.cxalloy.integration.repository.ProjectRepository;
import com.cxalloy.integration.repository.ProjectVisibilityPreferenceRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProjectAccessService {

    private final ProjectAccessAssignmentRepository assignmentRepository;
    private final ProjectVisibilityPreferenceRepository visibilityRepository;
    private final PersonRepository personRepository;
    private final ProjectRepository projectRepository;

    public ProjectAccessService(ProjectAccessAssignmentRepository assignmentRepository,
                                ProjectVisibilityPreferenceRepository visibilityRepository,
                                PersonRepository personRepository,
                                ProjectRepository projectRepository) {
        this.assignmentRepository = assignmentRepository;
        this.visibilityRepository = visibilityRepository;
        this.personRepository = personRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public boolean isCurrentUserAdmin() {
        return isAdmin(currentUsername());
    }

    @Transactional(readOnly = true)
    public String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(String username) {
        return StringUtils.hasText(username) && "admin".equalsIgnoreCase(username.trim());
    }

    @Transactional(readOnly = true)
    public Set<String> getAccessibleProjectIdsForCurrentUser() {
        return getAccessibleProjectIdsForUser(currentUsername());
    }

    @Transactional(readOnly = true)
    public List<Project> filterProjectsForCurrentUser(List<Project> projects) {
        Set<String> allowed = getAccessibleProjectIdsForCurrentUser();
        return projects.stream()
                .filter(project -> allowed.contains(project.getExternalId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public void requireProjectAccess(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return;
        }
        Set<String> allowed = getAccessibleProjectIdsForCurrentUser();
        if (allowed.contains(projectId.trim())) {
            return;
        }
        throw new IllegalStateException("You do not have access to project " + projectId);
    }

    @Transactional(readOnly = true)
    public void requireAdmin() {
        if (!isCurrentUserAdmin()) {
            throw new IllegalStateException("Admin access is required for project authorization");
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAssignments() {
        requireAdmin();
        Map<String, Project> projectMap = projectRepository.findAll().stream()
                .filter(project -> StringUtils.hasText(project.getExternalId()))
                .collect(Collectors.toMap(Project::getExternalId, project -> project, (left, right) -> left, LinkedHashMap::new));

        return assignmentRepository.findAllByOrderByProjectIdAscPersonNameAsc().stream()
                .map(assignment -> {
                    Project project = projectMap.get(assignment.getProjectId());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", assignment.getId());
                    row.put("projectId", assignment.getProjectId());
                    row.put("projectName", project != null ? project.getName() : assignment.getProjectId());
                    row.put("personExternalId", assignment.getPersonExternalId());
                    row.put("personEmail", assignment.getPersonEmail());
                    row.put("personName", assignment.getPersonName());
                    row.put("assignedBy", assignment.getAssignedBy());
                    row.put("assignedAt", assignment.getAssignedAt());
                    return row;
                })
                .toList();
    }

    public Map<String, Object> assign(ProjectAccessAssignmentRequest request) {
        requireAdmin();

        if (!StringUtils.hasText(request.getProjectId())) {
            throw new IllegalArgumentException("projectId is required");
        }
        Project project = projectRepository.findByExternalId(request.getProjectId().trim())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.getProjectId()));

        Person person = resolvePerson(request);
        String email = person != null && StringUtils.hasText(person.getEmail())
                ? person.getEmail().trim().toLowerCase(Locale.ROOT)
                : (request.getPersonEmail() == null ? null : request.getPersonEmail().trim().toLowerCase(Locale.ROOT));
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException("Selected person must have an email");
        }

        if (assignmentRepository.existsByProjectIdAndPersonEmailIgnoreCase(project.getExternalId(), email)) {
            throw new IllegalStateException("This person is already assigned to the project");
        }

        ProjectAccessAssignment assignment = new ProjectAccessAssignment();
        assignment.setProjectId(project.getExternalId());
        assignment.setPersonExternalId(person != null ? person.getExternalId() : request.getPersonExternalId());
        assignment.setPersonEmail(email);
        assignment.setPersonName(resolvePersonName(person, request));
        assignment.setAssignedBy(currentUsername());
        assignmentRepository.save(assignment);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", assignment.getId());
        response.put("projectId", assignment.getProjectId());
        response.put("projectName", project.getName());
        response.put("personExternalId", assignment.getPersonExternalId());
        response.put("personEmail", assignment.getPersonEmail());
        response.put("personName", assignment.getPersonName());
        response.put("assignedBy", assignment.getAssignedBy());
        response.put("assignedAt", assignment.getAssignedAt());
        return response;
    }

    public void remove(Long id) {
        requireAdmin();
        assignmentRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean isAssignedEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return false;
        }
        return assignmentRepository.existsByPersonEmailIgnoreCase(email.trim());
    }

    @Transactional(readOnly = true)
    public Set<String> getAccessibleProjectIdsForUser(String username) {
        if (!StringUtils.hasText(username)) {
            return Set.of();
        }
        if (isAdmin(username)) {
            return resolveAdminVisibleProjectIds(username.trim());
        }
        return assignmentRepository.findByPersonEmailIgnoreCaseOrderByProjectIdAsc(username.trim()).stream()
                .map(ProjectAccessAssignment::getProjectId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional(readOnly = true)
    public List<Project> getProjectCatalog() {
        requireAdmin();
        return projectRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getVisibilitySettings() {
        requireAdmin();
        String username = currentUsername();
        Set<String> selectedProjectIds = getSavedVisibilityProjectIds(username);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", username);
        response.put("selectedProjectIds", selectedProjectIds);
        response.put("isFiltered", !selectedProjectIds.isEmpty());
        response.put("selectedCount", selectedProjectIds.size());
        response.put("totalProjects", projectRepository.count());
        return response;
    }

    public Map<String, Object> saveVisibilitySettings(ProjectVisibilityRequest request) {
        requireAdmin();
        String username = currentUsername();
        String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> requestedIds = normalizeProjectIds(request != null ? request.getProjectIds() : List.of());
        Set<String> knownProjectIds = allProjectIds();

        List<String> invalidProjectIds = requestedIds.stream()
                .filter(projectId -> !knownProjectIds.contains(projectId))
                .toList();
        if (!invalidProjectIds.isEmpty()) {
            throw new IllegalArgumentException("Unknown project IDs: " + String.join(", ", invalidProjectIds));
        }

        visibilityRepository.deleteByUsernameIgnoreCase(normalizedUsername);
        visibilityRepository.flush();
        requestedIds.forEach(projectId -> {
            ProjectVisibilityPreference preference = new ProjectVisibilityPreference();
            preference.setUsername(normalizedUsername);
            preference.setProjectId(projectId);
            preference.setCreatedAt(LocalDateTime.now());
            visibilityRepository.save(preference);
        });
        visibilityRepository.flush();

        return getVisibilitySettings();
    }

    private Person resolvePerson(ProjectAccessAssignmentRequest request) {
        if (StringUtils.hasText(request.getPersonExternalId())) {
            return personRepository.findByExternalId(request.getPersonExternalId().trim()).orElse(null);
        }
        if (StringUtils.hasText(request.getPersonEmail())) {
            return personRepository.findAllByEmailIgnoreCase(request.getPersonEmail().trim()).stream().findFirst().orElse(null);
        }
        return null;
    }

    private String resolvePersonName(Person person, ProjectAccessAssignmentRequest request) {
        if (person != null) {
            String fullName = ((person.getFirstName() == null ? "" : person.getFirstName()) + " " +
                    (person.getLastName() == null ? "" : person.getLastName())).trim();
            if (StringUtils.hasText(fullName)) {
                return fullName;
            }
        }
        return StringUtils.hasText(request.getPersonName()) ? request.getPersonName().trim() : request.getPersonEmail();
    }

    private Set<String> resolveAdminVisibleProjectIds(String username) {
        Set<String> selectedProjectIds = getSavedVisibilityProjectIds(username);
        if (selectedProjectIds.isEmpty()) {
            return allProjectIds();
        }
        Set<String> knownProjectIds = allProjectIds();
        return selectedProjectIds.stream()
                .filter(knownProjectIds::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private LinkedHashSet<String> getSavedVisibilityProjectIds(String username) {
        if (!StringUtils.hasText(username)) {
            return new LinkedHashSet<>();
        }
        return visibilityRepository.findByUsernameIgnoreCaseOrderByProjectIdAsc(username.trim()).stream()
                .map(ProjectVisibilityPreference::getProjectId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private LinkedHashSet<String> allProjectIds() {
        return projectRepository.findAll().stream()
                .map(Project::getExternalId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private LinkedHashSet<String> normalizeProjectIds(List<String> projectIds) {
        if (projectIds == null) {
            return new LinkedHashSet<>();
        }
        return projectIds.stream()
                .filter(StringUtils::hasText)
                .map(projectId -> projectId.trim())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
