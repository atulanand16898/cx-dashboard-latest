package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ProjectVisibilityPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectVisibilityPreferenceRepository extends JpaRepository<ProjectVisibilityPreference, Long> {
    List<ProjectVisibilityPreference> findByUsernameIgnoreCaseOrderByProjectIdAsc(String username);
    boolean existsByUsernameIgnoreCase(String username);
    void deleteByUsernameIgnoreCase(String username);
}
