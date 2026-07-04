package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** One row of {@code media_assets} (V66) — a logical media asset over a BlobStore blob. */
@Entity
@Table(name = "media_assets")
public class MediaAssetEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "blob_id", nullable = false, length = 64)
    private String blobId;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "caption", length = 300)
    private String caption;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MediaAssetEntity() {}

    public MediaAssetEntity(String id, String tenantId, String blobId, String filename,
                            String contentType, long sizeBytes, String sha256) {
        this.id = id;
        this.tenantId = tenantId;
        this.blobId = blobId;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getBlobId() { return blobId; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getCaption() { return caption; }
    public void setCaption(String v) { this.caption = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
