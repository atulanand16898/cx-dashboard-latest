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
@Table(name = "xer_processing_activities", indexes = {
        @Index(name = "idx_xer_activities_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_activities_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_activities_task_id", columnList = "external_task_id")
})
public class XerActivity extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_task_id", nullable = false)
    private String externalTaskId;

    @Column(name = "external_project_id")
    private String externalProjectId;

    @Column(name = "external_wbs_id")
    private String externalWbsId;

    @Column(name = "external_calendar_id")
    private String externalCalendarId;

    @Column(name = "primary_resource_id")
    private String primaryResourceId;

    @Column(name = "task_code")
    private String taskCode;

    @Column(name = "task_name")
    private String taskName;

    @Column(name = "task_type")
    private String taskType;

    @Column(name = "duration_type")
    private String durationType;

    @Column(name = "status_code")
    private String statusCode;

    @Column(name = "complete_pct_type")
    private String completePctType;

    @Column(name = "phys_complete_pct")
    private Double physCompletePct;

    @Column(name = "early_start_date")
    private LocalDateTime earlyStartDate;

    @Column(name = "early_end_date")
    private LocalDateTime earlyEndDate;

    @Column(name = "target_duration_hours")
    private Double targetDurationHours;

    @Column(name = "remaining_duration_hours")
    private Double remainingDurationHours;

    // DCMA fields
    @Column(name = "total_float_hours")
    private Double totalFloatHours;

    @Column(name = "free_float_hours")
    private Double freeFloatHours;

    @Column(name = "constraint_type")
    private String constraintType;

    @Column(name = "constraint_date")
    private LocalDateTime constraintDate;

    @Column(name = "activity_target_start_date")
    private LocalDateTime activityTargetStartDate;

    @Column(name = "activity_target_end_date")
    private LocalDateTime activityTargetEndDate;

    @Column(name = "late_start_date")
    private LocalDateTime lateStartDate;

    @Column(name = "late_end_date")
    private LocalDateTime lateEndDate;
}
