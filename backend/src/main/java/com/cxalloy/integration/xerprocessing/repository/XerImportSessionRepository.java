package com.cxalloy.integration.xerprocessing.repository;

import com.cxalloy.integration.xerprocessing.model.XerImportSession;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface XerImportSessionRepository extends JpaRepository<XerImportSession, Long> {

    @EntityGraph(attributePaths = "project")
    Optional<XerImportSession> findWithProjectById(Long id);

    List<XerImportSession> findAllByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<XerImportSession> findTopByProjectIdAndImportTypeOrderByCreatedAtDesc(Long projectId, String importType);

    long countByProjectIdAndImportType(Long projectId, String importType);
}
