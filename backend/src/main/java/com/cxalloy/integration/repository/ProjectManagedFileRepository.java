package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ProjectManagedFile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectManagedFileRepository extends JpaRepository<ProjectManagedFile, Long> {
    @Override
    @EntityGraph(attributePaths = "folder")
    List<ProjectManagedFile> findAll();

    @EntityGraph(attributePaths = "folder")
    List<ProjectManagedFile> findByProjectIdOrderByUploadedAtDesc(String projectId);

    long countByFolderId(Long folderId);

    @EntityGraph(attributePaths = "folder")
    List<ProjectManagedFile> findByFolderId(Long folderId);

    @EntityGraph(attributePaths = "folder")
    Optional<ProjectManagedFile> findByIdAndProjectId(Long id, String projectId);
}
