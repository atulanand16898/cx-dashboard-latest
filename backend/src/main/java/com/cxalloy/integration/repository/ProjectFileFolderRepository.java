package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ProjectFileFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectFileFolderRepository extends JpaRepository<ProjectFileFolder, Long> {
    List<ProjectFileFolder> findByProjectIdOrderByNameAsc(String projectId);
    Optional<ProjectFileFolder> findByIdAndProjectId(Long id, String projectId);
    boolean existsByProjectIdAndNameIgnoreCase(String projectId, String name);
}
