package com.cxalloy.integration.xerprocessing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "xer_processing_projects", indexes = {
        @Index(name = "idx_xer_projects_project_key", columnList = "project_key", unique = true),
        @Index(name = "idx_xer_projects_datasource_key", columnList = "datasource_key"),
        @Index(name = "idx_xer_projects_workflow_stage", columnList = "workflow_stage")
})
public class XerProject extends XerAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datasource_key", nullable = false)
    private String datasourceKey = "primavera_p6";

    @Column(name = "project_key", nullable = false, unique = true)
    private String projectKey;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "project_code")
    private String projectCode;

    @Column(name = "status", nullable = false)
    private String status = "draft";

    @Column(name = "workflow_stage", nullable = false)
    private String workflowStage = "project_created";

    @Column(name = "baseline_status", nullable = false)
    private String baselineStatus = "pending";

    @Column(name = "progress_measurement_status", nullable = false)
    private String progressMeasurementStatus = "pending";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    protected void ensureProjectKey() {
        if (projectKey == null || projectKey.isBlank()) {
            projectKey = "xer-" + UUID.randomUUID();
        }
    }
}
