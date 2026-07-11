package com.yayatechandinnovations.yayasales.media.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "media")
public class MediaEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    private String filename;

    @Column(nullable = false)
    private byte[] bytes;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MediaEntity() {}

    public MediaEntity(String id, String storeId, String contentType, String filename,
                       byte[] bytes, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.contentType = contentType;
        this.filename = filename;
        this.bytes = bytes;
        this.sizeBytes = bytes.length;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getContentType() { return contentType; }
    public String getFilename() { return filename; }
    public byte[] getBytes() { return bytes; }
    public int getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
}
