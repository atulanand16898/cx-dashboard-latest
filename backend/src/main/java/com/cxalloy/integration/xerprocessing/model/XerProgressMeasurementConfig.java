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

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "xer_processing_progress_measurement_configs", indexes = {
        @Index(name = "idx_xer_progress_configs_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_progress_configs_baseline_import_id", columnList = "baseline_import_id")
})
public class XerProgressMeasurementConfig extends XerAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore
    private XerProject project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "baseline_import_id")
    @JsonIgnore
    private XerImportSession baselineImport;

    @Column(name = "selection_mode", nullable = false)
    private String selectionMode = "resource_filter";

    @Column(name = "resource_name", nullable = false)
    private String resourceName;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_names_json", columnDefinition = "TEXT")
    private String resourceNamesJson;

    @Column(name = "resource_types_json", columnDefinition = "TEXT")
    private String resourceTypesJson;

    @Column(name = "all_resource_names", nullable = false)
    private Boolean allResourceNames = false;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "configured_at", nullable = false)
    private LocalDateTime configuredAt = LocalDateTime.now();
}
