package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Blobs are strictly tenant-scoped — every lookup is by {@code (tenant_id, ...)}
 * so the inherited cross-tenant {@code findById} is never the access path.
 */
public interface KnowledgeBlobRepository
        extends JpaRepository<KnowledgeBlobEntity, KnowledgeBlobEntity.PK> {

    Optional<KnowledgeBlobEntity> findByTenantIdAndBlobId(String tenantId, String blobId);

    List<KnowledgeBlobEntity> findByTenantIdAndSha256(String tenantId, String sha256);

    long countByTenantId(String tenantId);

    @Query("select coalesce(sum(b.sizeBytes), 0) from KnowledgeBlobEntity b where b.tenantId = ?1")
    long sumSizeBytesByTenantId(String tenantId);

    void deleteByTenantIdAndBlobId(String tenantId, String blobId);

    /**
     * Ref-counted orphan GC (design §11): delete blobs older than the grace
     * window that no UPLOADED source version references. The reference check
     * scans each uploaded source's {@code location_json->'files'[].blobId};
     * the {@code jsonb_typeof … 'array'} guard keeps a malformed location from
     * erroring the sweep. Returns the number of blobs removed.
     */
    @Modifying
    @Query(value = """
            DELETE FROM knowledge_blobs b
             WHERE b.created_at < now() - make_interval(hours => :graceHours)
               AND NOT EXISTS (
                   SELECT 1 FROM knowledge_sources s,
                        jsonb_array_elements(
                            CASE WHEN jsonb_typeof(s.location_json->'files') = 'array'
                                 THEN s.location_json->'files' ELSE '[]'::jsonb END) f
                    WHERE s.tenant_id = b.tenant_id
                      AND s.location_kind = 'UPLOADED'
                      AND f->>'blobId' = b.blob_id)
            """, nativeQuery = true)
    int deleteOrphans(@Param("graceHours") int graceHours);
}
