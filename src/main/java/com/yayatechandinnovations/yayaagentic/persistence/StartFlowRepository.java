package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StartFlowRepository extends JpaRepository<StartFlowEntity, StartFlowEntity.PK> {
    List<StartFlowEntity> findByTenantId(String tenantId);
    List<StartFlowEntity> findByTenantIdAndIdOrderByVersionDesc(String tenantId, String id);
}
