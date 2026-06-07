package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeSourceRepository
        extends JpaRepository<KnowledgeSourceEntity, KnowledgeSourceEntity.PK> {

    List<KnowledgeSourceEntity> findByTenantId(String tenantId);
}
