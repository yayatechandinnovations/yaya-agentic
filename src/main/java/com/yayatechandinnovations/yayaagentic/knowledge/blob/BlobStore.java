package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;

import java.io.InputStream;
import java.util.Optional;

/**
 * Managed storage for uploaded knowledge files. The operator never names a
 * physical location — the framework owns it, and no code above this SPI knows
 * whether bytes live in Postgres {@code bytea} or a MinIO bucket. Selecting a
 * backend is a bean swap keyed by {@code yaya.agentic.knowledge.blob-store};
 * loaders, DTOs, and the admin surface are agnostic.
 *
 * <p>Design: {@code docs/design/knowledge-file-upload-design.md} §6. Every
 * method takes a {@link TenantId} — blobs are tenant-scoped and never readable
 * across tenants.
 */
public interface BlobStore {

    /**
     * Persist bytes and return an opaque, tenant-scoped handle. Implementations
     * de-duplicate within a tenant by {@code sha256}: storing content already
     * present returns the existing handle rather than a second copy.
     */
    StoredBlob store(TenantId tenant, String filename, String contentType, InputStream data, long sizeBytes);

    /** Stream bytes back for ingestion. Empty when unknown or cross-tenant. */
    Optional<InputStream> open(TenantId tenant, String blobId);

    Optional<BlobMetadata> stat(TenantId tenant, String blobId);

    void delete(TenantId tenant, String blobId);

    record StoredBlob(String blobId, String sha256, long sizeBytes, String contentType) {}

    record BlobMetadata(String filename, String contentType, long sizeBytes,
                        String sha256, String scanStatus) {}
}
