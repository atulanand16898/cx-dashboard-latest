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
@Table(name = "xer_processing_calendars", indexes = {
        @Index(name = "idx_xer_calendars_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_calendars_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_calendars_calendar_id", columnList = "external_calendar_id")
})
public class XerCalendar extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_calendar_id", nullable = false)
    private String externalCalendarId;

    @Column(name = "external_project_id")
    private String externalProjectId;

    @Column(name = "calendar_name")
    private String calendarName;

    @Column(name = "calendar_type")
    private String calendarType;

    @Column(name = "calendar_data", columnDefinition = "TEXT")
    private String calendarData;
}
