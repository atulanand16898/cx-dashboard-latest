package com.cxalloy.integration.xerprocessing.repository;

import com.cxalloy.integration.xerprocessing.model.XerActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface XerActivityRepository extends JpaRepository<XerActivity, Long> {

    List<XerActivity> findByProjectIdAndImportSessionId(Long projectId, Long importSessionId);
}
