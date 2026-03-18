package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cxalloy_roles")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "external_id") private String externalId;
    @Column(name = "project_id") private String projectId;
    @Column(name = "name") private String name;
    @Column(name = "abbreviation") private String abbreviation;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @Column(name = "created_at") private String createdAt;
    @Column(name = "raw_json", columnDefinition = "TEXT") private String rawJson;
    @Column(name = "synced_at") private LocalDateTime syncedAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getExternalId() { return externalId; } public void setExternalId(String v) { this.externalId = v; }
    public String getProjectId() { return projectId; } public void setProjectId(String v) { this.projectId = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public String getAbbreviation() { return abbreviation; } public void setAbbreviation(String v) { this.abbreviation = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public String getCreatedAt() { return createdAt; } public void setCreatedAt(String v) { this.createdAt = v; }
    public String getRawJson() { return rawJson; } public void setRawJson(String v) { this.rawJson = v; }
    public LocalDateTime getSyncedAt() { return syncedAt; } public void setSyncedAt(LocalDateTime v) { this.syncedAt = v; }
}
