package com.cxalloy.integration.repository;
import com.cxalloy.integration.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByExternalId(String externalId);
    List<Role> findByProjectId(String projectId);
    boolean existsByExternalId(String externalId);
}
