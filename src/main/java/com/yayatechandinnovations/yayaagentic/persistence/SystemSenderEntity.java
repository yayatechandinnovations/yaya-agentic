package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code system_senders} (V64). Domain conversion (to/from
 * {@code channel.system.SystemSender}) lives in {@code SystemSenderService},
 * same split as {@link ChatEntryPointEntity}.
 */
@Entity
@Table(name = "system_senders")
public class SystemSenderEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "entry_point_id", nullable = false, length = 64)
    private String entryPointId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trust_json", columnDefinition = "jsonb", nullable = false)
    private String trustJson = "{}";

    @Column(name = "mediation", nullable = false, length = 16)
    private String mediation = "VERBATIM";

    @Column(name = "response_tool_id", length = 128)
    private String responseToolId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "addressing_json", columnDefinition = "jsonb", nullable = false)
    private String addressingJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capability_mask_json", columnDefinition = "jsonb")
    private String capabilityMaskJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media_policy_json", columnDefinition = "jsonb")
    private String mediaPolicyJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "limits_json", columnDefinition = "jsonb")
    private String limitsJson;

    @Column(name = "whatsapp_sender_id", length = 64)
    private String whatsappSenderId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SystemSenderEntity() {}

    public SystemSenderEntity(String id, String tenantId, String entryPointId, String displayName) {
        this.id = id;
        this.tenantId = tenantId;
        this.entryPointId = entryPointId;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEntryPointId() { return entryPointId; }
    public void setEntryPointId(String v) { this.entryPointId = v; }
    public String getDisplayName() { return displayName; }
    public String getWhatsappSenderId() { return whatsappSenderId; }
    public void setWhatsappSenderId(String v) { this.whatsappSenderId = v; }
    public void setDisplayName(String v) { this.displayName = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getTrustJson() { return trustJson; }
    public void setTrustJson(String v) { this.trustJson = v; }
    public String getMediation() { return mediation; }
    public void setMediation(String v) { this.mediation = v; }
    public String getResponseToolId() { return responseToolId; }
    public void setResponseToolId(String v) { this.responseToolId = v; }
    public String getAddressingJson() { return addressingJson; }
    public void setAddressingJson(String v) { this.addressingJson = v; }
    public String getCapabilityMaskJson() { return capabilityMaskJson; }
    public void setCapabilityMaskJson(String v) { this.capabilityMaskJson = v; }
    public String getMediaPolicyJson() { return mediaPolicyJson; }
    public void setMediaPolicyJson(String v) { this.mediaPolicyJson = v; }
    public String getLimitsJson() { return limitsJson; }
    public void setLimitsJson(String v) { this.limitsJson = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
