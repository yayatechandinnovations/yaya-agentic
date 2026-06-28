package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code sms_campaigns} (V43). Mirrors the migration; domain
 * conversion (to/from {@code channel.sms.SmsCampaign}) lives in the service
 * layer. JSON columns ({@code sample_messages_json}, {@code opt_in_json},
 * {@code evidence_json}) are stored as {@code String} and marshalled via
 * Jackson by the service.
 */
@Entity
@Table(name = "sms_campaigns")
public class SmsCampaignEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "brand_id", nullable = false, length = 64)
    private String brandId;

    @Column(name = "use_case", nullable = false, length = 40)
    private String useCase;

    @Column(name = "description", nullable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_messages_json", columnDefinition = "jsonb", nullable = false)
    private String sampleMessagesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "opt_in_json", columnDefinition = "jsonb", nullable = false)
    private String optInJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private String evidenceJson;

    @Column(name = "requires_operator_review", nullable = false)
    private boolean requiresOperatorReview;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "twilio_campaign_sid", length = 64)
    private String twilioCampaignSid;

    @Column(name = "messaging_service_sid", length = 64)
    private String messagingServiceSid;

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

    protected SmsCampaignEntity() {}

    public SmsCampaignEntity(String id, String tenantId, String brandId, String useCase,
                             String description, String sampleMessagesJson, String optInJson,
                             boolean requiresOperatorReview, String status) {
        this.id = id;
        this.tenantId = tenantId;
        this.brandId = brandId;
        this.useCase = useCase;
        this.description = description;
        this.sampleMessagesJson = sampleMessagesJson;
        this.optInJson = optInJson;
        this.requiresOperatorReview = requiresOperatorReview;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getBrandId() { return brandId; }
    public String getUseCase() { return useCase; }
    public String getDescription() { return description; }
    public String getSampleMessagesJson() { return sampleMessagesJson; }
    public String getOptInJson() { return optInJson; }
    public String getEvidenceJson() { return evidenceJson; }
    public boolean isRequiresOperatorReview() { return requiresOperatorReview; }
    public String getStatus() { return status; }
    public String getTwilioCampaignSid() { return twilioCampaignSid; }
    public String getMessagingServiceSid() { return messagingServiceSid; }
    public String getLastFailureCode() { return lastFailureCode; }
    public String getLastFailureMessage() { return lastFailureMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }

    public void setUseCase(String v) { this.useCase = v; }
    public void setDescription(String v) { this.description = v; }
    public void setSampleMessagesJson(String v) { this.sampleMessagesJson = v; }
    public void setOptInJson(String v) { this.optInJson = v; }
    public void setEvidenceJson(String v) { this.evidenceJson = v; }
    public void setRequiresOperatorReview(boolean v) { this.requiresOperatorReview = v; }
    public void setStatus(String v) { this.status = v; }
    public void setTwilioCampaignSid(String v) { this.twilioCampaignSid = v; }
    public void setMessagingServiceSid(String v) { this.messagingServiceSid = v; }
    public void setLastFailureCode(String v) { this.lastFailureCode = v; }
    public void setLastFailureMessage(String v) { this.lastFailureMessage = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public void setLastSyncAt(OffsetDateTime v) { this.lastSyncAt = v; }
}
