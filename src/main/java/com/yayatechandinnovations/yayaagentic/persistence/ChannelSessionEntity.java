package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row of {@code channel_sessions}. Indexed view of "for this (sender,
 * end_user), what engine session are we in, when did we last hear from them,
 * and are they still open?" Lives outside the recorder per §9.2.
 */
@Entity
@Table(name = "channel_sessions")
public class ChannelSessionEntity {

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

    @Column(name = "status", nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "opened_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime openedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "last_inbound_at", nullable = false)
    private OffsetDateTime lastInboundAt;

    @Column(name = "last_outbound_at")
    private OffsetDateTime lastOutboundAt;

    @Column(name = "close_reason", length = 32)
    private String closeReason;

    protected ChannelSessionEntity() {}

    public ChannelSessionEntity(String tenantId, String senderId, String endUserPhone,
                                String sessionId, OffsetDateTime lastInboundAt) {
        this.tenantId = tenantId;
        this.senderId = senderId;
        this.endUserPhone = endUserPhone;
        this.sessionId = sessionId;
        this.lastInboundAt = lastInboundAt;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSenderId() { return senderId; }
    public String getEndUserPhone() { return endUserPhone; }
    public String getSessionId() { return sessionId; }
    public String getStatus() { return status; }
    public OffsetDateTime getOpenedAt() { return openedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public OffsetDateTime getLastInboundAt() { return lastInboundAt; }
    public OffsetDateTime getLastOutboundAt() { return lastOutboundAt; }
    public String getCloseReason() { return closeReason; }

    public void setStatus(String v) { this.status = v; }
    public void setClosedAt(OffsetDateTime v) { this.closedAt = v; }
    public void setLastInboundAt(OffsetDateTime v) { this.lastInboundAt = v; }
    public void setLastOutboundAt(OffsetDateTime v) { this.lastOutboundAt = v; }
    public void setCloseReason(String v) { this.closeReason = v; }
}
