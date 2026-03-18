package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.IssueRequest;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Issue;
import com.cxalloy.integration.service.IssueService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/issues")
public class IssueController {
    private static final Logger log = LoggerFactory.getLogger(IssueController.class);
    private final IssueService issueService;
    public IssueController(IssueService issueService) { this.issueService = issueService; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Issue>>> getAll(@RequestParam(required=false) String projectId) {
        List<Issue> list = projectId != null ? issueService.getByProject(projectId) : issueService.getAll();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).mustRevalidate())
                .body(ApiResponse.success(list, list.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Issue>> getById(@PathVariable Long id) {
        return issueService.getById(id).map(i -> ResponseEntity.ok(ApiResponse.success(i)))
               .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/external/{externalId}")
    public ResponseEntity<ApiResponse<Issue>> getByExternalId(@PathVariable String externalId) {
        return issueService.getByExternalId(externalId).map(i -> ResponseEntity.ok(ApiResponse.success(i)))
               .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SyncResult>> create(@Valid @RequestBody IssueRequest req) {
        try { return ResponseEntity.ok(ApiResponse.success(issueService.createIssue(req), "Issue created")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }

    @PutMapping("/{issueId}")
    public ResponseEntity<ApiResponse<SyncResult>> update(@PathVariable String issueId,
                                                           @Valid @RequestBody IssueRequest req) {
        try { return ResponseEntity.ok(ApiResponse.success(issueService.updateIssue(issueId, req), "Issue updated")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }

    @DeleteMapping("/{issueId}")
    public ResponseEntity<ApiResponse<SyncResult>> delete(@PathVariable String issueId,
                                                           @RequestParam(required=false) String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(issueService.deleteIssue(issueId, projectId), "Issue deleted")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResult>> syncAll(@RequestParam(required=false) String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(issueService.syncAllIssues(projectId), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }

    @PostMapping("/sync/{issueId}")
    public ResponseEntity<ApiResponse<SyncResult>> syncOne(@PathVariable String issueId,
                                                            @RequestParam(required=false) String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(issueService.syncIssueById(issueId, projectId), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }
}
