package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SavedReportRequest;
import com.cxalloy.integration.service.SavedReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class SavedReportController {

    private static final Logger logger = LoggerFactory.getLogger(SavedReportController.class);

    private final SavedReportService savedReportService;

    public SavedReportController(SavedReportService savedReportService) {
        this.savedReportService = savedReportService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getReports(@RequestParam String projectId) {
        List<Map<String, Object>> reports = savedReportService.getReports(projectId);
        return ResponseEntity.ok(ApiResponse.success(reports, reports.size()));
    }

    @GetMapping("/options")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOptions(@RequestParam String projectId) {
        return ResponseEntity.ok(ApiResponse.success(savedReportService.getFilterOptions(projectId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReport(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(savedReportService.getReport(id)));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generate(@RequestBody SavedReportRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    savedReportService.generateReport(request),
                    "Report generated and saved"
            ));
        } catch (Exception e) {
            logger.error("Report generation failed for project {}: {}", request.getProjectId(), e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Report generation failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
            ));
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id,
                                           @RequestParam(defaultValue = "json") String format) {
        byte[] bytes = savedReportService.downloadReport(id, format);
        String fileName = savedReportService.downloadFileName(id, format);
        MediaType mediaType = "csv".equalsIgnoreCase(format)
                ? MediaType.parseMediaType("text/csv")
                : "pdf".equalsIgnoreCase(format)
                ? MediaType.APPLICATION_PDF
                : MediaType.APPLICATION_JSON;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(mediaType)
                .body(bytes);
    }
}
