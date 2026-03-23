package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ProjectManagedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectManagedFileRepository extends JpaRepository<ProjectManagedFile, Long> {
    List<ProjectManagedFile> findByProjectIdOrderByUploadedAtDesc(String projectId);
    long countByFolderId(Long folderId);
    List<ProjectManagedFile> findByFolderId(Long folderId);
    Optional<ProjectManagedFile> findByIdAndProjectId(Long id, String projectId);
}
