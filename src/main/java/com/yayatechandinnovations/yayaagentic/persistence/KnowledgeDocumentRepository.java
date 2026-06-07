package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, UUID> {

    Optional<KnowledgeDocumentEntity> findByTenantIdAndSourceIdAndSourceVersionAndContentHash(
            String tenantId, String sourceId, Integer sourceVersion, String contentHash);

    long countByTenantIdAndSourceIdAndSourceVersion(String tenantId, String sourceId, Integer sourceVersion);
}
