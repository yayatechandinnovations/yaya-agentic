package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** One row of {@code outbound_pending}; messages parked because we couldn't
 *  reach the user inside the 24h service window. The next inbound from the
 *  same {@code (sender_id, end_user_phone)} releases them in queued_at order. */
@Entity
@Table(name = "outbound_pending")
public class OutboundPendingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "end_user_phone", nullable = false, length = 20)
    private String endUserPhone;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interactive_json", columnDefinition = "jsonb")
    private String interactiveJson;

    @Column(name = "queued_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime queuedAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    protected OutboundPendingEntity() {}

    public OutboundPendingEntity(String tenantId, String senderId, String endUserPhone,
                                 String sessionId, String body, String interactiveJson) {
        this.tenantId = tenantId;
        this.senderId = senderId;
        this.endUserPhone = endUserPhone;
        this.sessionId = sessionId;
        this.body = body;
        this.interactiveJson = interactiveJson;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSenderId() { return senderId; }
    public String getEndUserPhone() { return endUserPhone; }
    public String getSessionId() { return sessionId; }
    public String getBody() { return body; }
    public String getInteractiveJson() { return interactiveJson; }
    public OffsetDateTime getQueuedAt() { return queuedAt; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }

    public void setReleasedAt(OffsetDateTime v) { this.releasedAt = v; }
}
