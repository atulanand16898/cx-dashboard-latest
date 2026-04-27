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
@Table(name = "xer_processing_task_activity_codes", indexes = {
        @Index(name = "idx_xer_task_activity_codes_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_task_activity_codes_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_task_activity_codes_task_id", columnList = "external_task_id")
})
public class XerTaskActivityCode extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_task_id", nullable = false)
    private String externalTaskId;

    @Column(name = "external_project_id")
    private String externalProjectId;

    @Column(name = "external_activity_code_id")
    private String externalActivityCodeId;

    @Column(name = "code_type")
    private String codeType;

    @Column(name = "code_value")
    private String codeValue;
}
