package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.CxTask;
import com.cxalloy.integration.service.CxTaskService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final CxTaskService taskService;
    public TaskController(CxTaskService taskService) { this.taskService = taskService; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CxTask>>> getAll(
            @RequestParam(required=false) String projectId,
            @RequestParam(required=false) String issueId) {
        List<CxTask> list;
        if (issueId != null) list = taskService.getByIssue(issueId);
        else if (projectId != null) list = taskService.getByProject(projectId);
        else list = taskService.getAll();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).mustRevalidate())
                .body(ApiResponse.success(list, list.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CxTask>> getById(@PathVariable Long id) {
        return taskService.getById(id).map(t -> ResponseEntity.ok(ApiResponse.success(t)))
               .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResult>> sync(@RequestParam(required=false) String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(taskService.syncTasks(projectId), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }
}
