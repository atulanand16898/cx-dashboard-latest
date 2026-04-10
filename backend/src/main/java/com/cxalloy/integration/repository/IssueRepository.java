package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.Issue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IssueRepository extends JpaRepository<Issue, Long> {
    Optional<Issue> findByExternalId(String externalId);
    List<Issue> findAllByExternalId(String externalId);
    Optional<Issue> findByExternalIdAndProvider(String externalId, String provider);
    Optional<Issue> findBySourceKey(String sourceKey);
    List<Issue> findByProjectId(String projectId);
    List<Issue> findByProjectIdAndStatus(String projectId, String status);
    List<Issue> findByStatus(String status);
    List<Issue> findByAssignee(String assignee);
    boolean existsByExternalId(String externalId);
    boolean existsBySourceKey(String sourceKey);
    long countByProjectId(String projectId);
    long countByProjectIdAndProviderIgnoreCase(String projectId, String provider);
    long countByProjectIdAndStatus(String projectId, String status);
}
