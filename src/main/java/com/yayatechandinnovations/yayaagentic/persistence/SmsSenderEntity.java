package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code sms_senders} (V44). Mirrors the migration; domain
 * conversion (to/from {@code channel.sms.SmsSender}) lives in the service
 * layer, same split as {@link WhatsAppSenderEntity}. {@code webhook_secret_enc}
 * stays encrypted at rest; the service unwraps with {@code SecretCipher} only
 * when handing the secret to the inbound signature verifier.
 */
@Entity
@Table(name = "sms_senders")
public class SmsSenderEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "market", nullable = false, length = 4)
    private String market;

    @Column(name = "sender_kind", nullable = false, length = 16)
    private String senderKind = "LONG_CODE";

    @Column(name = "acquisition", nullable = false, length = 12)
    private String acquisition = "PURCHASED";

    @Column(name = "campaign_id", length = 64)
    private String campaignId;

    @Column(name = "regulatory_bundle_sid", length = 64)
    private String regulatoryBundleSid;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "twilio_sender_sid", length = 64)
    private String twilioSenderSid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "onboarding_state_json", columnDefinition = "jsonb", nullable = false)
    private String onboardingStateJson = "{}";

    @Column(name = "webhook_secret_enc", nullable = false)
    private byte[] webhookSecretEnc;

    @Column(name = "default_locale", nullable = false, length = 16)
    private String defaultLocale = "en-US";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb", nullable = false)
    private String metadataJson = "{}";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    protected SmsSenderEntity() {}

    public SmsSenderEntity(String id, String tenantId, String phoneE164, String market,
                           String status, byte[] webhookSecretEnc) {
        this.id = id;
        this.tenantId = tenantId;
        this.phoneE164 = phoneE164;
        this.market = market;
        this.status = status;
        this.webhookSecretEnc = webhookSecretEnc;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPhoneE164() { return phoneE164; }
    public String getMarket() { return market; }
    public String getSenderKind() { return senderKind; }
    public String getAcquisition() { return acquisition; }
    public String getCampaignId() { return campaignId; }
    public String getRegulatoryBundleSid() { return regulatoryBundleSid; }
    public String getStatus() { return status; }
    public String getTwilioSenderSid() { return twilioSenderSid; }
    public String getOnboardingStateJson() { return onboardingStateJson; }
    public byte[] getWebhookSecretEnc() { return webhookSecretEnc; }
    public String getDefaultLocale() { return defaultLocale; }
    public String getMetadataJson() { return metadataJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }

    public void setPhoneE164(String v) { this.phoneE164 = v; }
    public void setMarket(String v) { this.market = v; }
    public void setSenderKind(String v) { this.senderKind = v; }
    public void setAcquisition(String v) { this.acquisition = v; }
    public void setCampaignId(String v) { this.campaignId = v; }
    public void setRegulatoryBundleSid(String v) { this.regulatoryBundleSid = v; }
    public void setStatus(String v) { this.status = v; }
    public void setTwilioSenderSid(String v) { this.twilioSenderSid = v; }
    public void setOnboardingStateJson(String v) {
        this.onboardingStateJson = v == null || v.isBlank() ? "{}" : v;
    }
    public void setDefaultLocale(String v) { this.defaultLocale = v; }
    public void setMetadataJson(String v) {
        this.metadataJson = v == null || v.isBlank() ? "{}" : v;
    }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public void setLastSyncAt(OffsetDateTime v) { this.lastSyncAt = v; }
}
