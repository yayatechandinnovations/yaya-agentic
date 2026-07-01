package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One row of {@code knowledge_blobs} (V51) — the stored bytes of an uploaded
 * knowledge file, addressed by {@code (tenant_id, blob_id)}. {@code data} is
 * populated by the Postgres-backed {@code DbBlobStore}; a future MinIO store
 * leaves it null and uses {@code storageRef} for the object key.
 */
@Entity
@Table(name = "knowledge_blobs")
@IdClass(KnowledgeBlobEntity.PK.class)
public class KnowledgeBlobEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(name = "blob_id", length = 64)
    private String blobId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "data")
    private byte[] data;

    @Column(name = "storage_ref")
    private String storageRef;

    @Column(name = "scan_status", nullable = false, length = 16)
    private String scanStatus = "PENDING";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected KnowledgeBlobEntity() {}

    public KnowledgeBlobEntity(String tenantId, String blobId, String filename,
                               String contentType, long sizeBytes, String sha256,
                               byte[] data, String scanStatus) {
        this.tenantId = tenantId;
        this.blobId = blobId;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.data = data;
        this.scanStatus = scanStatus;
    }

    public String getTenantId() { return tenantId; }
    public String getBlobId() { return blobId; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public byte[] getData() { return data; }
    public String getStorageRef() { return storageRef; }
    public String getScanStatus() { return scanStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setScanStatus(String v) { this.scanStatus = v; }
    public void setStorageRef(String v) { this.storageRef = v; }

    public static class PK implements Serializable {
        private String tenantId;
        private String blobId;
        public PK() {}
        public PK(String tenantId, String blobId) {
            this.tenantId = tenantId;
            this.blobId = blobId;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId) && Objects.equals(blobId, pk.blobId);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, blobId); }
    }
}
