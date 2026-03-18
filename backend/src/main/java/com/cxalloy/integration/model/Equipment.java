package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Equipment entity — maps to CxAlloy GET /equipment endpoint.
 *
 * Confirmed fields from CxAlloy OpenAPI spec (api.cxalloy.com/openapi.yaml):
 *   equipment_id, project_id, name, tag, description,
 *   equipment_status_id, type_id (equipmenttype), discipline_id,
 *   space_id, building_id, floor_id,
 *   date_created, updated_at
 *
 * The `rawJson` column stores the full API payload so no data is lost
 * even as CxAlloy adds new fields (forward-compatible per their versioning policy).
 */
@Entity
@Table(name = "cxalloy_equipment", indexes = {
    @Index(name = "idx_equipment_external_id",  columnList = "external_id"),
    @Index(name = "idx_equipment_project_id",   columnList = "project_id"),
    @Index(name = "idx_equipment_status",        columnList = "status"),
    @Index(name = "idx_equipment_asset_type",   columnList = "equipment_type")
})
public class Equipment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CxAlloy equipment_id */
    @Column(name = "external_id")
    private String externalId;

    @Column(name = "project_id")
    private String projectId;

    /** Human-readable equipment name */
    @Column(name = "name")
    private String name;

    /** Equipment tag / asset tag (e.g. "AHU-01") */
    @Column(name = "tag")
    private String tag;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Normalised status string. CxAlloy returns an equipmentstatus_id integer;
     * we resolve it to its label during sync (or store the ID as fallback).
     */
    @Column(name = "status")
    private String status;

    /** Equipment type name (from equipmenttype, e.g. "Air Handling Unit") */
    @Column(name = "equipment_type")
    private String equipmentType;

    /** Discipline / trade (e.g. "Mechanical", "Electrical") */
    @Column(name = "discipline")
    private String discipline;

    /** Parent building name / id */
    @Column(name = "building_id")
    private String buildingId;

    /** First linked equipment system ID from CxAlloy include=systems */
    @Column(name = "system_id")
    private String systemId;

    /** First linked equipment system name from CxAlloy include=systems */
    @Column(name = "system_name")
    private String systemName;

    /** Floor / level */
    @Column(name = "floor_id")
    private String floorId;

    /** Space assigned to */
    @Column(name = "space_id")
    private String spaceId;

    /** Number of checklists linked (from rawJson if available) */
    @Column(name = "checklist_count")
    private Integer checklistCount;

    /** Number of open issues linked */
    @Column(name = "issue_count")
    private Integer issueCount;

    /** Number of linked tests */
    @Column(name = "test_count")
    private Integer testCount;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    /** Full API payload — forward-compatible storage */
    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    // ── Getters / setters ────────────────────────────────────────────────────
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getExternalId() { return externalId; } public void setExternalId(String v) { this.externalId = v; }
    public String getProjectId() { return projectId; } public void setProjectId(String v) { this.projectId = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public String getTag() { return tag; } public void setTag(String v) { this.tag = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getEquipmentType() { return equipmentType; } public void setEquipmentType(String v) { this.equipmentType = v; }
    public String getDiscipline() { return discipline; } public void setDiscipline(String v) { this.discipline = v; }
    public String getBuildingId() { return buildingId; } public void setBuildingId(String v) { this.buildingId = v; }
    public String getSystemId() { return systemId; } public void setSystemId(String v) { this.systemId = v; }
    public String getSystemName() { return systemName; } public void setSystemName(String v) { this.systemName = v; }
    public String getFloorId() { return floorId; } public void setFloorId(String v) { this.floorId = v; }
    public String getSpaceId() { return spaceId; } public void setSpaceId(String v) { this.spaceId = v; }
    public Integer getChecklistCount() { return checklistCount; } public void setChecklistCount(Integer v) { this.checklistCount = v; }
    public Integer getIssueCount() { return issueCount; } public void setIssueCount(Integer v) { this.issueCount = v; }
    public Integer getTestCount() { return testCount; } public void setTestCount(Integer v) { this.testCount = v; }
    public String getCreatedAt() { return createdAt; } public void setCreatedAt(String v) { this.createdAt = v; }
    public String getUpdatedAt() { return updatedAt; } public void setUpdatedAt(String v) { this.updatedAt = v; }
    public String getRawJson() { return rawJson; } public void setRawJson(String v) { this.rawJson = v; }
    public LocalDateTime getSyncedAt() { return syncedAt; } public void setSyncedAt(LocalDateTime v) { this.syncedAt = v; }
}
