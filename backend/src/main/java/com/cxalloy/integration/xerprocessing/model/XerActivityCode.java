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
@Table(name = "xer_processing_activity_codes", indexes = {
        @Index(name = "idx_xer_activity_codes_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_activity_codes_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_activity_codes_code_id", columnList = "external_activity_code_id")
})
public class XerActivityCode extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_activity_code_id")
    private String externalActivityCodeId;

    @Column(name = "external_code_type_id")
    private String externalCodeTypeId;

    @Column(name = "code_type")
    private String codeType;

    @Column(name = "short_name")
    private String shortName;

    @Column(name = "code_name")
    private String codeName;

    @Column(name = "sequence_number")
    private String sequenceNumber;
}
