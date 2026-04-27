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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "xer_processing_calendar_exceptions", indexes = {
        @Index(name = "idx_xer_calendar_exceptions_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_calendar_exceptions_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_calendar_exceptions_calendar_id", columnList = "external_calendar_id")
})
public class XerCalendarException extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_calendar_id", nullable = false)
    private String externalCalendarId;

    @Column(name = "exception_date")
    private LocalDate exceptionDate;

    @Column(name = "shift_pattern")
    private String shiftPattern;

    @Column(name = "total_shift_hours")
    private Double totalShiftHours;
}
