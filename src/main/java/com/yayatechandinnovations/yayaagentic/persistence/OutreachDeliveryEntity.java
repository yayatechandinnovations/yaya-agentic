package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row of {@code outreach_deliveries} (V88) — how one resolved target of an
 * ingest got the message: the WhatsApp leg (provider sid + status callbacks)
 * or the chat inbox, with the reason when WhatsApp fell back
 * (whatsapp-ops-integration-design.md §6.1, §14.2).
 */
@Entity
@Table(name = "outreach_deliveries")
public class OutreachDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "outreach_id", nullable = false, length = 64)
    private String outreachId;

    @Column(name = "recipient_key", nullable = false, length = 255)
    private String recipientKey;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "whatsapp_sender_id", length = 64)
    private String whatsappSenderId;

    @Column(name = "end_user_phone", length = 20)
    private String endUserPhone;

    @Column(name = "template_name", length = 128)
    private String templateName;

    @Column(name = "provider_message_sid", length = 64)
    private String providerMessageSid;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "status_at", nullable = false)
    private OffsetDateTime statusAt;

    @Column(name = "fallback_reason", length = 48)
    private String fallbackReason;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "body")
    private String body;

    @Column(name = "seeded_session_id", length = 64)
    private String seededSessionId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected OutreachDeliveryEntity() {}

    public OutreachDeliveryEntity(String tenantId, String outreachId, String recipientKey,
                                  String channel, String status) {
        this.tenantId = tenantId;
        this.outreachId = outreachId;
        this.recipientKey = recipientKey;
        this.channel = channel;
        this.status = status;
        this.statusAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getOutreachId() { return outreachId; }
    public String getRecipientKey() { return recipientKey; }
    public String getChannel() { return channel; }
    public String getWhatsappSenderId() { return whatsappSenderId; }
    public void setWhatsappSenderId(String v) { this.whatsappSenderId = v; }
    public String getEndUserPhone() { return endUserPhone; }
    public void setEndUserPhone(String v) { this.endUserPhone = v; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String v) { this.templateName = v; }
    public String getProviderMessageSid() { return providerMessageSid; }
    public void setProviderMessageSid(String v) { this.providerMessageSid = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) {
        this.status = v;
        this.statusAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
    public OffsetDateTime getStatusAt() { return statusAt; }
    public String getFallbackReason() { return fallbackReason; }
    public void setFallbackReason(String v) { this.fallbackReason = v; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String v) { this.failureCode = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public String getSeededSessionId() { return seededSessionId; }
    public void setSeededSessionId(String v) { this.seededSessionId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
