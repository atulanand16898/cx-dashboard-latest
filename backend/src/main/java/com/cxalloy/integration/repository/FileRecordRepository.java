package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ProjectedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileRecordRepository extends JpaRepository<ProjectedFile, Long> {

    Optional<ProjectedFile> findByExternalId(String externalId);

    List<ProjectedFile> findByProjectId(String projectId);

    boolean existsByExternalId(String externalId);

    long countByProjectId(String projectId);

    @Query("SELECT f FROM ProjectedFile f WHERE f.projectId = :projectId ORDER BY f.fileSize DESC")
    List<ProjectedFile> findByProjectIdOrderByFileSizeDesc(@Param("projectId") String projectId);

    @Modifying
    @Query("DELETE FROM ProjectedFile f WHERE f.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
