package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByExternalId(String externalId);
    List<Project> findByStatus(String status);
    boolean existsByExternalId(String externalId);
}
