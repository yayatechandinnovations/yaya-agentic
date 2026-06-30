package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** One row of {@code chat_logos} (V49) — a platform-hosted entry-point logo. */
@Entity
@Table(name = "chat_logos")
public class ChatLogoEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChatLogoEntity() {}

    public ChatLogoEntity(String id, String tenantId, String contentType, byte[] data) {
        this.id = id;
        this.tenantId = tenantId;
        this.contentType = contentType;
        this.data = data;
        this.byteSize = data == null ? 0 : data.length;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
    public int getByteSize() { return byteSize; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
