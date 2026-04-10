package com.cxalloy.integration.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_visibility_preferences", indexes = {
    @Index(name = "idx_project_visibility_username", columnList = "username"),
    @Index(name = "idx_project_visibility_project_id", columnList = "project_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_project_visibility_username_project", columnNames = {"username", "project_id"})
})
public class ProjectVisibilityPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "provider", length = 50)
    private String provider = DataProvider.CXALLOY.getKey();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
