package com.cxalloy.integration.xerprocessing.repository;

import com.cxalloy.integration.xerprocessing.model.XerProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface XerProjectRepository extends JpaRepository<XerProject, Long> {

    List<XerProject> findAllByOrderByUpdatedAtDesc();

    Optional<XerProject> findByProjectKey(String projectKey);
}
