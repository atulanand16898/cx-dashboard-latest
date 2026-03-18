package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cxalloy_assets", indexes = {
    @Index(name = "idx_assets_external_id", columnList = "external_id"),
    @Index(name = "idx_assets_project_id", columnList = "project_id")
})
public class Asset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "external_id") private String externalId;
    @Column(name = "project_id") private String projectId;
    @Column(name = "asset_type") private String assetType; // campus/building/floor/space/equipment/system
    @Column(name = "name") private String name;
    @Column(name = "tag") private String tag;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @Column(name = "status") private String status;
    @Column(name = "location") private String location;
    @Column(name = "parent_id") private String parentId;
    @Column(name = "created_at") private String createdAt;
    @Column(name = "updated_at") private String updatedAt;
    @Column(name = "raw_json", columnDefinition = "TEXT") private String rawJson;
    @Column(name = "synced_at") private LocalDateTime syncedAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getExternalId() { return externalId; } public void setExternalId(String v) { this.externalId = v; }
    public String getProjectId() { return projectId; } public void setProjectId(String v) { this.projectId = v; }
    public String getAssetType() { return assetType; } public void setAssetType(String v) { this.assetType = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public String getTag() { return tag; } public void setTag(String v) { this.tag = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getLocation() { return location; } public void setLocation(String v) { this.location = v; }
    public String getParentId() { return parentId; } public void setParentId(String v) { this.parentId = v; }
    public String getCreatedAt() { return createdAt; } public void setCreatedAt(String v) { this.createdAt = v; }
    public String getUpdatedAt() { return updatedAt; } public void setUpdatedAt(String v) { this.updatedAt = v; }
    public String getRawJson() { return rawJson; } public void setRawJson(String v) { this.rawJson = v; }
    public LocalDateTime getSyncedAt() { return syncedAt; } public void setSyncedAt(LocalDateTime v) { this.syncedAt = v; }
}
