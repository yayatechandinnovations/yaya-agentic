package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MediaAssetRepository extends JpaRepository<MediaAssetEntity, String> {

    List<MediaAssetEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<MediaAssetEntity> findByTenantIdAndId(String tenantId, String id);
}
