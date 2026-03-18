package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Checklist;
import com.cxalloy.integration.service.ChecklistService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/checklists")
public class ChecklistController {
    private final ChecklistService checklistService;
    public ChecklistController(ChecklistService checklistService) { this.checklistService = checklistService; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Checklist>>> getAll(@RequestParam(required=false) String projectId) {
        List<Checklist> list = projectId != null ? checklistService.getByProject(projectId) : checklistService.getAll();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).mustRevalidate())
                .body(ApiResponse.success(list, list.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Checklist>> getById(@PathVariable Long id) {
        return checklistService.getById(id).map(c -> ResponseEntity.ok(ApiResponse.success(c)))
               .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResult>> sync(@RequestParam(required=false) String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(checklistService.syncChecklists(projectId), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }
}
