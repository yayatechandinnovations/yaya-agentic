package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** One row of {@code outbound_messages}; row exists from the moment we
 *  receive the {@code MessageSid} back from Twilio. Status updates flow
 *  through the status-callback path. */
@Entity
@Table(name = "outbound_messages")
public class OutboundMessageEntity {

    @Id
    @Column(name = "message_sid", length = 64)
    private String messageSid;

    /** Null for a platform sender's own lines (whatsapp-shared-sender-design.md §4.2). */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "end_user_phone", nullable = false, length = 20)
    private String endUserPhone;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "sent_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime sentAt;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "QUEUED";

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "failed_at")
    private OffsetDateTime failedAt;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "segment_count")
    private Integer segmentCount;

    protected OutboundMessageEntity() {}

    public OutboundMessageEntity(String messageSid, String tenantId, String senderId,
                                 String endUserPhone, String sessionId, String body) {
        this.messageSid = messageSid;
        this.tenantId = tenantId;
        this.senderId = senderId;
        this.endUserPhone = endUserPhone;
        this.sessionId = sessionId;
        this.body = body;
    }

    public String getMessageSid() { return messageSid; }
    public String getTenantId() { return tenantId; }
    public String getSenderId() { return senderId; }
    public String getEndUserPhone() { return endUserPhone; }
    public String getSessionId() { return sessionId; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public String getBody() { return body; }
    public Long getTemplateId() { return templateId; }
    public String getStatus() { return status; }
    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
    public OffsetDateTime getFailedAt() { return failedAt; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public int getRetryCount() { return retryCount; }

    public void setStatus(String v) { this.status = v; }
    public void setDeliveredAt(OffsetDateTime v) { this.deliveredAt = v; }
    public void setFailedAt(OffsetDateTime v) { this.failedAt = v; }
    public void setFailureCode(String v) { this.failureCode = v; }
    public void setFailureMessage(String v) { this.failureMessage = v; }
    public void setRetryCount(int v) { this.retryCount = v; }
    public void setTemplateId(Long v) { this.templateId = v; }
    public Integer getSegmentCount() { return segmentCount; }
    public void setSegmentCount(Integer v) { this.segmentCount = v; }
}
