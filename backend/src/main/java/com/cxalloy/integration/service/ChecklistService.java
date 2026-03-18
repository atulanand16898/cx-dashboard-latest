package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Checklist;
import com.cxalloy.integration.repository.ChecklistRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

/**
 * Checklists in CxAlloy TQ API use POST /checklist with project_id in the JSON body.
 *
 * PAGINATION: CxAlloy returns a max of 500 records per page.
 * To get all records we must pass {"project_id": X, "page": N} and increment
 * until the returned array has fewer than PAGE_SIZE entries (or is empty).
 *
 * STATUS MAPPING: CxAlloy returns status as an integer code:
 *   0 = not_started, 1 = in_progress, 2 = finished/completed, 3 = on_hold, 4 = cancelled
 * We normalise to a lowercase string label so the frontend renders the correct badge.
 */
@Service
@Transactional
public class ChecklistService extends BaseProjectService {

    private static final int PAGE_SIZE = 500;

    private final ChecklistRepository checklistRepository;
    private final CxAlloyApiClient apiClient;

    public ChecklistService(ChecklistRepository checklistRepository, CxAlloyApiClient apiClient) {
        this.checklistRepository = checklistRepository;
        this.apiClient = apiClient;
    }

    @Caching(evict = {
        @CacheEvict(value = "checklists-by-project", allEntries = true),
        @CacheEvict(value = "checklists-all", allEntries = true)
    })
    public SyncResult syncChecklists(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/checklist", "GET");
        int totalSynced = 0;
        int page = 1;

        try {
            logger.info("Fetching checklists for project_id={} — all pages via POST /checklist", pid);

            while (true) {
                // CxAlloy /checklist ONLY accepts POST with JSON body.
                // GET /checklist?page=N returns 400 {"error":"Unknown Endpoint"}.
                // We include "page" in the POST body for pages 2+ — the HMAC signature
                // covers the body, so each page has a different signature, which is fine
                // because we sign each request independently.
                String body = page == 1
                    ? "{\"project_id\":" + pid + "}"
                    : "{\"project_id\":" + pid + ",\"page\":" + page + "}";
                logger.debug("Fetching checklists page {} for project {} via POST /checklist", page, pid);
                String raw = apiClient.post("/checklist", body);

                if (raw == null || raw.isBlank()) {
                    logger.info("Empty response on page {} — stopping pagination", page);
                    break;
                }

                // CxAlloy returns data under "records" key (confirmed from live API).
                // Stop only if the response has no data keys AND has an error indicator.
                boolean hasData = raw.contains("\"data\"") || raw.contains("\"records\"")
                                  || raw.contains("\"items\"") || raw.contains("\"results\"");
                if (raw.trim().startsWith("{") && !hasData &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) {
                    logger.warn("Error/non-data response on page {} — stopping. Body preview: {}", page,
                        raw.length() > 300 ? raw.substring(0, 300) : raw);
                    break;
                }

                saveRaw("/checklist?page=" + page, "checklists_list_p" + page, pid, raw);
                int count = parseAndSave(raw, pid);
                totalSynced += count;

                logger.info("Page {}: synced {} checklists (running total: {})", page, count, totalSynced);

                // If fewer than PAGE_SIZE records returned, we've hit the last page
                if (count < PAGE_SIZE) {
                    logger.info("Last page reached at page {} (got {} < {})", page, count, PAGE_SIZE);
                    break;
                }

                page++;

                // Safety cap to avoid infinite loops
                if (page > 50) {
                    logger.warn("Reached page cap (50) — stopping pagination for project {}", pid);
                    break;
                }
            }

            long dur = System.currentTimeMillis() - start;
            logger.info("Checklists sync complete for project {}: {} total records across {} pages", pid, totalSynced, page);
            finishLog(log, "SUCCESS", totalSynced, dur, null);
            return new SyncResult("/checklist", "SUCCESS", totalSynced,
                "Synced " + totalSynced + " checklists for project " + pid + " (" + page + " pages)", dur);

        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            logger.error("Checklist sync FAILED for project {}: {}", pid, e.getMessage());
            finishLog(log, "FAILED", totalSynced, dur, e.getMessage());
            throw new RuntimeException("Sync checklists failed: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "checklists-all")
    @Transactional(readOnly = true)
    public List<Checklist> getAll() { return rehydrateTagLevels(checklistRepository.findAll()); }

    @Cacheable(value = "entity-by-id", key = "\"checklists-\" + #id")
    @Transactional(readOnly = true)
    public Optional<Checklist> getById(Long id) { return checklistRepository.findById(id); }

    @Cacheable(value = "checklists-by-project", key = "#projectId")
    @Transactional(readOnly = true)
    public List<Checklist> getByProject(String projectId) {
        return rehydrateTagLevels(checklistRepository.findByProjectId(projectId));
    }

    /**
     * Re-derives tagLevel for any record where it is missing or was defaulted to "white".
     * Strategy (in priority order):
     *   1. checklistType column  — e.g. "Level-2 YELLOW Tag QA/QC/IVC"
     *   2. rawJson scan          — extracts checklist_type / type from the stored CxAlloy payload
     *      This recovers records where checklistType was not mapped during the original sync
     *      because the field was nested differently in that API version.
     * Does NOT write back to DB — purely in-memory correction for the API response.
     */
    private List<Checklist> rehydrateTagLevels(List<Checklist> list) {
        list.forEach(c -> {
            String stored = c.getTagLevel();
            // Only attempt re-derivation when stored value is missing or "white"
            if (stored != null && !stored.isBlank() && !stored.equals("white")) return;

            // 1. Try checklistType column
            String ct = c.getChecklistType();
            if (ct != null && !ct.isBlank()) {
                String derived = normTagLevel(ct);
                if (!derived.equals("white")) { c.setTagLevel(derived); return; }
            }

            // 2. Try rawJson — scan fields in priority order
            String raw = c.getRawJson();
            if (raw != null && !raw.isBlank()) {
                // Scan fields in priority order.
                // Numeric ID fields (checklist_type_id, type_id, level_id) are tried first
                // because they are unambiguous — "2" always means yellow.
                // String fields follow, relying on normTagLevel keyword matching.
                for (String field : new String[]{
                        "tag_color", "color",
                        "checklist_type_id", "type_id", "level_id", "tag_level_id",
                        "checklist_type", "type", "tag_type",
                        "template_name", "category", "classification", "name"}) {
                    String derivedFromRaw = extractAndNormFromJson(raw, field);
                    if (derivedFromRaw != null) { c.setTagLevel(derivedFromRaw); return; }
                }
            }

            // Genuinely unclassifiable — ensure white is set (not null/blank)
            if (stored == null || stored.isBlank()) c.setTagLevel("white");
        });
        return list;
    }

    /**
     * Extracts the value of a JSON field from a raw JSON string and
     * runs normTagLevel() on it. Handles both string values ("...") and
     * numeric values (e.g. "checklist_type_id": 2).
     * Returns null if the field is absent or maps to no real color.
     */
    private String extractAndNormFromJson(String raw, String fieldName) {
        String search = "\"" + fieldName + "\"";
        int idx = raw.indexOf(search);
        if (idx < 0) return null;
        int colon = raw.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        // Skip whitespace after colon
        int start = colon + 1;
        while (start < raw.length() && (raw.charAt(start) == ' ' || raw.charAt(start) == '\t')) start++;
        if (start >= raw.length()) return null;

        String value;
        if (raw.charAt(start) == '"') {
            // String value — extract content between quotes
            start++; // skip opening quote
            int end = raw.indexOf('"', start);
            if (end < 0) return null;
            value = raw.substring(start, end).trim();
        } else if (Character.isDigit(raw.charAt(start)) || raw.charAt(start) == '-') {
            // Numeric value — extract digits
            int end = start;
            while (end < raw.length() && (Character.isDigit(raw.charAt(end)) || raw.charAt(end) == '.')) end++;
            value = raw.substring(start, end).trim();
        } else {
            return null; // boolean / null / object / array — skip
        }

        if (value.isBlank()) return null;
        String derived = normTagLevel(value);
        return derived.equals("white") ? null : derived;
    }

    private int parseAndSave(String json, String pid) throws Exception {
        if (json == null || json.isBlank()) {
            logger.warn("Empty response for /checklist project={}", pid);
            return 0;
        }
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = extractData(root, "/checklist project=" + pid);
        List<Checklist> list = new ArrayList<>();
        if (data.isArray()) { for (JsonNode n : data) list.add(map(n, pid)); }
        else if (data.isObject()) list.add(map(data, pid));
        list.forEach(this::upsert);
        logger.debug("Parsed and saved {} checklists for project {}", list.size(), pid);
        return list.size();
    }

    private Checklist map(JsonNode n, String pid) {
        Checklist c = new Checklist();
        c.setExternalId(getAsText(n, "checklist_id",
                        getAsText(n, "id",
                        getAsText(n, "_id", null))));
        c.setProjectId(pid);
        c.setName(getAsText(n, "name", null));
        c.setDescription(getAsText(n, "description", null));

        // ── STATUS NORMALISATION ──────────────────────────────────────────────
        String rawStatus = getAsText(n, "status", getAsText(n, "state", null));
        c.setStatus(normaliseStatus(rawStatus));

        // ── TYPE + TAG LEVEL ─────────────────────────────────────────────────
        // checklist_type is the full string e.g. "Level-2 YELLOW Tag QA/QC/IVC"
        // This IS the primary level identifier in CxAlloy — it's used for color
        // classification on TrackerPulse, ChecklistFlow, PlannedVsActual pages.
        String checklistType = getAsText(n, "checklist_type",
                               getAsText(n, "type_name",
                               getAsText(n, "type",
                               getAsText(n, "tag_type", null))));
        c.setChecklistType(checklistType);

        // Derive normalised tagLevel from checklistType + explicit tag_level field
        String explicitTagLevel = getAsText(n, "tag_level",
                                  getAsText(n, "level", null));
        c.setTagLevel(normTagLevel(explicitTagLevel != null ? explicitTagLevel : checklistType));

        // ── LOCATION HIERARCHY ───────────────────────────────────────────────
        // CxAlloy /checklist returns space_id, building_id, system_id, zone_id, floor_id
        // for location-based grouping and hotspot analysis.
        c.setSpaceId(getAsText(n, "space_id", null));
        c.setBuildingId(getAsText(n, "building_id", null));
        c.setSystemId(getAsText(n, "system_id", null));
        c.setZoneId(getAsText(n, "zone_id", null));
        c.setFloorId(getAsText(n, "floor_id", null));

        c.setAssetId(getAsText(n, "asset_id",
                     getAsText(n, "asset_key",
                     getAsText(n, "equipment_id", null))));
        c.setAssignedTo(getAsText(n, "assigned_to", getAsText(n, "assignee", null)));
        c.setDueDate(getAsText(n, "due_date", getAsText(n, "planned_date", null)));
        c.setCompletedDate(getAsText(n, "completed_date", getAsText(n, "date_completed", null)));
        c.setCreatedAt(getAsText(n, "date_created",
                       getAsText(n, "created_at",
                       getAsText(n, "created_date", null))));
        c.setUpdatedAt(getAsText(n, "updated_at", getAsText(n, "date_updated", null)));
        c.setRawJson(n.toString());
        c.setSyncedAt(now());
        return c;
    }

    /**
     * Normalises a checklist type/level string to a short color token.
     * Input: "Level-2 YELLOW Tag QA/QC/IVC", "Level-1 RED Tag FAT",
     *        "L3", "green", "1", etc.
     * Output: "red" | "yellow" | "green" | "blue" | "white"
     */
    /**
     * Maps a raw checklist type string or level identifier to a canonical color token.
     *
     * Key rules:
     *  - Color name matching uses word-boundary regex to avoid false positives
     *    (e.g. "predefined" must NOT match "red", "hundred" must NOT match "red").
     *  - "l1"/"l2"/"l3"/"l4" use strict word-boundary matching to avoid mid-word hits.
     *  - Numeric IDs "1"–"4" are supported as exact equality matches.
     *  - Numeric IDs 5–9 and 10–99 are NOT mapped (would be other field types, not colors).
     */
    private String normTagLevel(String raw) {
        if (raw == null || raw.isBlank()) return "white";
        String s = raw.trim();

        // Exact numeric type-id (CxAlloy sometimes stores just the level number)
        if (s.equals("1")) return "red";
        if (s.equals("2")) return "yellow";
        if (s.equals("3")) return "green";
        if (s.equals("4")) return "blue";

        String lower = s.toLowerCase();

        // ── ITR patterns — word-boundary safe ────────────────────────────────
        if (lower.matches(".*\bitr[-_\s]?a\b.*") || lower.equals("itr-a") || lower.equals("itra")) return "red";
        if (lower.matches(".*\bitr[-_\s]?b\b.*") || lower.equals("itr-b") || lower.equals("itrb")) return "yellow";
        if (lower.matches(".*\bitr[-_\s]?c\b.*") || lower.equals("itr-c") || lower.equals("itrc")) return "green";
        if (lower.matches(".*\bitr[-_\s]?d\b.*") || lower.equals("itr-d") || lower.equals("itrd")) return "blue";

        // ── Explicit color words — MUST use word boundaries to avoid false positives ──
        // e.g. "predefined"→red, "hundred"→red, "blueprint"→blue without boundaries
        if (lower.matches(".*\bred\b.*"))    return "red";
        if (lower.matches(".*\byellow\b.*")) return "yellow";
        if (lower.matches(".*\bgreen\b.*"))  return "green";
        if (lower.matches(".*\bblue\b.*"))   return "blue";

        // ── Level number labels ───────────────────────────────────────────────
        if (lower.contains("level-1") || lower.contains("level 1")) return "red";
        if (lower.contains("level-2") || lower.contains("level 2")) return "yellow";
        if (lower.contains("level-3") || lower.contains("level 3")) return "green";
        if (lower.contains("level-4") || lower.contains("level 4")) return "blue";

        // ── Short level tokens — word-boundary only to prevent mid-word hits ─
        if (lower.matches(".*\bl1\b.*")) return "red";
        if (lower.matches(".*\bl2\b.*")) return "yellow";
        if (lower.matches(".*\bl3\b.*")) return "green";
        if (lower.matches(".*\bl4\b.*")) return "blue";

        // ── Cx commissioning phase abbreviations ─────────────────────────────
        if (lower.contains("pre-cx") || lower.contains("precx") || lower.contains("pre cx")) return "red";
        if (lower.matches(".*\bcx[-_]?a\b.*")) return "yellow";
        if (lower.matches(".*\bcx[-_]?b\b.*")) return "green";

        return "white";
    }

    /**
     * Normalises a CxAlloy status value to a lowercase string label.
     *
     * CxAlloy returns status as either:
     *  - Integer code: 0=not_started, 1=in_progress, 2=finished, 3=on_hold, 4=cancelled
     *  - String label: "Not Started", "In Progress", "Finished", "Completed", etc.
     *
     * We convert both forms to one consistent lowercase token that the frontend
     * StatusBadge can match against.
     */
    private String normaliseStatus(String raw) {
        if (raw == null || raw.isBlank()) return "not_started";
        String s = raw.trim();

        // Integer code path — CxAlloy numeric status IDs
        switch (s) {
            case "0": return "not_started";
            case "1": return "in_progress";
            case "2": return "complete";          // "Complete" in CxAlloy UI
            case "3": return "checklist_approved";// "Checklist Approved" in CxAlloy UI
            case "4": return "returned_with_comments"; // "Returned with Comments"
            case "5": return "on_hold";
            case "6": return "cancelled";
        }

        // String label path — normalise to lowercase snake_case
        String lower = s.toLowerCase().replace(" ", "_").replace("-", "_");

        // ── CxAlloy canonical 5-status pipeline (slide 10/11 mapping) ─────
        // "Checklist Approved" — the signed-off final status
        if (lower.equals("checklist_approved") || lower.equals("approved") ||
            lower.equals("signed_off") || lower.equals("accepted_by_owner")) {
            return "checklist_approved";
        }
        // "Returned with Comments" — reviewed but needs rework
        if (lower.equals("returned_with_comments") || lower.equals("returned") ||
            lower.contains("returned") || lower.equals("rejected") ||
            lower.equals("rework") || lower.equals("revision_required")) {
            return "returned_with_comments";
        }
        // "Complete" — submitted but not yet approved
        if (lower.equals("complete") || lower.equals("completed") ||
            lower.equals("finished") || lower.equals("done") ||
            lower.equals("closed")) {
            return "complete";
        }
        if (lower.equals("in_progress") || lower.equals("inprogress") ||
            lower.equals("started") || lower.equals("active")) {
            return "in_progress";
        }
        if (lower.equals("not_started") || lower.equals("notstarted") ||
            lower.equals("new") || lower.equals("open")) {
            return "not_started";
        }
        if (lower.equals("on_hold") || lower.equals("onhold") || lower.equals("hold")) {
            return "on_hold";
        }
        if (lower.equals("cancelled") || lower.equals("canceled")) {
            return "cancelled";
        }

        // Unknown — pass through as-is
        return lower;
    }

    private void upsert(Checklist c) {
        if (c.getExternalId() != null) {
            checklistRepository.findByExternalId(c.getExternalId()).ifPresentOrElse(existing -> {
                existing.setName(c.getName());
                existing.setStatus(c.getStatus());
                existing.setAssignedTo(c.getAssignedTo());
                existing.setUpdatedAt(c.getUpdatedAt());
                existing.setChecklistType(c.getChecklistType());
                existing.setTagLevel(c.getTagLevel());
                existing.setSpaceId(c.getSpaceId());
                existing.setBuildingId(c.getBuildingId());
                existing.setSystemId(c.getSystemId());
                existing.setZoneId(c.getZoneId());
                existing.setFloorId(c.getFloorId());
                existing.setAssetId(c.getAssetId());
                existing.setDueDate(c.getDueDate());
                existing.setCompletedDate(c.getCompletedDate());
                existing.setRawJson(c.getRawJson());
                existing.setSyncedAt(now());
                checklistRepository.save(existing);
            }, () -> checklistRepository.save(c));
        } else {
            checklistRepository.save(c);
        }
    }
}
