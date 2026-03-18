package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cxalloy_projects", indexes = {
    @Index(name = "idx_projects_external_id", columnList = "external_id")
})
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // CxAlloy uses "project_id" as the unique identifier (not "id")
    @Column(name = "external_id", unique = true)
    private String externalId;

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "name")
    private String name;

    @Column(name = "number")
    private String number;

    @Column(name = "status")
    private String status;

    @Column(name = "client")
    private String client;

    @Column(name = "building_owner")
    private String buildingOwner;

    @Column(name = "location")
    private String location;

    @Column(name = "size")
    private String size;

    @Column(name = "cost")
    private String cost;

    @Column(name = "phase")
    private String phase;

    @Column(name = "timezone")
    private String timezone;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner")
    private String owner;

    // Stores the raw date string from CxAlloy (e.g. "02/26/2026")
    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    @Column(name = "is_upgraded")
    private Boolean isUpgraded;

    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    public Project() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }

    public String getBuildingOwner() { return buildingOwner; }
    public void setBuildingOwner(String buildingOwner) { this.buildingOwner = buildingOwner; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }

    public String getCost() { return cost; }
    public void setCost(String cost) { this.cost = cost; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public Boolean getIsUpgraded() { return isUpgraded; }
    public void setIsUpgraded(Boolean isUpgraded) { this.isUpgraded = isUpgraded; }

    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }

    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }
}
