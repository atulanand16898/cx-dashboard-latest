package com.cxalloy.integration.dto;

import java.time.LocalDateTime;

public class SyncResult {

    private String endpoint;
    private String status;
    private int recordsSynced;
    private String message;
    private LocalDateTime syncedAt;
    private long durationMs;

    public SyncResult() {
        this.syncedAt = LocalDateTime.now();
    }

    public SyncResult(String endpoint, String status, int records, String message, long durationMs) {
        this.endpoint = endpoint;
        this.status = status;
        this.recordsSynced = records;
        this.message = message;
        this.syncedAt = LocalDateTime.now();
        this.durationMs = durationMs;
    }

    // Getters and Setters
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRecordsSynced() { return recordsSynced; }
    public void setRecordsSynced(int recordsSynced) { this.recordsSynced = recordsSynced; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
