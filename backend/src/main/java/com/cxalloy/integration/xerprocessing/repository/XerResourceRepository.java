package com.cxalloy.integration.xerprocessing.repository;

import com.cxalloy.integration.xerprocessing.model.XerResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface XerResourceRepository extends JpaRepository<XerResource, Long> {

    List<XerResource> findByProjectIdAndImportSessionIdOrderByResourceTypeAscResourceNameAsc(Long projectId, Long importSessionId);
}
