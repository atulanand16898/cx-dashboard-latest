package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.model.TrackerBrief;
import com.cxalloy.integration.service.TrackerBriefService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Tracker Briefs.
 *
 *   GET  /api/briefs?projectId=X[&period=W]  → list saved briefs
 *   GET  /api/briefs/count?projectId=X        → saved-report count
 *   POST /api/briefs                           → save after CSV export
 *   POST /api/briefs/generate?projectId=X[&period=W]  → auto-snapshot from DB
 */
@RestController
@RequestMapping("/api/briefs")
public class TrackerBriefController {

    private final TrackerBriefService briefService;

    public TrackerBriefController(TrackerBriefService briefService) {
        this.briefService = briefService;
    }

    /** GET /api/briefs?projectId=X[&period=W] */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TrackerBrief>>> getByProject(
            @RequestParam String projectId,
            @RequestParam(required = false) String period) {
        try {
            List<TrackerBrief> briefs = briefService.getBriefsForProject(projectId, period);
            return ResponseEntity.ok(ApiResponse.success(briefs, briefs.size()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to fetch briefs: " + e.getMessage()));
        }
    }

    /** GET /api/briefs/count?projectId=X */
    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> count(@RequestParam String projectId) {
        try {
            long c = briefService.countForProject(projectId);
            return ResponseEntity.ok(ApiResponse.success(c));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to count briefs: " + e.getMessage()));
        }
    }

    /** POST /api/briefs — manual save after CSV export */
    @PostMapping
    public ResponseEntity<ApiResponse<TrackerBrief>> create(@RequestBody Map<String, Object> body) {
        try {
            String  projectId = String.valueOf(body.getOrDefault("projectId", ""));
            String  title     = String.valueOf(body.getOrDefault("title",     ""));
            String  subtitle  = String.valueOf(body.getOrDefault("subtitle",  ""));
            Integer items     = body.get("items")  != null ? ((Number) body.get("items")).intValue()  : 0;
            Integer issues    = body.get("issues") != null ? ((Number) body.get("issues")).intValue() : 0;
            String  period    = String.valueOf(body.getOrDefault("period", "Overall"));

            if (projectId.isBlank()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("projectId is required"));
            }
            TrackerBrief saved = briefService.create(projectId, title, subtitle, items, issues, period);
            return ResponseEntity.ok(ApiResponse.success(saved, "Brief saved"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to save brief: " + e.getMessage()));
        }
    }

    /**
     * POST /api/briefs/generate?projectId=X[&period=W]
     * Reads checklist + issue counts from the local DB and persists a
     * TrackerBrief snapshot.  No CSV download needed — this is the
     * "Generate Snapshot" button flow.
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<TrackerBrief>> generate(
            @RequestParam String projectId,
            @RequestParam(required = false, defaultValue = "Overall") String period) {
        try {
            if (projectId == null || projectId.isBlank()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("projectId is required"));
            }
            TrackerBrief snapshot = briefService.generateSnapshot(projectId, period);
            return ResponseEntity.ok(ApiResponse.success(snapshot, "Snapshot generated"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to generate snapshot: " + e.getMessage()));
        }
    }
}
