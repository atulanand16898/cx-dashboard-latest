package com.cxalloy.integration.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "checklist_status_dates", indexes = {
    @Index(name = "idx_checklist_status_dates_project", columnList = "project_id"),
    @Index(name = "idx_checklist_status_dates_checklist", columnList = "checklist_external_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_checklist_status_dates_project_checklist", columnNames = {"project_id", "checklist_external_id"})
})
public class ChecklistStatusDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "provider", length = 50)
    private String provider = DataProvider.CXALLOY.getKey();

    @Column(name = "checklist_external_id", nullable = false)
    private String checklistExternalId;

    @Column(name = "checklist_name")
    private String checklistName;

    @Column(name = "latest_open_date")
    private LocalDate latestOpenDate;

    @Column(name = "latest_in_progress_date")
    private LocalDate latestInProgressDate;

    @Column(name = "latest_finished_date")
    private LocalDate latestFinishedDate;

    @Column(name = "last_known_status")
    private String lastKnownStatus;

    @Column(name = "source_checklistsection_id")
    private String sourceChecklistSectionId;

    @Column(name = "source_status_change_raw")
    private String sourceStatusChangeRaw;

    @Column(name = "source_date_created_raw")
    private String sourceDateCreatedRaw;

    @Column(name = "source_payload", columnDefinition = "TEXT")
    private String sourcePayload;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

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

    public String getChecklistExternalId() {
        return checklistExternalId;
    }

    public void setChecklistExternalId(String checklistExternalId) {
        this.checklistExternalId = checklistExternalId;
    }

    public String getChecklistName() {
        return checklistName;
    }

    public void setChecklistName(String checklistName) {
        this.checklistName = checklistName;
    }

    public LocalDate getLatestOpenDate() {
        return latestOpenDate;
    }

    public void setLatestOpenDate(LocalDate latestOpenDate) {
        this.latestOpenDate = latestOpenDate;
    }

    public LocalDate getLatestInProgressDate() {
        return latestInProgressDate;
    }

    public void setLatestInProgressDate(LocalDate latestInProgressDate) {
        this.latestInProgressDate = latestInProgressDate;
    }

    public LocalDate getLatestFinishedDate() {
        return latestFinishedDate;
    }

    public void setLatestFinishedDate(LocalDate latestFinishedDate) {
        this.latestFinishedDate = latestFinishedDate;
    }

    public String getLastKnownStatus() {
        return lastKnownStatus;
    }

    public void setLastKnownStatus(String lastKnownStatus) {
        this.lastKnownStatus = lastKnownStatus;
    }

    public String getSourceChecklistSectionId() {
        return sourceChecklistSectionId;
    }

    public void setSourceChecklistSectionId(String sourceChecklistSectionId) {
        this.sourceChecklistSectionId = sourceChecklistSectionId;
    }

    public String getSourceStatusChangeRaw() {
        return sourceStatusChangeRaw;
    }

    public void setSourceStatusChangeRaw(String sourceStatusChangeRaw) {
        this.sourceStatusChangeRaw = sourceStatusChangeRaw;
    }

    public String getSourceDateCreatedRaw() {
        return sourceDateCreatedRaw;
    }

    public void setSourceDateCreatedRaw(String sourceDateCreatedRaw) {
        this.sourceDateCreatedRaw = sourceDateCreatedRaw;
    }

    public String getSourcePayload() {
        return sourcePayload;
    }

    public void setSourcePayload(String sourcePayload) {
        this.sourcePayload = sourcePayload;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(LocalDateTime syncedAt) {
        this.syncedAt = syncedAt;
    }
}
