package com.cxalloy.integration.dto;

import java.util.List;
import java.util.Map;

/**
 * Structured result returned from individual sync endpoints like /api/sync/issues.
 * Provides per-project breakdown + overall summary so you can see exactly what happened.
 */
public class IndividualSyncResult {

    private String syncType;           // "issues", "tasks", "checklists", etc.
    private int totalProjects;
    private int successCount;
    private int failedCount;
    private int skippedCount;
    private int totalRecordsSynced;
    private long durationMs;
    private List<ProjectSyncDetail> details;

    // ── Constructors ─────────────────────────────────────────────────────────

    public IndividualSyncResult() {}

    // ── Inner class ──────────────────────────────────────────────────────────

    public static class ProjectSyncDetail {
        private String projectId;
        private String projectName;
        private String status;        // SUCCESS / FAILED / SKIPPED
        private int recordsSynced;
        private long durationMs;
        private String errorMessage;

        public ProjectSyncDetail() {}

        public static ProjectSyncDetail success(String id, String name, int records, long ms) {
            ProjectSyncDetail d = new ProjectSyncDetail();
            d.projectId = id; d.projectName = name;
            d.status = "SUCCESS"; d.recordsSynced = records; d.durationMs = ms;
            return d;
        }

        public static ProjectSyncDetail failed(String id, String name, String error, long ms) {
            ProjectSyncDetail d = new ProjectSyncDetail();
            d.projectId = id; d.projectName = name;
            d.status = "FAILED"; d.recordsSynced = 0; d.errorMessage = error; d.durationMs = ms;
            return d;
        }

        public static ProjectSyncDetail skipped(String id, String name, String reason) {
            ProjectSyncDetail d = new ProjectSyncDetail();
            d.projectId = id; d.projectName = name;
            d.status = "SKIPPED"; d.recordsSynced = 0; d.errorMessage = reason;
            return d;
        }

        // Getters
        public String getProjectId()    { return projectId; }
        public String getProjectName()  { return projectName; }
        public String getStatus()       { return status; }
        public int getRecordsSynced()   { return recordsSynced; }
        public long getDurationMs()     { return durationMs; }
        public String getErrorMessage() { return errorMessage; }
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getSyncType()                   { return syncType; }
    public void setSyncType(String t)             { this.syncType = t; }

    public int getTotalProjects()                 { return totalProjects; }
    public void setTotalProjects(int n)           { this.totalProjects = n; }

    public int getSuccessCount()                  { return successCount; }
    public void setSuccessCount(int n)            { this.successCount = n; }

    public int getFailedCount()                   { return failedCount; }
    public void setFailedCount(int n)             { this.failedCount = n; }

    public int getSkippedCount()                  { return skippedCount; }
    public void setSkippedCount(int n)            { this.skippedCount = n; }

    public int getTotalRecordsSynced()            { return totalRecordsSynced; }
    public void setTotalRecordsSynced(int n)      { this.totalRecordsSynced = n; }

    public long getDurationMs()                   { return durationMs; }
    public void setDurationMs(long ms)            { this.durationMs = ms; }

    public List<ProjectSyncDetail> getDetails()   { return details; }
    public void setDetails(List<ProjectSyncDetail> d) { this.details = d; }
}
