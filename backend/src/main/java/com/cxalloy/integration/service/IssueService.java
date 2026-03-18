package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.IssueRequest;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Issue;
import com.cxalloy.integration.repository.IssueRepository;
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
 * Issues in CxAlloy TQ API use POST /issue with project_id in the JSON body.
 * Confirmed from official OpenAPI spec at https://api.cxalloy.com/openapi.yaml
 *
 * PAGINATION: Page 1 uses POST with body {"project_id": X}.
 * Page 2+ uses GET /issue?project_id=X&page=N to avoid HMAC body mismatch.
 *
 * STATUS NORMALISATION: CxAlloy returns issue statuses as verbose strings:
 *   "Issue Closed", "Accepted By Owner", "Correction In Progress",
 *   "Gc To Verify", "Cxa To Verify", "Ready For Retest", "Issue Opened"
 * These are normalised to snake_case tokens the frontend can match reliably.
 */
@Service
@Transactional
public class IssueService extends BaseProjectService {

    private static final int PAGE_SIZE = 500;

    private final IssueRepository issueRepository;
    private final CxAlloyApiClient apiClient;

    public IssueService(IssueRepository issueRepository, CxAlloyApiClient apiClient) {
        this.issueRepository = issueRepository;
        this.apiClient = apiClient;
    }

    @Caching(evict = {
        @CacheEvict(value = "issues-by-project", allEntries = true),
        @CacheEvict(value = "issues-all", allEntries = true)
    })
    public SyncResult syncAllIssues(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/issue", "POST");
        int totalSynced = 0;
        int page = 1;
        try {
            logger.info("Fetching issues for project_id={} — page 1 via POST, subsequent via GET", pid);
            while (true) {
                String raw;
                if (page == 1) {
                    String body = "{\"project_id\":" + pid + "}";
                    raw = apiClient.post("/issue", body);
                } else {
                    raw = apiClient.get("/issue?project_id=" + pid + "&page=" + page);
                }
                if (raw == null || raw.isBlank()) break;
                if (raw.trim().startsWith("{") && !raw.contains("\"data\"") &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) {
                    logger.warn("Error response on issues page {} — stopping. Preview: {}", page,
                        raw.length() > 200 ? raw.substring(0, 200) : raw);
                    break;
                }
                saveRaw("/issue?page=" + page, "issues_list_p" + page, pid, raw);
                int count = parseAndSave(raw, pid);
                totalSynced += count;
                logger.info("Issues page {}: {} records (running total: {})", page, count, totalSynced);
                if (count < PAGE_SIZE) break;
                if (++page > 50) { logger.warn("Issues page cap reached for project {}", pid); break; }
            }
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, dur, null);
            return new SyncResult("/issue", "SUCCESS", totalSynced,
                "Synced " + totalSynced + " issues for project " + pid + " (" + page + " pages)", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            logger.error("Issue sync FAILED for project {}: {}", pid, e.getMessage());
            finishLog(log, "FAILED", totalSynced, dur, e.getMessage());
            throw new RuntimeException("Sync issues failed for project " + pid + ": " + e.getMessage(), e);
        }
    }

    public SyncResult syncIssueById(String issueId, String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/issue", "POST");
        try {
            // Fetch single issue by filtering via POST /issue with issue_id
            String body = "{\"project_id\":" + pid + ",\"issue_id\":" + issueId + "}";
            String raw = apiClient.post("/issue", body);
            saveRaw("/issue", "issue_single", issueId, raw);
            Issue issue = parseOne(raw, pid);
            upsertIssue(issue);
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", 1, dur, null);
            return new SyncResult("/issue", "SUCCESS", 1, "Issue synced", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Sync issue failed: " + e.getMessage(), e);
        }
    }

    @CacheEvict(value = {"issues-by-project", "issues-all"}, allEntries = true)
    public SyncResult createIssue(IssueRequest request) {
        long start = System.currentTimeMillis();
        ApiSyncLog log = startLog("/issue_create", "POST");
        try {
            String body = objectMapper.writeValueAsString(request);
            String raw = apiClient.post("/issue_create", body);
            saveRaw("/issue_create", "issue_created", null, raw);
            Issue issue = parseOne(raw, request.getProjectId());
            issueRepository.save(issue);
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", 1, dur, null);
            return new SyncResult("/issue_create", "SUCCESS", 1, "Issue created", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Create issue failed: " + e.getMessage(), e);
        }
    }

    @CacheEvict(value = {"issues-by-project", "issues-all"}, allEntries = true)
    public SyncResult updateIssue(String issueId, IssueRequest request) {
        long start = System.currentTimeMillis();
        ApiSyncLog log = startLog("/issue_update", "POST");
        try {
            String body = objectMapper.writeValueAsString(request);
            String raw = apiClient.post("/issue_update", body);
            saveRaw("/issue_update", "issue_updated", issueId, raw);
            Issue issue = parseOne(raw, request.getProjectId());
            upsertIssue(issue);
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", 1, dur, null);
            return new SyncResult("/issue_update", "SUCCESS", 1, "Issue updated", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Update issue failed: " + e.getMessage(), e);
        }
    }

    @CacheEvict(value = {"issues-by-project", "issues-all"}, allEntries = true)
    public SyncResult deleteIssue(String issueId, String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/issue_delete", "POST");
        try {
            String body = "{\"project_id\":" + pid + ",\"issue_id\":" + issueId + "}";
            String raw = apiClient.post("/issue_delete", body);
            saveRaw("/issue_delete", "issue_deleted", issueId, raw);
            issueRepository.findByExternalId(issueId).ifPresent(issueRepository::delete);
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", 1, dur, null);
            return new SyncResult("/issue_delete", "SUCCESS", 1, "Issue deleted", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Delete issue failed: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "issues-all")
    @Transactional(readOnly = true)
    public List<Issue> getAll() { return issueRepository.findAll(); }

    @Cacheable(value = "entity-by-id", key = "\"issue-\" + #id")
    @Transactional(readOnly = true)
    public Optional<Issue> getById(Long id) { return issueRepository.findById(id); }

    public Optional<Issue> getByExternalId(String extId) { return issueRepository.findByExternalId(extId); }

    @Cacheable(value = "issues-by-project", key = "#projectId")
    @Transactional(readOnly = true)
    public List<Issue> getByProject(String projectId) { return issueRepository.findByProjectId(projectId); }

    private int parseAndSave(String json, String pid) throws Exception {
        if (json == null || json.isBlank()) {
            logger.warn("Empty response from CxAlloy /issue for project {}", pid);
            return 0;
        }
        JsonNode root = objectMapper.readTree(json);
        logger.debug("Issue JSON root keys: {}", root.fieldNames());

        JsonNode data = null;
        for (String key : new String[]{"data", "issues", "items", "results", "records"}) {
            if (root.has(key) && !root.get(key).isNull()) {
                data = root.get(key);
                logger.debug("Found issue data under key '{}'", key);
                break;
            }
        }
        if (data == null) {
            data = root.isArray() ? root : null;
        }
        if (data == null || data.isNull() || (data.isArray() && data.isEmpty())) {
            logger.warn("No issue records found in response for project {}. Root keys: {}", pid, root.fieldNames());
            return 0;
        }

        List<Issue> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) list.add(map(n, pid));
        } else if (data.isObject()) {
            list.add(map(data, pid));
        }
        list.forEach(this::upsertIssue);
        logger.info("Parsed and saved {} issues for project {}", list.size(), pid);
        return list.size();
    }

    private Issue parseOne(String json, String pid) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = root.has("data") ? root.get("data") : root;
        return map(data.isArray() ? data.get(0) : data, pid);
    }

    private Issue map(JsonNode n, String pid) {
        Issue i = new Issue();
        i.setExternalId(getAsText(n, "issue_id", getAsText(n, "id", getAsText(n, "_id", null))));
        i.setProjectId(pid != null ? pid : getAsText(n, "project_id", null));
        i.setTitle(getAsText(n, "title",
                   getAsText(n, "subject",
                   getAsText(n, "name", "No Title"))));
        i.setDescription(getAsText(n, "description", null));
        i.setStatus(normaliseIssueStatus(getAsText(n, "status", null)));
        // Priority: CxAlloy returns "P1 - Critical", "P2 - High", "P3 - Medium", "P4 - Low"
        // Store raw value — the frontend normPriority() handles matching the exact strings.
        // Null priority is stored as null and treated as P4-Low on the frontend.
        i.setPriority(getAsText(n, "priority", getAsText(n, "priority_id", null)));
        i.setAssignee(getAsText(n, "assigned_to", getAsText(n, "assignee", null)));
        i.setReporter(getAsText(n, "reporter", getAsText(n, "created_by", null)));
        i.setDueDate(getAsText(n, "due_date", null));
        i.setAssetId(getAsText(n, "asset_id", getAsText(n, "asset_key", null)));
        i.setSourceType(getAsText(n, "source_type", null));
        i.setSourceId(getAsText(n, "source_id", null));

        // ── LOCATION HIERARCHY ───────────────────────────────────────────────
        // CxAlloy /issue response includes space_id, zone_id, building_id, floor_id.
        // These are used by IssueRadarPage to group issues into location hotspots.
        // We also derive a single "location" display string for quick reference.
        String spaceId    = getAsText(n, "space_id", null);
        String zoneId     = getAsText(n, "zone_id", null);
        String buildingId = getAsText(n, "building_id", null);
        String floorId    = getAsText(n, "floor_id", null);
        i.setSpaceId(spaceId);
        i.setZoneId(zoneId);
        i.setBuildingId(buildingId);
        i.setFloorId(floorId);

        // Derive display location: prefer space → zone → building → "Unassigned"
        String location = spaceId != null ? spaceId
                        : zoneId != null  ? zoneId
                        : buildingId != null ? buildingId
                        : "Unassigned";
        i.setLocation(location);

        i.setCreatedAt(getAsText(n, "date_created", getAsText(n, "created_at", null)));
        i.setUpdatedAt(getAsText(n, "updated_at", null));
        i.setRawJson(n.toString());
        i.setSyncedAt(now());
        return i;
    }

    /**
     * Normalises CxAlloy issue status strings to snake_case tokens.
     * CxAlloy real values (from API): "Issue Opened", "Issue Closed",
     * "Correction In Progress", "Gc To Verify", "Cxa To Verify",
     * "Ready For Retest", "Accepted By Owner", "Additional Information Needed"
     */
    private String normaliseIssueStatus(String raw) {
        if (raw == null || raw.isBlank()) return "open";
        String lower = raw.trim().toLowerCase().replace(" ", "_").replace("-", "_");
        switch (lower) {
            case "issue_opened":
            case "opened":
            case "open":           return "open";
            case "issue_closed":
            case "closed":
            case "done":
            case "resolved":       return "issue_closed";
            case "accepted_by_owner":
            case "accepted":       return "accepted_by_owner";
            case "correction_in_progress":
            case "in_progress":    return "correction_in_progress";
            case "gc_to_verify":
            case "gc_verify":      return "gc_to_verify";
            case "cxa_to_verify":
            case "cxa_verify":     return "cxa_to_verify";
            case "ready_for_retest":
            case "retest":         return "ready_for_retest";
            case "additional_information_needed":
            case "additional_info": return "additional_information_needed";
            default:               return lower;
        }
    }

    private void upsertIssue(Issue i) {
        if (i.getExternalId() != null) {
            issueRepository.findByExternalId(i.getExternalId()).ifPresentOrElse(existing -> {
                existing.setTitle(i.getTitle()); existing.setStatus(i.getStatus());
                existing.setPriority(i.getPriority()); existing.setAssignee(i.getAssignee());
                existing.setDueDate(i.getDueDate()); existing.setUpdatedAt(i.getUpdatedAt());
                existing.setAssetId(i.getAssetId());
                existing.setSourceType(i.getSourceType());
                existing.setSourceId(i.getSourceId());
                existing.setSpaceId(i.getSpaceId()); existing.setZoneId(i.getZoneId());
                existing.setBuildingId(i.getBuildingId()); existing.setFloorId(i.getFloorId());
                existing.setLocation(i.getLocation());
                existing.setRawJson(i.getRawJson()); existing.setSyncedAt(now());
                issueRepository.save(existing);
            }, () -> issueRepository.save(i));
        } else { issueRepository.save(i); }
    }
}
