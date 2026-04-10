package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.LeadCapture;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadCaptureRepository extends JpaRepository<LeadCapture, Long> {
}
