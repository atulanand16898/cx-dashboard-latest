package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.TrackerBrief;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TrackerBriefRepository extends JpaRepository<TrackerBrief, Long> {

    /** All briefs for a project, newest first */
    List<TrackerBrief> findByProjectIdOrderByExportedAtDesc(String projectId);

    /** Briefs for a project + period filter */
    List<TrackerBrief> findByProjectIdAndPeriodOrderByExportedAtDesc(String projectId, String period);

    /** Count for KPI card */
    long countByProjectId(String projectId);
}
