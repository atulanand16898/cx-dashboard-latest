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
@Table(name = "xer_processing_resources", indexes = {
        @Index(name = "idx_xer_resources_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_resources_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_resources_resource_id", columnList = "external_resource_id")
})
public class XerResource extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_resource_id", nullable = false)
    private String externalResourceId;

    @Column(name = "parent_resource_id")
    private String parentResourceId;

    @Column(name = "external_calendar_id")
    private String externalCalendarId;

    @Column(name = "external_role_id")
    private String externalRoleId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_short_name")
    private String resourceShortName;

    @Column(name = "resource_title_name")
    private String resourceTitleName;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "active_flag")
    private String activeFlag;

    @Column(name = "load_tasks_flag")
    private String loadTasksFlag;

    @Column(name = "level_flag")
    private String levelFlag;

    @Column(name = "resource_notes", columnDefinition = "TEXT")
    private String resourceNotes;
}
