package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cxalloy_tasks", indexes = {
    @Index(name = "idx_tasks_external_id", columnList = "external_id"),
    @Index(name = "idx_tasks_project_id", columnList = "project_id"),
    @Index(name = "idx_tasks_provider", columnList = "provider"),
    @Index(name = "idx_tasks_source_key", columnList = "source_key")
})
public class CxTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "external_id") private String externalId;
    @Column(name = "provider") private String provider = DataProvider.CXALLOY.getKey();
    @Column(name = "source_key", unique = true) private String sourceKey;
    @Column(name = "project_id") private String projectId;
    @Column(name = "title") private String title;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @Column(name = "status") private String status;
    @Column(name = "priority") private String priority;
    @Column(name = "assigned_to") private String assignedTo;
    @Column(name = "due_date") private String dueDate;
    @Column(name = "completed_date") private String completedDate;
    @Column(name = "issue_id") private String issueId;
    @Column(name = "created_at") private String createdAt;
    @Column(name = "updated_at") private String updatedAt;
    @Column(name = "raw_json", columnDefinition = "TEXT") private String rawJson;
    @Column(name = "synced_at") private LocalDateTime syncedAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getExternalId() { return externalId; } public void setExternalId(String v) { this.externalId = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { this.provider = v; }
    public String getSourceKey() { return sourceKey; } public void setSourceKey(String v) { this.sourceKey = v; }
    public String getProjectId() { return projectId; } public void setProjectId(String v) { this.projectId = v; }
    public String getTitle() { return title; } public void setTitle(String v) { this.title = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getPriority() { return priority; } public void setPriority(String v) { this.priority = v; }
    public String getAssignedTo() { return assignedTo; } public void setAssignedTo(String v) { this.assignedTo = v; }
    public String getDueDate() { return dueDate; } public void setDueDate(String v) { this.dueDate = v; }
    public String getCompletedDate() { return completedDate; } public void setCompletedDate(String v) { this.completedDate = v; }
    @Transient
    public String getActualFinishDate() { return completedDate; }
    public void setActualFinishDate(String v) { this.completedDate = v; }
    public String getIssueId() { return issueId; } public void setIssueId(String v) { this.issueId = v; }
    public String getCreatedAt() { return createdAt; } public void setCreatedAt(String v) { this.createdAt = v; }
    public String getUpdatedAt() { return updatedAt; } public void setUpdatedAt(String v) { this.updatedAt = v; }
    public String getRawJson() { return rawJson; } public void setRawJson(String v) { this.rawJson = v; }
    public LocalDateTime getSyncedAt() { return syncedAt; } public void setSyncedAt(LocalDateTime v) { this.syncedAt = v; }
}
