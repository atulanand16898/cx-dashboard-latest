package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.service.ProjectService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService projectService;
    public ProjectController(ProjectService projectService) { this.projectService = projectService; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Project>>> getAll() {
        List<Project> list = projectService.getAllForCurrentUser();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).mustRevalidate())
                .body(ApiResponse.success(list, list.size()));
    }

    /**
     * GET /api/projects/ids
     * Returns a compact list of all projects with just dbId, externalId, and name.
     * Use the externalId values when calling /api/sync/project/{id} or /api/sync/discover
     */
    @GetMapping("/ids")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getAllIds() {
        List<Map<String, String>> ids = new ArrayList<>();
        for (Project p : projectService.getAllForCurrentUser()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("dbId", String.valueOf(p.getId()));
            entry.put("externalId", p.getExternalId());
            entry.put("name", p.getName());
            ids.add(entry);
        }
        return ResponseEntity.ok(ApiResponse.success(ids, ids.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Project>> getById(@PathVariable Long id) {
        return projectService.getById(id).map(p -> ResponseEntity.ok(ApiResponse.success(p)))
               .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/external/{externalId}")
    public ResponseEntity<ApiResponse<Project>> getByExternalId(@PathVariable String externalId) {
        return projectService.getByExternalId(externalId).map(p -> ResponseEntity.ok(ApiResponse.success(p)))
               .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResult>> syncAll() {
        try { return ResponseEntity.ok(ApiResponse.success(projectService.syncAllProjects(), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }

    @PostMapping("/sync/{projectId}")
    public ResponseEntity<ApiResponse<SyncResult>> syncOne(@PathVariable String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(projectService.syncProjectById(projectId), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }
}
