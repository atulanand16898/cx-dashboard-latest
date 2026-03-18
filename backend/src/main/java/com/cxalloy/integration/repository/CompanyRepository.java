package com.cxalloy.integration.repository;
import com.cxalloy.integration.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;
@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByExternalId(String externalId);
    List<Company> findByProjectId(String projectId);
    boolean existsByExternalId(String externalId);
}
