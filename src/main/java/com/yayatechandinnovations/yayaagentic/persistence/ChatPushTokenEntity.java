package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** One row of {@code chat_push_tokens} (V67) — a device's wake token for a recipient. */
@Entity
@Table(name = "chat_push_tokens")
public class ChatPushTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "entry_point_id", nullable = false, length = 64)
    private String entryPointId;

    @Column(name = "recipient_key", nullable = false, length = 255)
    private String recipientKey;

    @Column(name = "platform", nullable = false, length = 16)
    private String platform = "WEBPUSH";

    @Column(name = "token", nullable = false)
    private String token;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "endpoint_json", columnDefinition = "jsonb")
    private String endpointJson;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChatPushTokenEntity() {}

    public ChatPushTokenEntity(String tenantId, String entryPointId, String recipientKey,
                               String platform, String token) {
        this.tenantId = tenantId;
        this.entryPointId = entryPointId;
        this.recipientKey = recipientKey;
        this.platform = platform;
        this.token = token;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEntryPointId() { return entryPointId; }
    public String getRecipientKey() { return recipientKey; }
    public String getPlatform() { return platform; }
    public String getToken() { return token; }
    public String getEndpointJson() { return endpointJson; }
    public void setEndpointJson(String v) { this.endpointJson = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
