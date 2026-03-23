package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ChecklistStatusDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChecklistStatusDateRepository extends JpaRepository<ChecklistStatusDate, Long> {
    Optional<ChecklistStatusDate> findByProjectIdAndChecklistExternalId(String projectId, String checklistExternalId);
    java.util.List<ChecklistStatusDate> findByProjectId(String projectId);
}
