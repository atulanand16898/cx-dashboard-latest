package com.cxalloy.integration.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_access_assignments", indexes = {
    @Index(name = "idx_project_access_project_id", columnList = "project_id"),
    @Index(name = "idx_project_access_email", columnList = "person_email")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_project_access_project_email", columnNames = {"project_id", "person_email"})
})
public class ProjectAccessAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "provider", length = 50)
    private String provider = DataProvider.CXALLOY.getKey();

    @Column(name = "person_external_id", length = 100)
    private String personExternalId;

    @Column(name = "person_email", nullable = false, length = 255)
    private String personEmail;

    @Column(name = "person_name", length = 255)
    private String personName;

    @Column(name = "assigned_by", length = 255)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @PrePersist
    public void prePersist() {
        if (assignedAt == null) {
            assignedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getPersonExternalId() {
        return personExternalId;
    }

    public void setPersonExternalId(String personExternalId) {
        this.personExternalId = personExternalId;
    }

    public String getPersonEmail() {
        return personEmail;
    }

    public void setPersonEmail(String personEmail) {
        this.personEmail = personEmail;
    }

    public String getPersonName() {
        return personName;
    }

    public void setPersonName(String personName) {
        this.personName = personName;
    }

    public String getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(String assignedBy) {
        this.assignedBy = assignedBy;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }
}
