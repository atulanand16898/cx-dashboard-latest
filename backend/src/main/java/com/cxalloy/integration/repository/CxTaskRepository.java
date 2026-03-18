package com.cxalloy.integration.repository;
import com.cxalloy.integration.model.CxTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;
@Repository
public interface CxTaskRepository extends JpaRepository<CxTask, Long> {
    Optional<CxTask> findByExternalId(String externalId);
    List<CxTask> findByProjectId(String projectId);
    List<CxTask> findByProjectIdAndStatus(String projectId, String status);
    List<CxTask> findByIssueId(String issueId);
    boolean existsByExternalId(String externalId);
}
