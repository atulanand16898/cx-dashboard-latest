package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.RawApiResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RawApiResponseRepository extends JpaRepository<RawApiResponse, Long> {
    List<RawApiResponse> findByEndpoint(String endpoint);
    List<RawApiResponse> findByResponseType(String responseType);
}
