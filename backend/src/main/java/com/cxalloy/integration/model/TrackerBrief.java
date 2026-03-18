package com.cxalloy.integration.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tracker_briefs", indexes = {
    @Index(name = "idx_briefs_project_id", columnList = "project_id"),
    @Index(name = "idx_briefs_period",     columnList = "period"),
    @Index(name = "idx_briefs_exported_at",columnList = "exported_at"),
})
public class TrackerBrief {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "title")
    private String title;

    @Column(name = "subtitle")
    private String subtitle;

    /** Number of checklist items included in this export */
    @Column(name = "items")
    private Integer items;

    /** Number of issues included in this export */
    @Column(name = "issues")
    private Integer issues;

    /** D | W | M | Overall */
    @Column(name = "period")
    private String period;

    @Column(name = "exported_at")
    private LocalDateTime exportedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt  == null) createdAt  = LocalDateTime.now();
        if (exportedAt == null) exportedAt = LocalDateTime.now();
    }

    // Getters / Setters
    public Long          getId()         { return id; }
    public void          setId(Long v)   { this.id = v; }
    public String        getProjectId()  { return projectId; }
    public void          setProjectId(String v)  { this.projectId = v; }
    public String        getTitle()      { return title; }
    public void          setTitle(String v)      { this.title = v; }
    public String        getSubtitle()   { return subtitle; }
    public void          setSubtitle(String v)   { this.subtitle = v; }
    public Integer       getItems()      { return items; }
    public void          setItems(Integer v)     { this.items = v; }
    public Integer       getIssues()     { return issues; }
    public void          setIssues(Integer v)    { this.issues = v; }
    public String        getPeriod()     { return period; }
    public void          setPeriod(String v)     { this.period = v; }
    public LocalDateTime getExportedAt() { return exportedAt; }
    public void          setExportedAt(LocalDateTime v) { this.exportedAt = v; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public void          setCreatedAt(LocalDateTime v)  { this.createdAt = v; }
}
