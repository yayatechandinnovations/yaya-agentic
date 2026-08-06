package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One row of {@code whatsapp_messaging_accounts} (V75): the dedicated Twilio
 * subaccount owning every sender registered under one tenant WABA. The auth
 * token stays encrypted on disk ({@code JwtSecretCipher} ciphertext + IV
 * columns, mirroring {@code TenantJwtSecretEntity}); the entity is
 * crypto-free and the service layer unwraps just-in-time.
 */
@Entity
@Table(name = "whatsapp_messaging_accounts")
public class WhatsAppMessagingAccountEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "waba_id", nullable = false, length = 64)
    private String wabaId;

    @Column(name = "twilio_subaccount_sid", length = 64)
    private String twilioSubaccountSid;

    @Column(name = "auth_token_ciphertext")
    private String authTokenCiphertext;

    @Column(name = "auth_token_iv", length = 64)
    private String authTokenIv;

    @Column(name = "friendly_name", nullable = false, length = 128)
    private String friendlyName;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected WhatsAppMessagingAccountEntity() {}

    public WhatsAppMessagingAccountEntity(String id, String tenantId, String wabaId,
                                          String friendlyName, String status) {
        this.id = id;
        this.tenantId = tenantId;
        this.wabaId = wabaId;
        this.friendlyName = friendlyName;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getWabaId() { return wabaId; }
    public String getTwilioSubaccountSid() { return twilioSubaccountSid; }
    public String getAuthTokenCiphertext() { return authTokenCiphertext; }
    public String getAuthTokenIv() { return authTokenIv; }
    public String getFriendlyName() { return friendlyName; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setTwilioSubaccountSid(String v) { this.twilioSubaccountSid = v; }
    public void setAuthTokenCiphertext(String v) { this.authTokenCiphertext = v; }
    public void setAuthTokenIv(String v) { this.authTokenIv = v; }
    public void setStatus(String v) { this.status = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
