package com.cxalloy.integration.repository;

import com.cxalloy.integration.model.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EquipmentRepository extends JpaRepository<Equipment, Long> {
    List<Equipment> findByProjectId(String projectId);
    List<Equipment> findByProjectIdAndEquipmentType(String projectId, String equipmentType);
    Optional<Equipment> findByExternalId(String externalId);
    Optional<Equipment> findByExternalIdAndProjectId(String externalId, String projectId);
}
