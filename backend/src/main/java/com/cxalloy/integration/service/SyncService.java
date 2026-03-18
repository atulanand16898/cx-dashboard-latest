package com.cxalloy.integration.service;

import com.cxalloy.integration.config.CxAlloyApiProperties;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.repository.ApiSyncLogRepository;
import com.cxalloy.integration.repository.RawApiResponseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

@Service
public class SyncService {

    private static final Logger logger = LoggerFactory.getLogger(SyncService.class);
    private static final List<String> TABLE_ORDER = List.of(
        "projects", "issues", "tasks", "checklists", "equipment",
        "persons", "companies", "roles", "assets", "files"
    );
    private static final Map<String, String> TABLE_LABELS = Map.of(
        "projects", "Projects",
        "issues", "Issues",
        "tasks", "Tasks",
        "checklists", "Checklists",
        "equipment", "Equipment",
        "persons", "Persons",
        "companies", "Companies",
        "roles", "Roles",
        "assets", "Assets",
        "files", "Files"
    );

    // ── Async job state ──────────────────────────────────────────────────────
    private volatile SyncJobState currentJob = null;
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    private final ProjectService projectService;
    private final IssueService issueService;
    private final PersonService personService;
    private final CompanyService companyService;
    private final RoleService roleService;
    private final AssetService assetService;
    private final EquipmentService equipmentService;
    private final ChecklistService checklistService;
    private final CxTaskService taskService;
    private final FileStorageService fileStorageService;
    private final ApiSyncLogRepository syncLogRepository;
    private final RawApiResponseRepository rawResponseRepository;
    private final CxAlloyApiProperties apiProperties;
    private final ExecutorService syncExecutor;

    public SyncService(ProjectService projectService, IssueService issueService,
                       PersonService personService, CompanyService companyService,
                       RoleService roleService, AssetService assetService,
                       EquipmentService equipmentService,
                       ChecklistService checklistService, CxTaskService taskService,
                       FileStorageService fileStorageService,
                       ApiSyncLogRepository syncLogRepository,
                       RawApiResponseRepository rawResponseRepository,
                       CxAlloyApiProperties apiProperties,
                       @Qualifier("syncExecutor") ExecutorService syncExecutor) {
        this.projectService = projectService;
        this.issueService = issueService;
        this.personService = personService;
        this.companyService = companyService;
        this.roleService = roleService;
        this.assetService = assetService;
        this.equipmentService = equipmentService;
        this.checklistService = checklistService;
        this.taskService = taskService;
        this.fileStorageService = fileStorageService;
        this.syncLogRepository = syncLogRepository;
        this.rawResponseRepository = rawResponseRepository;
        this.apiProperties = apiProperties;
        this.syncExecutor = syncExecutor;
    }

    // ── Async full sync ──────────────────────────────────────────────────────

    /**
     * Starts a full sync as a BACKGROUND job and returns immediately.
     *
     * THE KEY FIX: POST /sync/all used to run synchronously in the HTTP thread,
     * blocking for 20-60+ minutes. This caused Spring MVC thread starvation and
     * request timeouts in Postman/browser.
     *
     * Now: launches a background task and returns immediately.
     * Poll GET /api/sync/status to track progress.
     */
    @CacheEvict(value = "sync-stats", allEntries = true)
    public Map<String, Object> startAsyncSyncAll() {
        if (!syncRunning.compareAndSet(false, true)) {
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("jobStarted", false);
            busy.put("message", "A sync job is already running. Poll GET /api/sync/status for progress.");
            return busy;
        }

        SyncJobState job = new SyncJobState();
        currentJob = job;

        syncExecutor.submit(() -> {
            try {
                runFullSyncInternal(job);
            } finally {
                syncRunning.set(false);
                job.markComplete();
            }
        });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobStarted", true);
        response.put("message", "Full sync started in background. Poll GET /api/sync/status for progress.");
        response.put("pollUrl", "/api/sync/status");
        return response;
    }

    /**
     * Returns current/last sync job status. Call repeatedly to monitor progress.
     */
    public Map<String, Object> getSyncStatus() {
        if (currentJob == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("running", false);
            m.put("complete", false);
            m.put("failed", false);
            m.put("progressPercent", 0);
            m.put("currentItem", "No sync running");
            m.put("tableStatuses", TABLE_ORDER.stream()
                .map(key -> new TableProgress(key, TABLE_LABELS.getOrDefault(key, key)).toMap())
                .toList());
            m.put("status", "NO_JOB");
            m.put("message", "No sync job started yet. POST /api/sync/all to begin.");
            return m;
        }
        return currentJob.toMap();
    }

    /**
     * Blocking single-project sync. Used by POST /api/sync/project/{id}.
     */
    public Map<String, Object> syncProject(String projectId) {
        return syncProjectInternal(projectId, projectId, null);
    }

    @Cacheable(value = "sync-stats")
    public Map<String, Object> getSyncStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("recentLogs", syncLogRepository.findTop10ByOrderBySyncedAtDesc());
        stats.put("totalRawResponses", rawResponseRepository.count());
        stats.put("totalProjectsInDb", projectService.getAll().size());
        stats.put("configuredProjectId", apiProperties.getProjectId());
        return stats;
    }

    // ── Core full-sync logic (runs in background thread) ─────────────────────

    private void runFullSyncInternal(SyncJobState job) {
        long wallStart = System.currentTimeMillis();
        job.setPhase("SYNCING_PROJECTS");
        logger.info("=== BACKGROUND SYNC STEP 1: Fetching all projects ===");

        try {
            SyncResult r = projectService.syncAllProjects();
            job.setProjectsSynced(r.getRecordsSynced());
            job.markProjectsCompleted(r);
            logger.info("Projects synced: {}", r.getRecordsSynced());
        } catch (Exception e) {
            // FIX: log full stack trace, not just e.getMessage() (which can be null)
            logger.error("Projects sync FAILED — aborting full sync", e);
            job.setFailed(true);
            job.setPhase("FAILED_PROJECTS");
            job.markProjectsFailed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return;
        }

        List<Project> allProjects = projectService.getAll();
        List<Project> validProjects = allProjects.stream()
            .filter(p -> p.getExternalId() != null && !p.getExternalId().isBlank())
            .collect(Collectors.toList());

        long skipped = allProjects.size() - validProjects.size();
        if (skipped > 0) logger.warn("{} projects skipped — no externalId", skipped);

        job.setTotalProjects(validProjects.size());
        job.setPhase("SYNCING_DATA");
        logger.info("=== BACKGROUND SYNC STEP 2: Syncing data for {} projects in batches of 5 ===",
            validProjects.size());

        // BATCH PROCESSING FIX:
        // Old code submitted ALL project futures at once, then blocked on all of them.
        // With 525 projects × 7 endpoints = 3675 simultaneous HTTP calls → rate limit → avalanche timeout.
        // New code: process 5 projects at a time, wait for each batch to finish before next.
        // Max concurrent HTTP calls: 5 projects × 7 endpoints = 35 — safe and predictable.
        int batchSize = 5;

        for (int i = 0; i < validProjects.size(); i += batchSize) {
            int end = Math.min(i + batchSize, validProjects.size());
            List<Project> batch = validProjects.subList(i, end);

            logger.info("Processing batch {}/{}: projects {}-{}",
                (i / batchSize) + 1,
                (int) Math.ceil((double) validProjects.size() / batchSize),
                i + 1, end);

            List<CompletableFuture<Map<String, Object>>> futures = batch.stream()
                .map(p -> CompletableFuture.supplyAsync(
                    () -> syncProjectInternal(p.getExternalId(), p.getName(), job),
                    syncExecutor))
                .collect(Collectors.toList());

            for (CompletableFuture<Map<String, Object>> f : futures) {
                try {
                    Map<String, Object> result = f.get(10, TimeUnit.MINUTES);
                    if (!"SKIPPED".equals(result.get("status"))) {
                        job.incrementCompleted();
                        job.addRecordsSynced((int) result.getOrDefault("recordsSynced", 0));
                        if ((int) result.getOrDefault("failed", 0) > 0) job.incrementFailed();
                    }
                } catch (TimeoutException e) {
                    logger.error("Project sync timed out after 10 minutes", e);
                    job.incrementFailed();
                } catch (Exception e) {
                    // FIX: log full stack trace — was previously "future failed: null"
                    logger.error("Project sync future failed", e);
                    job.incrementFailed();
                }
            }

            // Small inter-batch delay to respect CxAlloy API rate limits
            if (end < validProjects.size()) {
                try { Thread.sleep(300); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        long wallMs = System.currentTimeMillis() - wallStart;
        logger.info("=== FULL SYNC COMPLETE — {} projects, {} records, {} failed, took {}s ===",
            job.getCompleted(), job.getRecordsSynced(), job.getFailedCount(), wallMs / 1000);
    }

    // ── Core per-project sync ────────────────────────────────────────────────

    private Map<String, Object> syncProjectInternal(String pid, String pName, SyncJobState job) {
        logger.info("--- Parallel sync starting: {} ({}) ---", pName, pid);
        long start = System.currentTimeMillis();

        CompletableFuture<SyncResult> issueFuture      = asyncSync(() -> issueService.syncAllIssues(pid),      "/issue",     "issues",     pid, pName, job);
        CompletableFuture<SyncResult> taskFuture       = asyncSync(() -> taskService.syncTasks(pid),           "/task",      "tasks",      pid, pName, job);
        CompletableFuture<SyncResult> personFuture     = asyncSync(() -> personService.syncPersons(pid),       "/person",    "persons",    pid, pName, job);
        CompletableFuture<SyncResult> companyFuture    = asyncSync(() -> companyService.syncCompanies(pid),    "/company",   "companies",  pid, pName, job);
        CompletableFuture<SyncResult> roleFuture       = asyncSync(() -> roleService.syncRoles(pid),           "/role",      "roles",      pid, pName, job);
        CompletableFuture<SyncResult> checklistFuture  = asyncSync(() -> checklistService.syncChecklists(pid), "/checklist", "checklists", pid, pName, job);
        CompletableFuture<SyncResult> equipmentFuture  = asyncSync(() -> equipmentService.syncEquipment(pid),  "/equipment", "equipment",  pid, pName, job);

        CompletableFuture<List<SyncResult>> assetFuture = CompletableFuture.supplyAsync(() -> {
            if (job != null) {
                job.markTableStarted("assets", pName);
            }
            try {
                List<SyncResult> assetResults = assetService.syncAllAssets(pid);
                if (job != null) {
                    job.markTableCompleted("assets", pName, summarizeAssetResults(assetResults));
                }
                return assetResults;
            } catch (Exception e) {
                logger.error("Assets sync failed for project {}", pid, e);
                if (job != null) {
                    job.markTableCompleted("assets", pName, new SyncResult("/assets", "FAILED", 0, e.getMessage(), 0));
                }
                return List.of(new SyncResult("/assets", "FAILED", 0, e.getMessage(), 0));
            }
        }, syncExecutor);

        // File metadata sync — runs after checklists complete to ensure records exist
        CompletableFuture<SyncResult> filesFuture = checklistFuture.thenApplyAsync(checkResult -> {
            if (job != null) {
                job.markTableStarted("files", pName);
            }
            try {
                long t = System.currentTimeMillis();
                int fileCount = fileStorageService.syncFilesForProject(pid);
                SyncResult result = new SyncResult("/files", "SUCCESS", fileCount,
                    "File metadata synced for project " + pid, System.currentTimeMillis() - t);
                if (job != null) {
                    job.markTableCompleted("files", pName, result);
                }
                return result;
            } catch (Exception e) {
                logger.error("Files sync failed for project {}", pid, e);
                SyncResult result = new SyncResult("/files", "FAILED", 0, e.getMessage(), 0);
                if (job != null) {
                    job.markTableCompleted("files", pName, result);
                }
                return result;
            }
        }, syncExecutor);

        List<SyncResult> results = new ArrayList<>();
        results.add(getResult(issueFuture,    "/issue",     pid));
        results.add(getResult(taskFuture,      "/task",      pid));
        results.add(getResult(personFuture,    "/person",    pid));
        results.add(getResult(companyFuture,   "/company",   pid));
        results.add(getResult(roleFuture,      "/role",      pid));
        results.add(getResult(checklistFuture, "/checklist", pid));
        results.add(getResult(equipmentFuture, "/equipment", pid));
        results.add(getResult(filesFuture,     "/files",     pid));
        try {
            results.addAll(assetFuture.get(5, TimeUnit.MINUTES));
        } catch (TimeoutException e) {
            logger.error("/assets timed out for project {}", pid);
            results.add(new SyncResult("/assets", "FAILED", 0, "Timed out", 0));
        } catch (Exception e) {
            logger.error("/assets future failed for project {}", pid, e);
            results.add(new SyncResult("/assets", "FAILED", 0, e.getMessage(), 0));
        }

        boolean allNotFound = results.stream().allMatch(r ->
            "FAILED".equals(r.getStatus()) &&
            r.getMessage() != null &&
            r.getMessage().contains("Project does not exist"));

        if (allNotFound) {
            logger.warn("Skipping project {} ({}) — not accessible in this account", pName, pid);
            Map<String, Object> skipped = new HashMap<>();
            skipped.put("projectId", pid);
            skipped.put("projectName", pName);
            skipped.put("status", "SKIPPED");
            skipped.put("recordsSynced", 0);
            skipped.put("failed", 0);
            return skipped;
        }

        int synced = results.stream().mapToInt(SyncResult::getRecordsSynced).sum();
        int failed = (int) results.stream().filter(r -> "FAILED".equals(r.getStatus())).count();
        long dur = System.currentTimeMillis() - start;
        logger.info("--- Project {} done in {}ms — synced: {}, failed: {} ---", pName, dur, synced, failed);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectId", pid);
        summary.put("projectName", pName);
        summary.put("recordsSynced", synced);
        summary.put("failed", failed);
        summary.put("durationMs", dur);
        summary.put("status", failed == 0 ? "SUCCESS" : (synced > 0 ? "PARTIAL" : "FAILED"));
        summary.put("details", results);
        return summary;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private interface SyncAction { SyncResult run() throws Exception; }

    private CompletableFuture<SyncResult> asyncSync(SyncAction action,
                                                    String endpoint,
                                                    String tableKey,
                                                    String pid,
                                                    String projectName,
                                                    SyncJobState job) {
        return CompletableFuture.supplyAsync(() -> {
            if (job != null) {
                job.markTableStarted(tableKey, projectName);
            }
            try {
                SyncResult result = action.run();
                if (job != null) {
                    job.markTableCompleted(tableKey, projectName, result);
                }
                return result;
            } catch (Exception e) {
                // FIX: pass 'e' as second arg so Logback prints the full stack trace
                logger.error("{} sync failed for project {}", endpoint, pid, e);
                SyncResult result = new SyncResult(endpoint, "FAILED", 0,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), 0);
                if (job != null) {
                    job.markTableCompleted(tableKey, projectName, result);
                }
                return result;
            }
        }, syncExecutor);
    }

    private SyncResult summarizeAssetResults(List<SyncResult> assetResults) {
        int records = assetResults.stream().mapToInt(SyncResult::getRecordsSynced).sum();
        long failed = assetResults.stream().filter(result -> "FAILED".equals(result.getStatus())).count();
        long duration = assetResults.stream().mapToLong(SyncResult::getDurationMs).sum();
        String status = failed == 0 ? "SUCCESS" : (records > 0 ? "PARTIAL" : "FAILED");
        return new SyncResult("/assets", status, records, "Synced asset records", duration);
    }

    private SyncResult getResult(CompletableFuture<SyncResult> future, String endpoint, String pid) {
        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            logger.error("{} timed out for project {}", endpoint, pid);
            return new SyncResult(endpoint, "FAILED", 0, "Timed out after 5 minutes", 0);
        } catch (Exception e) {
            logger.error("{} future failed for project {}", endpoint, pid, e);
            return new SyncResult(endpoint, "FAILED", 0,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), 0);
        }
    }

    // ── SyncJobState ─────────────────────────────────────────────────────────

    public static class SyncJobState {
        private volatile String phase = "STARTING";
        private volatile boolean failed = false;
        private volatile boolean complete = false;
        private final long startTime = System.currentTimeMillis();
        private volatile long endTime = -1;
        private volatile long lastUpdatedAt = startTime;
        private volatile String currentItem = "Preparing sync";

        private final AtomicInteger totalProjects = new AtomicInteger(0);
        private final AtomicInteger completed = new AtomicInteger(0);
        private final AtomicInteger failedCount = new AtomicInteger(0);
        private final AtomicInteger recordsSynced = new AtomicInteger(0);
        private final AtomicInteger projectsSynced = new AtomicInteger(0);
        private final Map<String, TableProgress> tableStatuses = new ConcurrentHashMap<>();

        SyncJobState() {
            TABLE_ORDER.forEach(key -> tableStatuses.put(key, new TableProgress(key, TABLE_LABELS.getOrDefault(key, key))));
        }

        private void touch() { this.lastUpdatedAt = System.currentTimeMillis(); }
        void setPhase(String p)         { this.phase = p; touch(); }
        void setFailed(boolean f)       { this.failed = f; touch(); }
        void setTotalProjects(int n)    { totalProjects.set(n); touch(); }
        void setProjectsSynced(int n)   { projectsSynced.set(n); touch(); }
        void incrementCompleted()       { completed.incrementAndGet(); touch(); }
        void incrementFailed()          { failedCount.incrementAndGet(); touch(); }
        void addRecordsSynced(int n)    { recordsSynced.addAndGet(n); touch(); }
        void markComplete()             {
            this.complete = true;
            this.endTime = System.currentTimeMillis();
            this.currentItem = failed ? "Sync finished with failures" : "Sync completed";
            tableStatuses.values().forEach(TableProgress::finalizeIfIdle);
            touch();
        }

        void markProjectsCompleted(SyncResult result) {
            TableProgress progress = tableStatuses.get("projects");
            progress.status = "COMPLETED";
            progress.recordsSynced += result.getRecordsSynced();
            progress.message = result.getMessage();
            currentItem = "Projects loaded";
            touch();
        }

        void markProjectsFailed(String message) {
            TableProgress progress = tableStatuses.get("projects");
            progress.status = "FAILED";
            progress.failedProjects += 1;
            progress.message = message;
            currentItem = "Project sync failed";
            touch();
        }

        void markTableStarted(String key, String projectName) {
            TableProgress progress = tableStatuses.computeIfAbsent(key, missing -> new TableProgress(missing, TABLE_LABELS.getOrDefault(missing, missing)));
            progress.runningCount += 1;
            progress.status = "RUNNING";
            progress.projectName = projectName;
            currentItem = progress.label + " • " + projectName;
            touch();
        }

        void markTableCompleted(String key, String projectName, SyncResult result) {
            TableProgress progress = tableStatuses.computeIfAbsent(key, missing -> new TableProgress(missing, TABLE_LABELS.getOrDefault(missing, missing)));
            progress.runningCount = Math.max(0, progress.runningCount - 1);
            progress.projectName = projectName;
            progress.message = result.getMessage();
            progress.recordsSynced += result.getRecordsSynced();
            if ("FAILED".equalsIgnoreCase(result.getStatus())) {
                progress.failedProjects += 1;
            } else {
                progress.completedProjects += 1;
            }
            if (progress.runningCount > 0) {
                progress.status = "RUNNING";
            } else if (progress.failedProjects > 0 && progress.completedProjects == 0) {
                progress.status = "FAILED";
            } else if (progress.failedProjects > 0) {
                progress.status = "PARTIAL";
            } else {
                progress.status = "COMPLETED";
            }
            currentItem = progress.label + " • " + projectName;
            touch();
        }

        int getCompleted()    { return completed.get(); }
        int getFailedCount()  { return failedCount.get(); }
        int getRecordsSynced(){ return recordsSynced.get(); }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("running", !complete && !failed);
            m.put("phase", phase);
            m.put("complete", complete);
            m.put("failed", failed);
            m.put("totalProjects", totalProjects.get());
            m.put("projectsCompleted", completed.get());
            m.put("projectsFailed", failedCount.get());
            m.put("recordsSynced", recordsSynced.get());
            m.put("projectsSynced", projectsSynced.get());
            m.put("currentItem", currentItem);
            m.put("startedAt", isoDate(startTime));
            m.put("lastUpdatedAt", isoDate(lastUpdatedAt));
            if (complete && endTime > 0) {
                m.put("completedAt", isoDate(endTime));
            }
            m.put("elapsedSeconds", (System.currentTimeMillis() - startTime) / 1000.0);
            if (complete && endTime > 0)
                m.put("totalDurationSeconds", (endTime - startTime) / 1000.0);
            int total = totalProjects.get();
            if (total > 0)
                m.put("progressPercent",
                    Math.round((completed.get() + failedCount.get()) * 100.0 / total));
            m.put("tableStatuses", TABLE_ORDER.stream()
                .map(key -> tableStatuses.getOrDefault(key, new TableProgress(key, TABLE_LABELS.getOrDefault(key, key))).toMap())
                .toList());
            return m;
        }

        private String isoDate(long epochMillis) {
            return java.time.Instant.ofEpochMilli(epochMillis).toString();
        }
    }

    private static class TableProgress {
        private final String key;
        private final String label;
        private volatile String status = "PENDING";
        private volatile int runningCount = 0;
        private volatile int completedProjects = 0;
        private volatile int failedProjects = 0;
        private volatile int recordsSynced = 0;
        private volatile String projectName = "";
        private volatile String message = "";

        private TableProgress(String key, String label) {
            this.key = key;
            this.label = label;
        }

        private void finalizeIfIdle() {
            if ("PENDING".equals(status) && "projects".equals(key)) {
                status = "COMPLETED";
            } else if ("RUNNING".equals(status) && runningCount == 0) {
                status = failedProjects > 0 ? (completedProjects > 0 ? "PARTIAL" : "FAILED") : "COMPLETED";
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("label", label);
            map.put("status", status);
            map.put("runningCount", runningCount);
            map.put("completedProjects", completedProjects);
            map.put("failedProjects", failedProjects);
            map.put("recordsSynced", recordsSynced);
            map.put("projectName", projectName);
            map.put("message", message);
            return map;
        }
    }
}
