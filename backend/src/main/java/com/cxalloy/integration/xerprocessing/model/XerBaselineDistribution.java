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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "xer_processing_baseline_distributions", indexes = {
        @Index(name = "idx_xer_baseline_distributions_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_baseline_distributions_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_baseline_distributions_task_id", columnList = "external_task_id"),
        @Index(name = "idx_xer_baseline_distributions_day", columnList = "distribution_day")
})
public class XerBaselineDistribution extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_task_id", nullable = false)
    private String externalTaskId;

    @Column(name = "task_code")
    private String taskCode;

    @Column(name = "external_calendar_id")
    private String externalCalendarId;

    @Column(name = "external_resource_id")
    private String externalResourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_short_name")
    private String resourceShortName;

    @Column(name = "status_code")
    private String statusCode;

    @Column(name = "complete_pct_type")
    private String completePctType;

    @Column(name = "phys_complete_pct")
    private Double physCompletePct;

    @Column(name = "pct_complete")
    private Double pctComplete;

    @Column(name = "target_qty")
    private Double targetQty;

    @Column(name = "remaining_qty")
    private Double remainingQty;

    @Column(name = "actual_units")
    private Double actualUnits;

    @Column(name = "remaining_units")
    private Double remainingUnits;

    @Column(name = "target_cost")
    private Double targetCost;

    @Column(name = "actual_cost")
    private Double actualCost;

    @Column(name = "remaining_cost")
    private Double remainingCost;

    @Column(name = "distribution_type")
    private String distributionType;

    @Column(name = "band_source")
    private String bandSource;

    @Column(name = "band_start_ts")
    private LocalDateTime bandStartTs;

    @Column(name = "band_end_ts")
    private LocalDateTime bandEndTs;

    @Column(name = "distribution_day")
    private LocalDate distributionDay;

    @Column(name = "final_work_hours")
    private Double finalWorkHours;

    @Column(name = "total_band_hours")
    private Double totalBandHours;

    @Column(name = "daily_hour_share")
    private Double dailyHourShare;

    @Column(name = "daily_distribution_units")
    private Double dailyDistributionUnits;

    @Column(name = "daily_distribution_cost")
    private Double dailyDistributionCost;

    @Column(name = "file_data_ts")
    private LocalDateTime fileDataTs;

    @Column(name = "early_start_date")
    private LocalDateTime earlyStartDate;

    @Column(name = "early_end_date")
    private LocalDateTime earlyEndDate;

    @Column(name = "actual_start_date")
    private LocalDateTime actualStartDate;

    @Column(name = "actual_end_date")
    private LocalDateTime actualEndDate;

    @Column(name = "restart_date")
    private LocalDateTime restartDate;

    @Column(name = "reend_date")
    private LocalDateTime reendDate;
}
