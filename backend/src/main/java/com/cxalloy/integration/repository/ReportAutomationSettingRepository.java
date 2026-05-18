package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ReportAutomationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportAutomationSettingRepository extends JpaRepository<ReportAutomationSetting, Long> {
    Optional<ReportAutomationSetting> findByProjectIdAndProvider(String projectId, String provider);
    List<ReportAutomationSetting> findByEnabledTrueOrderByUpdatedAtAsc();
}
