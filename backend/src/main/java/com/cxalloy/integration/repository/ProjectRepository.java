package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByExternalId(String externalId);
    List<Project> findAllByExternalId(String externalId);
    Optional<Project> findByExternalIdAndProvider(String externalId, String provider);
    Optional<Project> findBySourceKey(String sourceKey);
    List<Project> findByStatus(String status);
    long countByProviderIgnoreCase(String provider);
    boolean existsByExternalId(String externalId);
    boolean existsBySourceKey(String sourceKey);
}
