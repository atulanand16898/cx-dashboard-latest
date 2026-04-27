package com.cxalloy.integration.xerprocessing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "xer_processing_dcma_checkpoints", indexes = {
        @Index(name = "idx_xer_dcma_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_dcma_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_dcma_checkpoint_id", columnList = "checkpoint_id")
})
public class XerDcmaCheckpoint extends XerAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore
    private XerProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_session_id", nullable = false)
    @JsonIgnore
    private XerImportSession importSession;

    /** 1–14 per the DCMA 14-Point Schedule Assessment */
    @Column(name = "checkpoint_id", nullable = false)
    private Integer checkpointId;

    @Column(name = "checkpoint_name", nullable = false)
    private String checkpointName;

    /** PASS or FAIL */
    @Column(name = "status", nullable = false)
    private String status;

    /** Percentage score (0–100); for CPLI/BEI stored as value × 100 */
    @Column(name = "score")
    private Double score;

    /** Pass threshold (default 95.0 for %-based checks) */
    @Column(name = "threshold")
    private Double threshold;

    @Column(name = "violating_count")
    private Integer violatingCount;

    @Column(name = "total_count")
    private Integer totalCount;

    /** JSON array of exception objects (activityId, reason, …) capped at 200 */
    @Column(name = "exceptions_json", columnDefinition = "TEXT")
    private String exceptionsJson;

    /** Extra metric data (cpliValue, beiValue, note, etc.) stored as JSON */
    @Column(name = "extra_json", columnDefinition = "TEXT")
    private String extraJson;

    // ── Session-level summary columns (only populated on checkpointId = 0 sentinel row) ──
    @Column(name = "overall_status")
    private String overallStatus;

    @Column(name = "pass_count")
    private Integer passCount;

    @Column(name = "fail_count")
    private Integer failCount;

    @Column(name = "schedulable_activity_count")
    private Integer schedulableActivityCount;

    @Column(name = "relationship_count")
    private Integer relationshipCount;
}
