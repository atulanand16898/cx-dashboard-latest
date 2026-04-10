package com.cxalloy.integration.repository;
import com.cxalloy.integration.model.Checklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;
@Repository
public interface ChecklistRepository extends JpaRepository<Checklist, Long> {
    Optional<Checklist> findByExternalId(String externalId);
    List<Checklist> findAllByExternalId(String externalId);
    Optional<Checklist> findByExternalIdAndProvider(String externalId, String provider);
    Optional<Checklist> findBySourceKey(String sourceKey);
    List<Checklist> findByProjectId(String projectId);
    List<Checklist> findByProjectIdAndStatus(String projectId, String status);
    boolean existsByExternalId(String externalId);
    boolean existsBySourceKey(String sourceKey);
    long countByProjectId(String projectId);
    long countByProjectIdAndProviderIgnoreCase(String projectId, String provider);
    long countByProjectIdAndStatus(String projectId, String status);
}
