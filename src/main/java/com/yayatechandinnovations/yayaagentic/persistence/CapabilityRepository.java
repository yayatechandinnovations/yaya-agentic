package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CapabilityRepository extends JpaRepository<CapabilityEntity, CapabilityEntity.PK> {
    List<CapabilityEntity> findByTenantIdAndIdOrderByVersionDesc(String tenantId, String id);
    List<CapabilityEntity> findByTenantId(String tenantId);
}
