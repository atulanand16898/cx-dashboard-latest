package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Asset;
import com.cxalloy.integration.repository.AssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

/**
 * Asset endpoints confirmed from official CxAlloy OpenAPI spec (https://api.cxalloy.com/openapi.yaml).
 * All are GET with ?project_id=<id> query parameter.
 *
 * Confirmed endpoints:
 *   GET /campus    - no pagination
 *   GET /building  - paginated
 *   GET /equipment - paginated
 *   GET /floor     - paginated
 *   GET /space     - paginated
 *   GET /system    - paginated
 *   GET /zone      - paginated  (was missing in original code!)
 */
@Service
@Transactional
public class AssetService extends BaseProjectService {

    // All asset endpoints confirmed in CxAlloy OpenAPI spec — all are GET ?project_id=<id>
    // LinkedHashMap to preserve insertion order for predictable sync order
    private static final Map<String, String> ASSET_ENDPOINTS = new LinkedHashMap<>();
    static {
        ASSET_ENDPOINTS.put("campus",    "/campus");
        ASSET_ENDPOINTS.put("building",  "/building");
        ASSET_ENDPOINTS.put("equipment", "/equipment");
        ASSET_ENDPOINTS.put("floor",     "/floor");
        ASSET_ENDPOINTS.put("space",     "/space");
        ASSET_ENDPOINTS.put("system",    "/system");
        ASSET_ENDPOINTS.put("zone",      "/zone");   // Added: present in spec, was missing!
    }

    private final AssetRepository assetRepository;
    private final CxAlloyApiClient apiClient;

    public AssetService(AssetRepository assetRepository, CxAlloyApiClient apiClient) {
        this.assetRepository = assetRepository;
        this.apiClient = apiClient;
    }

    /** Sync one asset type with pagination */
    @Caching(evict = {
        @CacheEvict(value = "assets-by-project", allEntries = true),
        @CacheEvict(value = "assets-all", allEntries = true)
    })
    public SyncResult syncAssetType(String assetType, String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        String endpoint = ASSET_ENDPOINTS.getOrDefault(assetType.toLowerCase(), "/" + assetType);
        ApiSyncLog log = startLog(endpoint, "GET");
        int totalSynced = 0;
        int page = 1;
        try {
            while (true) {
                String url = endpoint + "?project_id=" + pid + (page > 1 ? "&page=" + page : "");
                String raw = apiClient.get(url);
                if (raw == null || raw.isBlank()) break;
                if (raw.trim().startsWith("{") && !raw.contains("\"data\"") &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) {
                    logger.warn("Error response on {} page {} — stopping.", assetType, page);
                    break;
                }
                saveRaw(endpoint + "?page=" + page, assetType + "_list_p" + page, pid, raw);
                int count = parseAndSave(raw, pid, assetType);
                totalSynced += count;
                logger.info("{} page {}: {} records (total: {})", assetType, page, count, totalSynced);
                if (count < 500) break;
                if (++page > 50) { logger.warn("Page cap reached for {} project {}", assetType, pid); break; }
            }
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, dur, null);
            return new SyncResult(endpoint, "SUCCESS", totalSynced,
                "Synced " + totalSynced + " " + assetType + " (" + page + " pages)", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Sync " + assetType + " failed: " + e.getMessage(), e);
        }
    }

    /** Sync all 7 asset types */
    @Caching(evict = {
        @CacheEvict(value = "assets-by-project", allEntries = true),
        @CacheEvict(value = "assets-all", allEntries = true)
    })
    public List<SyncResult> syncAllAssets(String projectId) {
        List<SyncResult> results = new ArrayList<>();
        for (String type : ASSET_ENDPOINTS.keySet()) {
            try {
                results.add(syncAssetType(type, projectId));
            } catch (Exception e) {
                logger.error("Sync asset type {} failed: {}", type, e.getMessage());
                results.add(new SyncResult("/" + type, "FAILED", 0, e.getMessage(), 0));
            }
        }
        return results;
    }

    @Cacheable(value = "assets-all")
    @Transactional(readOnly = true)
    public List<Asset> getAll() { return assetRepository.findAll(); }

    @Cacheable(value = "entity-by-id", key = "\"asset-\" + #id")
    @Transactional(readOnly = true)
    public Optional<Asset> getById(Long id) { return assetRepository.findById(id); }

    @Cacheable(value = "assets-by-project", key = "#projectId")
    @Transactional(readOnly = true)
    public List<Asset> getByProject(String projectId) { return assetRepository.findByProjectId(projectId); }

    @Cacheable(value = "assets-by-project", key = "#projectId + '-' + #assetType")
    @Transactional(readOnly = true)
    public List<Asset> getByProjectAndType(String projectId, String assetType) {
        return assetRepository.findByProjectIdAndAssetType(projectId, assetType);
    }

    private int parseAndSave(String json, String pid, String assetType) throws Exception {
        if (json == null || json.isBlank()) { logger.warn("Empty response for /{} project={}", assetType, pid); return 0; }
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = extractData(root, "/" + assetType + " project=" + pid);
        List<Asset> list = new ArrayList<>();
        if (data.isArray()) { for (JsonNode n : data) list.add(map(n, pid, assetType)); }
        else if (data.isObject() && data.size() > 0) list.add(map(data, pid, assetType));
        list.forEach(this::upsert);
        logger.info("Parsed {} {} for project {}", list.size(), assetType, pid);
        return list.size();
    }

    private Asset map(JsonNode n, String pid, String assetType) {
        Asset a = new Asset();
        a.setExternalId(getAsText(n, assetType + "_id", getAsText(n, "id", getAsText(n, "_id", null))));
        a.setProjectId(pid);
        a.setAssetType(assetType);
        a.setName(getAsText(n, "name", null));
        a.setTag(getAsText(n, "tag", getAsText(n, "equipment_id", null)));
        a.setDescription(getAsText(n, "description", null));
        a.setStatus(getAsText(n, "status", null));
        a.setLocation(getAsText(n, "location", null));
        a.setParentId(getAsText(n, "parent_id", getAsText(n, "building_id", null)));
        a.setCreatedAt(getAsText(n, "date_created", getAsText(n, "created_at", null)));
        a.setUpdatedAt(getAsText(n, "updated_at", null));
        a.setRawJson(n.toString());
        a.setSyncedAt(now());
        return a;
    }

    private void upsert(Asset a) {
        if (a.getExternalId() != null) {
            assetRepository.findByExternalId(a.getExternalId()).ifPresentOrElse(existing -> {
                existing.setName(a.getName()); existing.setTag(a.getTag());
                existing.setStatus(a.getStatus()); existing.setUpdatedAt(a.getUpdatedAt());
                existing.setRawJson(a.getRawJson()); existing.setSyncedAt(now());
                assetRepository.save(existing);
            }, () -> assetRepository.save(a));
        } else { assetRepository.save(a); }
    }
}
