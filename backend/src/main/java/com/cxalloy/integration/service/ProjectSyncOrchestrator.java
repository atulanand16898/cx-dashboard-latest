package com.cxalloy.integration.service;

import com.cxalloy.integration.dto.IndividualSyncResult;
import com.cxalloy.integration.dto.IndividualSyncResult.ProjectSyncDetail;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates individual sync operations (issues, tasks, checklists, etc.)
 * against a filtered or full list of projects.
 *
 * KEY DESIGN DECISIONS:
 *  1. Batch size = 10 projects per batch  (safe: 10 × 1 endpoint = 10 concurrent calls)
 *  2. 200ms sleep between batches         (respects CxAlloy rate limits)
 *  3. CompletableFuture with 5min timeout per project
 *  4. Full error isolation — one project failure never stops the batch
 *  5. Detailed per-project result returned for Postman debugging
 */
@Service
public class ProjectSyncOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ProjectSyncOrchestrator.class);

    // Batch size for individual sync endpoints.
    // /sync/all uses 5 (7 endpoints × 5 = 35 calls). Since individual endpoints hit only 1
    // endpoint per project, we can safely use 10 (10 × 1 = 10 concurrent calls).
    private static final int BATCH_SIZE = 10;
    private static final int BATCH_SLEEP_MS = 200;
    private static final int TIMEOUT_MINUTES = 5;

    private final ProjectService projectService;
    private final ExecutorService syncExecutor;

    public ProjectSyncOrchestrator(ProjectService projectService,
                                   @Qualifier("syncExecutor") ExecutorService syncExecutor) {
        this.projectService = projectService;
        this.syncExecutor = syncExecutor;
    }

    /**
     * Core orchestration method.
     *
     * @param syncType     label for this sync, e.g. "issues", "tasks"
     * @param projectIds   null/empty = all projects; otherwise filter to these externalIds
     * @param syncFn       the function to call per project — receives externalId, returns SyncResult
     */
    public IndividualSyncResult runSync(
            String syncType,
            List<String> projectIds,
            Function<String, SyncResult> syncFn) {

        long wallStart = System.currentTimeMillis();
        logger.info("=== Individual sync [{}] starting — filter={} ===",
            syncType, projectIds == null || projectIds.isEmpty() ? "ALL" : projectIds);

        // ── 1. Resolve project list ──────────────────────────────────────────
        List<Project> projects = resolveProjects(projectIds);
        logger.info("[{}] Resolved {} projects to sync", syncType, projects.size());

        // ── 2. Process in batches ────────────────────────────────────────────
        List<ProjectSyncDetail> details = new ArrayList<>();
        int successCount = 0, failedCount = 0, skippedCount = 0, totalRecords = 0;

        int totalBatches = (int) Math.ceil((double) projects.size() / BATCH_SIZE);

        for (int i = 0; i < projects.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, projects.size());
            List<Project> batch = projects.subList(i, end);
            int batchNum = (i / BATCH_SIZE) + 1;

            logger.info("[{}] Batch {}/{}: projects {}-{}", syncType, batchNum, totalBatches, i + 1, end);

            // Submit all projects in this batch concurrently
            List<CompletableFuture<ProjectSyncDetail>> futures = batch.stream()
                .map(p -> CompletableFuture.supplyAsync(
                    () -> syncOneProject(syncType, p, syncFn),
                    syncExecutor))
                .collect(Collectors.toList());

            // Collect results — failures are isolated, never propagate
            for (CompletableFuture<ProjectSyncDetail> future : futures) {
                try {
                    details.add(future.get(TIMEOUT_MINUTES, TimeUnit.MINUTES));
                } catch (TimeoutException e) {
                    logger.error("[{}] Project sync timed out after {}m", syncType, TIMEOUT_MINUTES);
                    // We can't know the project name here, but we still record it
                    details.add(ProjectSyncDetail.failed("unknown", "unknown",
                        "Timed out after " + TIMEOUT_MINUTES + " minutes", TIMEOUT_MINUTES * 60_000L));
                } catch (Exception e) {
                    logger.error("[{}] Future failed unexpectedly", syncType, e);
                    details.add(ProjectSyncDetail.failed("unknown", "unknown",
                        e.getMessage(), 0));
                }
            }

            // Rate-limit respect: sleep between batches (not after last batch)
            if (end < projects.size()) {
                try { Thread.sleep(BATCH_SLEEP_MS); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warn("[{}] Sync interrupted at batch {}", syncType, batchNum);
                    break;
                }
            }
        }

        // ── 3. Aggregate stats ───────────────────────────────────────────────
        for (ProjectSyncDetail d : details) {
            switch (d.getStatus()) {
                case "SUCCESS" -> { successCount++; totalRecords += d.getRecordsSynced(); }
                case "FAILED"  -> failedCount++;
                case "SKIPPED" -> skippedCount++;
            }
        }

        long wallMs = System.currentTimeMillis() - wallStart;
        logger.info("=== [{}] sync complete — success={}, failed={}, skipped={}, records={}, took={}ms ===",
            syncType, successCount, failedCount, skippedCount, totalRecords, wallMs);

        IndividualSyncResult result = new IndividualSyncResult();
        result.setSyncType(syncType);
        result.setTotalProjects(projects.size());
        result.setSuccessCount(successCount);
        result.setFailedCount(failedCount);
        result.setSkippedCount(skippedCount);
        result.setTotalRecordsSynced(totalRecords);
        result.setDurationMs(wallMs);
        result.setDetails(details);
        return result;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the list of projects to sync.
     * - Empty/null projectIds → all projects with valid externalIds
     * - Provided projectIds → look up each one, warn on misses
     */
    private List<Project> resolveProjects(List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return projectService.getAll().stream()
                .filter(p -> p.getExternalId() != null && !p.getExternalId().isBlank())
                .collect(Collectors.toList());
        }

        List<Project> resolved = new ArrayList<>();
        for (String id : projectIds) {
            projectService.getByExternalId(id).ifPresentOrElse(
                resolved::add,
                () -> logger.warn("No project found for externalId='{}' — skipping", id)
            );
        }
        return resolved;
    }

    /**
     * Runs the sync function for a single project, fully isolated.
     * Never throws — returns a ProjectSyncDetail with FAILED status on error.
     */
    private ProjectSyncDetail syncOneProject(
            String syncType,
            Project project,
            Function<String, SyncResult> syncFn) {

        String pid  = project.getExternalId();
        String name = project.getName() != null ? project.getName() : pid;
        long start  = System.currentTimeMillis();

        try {
            SyncResult result = syncFn.apply(pid);
            long dur = System.currentTimeMillis() - start;

            if ("FAILED".equals(result.getStatus())) {
                logger.warn("[{}] Project {} failed: {}", syncType, name, result.getMessage());
                return ProjectSyncDetail.failed(pid, name, result.getMessage(), dur);
            }

            // Detect "project not accessible" from CxAlloy (common for cross-account projects)
            String msg = result.getMessage();
            if (msg != null && msg.contains("Project does not exist")) {
                logger.debug("[{}] Project {} not accessible in this account — skipped", syncType, name);
                return ProjectSyncDetail.skipped(pid, name, "Not accessible in this CxAlloy account");
            }

            logger.debug("[{}] Project {} synced {} records in {}ms", syncType, name, result.getRecordsSynced(), dur);
            return ProjectSyncDetail.success(pid, name, result.getRecordsSynced(), dur);

        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            // Project-not-accessible returns as an exception from the API client
            if (errMsg.contains("Project does not exist") || errMsg.contains("not found")) {
                return ProjectSyncDetail.skipped(pid, name, "Not accessible in this CxAlloy account");
            }

            logger.error("[{}] Exception syncing project {}: {}", syncType, name, errMsg);
            return ProjectSyncDetail.failed(pid, name, errMsg, dur);
        }
    }
}
