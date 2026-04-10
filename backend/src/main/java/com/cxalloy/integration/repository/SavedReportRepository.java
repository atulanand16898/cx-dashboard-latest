package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.SavedReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SavedReportRepository extends JpaRepository<SavedReport, Long> {
    List<SavedReport> findByProjectIdOrderByGeneratedAtDesc(String projectId);
    List<SavedReport> findByProjectIdAndProviderOrderByGeneratedAtDesc(String projectId, String provider);
}
