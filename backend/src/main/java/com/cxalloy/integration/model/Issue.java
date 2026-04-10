package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cxalloy_issues", indexes = {
    @Index(name = "idx_issues_external_id", columnList = "external_id"),
    @Index(name = "idx_issues_project_id",  columnList = "project_id"),
    @Index(name = "idx_issues_status",      columnList = "status"),
    @Index(name = "idx_issues_priority",    columnList = "priority"),
    @Index(name = "idx_issues_provider",    columnList = "provider"),
    @Index(name = "idx_issues_source_key",  columnList = "source_key"),
})
public class Issue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id") private String externalId;
    @Column(name = "provider") private String provider = DataProvider.CXALLOY.getKey();
    @Column(name = "source_key", unique = true) private String sourceKey;
    @Column(name = "project_id") private String projectId;
    @Column(name = "title") private String title;
    @Column(name = "description", columnDefinition = "TEXT") private String description;

    /** Normalised status: open / issue_closed / correction_in_progress / gc_to_verify /
     *  cxa_to_verify / ready_for_retest / accepted_by_owner / additional_information_needed */
    @Column(name = "status") private String status;

    /** Priority: "P1 - Critical", "P2 - High", "P3 - Medium", "P4 - Low" or raw from CxAlloy */
    @Column(name = "priority") private String priority;

    @Column(name = "assignee") private String assignee;
    @Column(name = "reporter") private String reporter;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "due_date") private String dueDate;
    @Column(name = "asset_id") private String assetId;
    @Column(name = "source_type") private String sourceType;
    @Column(name = "source_id") private String sourceId;

    /**
     * Location hierarchy from CxAlloy /issue response.
     * Used by IssueRadarPage to group issues by hotspot location.
     * CxAlloy returns: space_id, zone_id, building_id, floor_id
     * We store the display name or ID, whichever is available.
     */
    @Column(name = "space_id")    private String spaceId;
    @Column(name = "zone_id")     private String zoneId;
    @Column(name = "building_id") private String buildingId;
    @Column(name = "floor_id")    private String floorId;

    /** Derived location display string: space_id or zone_id or building_id or "Unassigned" */
    @Column(name = "location") private String location;

    @Column(name = "actual_finish_date") private String actualFinishDate;
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
    public String getAssignee() { return assignee; } public void setAssignee(String v) { this.assignee = v; }
    public String getReporter() { return reporter; } public void setReporter(String v) { this.reporter = v; }
    public String getCreatedBy() { return createdBy; } public void setCreatedBy(String v) { this.createdBy = v; }
    public String getDueDate() { return dueDate; } public void setDueDate(String v) { this.dueDate = v; }
    public String getAssetId() { return assetId; } public void setAssetId(String v) { this.assetId = v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { this.sourceType = v; }
    public String getSourceId() { return sourceId; } public void setSourceId(String v) { this.sourceId = v; }
    public String getSpaceId() { return spaceId; } public void setSpaceId(String v) { this.spaceId = v; }
    public String getZoneId() { return zoneId; } public void setZoneId(String v) { this.zoneId = v; }
    public String getBuildingId() { return buildingId; } public void setBuildingId(String v) { this.buildingId = v; }
    public String getFloorId() { return floorId; } public void setFloorId(String v) { this.floorId = v; }
    public String getLocation() { return location; } public void setLocation(String v) { this.location = v; }
    public String getActualFinishDate() { return actualFinishDate; } public void setActualFinishDate(String v) { this.actualFinishDate = v; }
    public String getCreatedAt() { return createdAt; } public void setCreatedAt(String v) { this.createdAt = v; }
    public String getUpdatedAt() { return updatedAt; } public void setUpdatedAt(String v) { this.updatedAt = v; }
    public String getRawJson() { return rawJson; } public void setRawJson(String v) { this.rawJson = v; }
    public LocalDateTime getSyncedAt() { return syncedAt; } public void setSyncedAt(LocalDateTime v) { this.syncedAt = v; }
}
