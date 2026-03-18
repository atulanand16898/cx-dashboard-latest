package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persists file records fetched from the CxAlloy /file endpoint.
 *
 * Fields sourced from the CxAlloy file object:
 *   id, name, file_size (bytes), mime_type, created_date,
 *   asset_type, asset_id, project_id, sha256, url
 *
 * Caching is handled at the service layer via Spring @Cacheable (Caffeine).
 * No Hibernate L2 entity cache — that requires a JCache provider on the classpath.
 */
@Entity
@Table(name = "cxalloy_files", indexes = {
    @Index(name = "idx_files_project_id", columnList = "project_id"),
    @Index(name = "idx_files_external_id", columnList = "external_id"),
    @Index(name = "idx_files_sha256", columnList = "sha256"),
})
public class ProjectedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "project_id", length = 50, nullable = false)
    private String projectId;

    @Column(name = "name", length = 512)
    private String name;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "mime_type", length = 200)
    private String mimeType;

    @Column(name = "asset_type", length = 100)
    private String assetType;

    @Column(name = "asset_id", length = 100)
    private String assetId;

    @Column(name = "sha256", length = 100)
    private String sha256;

    @Column(name = "url", length = 1024)
    private String url;

    @Column(name = "created_date", length = 50)
    private String createdDate;

    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String v) { this.externalId = v; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String v) { this.projectId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long v) { this.fileSize = v; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String v) { this.mimeType = v; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String v) { this.assetType = v; }
    public String getAssetId() { return assetId; }
    public void setAssetId(String v) { this.assetId = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public String getCreatedDate() { return createdDate; }
    public void setCreatedDate(String v) { this.createdDate = v; }
    public String getRawJson() { return rawJson; }
    public void setRawJson(String v) { this.rawJson = v; }
    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime v) { this.syncedAt = v; }
}
