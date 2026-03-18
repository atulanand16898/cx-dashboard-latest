package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.ApiSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApiSyncLogRepository extends JpaRepository<ApiSyncLog, Long> {
    List<ApiSyncLog> findByEndpointOrderBySyncedAtDesc(String endpoint);
    List<ApiSyncLog> findTop10ByOrderBySyncedAtDesc();
}
