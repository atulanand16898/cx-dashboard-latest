package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalDate;

@Entity
@Table(name = "cxalloy_checklists", indexes = {
    @Index(name = "idx_checklists_external_id", columnList = "external_id"),
    @Index(name = "idx_checklists_project_id",  columnList = "project_id"),
    @Index(name = "idx_checklists_status",       columnList = "status"),
    @Index(name = "idx_checklists_tag_level",    columnList = "tag_level"),
    @Index(name = "idx_checklists_provider",     columnList = "provider"),
    @Index(name = "idx_checklists_source_key",   columnList = "source_key"),
})
public class Checklist {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id") private String externalId;
    @Column(name = "provider") private String provider = DataProvider.CXALLOY.getKey();
    @Column(name = "source_key", unique = true) private String sourceKey;
    @Column(name = "project_id") private String projectId;
    @Column(name = "name") private String name;
    @Column(name = "description", columnDefinition = "TEXT") private String description;

    /** Normalised status: not_started / in_progress / near_complete / finished / on_hold / cancelled */
    @Column(name = "status") private String status;

    /**
     * checklistType — the full type string from CxAlloy, e.g.
     * "Level-2 YELLOW Tag QA/QC/IVC", "Level-1 RED Tag FAT".
     * This IS the primary level/tag identifier in CxAlloy.
     */
    @Column(name = "checklist_type") private String checklistType;

    /**
     * tagLevel — normalised short level token extracted from checklistType.
     * Values: "red", "yellow", "green", "blue", "white".
     * Populated during sync via normTagLevel().
     */
    @Column(name = "tag_level") private String tagLevel;

    /** Location hierarchy from CxAlloy API */
    @Column(name = "space_id")    private String spaceId;
    @Column(name = "building_id") private String buildingId;
    @Column(name = "system_id")   private String systemId;
    @Column(name = "zone_id")     private String zoneId;
    @Column(name = "floor_id")    private String floorId;

    @Column(name = "asset_id")    private String assetId;
    @Column(name = "assigned_to") private String assignedTo;
    @Column(name = "due_date")    private String dueDate;
    @Column(name = "completed_date") private String completedDate;
    @Column(name = "created_at")  private String createdAt;
    @Column(name = "updated_at")  private String updatedAt;
    @Column(name = "raw_json", columnDefinition = "TEXT") private String rawJson;
    @Column(name = "synced_at")   private LocalDateTime syncedAt;
    @Transient private LocalDate latestOpenDate;
    @Transient private LocalDate latestInProgressDate;
    @Transient private LocalDate latestFinishedDate;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getExternalId() { return externalId; } public void setExternalId(String v) { this.externalId = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { this.provider = v; }
    public String getSourceKey() { return sourceKey; } public void setSourceKey(String v) { this.sourceKey = v; }
    public String getProjectId() { return projectId; } public void setProjectId(String v) { this.projectId = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getChecklistType() { return checklistType; } public void setChecklistType(String v) { this.checklistType = v; }
    public String getTagLevel() { return tagLevel; } public void setTagLevel(String v) { this.tagLevel = v; }
    public String getSpaceId() { return spaceId; } public void setSpaceId(String v) { this.spaceId = v; }
    public String getBuildingId() { return buildingId; } public void setBuildingId(String v) { this.buildingId = v; }
    public String getSystemId() { return systemId; } public void setSystemId(String v) { this.systemId = v; }
    public String getZoneId() { return zoneId; } public void setZoneId(String v) { this.zoneId = v; }
    public String getFloorId() { return floorId; } public void setFloorId(String v) { this.floorId = v; }
    public String getAssetId() { return assetId; } public void setAssetId(String v) { this.assetId = v; }
    public String getAssignedTo() { return assignedTo; } public void setAssignedTo(String v) { this.assignedTo = v; }
    public String getDueDate() { return dueDate; } public void setDueDate(String v) { this.dueDate = v; }
    public String getCompletedDate() { return completedDate; } public void setCompletedDate(String v) { this.completedDate = v; }
    @Transient
    public String getActualFinishDate() { return completedDate; }
    public void setActualFinishDate(String v) { this.completedDate = v; }
    public String getCreatedAt() { return createdAt; } public void setCreatedAt(String v) { this.createdAt = v; }
    public String getUpdatedAt() { return updatedAt; } public void setUpdatedAt(String v) { this.updatedAt = v; }
    public String getRawJson() { return rawJson; } public void setRawJson(String v) { this.rawJson = v; }
    public LocalDateTime getSyncedAt() { return syncedAt; } public void setSyncedAt(LocalDateTime v) { this.syncedAt = v; }
    public LocalDate getLatestOpenDate() { return latestOpenDate; } public void setLatestOpenDate(LocalDate v) { this.latestOpenDate = v; }
    public LocalDate getLatestInProgressDate() { return latestInProgressDate; } public void setLatestInProgressDate(LocalDate v) { this.latestInProgressDate = v; }
    public LocalDate getLatestFinishedDate() { return latestFinishedDate; } public void setLatestFinishedDate(LocalDate v) { this.latestFinishedDate = v; }
}
