package com.cxalloy.integration.xerprocessing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "xer_processing_task_resources", indexes = {
        @Index(name = "idx_xer_task_resources_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_task_resources_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_task_resources_task_id", columnList = "external_task_id"),
        @Index(name = "idx_xer_task_resources_resource_id", columnList = "external_resource_id")
})
public class XerTaskResource extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_task_id", nullable = false)
    private String externalTaskId;

    @Column(name = "external_project_id")
    private String externalProjectId;

    @Column(name = "external_resource_id")
    private String externalResourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_short_name")
    private String resourceShortName;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "remaining_qty")
    private Double remainingQty;

    @Column(name = "target_qty")
    private Double targetQty;

    @Column(name = "actual_regular_qty")
    private Double actualRegularQty;

    @Column(name = "cost_per_qty")
    private Double costPerQty;

    @Column(name = "target_cost")
    private Double targetCost;

    @Column(name = "actual_regular_cost")
    private Double actualRegularCost;

    @Column(name = "remaining_cost")
    private Double remainingCost;

    @Column(name = "actual_start_date")
    private LocalDateTime actualStartDate;

    @Column(name = "actual_end_date")
    private LocalDateTime actualEndDate;

    @Column(name = "restart_date")
    private LocalDateTime restartDate;

    @Column(name = "reend_date")
    private LocalDateTime reendDate;

    @Column(name = "target_start_date")
    private LocalDateTime targetStartDate;

    @Column(name = "target_end_date")
    private LocalDateTime targetEndDate;

    @Column(name = "remaining_late_start_date")
    private LocalDateTime remainingLateStartDate;

    @Column(name = "remaining_late_end_date")
    private LocalDateTime remainingLateEndDate;
}
