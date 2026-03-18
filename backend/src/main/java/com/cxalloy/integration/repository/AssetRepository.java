package com.cxalloy.integration.repository;
import com.cxalloy.integration.model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;
@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {
    Optional<Asset> findByExternalId(String externalId);
    List<Asset> findByProjectId(String projectId);
    List<Asset> findByProjectIdAndAssetType(String projectId, String assetType);
    boolean existsByExternalId(String externalId);
}
