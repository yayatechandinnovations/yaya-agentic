package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolRepository extends JpaRepository<ToolEntity, ToolEntity.PK> {
    List<ToolEntity> findByTenantIdAndIdOrderByVersionDesc(String tenantId, String id);
}
