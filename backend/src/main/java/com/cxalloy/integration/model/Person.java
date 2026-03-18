package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cxalloy_persons", indexes = {
    @Index(name = "idx_persons_external_id", columnList = "external_id")
})
public class Person {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "external_id") private String externalId;
    @Column(name = "project_id") private String projectId;
    @Column(name = "first_name") private String firstName;
    @Column(name = "last_name") private String lastName;
    @Column(name = "email") private String email;
    @Column(name = "company") private String company;
    @Column(name = "role") private String role;
    @Column(name = "status") private String status;
    @Column(name = "created_at") private String createdAt;
    @Column(name = "raw_json", columnDefinition = "TEXT") private String rawJson;
    @Column(name = "synced_at") private LocalDateTime syncedAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getExternalId() { return externalId; } public void setExternalId(String v) { this.externalId = v; }
    public String getProjectId() { return projectId; } public void setProjectId(String v) { this.projectId = v; }
    public String getFirstName() { return firstName; } public void setFirstName(String v) { this.firstName = v; }
    public String getLastName() { return lastName; } public void setLastName(String v) { this.lastName = v; }
    public String getEmail() { return email; } public void setEmail(String v) { this.email = v; }
    public String getCompany() { return company; } public void setCompany(String v) { this.company = v; }
    public String getRole() { return role; } public void setRole(String v) { this.role = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getCreatedAt() { return createdAt; } public void setCreatedAt(String v) { this.createdAt = v; }
    public String getRawJson() { return rawJson; } public void setRawJson(String v) { this.rawJson = v; }
    public LocalDateTime getSyncedAt() { return syncedAt; } public void setSyncedAt(LocalDateTime v) { this.syncedAt = v; }
}
