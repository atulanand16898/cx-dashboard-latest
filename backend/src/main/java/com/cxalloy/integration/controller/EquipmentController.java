package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.EquipmentMatrixDto;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Equipment;
import com.cxalloy.integration.service.EquipmentMatrixService;
import com.cxalloy.integration.service.EquipmentService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * EquipmentController
 *
 * GET  /api/equipment                — all equipment (optionally filtered by projectId)
 * GET  /api/equipment/{id}           — single equipment record by DB id
 * GET  /api/equipment/matrix         — Equipment-Checklist Matrix (cross-reference view)
 * POST /api/equipment/sync           — sync from CxAlloy GET /equipment for a project
 *
 * Example Postman calls:
 *   GET  http://localhost:8080/api/equipment?projectId=47168
 *   GET  http://localhost:8080/api/equipment/matrix?projectId=47168
 *   POST http://localhost:8080/api/equipment/sync?projectId=47168
 */
@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final EquipmentService equipmentService;
    private final EquipmentMatrixService equipmentMatrixService;

    public EquipmentController(EquipmentService equipmentService,
                                EquipmentMatrixService equipmentMatrixService) {
        this.equipmentService = equipmentService;
        this.equipmentMatrixService = equipmentMatrixService;
    }

    /**
     * GET /api/equipment
     * Optional ?projectId=X to scope to one project.
     * Optional ?type=X to further filter by equipment type name.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Equipment>>> getAll(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String type) {
        List<Equipment> list;
        if (projectId != null && type != null) {
            list = equipmentService.getByProjectAndType(projectId, type);
        } else if (projectId != null) {
            list = equipmentService.getByProject(projectId);
        } else {
            list = equipmentService.getAll();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).mustRevalidate())
                .body(ApiResponse.success(list, list.size()));
    }

    /** GET /api/equipment/{id} — fetch single record by DB primary key */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Equipment>> getById(@PathVariable Long id) {
        return equipmentService.getById(id)
                .map(e -> ResponseEntity.ok(ApiResponse.success(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/equipment/live?projectId=X
     * Fetches equipment directly from CxAlloy API (no DB write, no cache).
     * Used by the Asset Readiness page when no synced data exists yet.
     */
    @GetMapping("/live")
    public ResponseEntity<ApiResponse<List<Equipment>>> getLive(
            @RequestParam(required = false) String projectId) {
        try {
            List<Equipment> list = equipmentService.fetchLive(projectId);
            return ResponseEntity.ok(ApiResponse.success(list, list.size()));
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(ApiResponse.error("Live equipment fetch failed: " + e.getMessage()));
        }
    }

     /* Syncs CxAlloy GET /equipment (paginated) into the local DB.
     * Returns a SyncResult with record count and duration.
     */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<SyncResult>> sync(
            @RequestParam(required = false) String projectId) {
        try {
            SyncResult result = equipmentService.syncEquipment(projectId);
            return ResponseEntity.ok(ApiResponse.success(result,
                "Equipment sync complete: " + result.getRecordsSynced() + " records"));
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(ApiResponse.error("Equipment sync failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/equipment/sync-types?projectId=X
     *
     * Syncs CxAlloy GET /equipmenttype to build a type_id → type_name lookup,
     * then back-fills equipment.equipmentType for all rows that have a numeric
     * type value instead of a human-readable name (e.g. "712338" → "BMS Panels").
     *
     * Call this once after the initial equipment sync if type names are missing.
     */
    @PostMapping("/sync-types")
    public ResponseEntity<ApiResponse<SyncResult>> syncTypes(
            @RequestParam(required = false) String projectId) {
        try {
            SyncResult result = equipmentService.syncEquipmentTypes(projectId);
            return ResponseEntity.ok(ApiResponse.success(result, "Equipment types synced"));
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(ApiResponse.error("Equipment type sync failed: " + e.getMessage()));
        }
    }
     /*
     * Returns the Equipment-Checklist Matrix:
     * each row = one equipment item, each column = a tag level (red/yellow/green/L2/L3).
     * Used by the Asset Readiness page matrix section.
     */
    @GetMapping("/matrix")
    public ResponseEntity<ApiResponse<EquipmentMatrixDto>> getMatrix(
            @RequestParam(required = false) String projectId) {
        try {
            EquipmentMatrixDto matrix = equipmentMatrixService.buildMatrix(projectId);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).mustRevalidate())
                    .body(ApiResponse.success(matrix, matrix.getRows().size()));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Matrix build failed: " + e.getMessage()));
        }
    }
}
