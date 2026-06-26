package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code whatsapp_senders}. Field-level mapping mirrors the V25
 * migration; channel-domain conversion (to/from
 * {@code channel.whatsapp.WhatsAppSender}) lives in the service layer, not on
 * this class.
 *
 * <p>JSON columns are stored as {@code String} per the project-wide pattern
 * (see {@code ToolEntity}) — the service layer marshals to/from records via
 * Jackson. {@code webhook_secret_enc} stays encrypted on disk; the service
 * unwraps with {@code SecretCipher} just before handing the secret to the
 * BSP signature verifier.
 */
@Entity
@Table(name = "whatsapp_senders")
public class WhatsAppSenderEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "approved_display_name", length = 128)
    private String approvedDisplayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_profile_json", columnDefinition = "jsonb", nullable = false)
    private String businessProfileJson;

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

    protected WhatsAppSenderEntity() {}

    public WhatsAppSenderEntity(String id, String tenantId, String phoneE164,
                                String displayName, String businessProfileJson,
                                String status, byte[] webhookSecretEnc) {
        this.id = id;
        this.tenantId = tenantId;
        this.phoneE164 = phoneE164;
        this.displayName = displayName;
        this.businessProfileJson = businessProfileJson;
        this.status = status;
        this.webhookSecretEnc = webhookSecretEnc;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPhoneE164() { return phoneE164; }
    public String getDisplayName() { return displayName; }
    public String getApprovedDisplayName() { return approvedDisplayName; }
    public String getBusinessProfileJson() { return businessProfileJson; }
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
    public void setDisplayName(String v) { this.displayName = v; }
    public void setApprovedDisplayName(String v) { this.approvedDisplayName = v; }
    public void setBusinessProfileJson(String v) { this.businessProfileJson = v; }
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
