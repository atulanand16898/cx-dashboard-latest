package com.cxalloy.integration.xerprocessing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "xer_processing_import_sessions", indexes = {
        @Index(name = "idx_xer_import_sessions_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_import_sessions_import_type", columnList = "import_type"),
        @Index(name = "idx_xer_import_sessions_status", columnList = "status")
})
public class XerImportSession extends XerAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore
    private XerProject project;

    @Column(name = "import_type", nullable = false)
    private String importType = "baseline";

    @Column(name = "workflow_step", nullable = false)
    private String workflowStep = "baseline_upload";

    @Column(name = "status", nullable = false)
    private String status = "registered";

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "stored_file_name")
    private String storedFileName;

    @Column(name = "project_code_from_file")
    private String projectCodeFromFile;

    @Column(name = "revision_label")
    private String revisionLabel;

    @Column(name = "data_date")
    private LocalDate dataDate;

    @Column(name = "storage_path")
    private String storagePath;

    @Column(name = "processor_name")
    private String processorName = "Baseline_Workout.py";

    @Column(name = "processor_version")
    private String processorVersion;

    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @Column(name = "progress_json", columnDefinition = "TEXT")
    private String progressJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
