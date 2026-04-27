package com.cxalloy.integration.xerprocessing.repository;

import com.cxalloy.integration.xerprocessing.model.XerDcmaCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface XerDcmaCheckpointRepository extends JpaRepository<XerDcmaCheckpoint, Long> {

    List<XerDcmaCheckpoint> findByProjectIdAndImportSessionIdOrderByCheckpointIdAsc(Long projectId, Long importSessionId);

    List<XerDcmaCheckpoint> findByProjectIdOrderByImportSessionIdDescCheckpointIdAsc(Long projectId);

    @Transactional
    @Modifying
    @Query("DELETE FROM XerDcmaCheckpoint c WHERE c.project.id = :projectId AND c.importSession.id = :importSessionId")
    void deleteByProjectIdAndImportSessionId(Long projectId, Long importSessionId);
}
