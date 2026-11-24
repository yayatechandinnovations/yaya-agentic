package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code inbound_messages}. The PK is the BSP's
 * {@code message_sid} so a Twilio retry hits the dedup check on the way in
 * (§9 step 3, §16 replay-attack mitigation).
 */
@Entity
@Table(name = "inbound_messages")
public class InboundMessageEntity {

    @Id
    @Column(name = "message_sid", length = 64)
    private String messageSid;

    /** Null on a platform sender until the router picks the workspace (§7.4). */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "end_user_phone", nullable = false, length = 20)
    private String endUserPhone;

    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "body_redacted")
    private String bodyRedacted;

    @Column(name = "num_media", nullable = false)
    private int numMedia;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload_json", columnDefinition = "jsonb", nullable = false)
    private String rawPayloadJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media_transcripts_json", columnDefinition = "jsonb")
    private String mediaTranscriptsJson;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    /** Why the router picked {@link #tenantId} on a platform sender (§7.2). */
    @Column(name = "route_reason", length = 32)
    private String routeReason;

    @Column(name = "processing_error")
    private String processingError;

    protected InboundMessageEntity() {}

    public InboundMessageEntity(String messageSid, String tenantId, String senderId,
                                String endUserPhone, int numMedia, String rawPayloadJson) {
        this.messageSid = messageSid;
        this.tenantId = tenantId;
        this.senderId = senderId;
        this.endUserPhone = endUserPhone;
        this.numMedia = numMedia;
        this.rawPayloadJson = rawPayloadJson;
    }

    public String getMessageSid() { return messageSid; }
    public String getTenantId() { return tenantId; }
    public String getSenderId() { return senderId; }
    public String getEndUserPhone() { return endUserPhone; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public String getBodyRedacted() { return bodyRedacted; }
    public int getNumMedia() { return numMedia; }
    public String getRawPayloadJson() { return rawPayloadJson; }
    public String getMediaTranscriptsJson() { return mediaTranscriptsJson; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public String getProcessingError() { return processingError; }

    public void setBodyRedacted(String v) { this.bodyRedacted = v; }
    /** Only to redact a payload that turned out to carry a secret. */
    public void setRawPayloadJson(String v) { this.rawPayloadJson = v; }
    public void setMediaTranscriptsJson(String v) { this.mediaTranscriptsJson = v; }
    public void setProcessedAt(OffsetDateTime v) { this.processedAt = v; }
    public void setProcessingError(String v) { this.processingError = v; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getRouteReason() { return routeReason; }
    public void setRouteReason(String v) { this.routeReason = v; }
}
