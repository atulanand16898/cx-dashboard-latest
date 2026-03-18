package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ProjectAccessAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectAccessAssignmentRepository extends JpaRepository<ProjectAccessAssignment, Long> {
    List<ProjectAccessAssignment> findAllByOrderByProjectIdAscPersonNameAsc();
    List<ProjectAccessAssignment> findByProjectIdOrderByPersonNameAsc(String projectId);
    List<ProjectAccessAssignment> findByPersonEmailIgnoreCaseOrderByProjectIdAsc(String personEmail);
    boolean existsByPersonEmailIgnoreCase(String personEmail);
    boolean existsByProjectIdAndPersonEmailIgnoreCase(String projectId, String personEmail);
    Optional<ProjectAccessAssignment> findByProjectIdAndPersonEmailIgnoreCase(String projectId, String personEmail);
}
