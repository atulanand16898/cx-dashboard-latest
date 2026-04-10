package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Checklist;
import com.cxalloy.integration.model.ChecklistStatusDate;
import com.cxalloy.integration.service.ChecklistService;
import com.cxalloy.integration.service.ChecklistStatusDateService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/checklists")
public class ChecklistController {
    private final ChecklistService checklistService;
    private final ChecklistStatusDateService checklistStatusDateService;

    public ChecklistController(
            ChecklistService checklistService,
            ChecklistStatusDateService checklistStatusDateService) {
        this.checklistService = checklistService;
        this.checklistStatusDateService = checklistStatusDateService;
    }

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
        catch (Exception e) { return syncErrorResponse(e, "Checklist sync failed"); }
    }

    @PostMapping("/{externalId}/status-dates/sync")
    public ResponseEntity<ApiResponse<SyncResult>> syncStatusDates(
            @PathVariable String externalId,
            @RequestParam String projectId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    checklistStatusDateService.syncOne(projectId, externalId),
                    "Checklist status dates synced"));
        } catch (Exception e) {
            return syncErrorResponse(e, "Checklist status-date sync failed");
        }
    }

    @PostMapping("/status-dates/sync")
    public ResponseEntity<ApiResponse<SyncResult>> syncProjectStatusDates(@RequestParam String projectId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    checklistStatusDateService.syncProject(projectId),
                    "Project checklist status dates synced"));
        } catch (Exception e) {
            return syncErrorResponse(e, "Project checklist status-date sync failed");
        }
    }

    @GetMapping("/{externalId}/status-dates")
    public ResponseEntity<ApiResponse<ChecklistStatusDate>> getStatusDates(
            @PathVariable String externalId,
            @RequestParam String projectId) {
        return checklistStatusDateService.getOne(projectId, externalId)
                .map(row -> ResponseEntity.ok(ApiResponse.success(row)))
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<ApiResponse<SyncResult>> syncErrorResponse(Exception e, String fallbackMessage) {
        String message = (e.getMessage() == null || e.getMessage().isBlank()) ? fallbackMessage : e.getMessage();
        HttpStatus status = isTimeoutLike(message) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(ApiResponse.error(message));
    }

    private boolean isTimeoutLike(String message) {
        String lower = message.toLowerCase();
        return lower.contains("timed out")
                || lower.contains("timeout")
                || lower.contains("could not reach")
                || lower.contains("connection refused")
                || lower.contains("deadline");
    }
}
