package com.cxalloy.integration.xerprocessing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@MappedSuperclass
public abstract class XerImportScopedEntity extends XerAuditedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore
    private XerProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_session_id", nullable = false)
    @JsonIgnore
    private XerImportSession importSession;

    @Column(name = "project_code")
    private String projectCode;

    @Column(name = "revision_label")
    private String revisionLabel;

    @Column(name = "data_date")
    private LocalDate dataDate;

    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;
}
