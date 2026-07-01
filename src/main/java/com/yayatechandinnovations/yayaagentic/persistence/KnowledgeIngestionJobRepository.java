package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeIngestionJobRepository
        extends JpaRepository<KnowledgeIngestionJobEntity, UUID> {

    /**
     * Claim runnable jobs. {@code FOR UPDATE SKIP LOCKED} lets every worker/replica
     * grab a disjoint set with no coordinator — rows another transaction already
     * holds are skipped rather than blocked on. The caller must be inside a
     * transaction and flip the returned rows to RUNNING before it commits.
     */
    @Query(value = """
            SELECT * FROM knowledge_ingestion_jobs
             WHERE state = 'QUEUED' AND run_after <= now()
             ORDER BY created_at
             FOR UPDATE SKIP LOCKED
             LIMIT :limit
            """, nativeQuery = true)
    List<KnowledgeIngestionJobEntity> claimBatch(@Param("limit") int limit);

    boolean existsByTenantIdAndSourceIdAndSourceVersionAndStateIn(
            String tenantId, String sourceId, int sourceVersion, Collection<String> states);

    Optional<KnowledgeIngestionJobEntity>
            findFirstByTenantIdAndSourceIdOrderByCreatedAtDesc(String tenantId, String sourceId);
}
