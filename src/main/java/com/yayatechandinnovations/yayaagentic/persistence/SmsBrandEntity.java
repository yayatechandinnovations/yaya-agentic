package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code sms_brands} (V43). Field-level mapping mirrors the
 * migration; channel-domain conversion (to/from {@code channel.sms.SmsBrand})
 * lives in the service layer, not on this class — same split as
 * {@link WhatsAppSenderEntity}.
 *
 * <p>{@code business_json} is stored as {@code String} per the project-wide
 * JSON pattern; the service marshals to/from {@code BusinessIdentity} via
 * Jackson.
 */
@Entity
@Table(name = "sms_brands")
public class SmsBrandEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_json", columnDefinition = "jsonb", nullable = false)
    private String businessJson;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "twilio_profile_sid", length = 64)
    private String twilioProfileSid;

    @Column(name = "tcr_brand_id", length = 64)
    private String tcrBrandId;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @Column(name = "last_failure_message")
    private String lastFailureMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    protected SmsBrandEntity() {}

    public SmsBrandEntity(String id, String tenantId, String businessJson, String status) {
        this.id = id;
        this.tenantId = tenantId;
        this.businessJson = businessJson;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getBusinessJson() { return businessJson; }
    public String getStatus() { return status; }
    public String getTwilioProfileSid() { return twilioProfileSid; }
    public String getTcrBrandId() { return tcrBrandId; }
    public String getLastFailureCode() { return lastFailureCode; }
    public String getLastFailureMessage() { return lastFailureMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }

    public void setBusinessJson(String v) { this.businessJson = v; }
    public void setStatus(String v) { this.status = v; }
    public void setTwilioProfileSid(String v) { this.twilioProfileSid = v; }
    public void setTcrBrandId(String v) { this.tcrBrandId = v; }
    public void setLastFailureCode(String v) { this.lastFailureCode = v; }
    public void setLastFailureMessage(String v) { this.lastFailureMessage = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public void setLastSyncAt(OffsetDateTime v) { this.lastSyncAt = v; }
}
