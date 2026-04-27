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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "xer_processing_calendar_workdays", indexes = {
        @Index(name = "idx_xer_calendar_workdays_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_calendar_workdays_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_calendar_workdays_calendar_id", columnList = "external_calendar_id")
})
public class XerCalendarWorkday extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_calendar_id", nullable = false)
    private String externalCalendarId;

    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "min_start_time")
    private String minStartTime;

    @Column(name = "max_finish_time")
    private String maxFinishTime;

    @Column(name = "total_hours_worked")
    private Double totalHoursWorked;
}
