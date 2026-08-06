package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One issued receipt instance in a verified user's wallet
 * ({@code chat-experience-design.md} §7.5). {@code templateSnapshot} pins the
 * layout at issuance — a later template edit never retro-edits paper already
 * handed to a user.
 */
@Entity
@Table(name = "chat_receipts")
public class ChatReceiptEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "handle", length = 64)
    private String handle;

    @Column(name = "template_id", length = 64)
    private String templateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> templateSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> data;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChatReceiptEntity() {}

    public ChatReceiptEntity(String id, String tenantId, UUID accountId, String sessionId,
                             String handle, String templateId,
                             Map<String, Object> templateSnapshot, Map<String, Object> data) {
        this.id = id;
        this.tenantId = tenantId;
        this.accountId = accountId;
        this.sessionId = sessionId;
        this.handle = handle;
        this.templateId = templateId;
        this.templateSnapshot = templateSnapshot;
        this.data = data;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getAccountId() { return accountId; }
    public String getSessionId() { return sessionId; }
    public String getHandle() { return handle; }
    public String getTemplateId() { return templateId; }
    public Map<String, Object> getTemplateSnapshot() { return templateSnapshot; }
    public Map<String, Object> getData() { return data; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
