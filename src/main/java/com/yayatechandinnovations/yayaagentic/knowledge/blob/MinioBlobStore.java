package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeBlobEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeBlobRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * MinIO-backed {@link BlobStore} (active when {@code blob-store=minio}). Bytes
 * live in an S3-compatible bucket; metadata + within-tenant dedup reuse the
 * {@code knowledge_blobs} row (with {@code data} null and {@code storage_ref}
 * holding the object key). This split — DB metadata, object-store bytes — keeps
 * large binaries out of Postgres while a source's blob reference never changes
 * regardless of backend (design §6).
 */
@Component
@ConditionalOnProperty(name = "yaya.agentic.knowledge.blob-store", havingValue = "minio")
public class MinioBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(MinioBlobStore.class);

    private final MinioClient client;
    private final MinioProperties props;
    private final KnowledgeBlobRepository blobs;

    public MinioBlobStore(MinioClient client, MinioProperties props,
                          KnowledgeBlobRepository blobs) {
        this.client = client;
        this.props = props;
        this.blobs = blobs;
    }

    @Override
    public StoredBlob store(TenantId tenant, String filename, String contentType,
                            InputStream data, long sizeBytes) {
        byte[] bytes = readAll(data);
        String sha = sha256Hex(bytes);

        Optional<KnowledgeBlobEntity> existing = blobs.findByTenantIdAndSha256(tenant.value(), sha)
                .stream().findFirst();
        if (existing.isPresent()) {
            KnowledgeBlobEntity e = existing.get();
            return new StoredBlob(e.getBlobId(), e.getSha256(), e.getSizeBytes(), e.getContentType());
        }

        String blobId = "kb_" + UUID.randomUUID().toString().replace("-", "");
        String objectKey = tenant.value() + "/" + blobId;
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO putObject failed for " + objectKey, e);
        }

        KnowledgeBlobEntity entity = new KnowledgeBlobEntity(
                tenant.value(), blobId, filename, contentType,
                bytes.length, sha, null, "CLEAN");
        entity.setStorageRef(objectKey);
        blobs.save(entity);
        return new StoredBlob(blobId, sha, bytes.length, contentType);
    }

    @Override
    public Optional<InputStream> open(TenantId tenant, String blobId) {
        Optional<KnowledgeBlobEntity> row = blobs.findByTenantIdAndBlobId(tenant.value(), blobId);
        if (row.isEmpty()) return Optional.empty();
        try {
            InputStream stream = client.getObject(GetObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(row.get().getStorageRef())
                    .build());
            return Optional.of(stream);
        } catch (Exception e) {
            LOG.warn("MinIO getObject failed for {}: {}", row.get().getStorageRef(), e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<BlobMetadata> stat(TenantId tenant, String blobId) {
        return blobs.findByTenantIdAndBlobId(tenant.value(), blobId)
                .map(e -> new BlobMetadata(e.getFilename(), e.getContentType(),
                        e.getSizeBytes(), e.getSha256(), e.getScanStatus()));
    }

    @Override
    public void delete(TenantId tenant, String blobId) {
        blobs.findByTenantIdAndBlobId(tenant.value(), blobId).ifPresent(e -> {
            try {
                client.removeObject(RemoveObjectArgs.builder()
                        .bucket(props.bucket())
                        .object(e.getStorageRef())
                        .build());
            } catch (Exception ex) {
                LOG.warn("MinIO removeObject failed for {}: {}", e.getStorageRef(), ex.toString());
            }
            blobs.deleteByTenantIdAndBlobId(tenant.value(), blobId);
        });
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
