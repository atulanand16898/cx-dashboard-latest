package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.ProjectAccessAssignmentRequest;
import com.cxalloy.integration.dto.ProjectVisibilityRequest;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.service.ProjectAccessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/project-access")
public class ProjectAccessController {

    private final ProjectAccessService projectAccessService;

    public ProjectAccessController(ProjectAccessService projectAccessService) {
        this.projectAccessService = projectAccessService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAssignments() {
        List<Map<String, Object>> assignments = projectAccessService.getAssignments();
        return ResponseEntity.ok(ApiResponse.success(assignments, assignments.size()));
    }

    @GetMapping("/projects")
    public ResponseEntity<ApiResponse<List<Project>>> getProjectCatalog() {
        List<Project> projects = projectAccessService.getProjectCatalog();
        return ResponseEntity.ok(ApiResponse.success(projects, projects.size()));
    }

    @GetMapping("/visibility")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getVisibilitySettings() {
        return ResponseEntity.ok(ApiResponse.success(projectAccessService.getVisibilitySettings()));
    }

    @PutMapping("/visibility")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveVisibilitySettings(@RequestBody ProjectVisibilityRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                projectAccessService.saveVisibilitySettings(request),
                "Visible projects updated"
        ));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> assign(@RequestBody ProjectAccessAssignmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                projectAccessService.assign(request),
                "Project access assigned"
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> remove(@PathVariable Long id) {
        projectAccessService.remove(id);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("id", id),
                "Project access removed"
        ));
    }
}
