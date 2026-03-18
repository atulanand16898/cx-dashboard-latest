package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Asset;
import com.cxalloy.integration.service.AssetService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    private final AssetService assetService;
    public AssetController(AssetService assetService) { this.assetService = assetService; }

    /** GET /api/assets?projectId=X&type=equipment */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Asset>>> getAll(
            @RequestParam(required=false) String projectId,
            @RequestParam(required=false) String type) {
        List<Asset> list;
        if (projectId != null && type != null) list = assetService.getByProjectAndType(projectId, type);
        else if (projectId != null) list = assetService.getByProject(projectId);
        else list = assetService.getAll();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).mustRevalidate())
                .body(ApiResponse.success(list, list.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Asset>> getById(@PathVariable Long id) {
        return assetService.getById(id).map(a -> ResponseEntity.ok(ApiResponse.success(a)))
               .orElse(ResponseEntity.notFound().build());
    }

    /** POST /api/assets/sync?projectId=X — syncs all asset types (equipment,campus,building,floor,space,system) */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<List<SyncResult>>> syncAll(@RequestParam(required=false) String projectId) {
        try {
            List<SyncResult> results = assetService.syncAllAssets(projectId);
            return ResponseEntity.ok(ApiResponse.success(results, results.size()));
        } catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }

    /** POST /api/assets/sync/{type}?projectId=X — syncs one asset type */
    @PostMapping("/sync/{type}")
    public ResponseEntity<ApiResponse<SyncResult>> syncType(@PathVariable String type,
                                                             @RequestParam(required=false) String projectId) {
        try { return ResponseEntity.ok(ApiResponse.success(assetService.syncAssetType(type, projectId), "Synced")); }
        catch (Exception e) { return ResponseEntity.status(502).body(ApiResponse.error(e.getMessage())); }
    }
}
