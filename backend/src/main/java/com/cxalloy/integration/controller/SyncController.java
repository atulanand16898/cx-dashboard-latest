package com.cxalloy.integration.controller;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.IndividualSyncResult;
import com.cxalloy.integration.dto.SyncRequest;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * SyncController — exposes fine-grained sync endpoints per CxAlloy data type.
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  ENDPOINT REFERENCE                                                      │
 * │                                                                          │
 * │  POST /api/sync/all              Full sync, background, poll /status     │
 * │  GET  /api/sync/status           Background job progress                 │
 * │  GET  /api/sync/stats            DB + log stats                          │
 * │                                                                          │
 * │  ── Individual syncs (all return full per-project breakdown) ──          │
 * │  POST /api/sync/issues           Sync issues for all (or filtered) projects   │
 * │  POST /api/sync/tasks            Sync tasks                              │
 * │  POST /api/sync/checklists       Sync checklists                         │
 * │  POST /api/sync/persons          Sync persons                            │
 * │  POST /api/sync/companies        Sync companies                          │
 * │  POST /api/sync/roles            Sync roles                              │
 * │  POST /api/sync/assets           Sync all asset sub-types                │
 * │                                                                          │
 * │  ── Filter options ──                                                    │
 * │  Option A — single project:  ?projectId=abc123                          │
 * │  Option B — multiple:        Body { "projectIds": ["abc123","def456"] }  │
 * │  Option C — all projects:    No body, no param                           │
 * │                                                                          │
 * │  ── Single project deep sync ──                                          │
 * │  POST /api/sync/project/{id}     All data types for one project          │
 * │                                                                          │
 * │  ── Debug / diagnostic ──                                                │
 * │  GET  /api/sync/raw-preview      Hit CxAlloy directly, see raw JSON      │
 * │  GET  /api/sync/discover         Probe all endpoints for a project_id    │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;
    private final ProjectSyncOrchestrator orchestrator;
    private final IssueService issueService;
    private final CxTaskService taskService;
    private final ChecklistService checklistService;
    private final PersonService personService;
    private final CompanyService companyService;
    private final RoleService roleService;
    private final AssetService assetService;
    private final EquipmentService equipmentService;
    private final FileStorageService fileStorageService;
    private final CxAlloyApiClient apiClient;
    private final ProjectService projectService;

    public SyncController(SyncService syncService,
                          ProjectSyncOrchestrator orchestrator,
                          IssueService issueService,
                          CxTaskService taskService,
                          ChecklistService checklistService,
                          PersonService personService,
                          CompanyService companyService,
                          RoleService roleService,
                          AssetService assetService,
                          EquipmentService equipmentService,
                          FileStorageService fileStorageService,
                          CxAlloyApiClient apiClient,
                          ProjectService projectService) {
        this.syncService      = syncService;
        this.orchestrator     = orchestrator;
        this.issueService     = issueService;
        this.taskService      = taskService;
        this.checklistService = checklistService;
        this.personService    = personService;
        this.companyService   = companyService;
        this.roleService      = roleService;
        this.assetService     = assetService;
        this.equipmentService = equipmentService;
        this.fileStorageService = fileStorageService;
        this.apiClient        = apiClient;
        this.projectService   = projectService;
    }

    // ── Full / background sync ────────────────────────────────────────────────

    /**
     * POST /api/sync/all
     * Launches background sync across ALL 525 projects x all endpoints.
     * Returns 202 immediately. Poll GET /api/sync/status for progress.
     */
    @PostMapping("/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncAll() {
        Map<String, Object> result = syncService.startAsyncSyncAll();
        boolean started = Boolean.TRUE.equals(result.get("jobStarted"));
        int code = started ? 202 : 409;
        return ResponseEntity.status(code).body(ApiResponse.success(result,
            started ? "Sync job started — poll GET /api/sync/status for progress"
                    : "Sync already running"));
    }

    /** GET /api/sync/status — poll progress of background sync */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncStatus() {
        return ResponseEntity.ok(ApiResponse.success(syncService.getSyncStatus(), "Sync job status"));
    }

    /** GET /api/sync/stats */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        return ResponseEntity.ok(ApiResponse.success(syncService.getSyncStats()));
    }

    // ── Individual sync endpoints ─────────────────────────────────────────────

    /**
     * POST /api/sync/issues
     *
     * Syncs issues only. Much faster than /sync/all.
     * Processes in batches of 10 with 200ms inter-batch delay.
     *
     * Postman:
     *   All projects:  POST http://localhost:8080/api/sync/issues
     *   One project:   POST http://localhost:8080/api/sync/issues?projectId=abc123
     *   Multiple:      POST http://localhost:8080/api/sync/issues
     *                  Body (raw JSON): { "projectIds": ["abc123", "def456"] }
     */
    @PostMapping("/issues")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncIssues(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "issues", ids,
            pid -> issueService.syncAllIssues(pid));
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("issues", result)));
    }

    /**
     * POST /api/sync/tasks
     *
     * Postman:
     *   All projects:  POST http://localhost:8080/api/sync/tasks
     *   One project:   POST http://localhost:8080/api/sync/tasks?projectId=abc123
     */
    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncTasks(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "tasks", ids,
            pid -> taskService.syncTasks(pid));
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("tasks", result)));
    }

    /**
     * POST /api/sync/checklists
     *
     * Postman:
     *   All projects:  POST http://localhost:8080/api/sync/checklists
     *   One project:   POST http://localhost:8080/api/sync/checklists?projectId=abc123
     */
    @PostMapping("/checklists")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncChecklists(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "checklists", ids,
            pid -> checklistService.syncChecklists(pid));
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("checklists", result)));
    }

    /**
     * POST /api/sync/persons
     *
     * Postman:
     *   All projects:  POST http://localhost:8080/api/sync/persons
     *   One project:   POST http://localhost:8080/api/sync/persons?projectId=abc123
     */
    @PostMapping("/persons")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncPersons(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "persons", ids,
            pid -> personService.syncPersons(pid));
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("persons", result)));
    }

    /**
     * POST /api/sync/companies
     *
     * Postman:
     *   All projects:  POST http://localhost:8080/api/sync/companies
     *   One project:   POST http://localhost:8080/api/sync/companies?projectId=abc123
     */
    @PostMapping("/companies")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncCompanies(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "companies", ids,
            pid -> companyService.syncCompanies(pid));
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("companies", result)));
    }

    /**
     * POST /api/sync/roles
     *
     * Postman:
     *   All projects:  POST http://localhost:8080/api/sync/roles
     *   One project:   POST http://localhost:8080/api/sync/roles?projectId=abc123
     */
    @PostMapping("/roles")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncRoles(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "roles", ids,
            pid -> roleService.syncRoles(pid));
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("roles", result)));
    }

    /**
     * POST /api/sync/assets
     *
     * Syncs all asset sub-types: campus, building, equipment, floor, space, system, zone.
     *
     * Postman:
     *   All projects:  POST http://localhost:8080/api/sync/assets
     *   One project:   POST http://localhost:8080/api/sync/assets?projectId=abc123
     */
    @PostMapping("/assets")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncAssets(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "assets", ids,
            pid -> {
                var assetResults = assetService.syncAllAssets(pid);
                int total   = assetResults.stream().mapToInt(r -> r.getRecordsSynced()).sum();
                long dur    = assetResults.stream().mapToLong(r -> r.getDurationMs()).sum();
                long failed = assetResults.stream().filter(r -> "FAILED".equals(r.getStatus())).count();
                String st   = failed == 0 ? "SUCCESS" : (total > 0 ? "PARTIAL" : "FAILED");
                return new com.cxalloy.integration.dto.SyncResult(
                    "/assets", st, total,
                    "Synced " + total + " asset records across " + assetResults.size() + " asset types", dur);
            });
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("assets", result)));
    }

    /**
     * POST /api/sync/equipment
     *
     * Syncs CxAlloy GET /equipment (paginated, 500/page) for individual equipment records.
     * This is separate from /assets which groups equipment under the broader asset umbrella.
     *
     * Postman:
     *   All projects:  POST http://localhost:8080/api/sync/equipment
     *   One project:   POST http://localhost:8080/api/sync/equipment?projectId=abc123
     */
    @PostMapping("/equipment")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncEquipment(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "equipment", ids,
            pid -> equipmentService.syncEquipment(pid));
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("equipment", result)));
    }

    /**
     * POST /api/sync/files
     *
     * Syncs file attachment metadata for a project via the FileStorageService.
     * Calls FileStorageService.syncFilesForProject() which fetches checklist file records.
     *
     * Postman:
     *   One project:   POST http://localhost:8080/api/sync/files?projectId=abc123
     */
    @PostMapping("/files")
    public ResponseEntity<ApiResponse<IndividualSyncResult>> syncFiles(
            @RequestBody(required = false) SyncRequest request,
            @RequestParam(required = false) String projectId) {

        List<String> ids = resolveIds(request, projectId);
        IndividualSyncResult result = orchestrator.runSync(
            "files", ids,
            pid -> {
                // syncFilesForProject runs in its own REQUIRES_NEW transaction and
                // returns the count of files synced. We wrap it in a proper SyncResult.
                long t = System.currentTimeMillis();
                int count = fileStorageService.syncFilesForProject(pid);
                long dur = System.currentTimeMillis() - t;
                return new com.cxalloy.integration.dto.SyncResult(
                    "/file", "SUCCESS", count,
                    "Synced " + count + " file records for project " + pid, dur);
            });
        return ResponseEntity.ok(ApiResponse.success(result, buildSummary("files", result)));
    }

    // ── Per-project deep sync ─────────────────────────────────────────────────

    /**
     * POST /api/sync/project/{id}
     *
     * Deep sync (all data types) for ONE project synchronously.
     * Accepts CxAlloy externalId or DB numeric id.
     *
     * Postman: POST http://localhost:8080/api/sync/project/abc123
     */
    @PostMapping("/project/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncProject(@PathVariable String id) {
        String externalId = resolveToExternalId(id);
        if (externalId == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(
                "Project not found for id='" + id + "'. " +
                "Run POST /api/projects/sync first, then GET /api/projects to find valid IDs."));
        }
        Map<String, Object> result = syncService.syncProject(externalId);
        String status = (String) result.get("overallStatus");
        int code = "SUCCESS".equals(status) ? 200 : 207;
        return ResponseEntity.status(code).body(ApiResponse.success(result,
            "Project sync completed [externalId=" + externalId + "]: " + status));
    }

    // ── Diagnostic endpoints ──────────────────────────────────────────────────

    /**
     * GET /api/sync/raw-preview
     *
     * Calls CxAlloy directly and returns raw JSON. No DB write.
     * Use to inspect exactly what CxAlloy returns for any endpoint + project.
     *
     * Postman examples:
     *   GET http://localhost:8080/api/sync/raw-preview?endpoint=/project
     *   GET http://localhost:8080/api/sync/raw-preview?endpoint=/issue&project_id=abc123
     *   GET http://localhost:8080/api/sync/raw-preview?endpoint=/task&project_id=abc123
     *   GET http://localhost:8080/api/sync/raw-preview?endpoint=/checklist&project_id=abc123
     *   GET http://localhost:8080/api/sync/raw-preview?endpoint=/person&project_id=abc123
     *   GET http://localhost:8080/api/sync/raw-preview?endpoint=/building&project_id=abc123
     */
    @GetMapping("/raw-preview")
    public ResponseEntity<ApiResponse<String>> rawPreview(
            @RequestParam String endpoint,
            @RequestParam(required = false) String project_id) {
        try {
            String url = (project_id != null && !project_id.isBlank())
                ? endpoint + "?project_id=" + project_id
                : endpoint;
            String raw = apiClient.get(url);
            return ResponseEntity.ok(ApiResponse.success(raw, "Raw CxAlloy response for: " + url));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiResponse.error(
                "CxAlloy API call failed for [" + endpoint + "]: " + e.getMessage()));
        }
    }

    /**
     * GET /api/sync/discover
     *
     * Probes all known CxAlloy endpoint names for a project_id.
     * Reports which ones succeed vs fail.
     *
     * Postman: GET http://localhost:8080/api/sync/discover?project_id=abc123
     */
    @GetMapping("/discover")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> discoverEndpoints(
            @RequestParam String project_id) {

        List<String> candidates = Arrays.asList(
            "/issue", "/deficiency", "/punchlist", "/punch", "/observation",
            "/task", "/action", "/actionitem", "/workorder",
            "/checklist", "/form", "/inspection", "/template",
            "/person", "/contact", "/user", "/member",
            "/company", "/firm", "/organization", "/contractor",
            "/role", "/trade",
            "/equipment", "/asset", "/campus", "/building",
            "/floor", "/space", "/system", "/zone"
        );

        List<Map<String, String>> results = new ArrayList<>();
        for (String ep : candidates) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("endpoint", ep);
            try {
                String raw = apiClient.get(ep + "?project_id=" + project_id);
                String preview = raw != null && raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
                row.put("status", "SUCCESS");
                row.put("preview", preview);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("{")) msg = msg.substring(msg.indexOf("{"));
                row.put("status", "FAILED");
                row.put("error", msg);
            }
            results.add(row);
        }
        return ResponseEntity.ok(ApiResponse.success(results,
            "Endpoint discovery for project_id=" + project_id));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Merges ?projectId query param and SyncRequest body into a single list.
     * Query param wins for single-project calls (simpler Postman usage).
     * Returns null if no filter specified (= sync all projects).
     */
    private List<String> resolveIds(SyncRequest request, String singleProjectId) {
        if (singleProjectId != null && !singleProjectId.isBlank()) {
            return List.of(singleProjectId.trim());
        }
        if (request != null && request.hasFilter()) {
            return request.getProjectIds();
        }
        return null; // null = all projects
    }

    /** Resolves path variable to CxAlloy externalId (accepts DB id or externalId). */
    private String resolveToExternalId(String id) {
        var byExternal = projectService.getByExternalId(id);
        if (byExternal.isPresent()) return byExternal.get().getExternalId();
        try {
            long dbId = Long.parseLong(id);
            return projectService.getById(dbId).map(Project::getExternalId).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Builds a readable summary message for the response envelope. */
    private String buildSummary(String type, IndividualSyncResult r) {
        return String.format(
            "%s sync complete — %d/%d projects succeeded, %d skipped, %d records synced, took %.1fs",
            type, r.getSuccessCount(), r.getTotalProjects(),
            r.getSkippedCount(), r.getTotalRecordsSynced(), r.getDurationMs() / 1000.0);
    }
}
