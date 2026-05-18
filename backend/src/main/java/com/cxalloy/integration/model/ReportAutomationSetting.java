package com.cxalloy.integration.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "report_automation_settings", indexes = {
    @Index(name = "idx_report_automation_project_id", columnList = "project_id"),
    @Index(name = "idx_report_automation_provider", columnList = "provider"),
    @Index(name = "idx_report_automation_enabled", columnList = "enabled")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_report_automation_project_provider", columnNames = {"project_id", "provider"})
})
public class ReportAutomationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider = DataProvider.CXALLOY.getKey();

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "schedule_time", length = 10)
    private String scheduleTime;

    @Column(name = "export_folder_path", columnDefinition = "TEXT")
    private String exportFolderPath;

    @Column(name = "export_format", length = 20)
    private String exportFormat;

    @Column(name = "sync_before_export", nullable = false)
    private boolean syncBeforeExport = true;

    @Column(name = "request_json", columnDefinition = "TEXT")
    private String requestJson;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status", length = 50)
    private String lastRunStatus;

    @Column(name = "last_run_message", columnDefinition = "TEXT")
    private String lastRunMessage;

    @Column(name = "last_output_path", columnDefinition = "TEXT")
    private String lastOutputPath;

    @Column(name = "last_generated_report_id")
    private Long lastGeneratedReportId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getScheduleTime() { return scheduleTime; }
    public void setScheduleTime(String scheduleTime) { this.scheduleTime = scheduleTime; }
    public String getExportFolderPath() { return exportFolderPath; }
    public void setExportFolderPath(String exportFolderPath) { this.exportFolderPath = exportFolderPath; }
    public String getExportFormat() { return exportFormat; }
    public void setExportFormat(String exportFormat) { this.exportFormat = exportFormat; }
    public boolean isSyncBeforeExport() { return syncBeforeExport; }
    public void setSyncBeforeExport(boolean syncBeforeExport) { this.syncBeforeExport = syncBeforeExport; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }
    public String getLastRunMessage() { return lastRunMessage; }
    public void setLastRunMessage(String lastRunMessage) { this.lastRunMessage = lastRunMessage; }
    public String getLastOutputPath() { return lastOutputPath; }
    public void setLastOutputPath(String lastOutputPath) { this.lastOutputPath = lastOutputPath; }
    public Long getLastGeneratedReportId() { return lastGeneratedReportId; }
    public void setLastGeneratedReportId(Long lastGeneratedReportId) { this.lastGeneratedReportId = lastGeneratedReportId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
