package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.service.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * FileStorageController
 *
 * All data is served from the local DB (cxalloy_files table), backed by
 * Spring Cache (Caffeine L1) with 10-minute TTL.
 *
 * To refresh data from CxAlloy:  POST /api/files/sync?projectId=X
 * To force re-analyze from DB:   POST /api/files/analyze?projectId=X
 */
@RestController
@RequestMapping("/api/files")
public class FileStorageController {

    private static final Logger log = LoggerFactory.getLogger(FileStorageController.class);

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public FileStorageController(FileStorageService fileStorageService, ObjectMapper objectMapper) {
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
    }

    /** POST /api/files/sync — sync from CxAlloy → DB, then return report */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sync(
            @RequestParam(required = false) String projectId) {
        log.info("POST /api/files/sync projectId={}", projectId);
        try {
            SyncResult syncResult = fileStorageService.syncFiles(projectId);
            Map<String, Object> report = fileStorageService.analyzeFiles(projectId);
            report.put("syncResult", Map.of(
                    "totalSynced", syncResult.getRecordsSynced(),
                    "durationMs", syncResult.getDurationMs()
            ));
            return ResponseEntity.ok(ApiResponse.success(report,
                    "Synced " + syncResult.getRecordsSynced() + " files and analyzed"));
        } catch (Exception e) {
            log.error("File sync failed: {}", e.getMessage(), e);
            return ResponseEntity.status(502).body(ApiResponse.error("File sync failed: " + e.getMessage()));
        }
    }

    /** POST /api/files/analyze — evict cache, re-analyze from DB (no CxAlloy call) */
    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyze(
            @RequestParam(required = false) String projectId) {
        log.info("POST /api/files/analyze projectId={}", projectId);
        try {
            fileStorageService.evictCache(projectId);
            Map<String, Object> report = fileStorageService.analyzeFiles(projectId);
            return ResponseEntity.ok(ApiResponse.success(report,
                    "Analysis complete for " + report.get("totalFiles") + " files"));
        } catch (Exception e) {
            log.error("File analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.status(502).body(ApiResponse.error("File analysis failed: " + e.getMessage()));
        }
    }

    /** GET /api/files/report — served from DB + Spring cache (fast) */
    @GetMapping("/report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReport(
            @RequestParam(required = false) String projectId) {
        log.info("GET /api/files/report projectId={}", projectId);
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    fileStorageService.getReport(projectId), "File storage report"));
        } catch (Exception e) {
            log.error("File report failed: {}", e.getMessage(), e);
            return ResponseEntity.status(502).body(ApiResponse.error("File report failed: " + e.getMessage()));
        }
    }

    /** GET /api/files/duplicates */
    @GetMapping("/duplicates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDuplicates(
            @RequestParam(required = false) String projectId) {
        log.info("GET /api/files/duplicates projectId={}", projectId);
        try {
            List<Map<String, Object>> dups = fileStorageService.getDuplicates(projectId);
            return ResponseEntity.ok(ApiResponse.success(dups, dups.size() + " duplicate groups"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error("Duplicate check failed: " + e.getMessage()));
        }
    }

    /** GET /api/files/largest */
    @GetMapping("/largest")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLargest(
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "10") int limit) {
        log.info("GET /api/files/largest projectId={} limit={}", projectId, limit);
        try {
            List<Map<String, Object>> files = fileStorageService.getLargestFiles(projectId, limit);
            return ResponseEntity.ok(ApiResponse.success(files, "Top " + files.size() + " largest files"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error("Largest files query failed: " + e.getMessage()));
        }
    }

    /** GET /api/files/heaviest-assets */
    @GetMapping("/heaviest-assets")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getHeaviestAssets(
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "5") int limit) {
        log.info("GET /api/files/heaviest-assets projectId={} limit={}", projectId, limit);
        try {
            List<Map<String, Object>> assets = fileStorageService.getHeaviestAssets(projectId, limit);
            return ResponseEntity.ok(ApiResponse.success(assets, "Top " + assets.size() + " assets by storage"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error("Heaviest assets failed: " + e.getMessage()));
        }
    }

    /** GET /api/files/export/json */
    @GetMapping("/export/json")
    public ResponseEntity<byte[]> exportJson(
            @RequestParam(required = false) String projectId) {
        try {
            Map<String, Object> report = fileStorageService.getReport(projectId);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            String fn = "cxalloy-file-analysis" + (projectId != null ? "-" + projectId : "") + ".json";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fn + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(("Export failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** GET /api/files/export/pdf (returns JSON until PDF library is added) */
    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam(required = false) String projectId) {
        return exportJson(projectId);
    }
}
