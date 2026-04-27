package com.cxalloy.integration.xerprocessing.repository;

import com.cxalloy.integration.xerprocessing.model.XerProgressMeasurementConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface XerProgressMeasurementConfigRepository extends JpaRepository<XerProgressMeasurementConfig, Long> {

    Optional<XerProgressMeasurementConfig> findTopByProjectIdOrderByConfiguredAtDesc(Long projectId);
}
