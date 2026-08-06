package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One row of {@code whatsapp_onboarding_attempts} (V75) — the durable
 * idempotency ledger for the embedded-signup flow. The idempotency key is
 * derived from (tenant, phone, WABA), so repeated submissions resume this
 * row's state machine instead of provisioning duplicate Twilio resources.
 */
@Entity
@Table(name = "whatsapp_onboarding_attempts")
public class WhatsAppOnboardingAttemptEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "waba_id", nullable = false, length = 64)
    private String wabaId;

    @Column(name = "meta_phone_number_id", length = 64)
    private String metaPhoneNumberId;

    @Column(name = "sender_id", length = 64)
    private String senderId;

    @Column(name = "messaging_account_id", length = 64)
    private String messagingAccountId;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    protected WhatsAppOnboardingAttemptEntity() {}

    public WhatsAppOnboardingAttemptEntity(String id, String tenantId, String phoneE164,
                                           String wabaId, String metaPhoneNumberId,
                                           String state, String idempotencyKey) {
        this.id = id;
        this.tenantId = tenantId;
        this.phoneE164 = phoneE164;
        this.wabaId = wabaId;
        this.metaPhoneNumberId = metaPhoneNumberId;
        this.state = state;
        this.idempotencyKey = idempotencyKey;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPhoneE164() { return phoneE164; }
    public String getWabaId() { return wabaId; }
    public String getMetaPhoneNumberId() { return metaPhoneNumberId; }
    public String getSenderId() { return senderId; }
    public String getMessagingAccountId() { return messagingAccountId; }
    public String getState() { return state; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }

    public void setMetaPhoneNumberId(String v) { this.metaPhoneNumberId = v; }
    public void setSenderId(String v) { this.senderId = v; }
    public void setMessagingAccountId(String v) { this.messagingAccountId = v; }
    public void setState(String v) { this.state = v; }
    public void setFailureCode(String v) { this.failureCode = v; }
    public void setFailureMessage(String v) { this.failureMessage = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }
}
