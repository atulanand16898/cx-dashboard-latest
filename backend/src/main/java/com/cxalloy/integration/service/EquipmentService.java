package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Equipment;
import com.cxalloy.integration.repository.EquipmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EquipmentService — syncs CxAlloy GET /equipment endpoint.
 *
 * From the CxAlloy OpenAPI spec (api.cxalloy.com/openapi.yaml):
 *   GET /equipment?project_id=<id>&page=<n>
 *   Paginated (500 records/page)
 *
 * Key response fields:
 *   equipment_id, name, tag, description,
 *   equipment_status_id, type_id, discipline_id,
 *   space_id, building_id, floor_id,
 *   date_created, updated_at
 *
 * Optional ?include= parameters available:
 *   systems, zones, attributes, areas_served,
 *   issues, checklists, tests, files, collaborators, time_to_close, extended_status
 */
@Service
@Transactional
public class EquipmentService extends BaseProjectService {

    private static final int PAGE_SIZE = 500;

    private final EquipmentRepository equipmentRepository;
    private final CxAlloyApiClient apiClient;

    public EquipmentService(EquipmentRepository equipmentRepository, CxAlloyApiClient apiClient) {
        this.equipmentRepository = equipmentRepository;
        this.apiClient = apiClient;
    }

    // ── Live fetch (no DB write) ──────────────────────────────────────────────

    /**
     * Fetches equipment directly from CxAlloy in real-time without persisting to DB.
     * Used when the synced DB table is empty and the page needs immediate data.
     * Reads up to 2 pages (1000 items) to keep response time acceptable.
     */
    @Transactional(readOnly = true)
    public List<Equipment> fetchLive(String projectId) {
        String pid = resolveProjectId(projectId);
        List<Equipment> result = new ArrayList<>();
        int page = 1;
        try {
            while (page <= 2) {   // cap at 2 pages (1000 records) for live preview
                String url = "/equipment?project_id=" + pid
                        + "&include=type,systems,tests"
                        + (page > 1 ? "&page=" + page : "");
                String raw = apiClient.get(url);
                if (raw == null || raw.isBlank()) break;
                if (raw.trim().startsWith("{") && !raw.contains("\"data\"") &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) break;

                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(raw);
                com.fasterxml.jackson.databind.JsonNode data = extractData(root, "/equipment live project=" + pid);
                if (data.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : data) result.add(map(n, pid));
                } else if (data.isObject() && data.size() > 0) {
                    result.add(map(data, pid));
                }
                if (result.size() < page * PAGE_SIZE) break;
                page++;
            }
        } catch (Exception e) {
            logger.warn("fetchLive equipment failed for project {}: {}", pid, e.getMessage());
        }
        return result;
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    @Caching(evict = {
        @CacheEvict(value = "equipment-by-project", allEntries = true),
        @CacheEvict(value = "equipment-all",         allEntries = true)
    })
    public SyncResult syncEquipment(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/equipment", "GET");
        int totalSynced = 0;
        int page = 1;
        try {
            logger.info("Syncing equipment for project_id={} via GET /equipment (paginated)", pid);
            while (true) {
                // Include type and systems so we get the full type.name (e.g. "BMS Panels")
                // instead of just a type_id integer. CxAlloy supports ?include= on /equipment.
                String url = "/equipment?project_id=" + pid
                        + "&include=type,systems,tests"
                        + (page > 1 ? "&page=" + page : "");
                String raw = apiClient.get(url);
                if (raw == null || raw.isBlank()) break;

                // Detect error responses before parsing
                if (raw.trim().startsWith("{") && !raw.contains("\"data\"") &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) {
                    logger.warn("Error response on /equipment page {} for project {} — stopping. Snippet: {}",
                        page, pid, raw.length() > 200 ? raw.substring(0, 200) : raw);
                    break;
                }

                saveRaw("/equipment?page=" + page, "equipment_list_p" + page, pid, raw);
                int count = parseAndSave(raw, pid);
                totalSynced += count;
                logger.info("Equipment page {}: {} records (running total: {})", page, count, totalSynced);

                if (count < PAGE_SIZE) break;
                if (++page > 50) {
                    logger.warn("Equipment page cap (50) reached for project {}", pid);
                    break;
                }
            }
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, dur, null);
            logger.info("Equipment sync complete for project {}: {} records in {} pages", pid, totalSynced, page);
            return new SyncResult("/equipment", "SUCCESS", totalSynced,
                "Synced " + totalSynced + " equipment records (" + page + " pages)", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            logger.error("Equipment sync FAILED for project {}: {}", pid, e.getMessage());
            finishLog(log, "FAILED", totalSynced, dur, e.getMessage());
            throw new RuntimeException("Sync equipment failed for project " + pid + ": " + e.getMessage(), e);
        }
    }

    // ── Equipment Type back-fill ──────────────────────────────────────────────

    /**
     * Syncs GET /equipmenttype?project_id=X to get type_id → name mappings,
     * then updates all equipment rows whose equipmentType is null or numeric.
     *
     * CxAlloy /equipmenttype returns: [{id, name, description, discipline_id, ...}]
     * This is the same data visible at /project/XXXX/equipmenttype in the UI.
     */
    @Caching(evict = {
        @CacheEvict(value = "equipment-by-project", allEntries = true),
        @CacheEvict(value = "equipment-all",         allEntries = true)
    })
    public SyncResult syncEquipmentTypes(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        int updated = 0;

        try {
            // Fetch equipment types from CxAlloy
            String url = "/equipmenttype?project_id=" + pid;
            String raw = apiClient.get(url);
            if (raw == null || raw.isBlank()) {
                return new SyncResult("/equipmenttype", "SUCCESS", 0,
                        "No equipment types returned", System.currentTimeMillis() - start);
            }

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(raw);
            com.fasterxml.jackson.databind.JsonNode data = extractData(root, "/equipmenttype project=" + pid);

            // Build id → name map
            Map<String, String> typeIdToName = new java.util.HashMap<>();
            if (data.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode t : data) {
                    String id   = getAsText(t, "id",   getAsText(t, "type_id", null));
                    String name = getAsText(t, "name", getAsText(t, "title",   null));
                    if (id != null && name != null && !name.isBlank()) {
                        typeIdToName.put(id, name);
                        logger.debug("Equipment type: {} → {}", id, name);
                    }
                }
            }

            if (typeIdToName.isEmpty()) {
                logger.warn("No equipment types found for project {}", pid);
                return new SyncResult("/equipmenttype", "SUCCESS", 0,
                        "No types found", System.currentTimeMillis() - start);
            }

            // Back-fill equipment rows that have a numeric type or null type
            List<Equipment> eqList = equipmentRepository.findByProjectId(pid);
            for (Equipment eq : eqList) {
                String current = eq.getEquipmentType();
                // Needs update if null, blank, or purely numeric (i.e. a raw type_id)
                boolean needsUpdate = current == null || current.isBlank()
                        || current.matches("\\d+");

                if (!needsUpdate) continue;

                String resolved = null;
                if (current != null && current.matches("\\d+")) {
                    // Direct numeric ID — look up
                    resolved = typeIdToName.get(current);
                }
                // Also try to find from rawJson type_id field
                if (resolved == null && eq.getRawJson() != null) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode rawNode =
                                objectMapper.readTree(eq.getRawJson());
                        String typeId = getAsText(rawNode, "type_id",
                                        getAsText(rawNode, "equipment_type_id",
                                        getAsText(rawNode, "asset_type_id", null)));
                        if (typeId != null) resolved = typeIdToName.get(typeId);
                        // Also try nested type.id
                        if (resolved == null && rawNode.has("type") && rawNode.get("type").isObject()) {
                            String nestedId = getAsText(rawNode.get("type"), "id", null);
                            if (nestedId != null) resolved = typeIdToName.get(nestedId);
                        }
                    } catch (Exception ignored) {}
                }

                if (resolved != null && !resolved.isBlank()) {
                    eq.setEquipmentType(resolved);
                    equipmentRepository.save(eq);
                    updated++;
                }
            }

            logger.info("Equipment type back-fill: {} rows updated for project {}", updated, pid);
            long dur = System.currentTimeMillis() - start;
            return new SyncResult("/equipmenttype", "SUCCESS", updated,
                    String.format("Synced %d type names, updated %d equipment rows", typeIdToName.size(), updated), dur);

        } catch (Exception e) {
            logger.error("Equipment type sync failed for project {}: {}", pid, e.getMessage());
            throw new RuntimeException("Equipment type sync failed: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "equipment-all")
    @Transactional(readOnly = true)
    public List<Equipment> getAll() { return equipmentRepository.findAll(); }

    @Cacheable(value = "entity-by-id", key = "'equipment-' + #id")
    @Transactional(readOnly = true)
    public Optional<Equipment> getById(Long id) { return equipmentRepository.findById(id); }

    @Cacheable(value = "equipment-by-project", key = "#projectId")
    @Transactional(readOnly = true)
    public List<Equipment> getByProject(String projectId) {
        return equipmentRepository.findByProjectId(projectId);
    }

    @Cacheable(value = "equipment-by-project", key = "#projectId + '-' + #equipmentType")
    @Transactional(readOnly = true)
    public List<Equipment> getByProjectAndType(String projectId, String equipmentType) {
        return equipmentRepository.findByProjectIdAndEquipmentType(projectId, equipmentType);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private int parseAndSave(String json, String pid) throws Exception {
        if (json == null || json.isBlank()) {
            logger.warn("Empty response for /equipment project={}", pid);
            return 0;
        }
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = extractData(root, "/equipment project=" + pid);
        List<Equipment> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) list.add(map(n, pid));
        } else if (data.isObject() && data.size() > 0) {
            list.add(map(data, pid));
        }
        list.forEach(this::upsert);
        logger.info("Parsed {} equipment records for project {}", list.size(), pid);
        return list.size();
    }

    /**
     * Maps a CxAlloy equipment JSON node to our Equipment entity.
     *
     * CxAlloy /equipment response fields (from OpenAPI spec):
     *   equipment_id      → externalId
     *   name              → name
     *   tag               → tag           (physical tag label on the asset)
     *   description       → description
     *   equipment_status_id → stored in rawJson; status label resolved if present
     *   type_id / type    → equipmentType
     *   discipline_id / discipline → discipline
     *   space_id          → spaceId
     *   building_id       → buildingId
     *   floor_id          → floorId
     *   date_created      → createdAt
     *   updated_at        → updatedAt
     */
    private Equipment map(JsonNode n, String pid) {
        Equipment e = new Equipment();

        // Primary ID — CxAlloy uses "equipment_id" as the canonical field
        e.setExternalId(getAsText(n, "equipment_id",
            getAsText(n, "id", getAsText(n, "_id", null))));
        e.setProjectId(pid);
        e.setName(getAsText(n, "name", null));

        // Tag is the physical label on the equipment (e.g. "AHU-01", "FCU-B2-05")
        e.setTag(getAsText(n, "tag", getAsText(n, "equipment_id", null)));
        e.setDescription(getAsText(n, "description", null));

        // ── EQUIPMENT STATUS ─────────────────────────────────────────────────
        // CxAlloy /equipment returns equipment_status_id (integer) as the primary
        // status field, NOT a string. The standard CxAlloy commissioning status IDs:
        //   1 = Not Assigned    5 = Cx Complete
        //   2 = Asset Assigned  6 = Ready For Startup
        //   3 = Pre-Cx          7 = In Service
        //   4 = Cx In Progress
        // Some deployments also return a "status" or "equipment_status" string label.
        // We check the string first (in case it's present) then fall back to numeric ID.
        String statusStr = getAsText(n, "status",
            getAsText(n, "equipment_status",
            getAsText(n, "commission_status", null)));
        if (statusStr != null && !statusStr.isBlank() && !statusStr.matches("\\d+")) {
            e.setStatus(statusStr);  // already a string label
        } else {
            // Numeric ID path — normalise to display string
            String statusId = statusStr != null ? statusStr
                : getAsText(n, "equipment_status_id",
                  getAsText(n, "status_id", null));
            e.setStatus(normEquipmentStatus(statusId));
        }

        // ── EQUIPMENT TYPE ────────────────────────────────────────────────────
        // CxAlloy can return type as:
        //   a) Full object: {"id": 712338, "name": "BMS Panels"}   when ?include=type
        //   b) Just the name string: "BMS Panels"
        //   c) Just the numeric ID: 712338 (when include not used)
        // We try each in order.
        String equipType = null;
        if (n.has("type") && !n.get("type").isNull()) {
            com.fasterxml.jackson.databind.JsonNode typeNode = n.get("type");
            if (typeNode.isObject()) {
                // Full object — grab name
                equipType = getAsText(typeNode, "name",
                            getAsText(typeNode, "title",
                            getAsText(typeNode, "label", null)));
            } else if (typeNode.isTextual()) {
                equipType = typeNode.asText();
            }
            // If numeric, leave null — we'll try equipment_type below
        }
        if (equipType == null || equipType.isBlank()) {
            equipType = getAsText(n, "equipment_type",
                        getAsText(n, "type_name",
                        getAsText(n, "asset_type", null)));
        }
        // Also try systems array: [{name:"BMS Panels"}]
        if ((equipType == null || equipType.isBlank()) && n.has("systems") && n.get("systems").isArray()
                && n.get("systems").size() > 0) {
            com.fasterxml.jackson.databind.JsonNode sys = n.get("systems").get(0);
            if (sys.isObject()) {
                equipType = getAsText(sys, "name", getAsText(sys, "title", null));
            }
        }
        e.setEquipmentType(equipType);

        // Discipline
        if (n.has("discipline") && n.get("discipline").isObject()) {
            e.setDiscipline(getAsText(n.get("discipline"), "name", null));
        } else {
            e.setDiscipline(getAsText(n, "discipline", null));
        }

        // Location hierarchy
        e.setBuildingId(getAsText(n, "building_id",
            getAsText(n, "building", null)));
        e.setFloorId(getAsText(n, "floor_id",
            getAsText(n, "floor", null)));
        e.setSpaceId(getAsText(n, "space_id",
            getAsText(n, "space", null)));

        if (n.has("systems") && n.get("systems").isArray() && n.get("systems").size() > 0) {
            JsonNode systemNode = n.get("systems").get(0);
            if (systemNode.isObject()) {
                e.setSystemId(getAsText(systemNode, "system_id", getAsText(systemNode, "id", null)));
                e.setSystemName(getAsText(systemNode, "name", getAsText(systemNode, "title", null)));
            }
        }

        // Checklist / issue counts if returned in summary
        if (n.has("checklist_count") && !n.get("checklist_count").isNull()) {
            try { e.setChecklistCount(n.get("checklist_count").asInt()); } catch (Exception ignored) {}
        }
        if (n.has("issue_count") && !n.get("issue_count").isNull()) {
            try { e.setIssueCount(n.get("issue_count").asInt()); } catch (Exception ignored) {}
        }
        if (n.has("test_count") && !n.get("test_count").isNull()) {
            try { e.setTestCount(n.get("test_count").asInt()); } catch (Exception ignored) {}
        } else if (n.has("tests_count") && !n.get("tests_count").isNull()) {
            try { e.setTestCount(n.get("tests_count").asInt()); } catch (Exception ignored) {}
        } else if (n.has("tests") && n.get("tests").isArray()) {
            e.setTestCount(n.get("tests").size());
        }

        // Dates — CxAlloy uses "date_created" (ISO 8601 per Feb 2026 release) + "updated_at"
        e.setCreatedAt(getAsText(n, "date_created",
            getAsText(n, "created_at", null)));
        e.setUpdatedAt(getAsText(n, "updated_at", null));

        e.setRawJson(n.toString());
        e.setSyncedAt(now());
        return e;
    }

    private void upsert(Equipment eq) {
        if (eq.getExternalId() != null) {
            equipmentRepository.findByExternalId(eq.getExternalId()).ifPresentOrElse(existing -> {
                existing.setName(eq.getName());
                existing.setTag(eq.getTag());
                existing.setStatus(eq.getStatus());
                existing.setEquipmentType(eq.getEquipmentType());
                existing.setDiscipline(eq.getDiscipline());
                existing.setBuildingId(eq.getBuildingId());
                existing.setSystemId(eq.getSystemId());
                existing.setSystemName(eq.getSystemName());
                existing.setFloorId(eq.getFloorId());
                existing.setSpaceId(eq.getSpaceId());
                existing.setChecklistCount(eq.getChecklistCount());
                existing.setIssueCount(eq.getIssueCount());
                existing.setTestCount(eq.getTestCount());
                existing.setUpdatedAt(eq.getUpdatedAt());
                existing.setRawJson(eq.getRawJson());
                existing.setSyncedAt(now());
                equipmentRepository.save(existing);
            }, () -> equipmentRepository.save(eq));
        } else {
            equipmentRepository.save(eq);
        }
    }

    /**
     * Converts a CxAlloy equipment_status_id integer to a human-readable
     * commissioning state name used by the AssetReadiness page.
     *
     * Standard CxAlloy TQ commissioning status IDs:
     *   1 = Not Assigned       5 = Cx Complete
     *   2 = Asset Assigned     6 = Ready For Startup
     *   3 = Pre-Cx             7 = In Service
     *   4 = Cx In Progress
     *
     * Passes through unrecognised string labels unchanged so live data
     * never shows a blank status field.
     */
    private String normEquipmentStatus(String idOrLabel) {
        if (idOrLabel == null || idOrLabel.isBlank()) return "Not Assigned";
        switch (idOrLabel.trim()) {
            case "1": return "Not Assigned";
            case "2": return "Asset Assigned";
            case "3": return "Pre-Cx";
            case "4": return "Cx In Progress";
            case "5": return "Cx Complete";
            case "6": return "Ready For Startup";
            case "7": return "In Service";
            default:  return idOrLabel; // already a string label — pass through
        }
    }
}
