package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeBlobEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeBlobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Postgres {@code bytea}-backed {@link BlobStore} — the zero-extra-infra default,
 * matching the pattern {@code ChatLogoService} already uses for logos. Selected
 * by {@code yaya.agentic.knowledge.blob-store=db} (also the fallback when unset).
 * A MinIO-backed store lands behind {@code =minio} without touching callers.
 */
@Component
@ConditionalOnProperty(name = "yaya.agentic.knowledge.blob-store",
        havingValue = "db", matchIfMissing = true)
public class DbBlobStore implements BlobStore {

    private final KnowledgeBlobRepository blobs;

    public DbBlobStore(KnowledgeBlobRepository blobs) {
        this.blobs = blobs;
    }

    @Override
    public StoredBlob store(TenantId tenant, String filename, String contentType,
                            InputStream data, long sizeBytes) {
        byte[] bytes = readAll(data);
        String sha = sha256Hex(bytes);

        // Within-tenant dedup: identical content reuses the existing handle
        // (design §11, decision #3). Idempotent re-uploads cost nothing.
        Optional<KnowledgeBlobEntity> existing = blobs.findByTenantIdAndSha256(tenant.value(), sha)
                .stream().findFirst();
        if (existing.isPresent()) {
            KnowledgeBlobEntity e = existing.get();
            return new StoredBlob(e.getBlobId(), e.getSha256(), e.getSizeBytes(), e.getContentType());
        }

        String blobId = "kb_" + UUID.randomUUID().toString().replace("-", "");
        KnowledgeBlobEntity entity = new KnowledgeBlobEntity(
                tenant.value(), blobId, filename, contentType,
                bytes.length, sha, bytes, "CLEAN");
        blobs.save(entity);
        return new StoredBlob(blobId, sha, bytes.length, contentType);
    }

    @Override
    public Optional<InputStream> open(TenantId tenant, String blobId) {
        return blobs.findByTenantIdAndBlobId(tenant.value(), blobId)
                .map(KnowledgeBlobEntity::getData)
                .map(ByteArrayInputStream::new);
    }

    @Override
    public Optional<BlobMetadata> stat(TenantId tenant, String blobId) {
        return blobs.findByTenantIdAndBlobId(tenant.value(), blobId)
                .map(e -> new BlobMetadata(e.getFilename(), e.getContentType(),
                        e.getSizeBytes(), e.getSha256(), e.getScanStatus()));
    }

    @Override
    public void delete(TenantId tenant, String blobId) {
        blobs.deleteByTenantIdAndBlobId(tenant.value(), blobId);
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading upload bytes", e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
