package com.cxalloy.integration.xerprocessing.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.xerprocessing.dto.XerBaselineAnalysisResponse;
import com.cxalloy.integration.xerprocessing.dto.XerDcmaCheckpointResponse;
import com.cxalloy.integration.xerprocessing.dto.CreateXerProjectRequest;
import com.cxalloy.integration.xerprocessing.dto.XerProgressMeasurementOptionsResponse;
import com.cxalloy.integration.xerprocessing.dto.RegisterXerBaselineRequest;
import com.cxalloy.integration.xerprocessing.dto.SaveXerProgressMeasurementRequest;
import com.cxalloy.integration.xerprocessing.dto.XerWorkflowSummaryResponse;
import com.cxalloy.integration.xerprocessing.model.XerImportSession;
import com.cxalloy.integration.xerprocessing.model.XerProgressMeasurementConfig;
import com.cxalloy.integration.xerprocessing.model.XerProject;
import com.cxalloy.integration.xerprocessing.service.XerProcessingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/xer-processing")
public class XerProcessingController {

    private final XerProcessingService xerProcessingService;

    public XerProcessingController(XerProcessingService xerProcessingService) {
        this.xerProcessingService = xerProcessingService;
    }

    @GetMapping("/projects")
    public ResponseEntity<ApiResponse<List<XerProject>>> listProjects() {
        List<XerProject> projects = xerProcessingService.listProjects();
        return ResponseEntity.ok(ApiResponse.success(projects, projects.size()));
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<ApiResponse<XerProject>> getProject(@PathVariable Long id) {
        return xerProcessingService.getProject(id)
                .map(project -> ResponseEntity.ok(ApiResponse.success(project)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/projects")
    public ResponseEntity<ApiResponse<XerProject>> createProject(@Valid @RequestBody CreateXerProjectRequest request) {
        XerProject project = xerProcessingService.createProject(request);
        return ResponseEntity.ok(ApiResponse.success(project, "XER project created"));
    }

    @PostMapping("/projects/{id}/baseline-imports")
    public ResponseEntity<ApiResponse<XerImportSession>> registerBaselineImport(
            @PathVariable Long id,
            @Valid @RequestBody RegisterXerBaselineRequest request
    ) {
        XerImportSession importSession = xerProcessingService.registerBaselineImport(id, request);
        return ResponseEntity.ok(ApiResponse.success(importSession, "Baseline import registered"));
    }

    @PostMapping(value = "/projects/{id}/baseline-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<XerImportSession>> uploadBaseline(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file
    ) {
        XerImportSession importSession = xerProcessingService.uploadBaseline(id, file);
        return ResponseEntity.ok(ApiResponse.success(importSession, "Baseline upload accepted and processing started"));
    }

    @PostMapping("/projects/{id}/progress-measurement")
    public ResponseEntity<ApiResponse<XerProgressMeasurementConfig>> saveProgressMeasurement(
            @PathVariable Long id,
            @Valid @RequestBody SaveXerProgressMeasurementRequest request
    ) {
        XerProgressMeasurementConfig config = xerProcessingService.saveProgressMeasurement(id, request);
        return ResponseEntity.ok(ApiResponse.success(config, "Progress measurement configuration saved"));
    }

    @GetMapping("/projects/{id}/progress-measurement/options")
    public ResponseEntity<ApiResponse<XerProgressMeasurementOptionsResponse>> getProgressMeasurementOptions(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(xerProcessingService.getProgressMeasurementOptions(id)));
    }

    @GetMapping("/projects/{id}/workflow")
    public ResponseEntity<ApiResponse<XerWorkflowSummaryResponse>> getWorkflow(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(xerProcessingService.getWorkflowSummary(id)));
    }

    @GetMapping("/projects/{id}/baseline-analysis")
    public ResponseEntity<ApiResponse<XerBaselineAnalysisResponse>> getBaselineAnalysis(
            @PathVariable Long id,
            @RequestParam(value = "dataDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataDate
    ) {
        return ResponseEntity.ok(ApiResponse.success(xerProcessingService.getBaselineAnalysis(id, dataDate)));
    }

    @GetMapping("/projects/{id}/dcma-checkpoints")
    public ResponseEntity<ApiResponse<XerDcmaCheckpointResponse>> getDcmaCheckpoints(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(xerProcessingService.getDcmaCheckpoints(id)));
    }
}
