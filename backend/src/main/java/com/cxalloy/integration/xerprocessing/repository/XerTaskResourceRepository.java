package com.cxalloy.integration.xerprocessing.repository;

import com.cxalloy.integration.xerprocessing.model.XerTaskResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface XerTaskResourceRepository extends JpaRepository<XerTaskResource, Long> {

    List<XerTaskResource> findByProjectIdAndImportSessionId(Long projectId, Long importSessionId);

    List<XerTaskResource> findByProjectIdAndImportSessionIdOrderByResourceTypeAscResourceNameAsc(Long projectId, Long importSessionId);
}
