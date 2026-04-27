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
@Table(name = "xer_processing_task_predecessors", indexes = {
        @Index(name = "idx_xer_task_predecessors_project_id", columnList = "project_id"),
        @Index(name = "idx_xer_task_predecessors_import_session_id", columnList = "import_session_id"),
        @Index(name = "idx_xer_task_predecessors_task_id", columnList = "external_task_id")
})
public class XerTaskPredecessor extends XerImportScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_task_id", nullable = false)
    private String externalTaskId;

    @Column(name = "external_project_id")
    private String externalProjectId;

    @Column(name = "external_predecessor_task_id")
    private String externalPredecessorTaskId;

    @Column(name = "relationship_type")
    private String relationshipType;

    @Column(name = "lag_hours")
    private Double lagHours;
}
