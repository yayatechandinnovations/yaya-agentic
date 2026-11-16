package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "whatsapp_templates")
public class WhatsAppTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code platform}, or the id of a workspace's own messaging account. */
    @Column(name = "account_key", nullable = false, length = 64)
    private String accountKey;

    /** Audit only: the workspace that first declared it; null when declared from the console. */
    @Column(name = "created_by_tenant", length = 64)
    private String createdByTenant;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "placeholder_count", nullable = false)
    private int placeholderCount;

    @Column(name = "bsp_template_id", length = 128)
    private String bspTemplateId;

    @Column(name = "bsp_content_sid", length = 64)
    private String bspContentSid;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "header_kind", nullable = false, length = 16)
    private String headerKind = "NONE";

    @Column(name = "buttons_json", columnDefinition = "TEXT")
    private String buttonsJson;

    @Column(name = "samples_json", columnDefinition = "TEXT")
    private String samplesJson;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    protected WhatsAppTemplateEntity() {}

    public WhatsAppTemplateEntity(String accountKey, String createdByTenant, String name, String locale,
                                  String category, String body, int placeholderCount) {
        this.accountKey = accountKey;
        this.createdByTenant = createdByTenant;
        this.name = name;
        this.locale = locale;
        this.category = category;
        this.body = body;
        this.placeholderCount = placeholderCount;
    }

    public Long getId() { return id; }
    public String getAccountKey() { return accountKey; }
    public String getCreatedByTenant() { return createdByTenant; }
    public String getName() { return name; }
    public String getLocale() { return locale; }
    public String getCategory() { return category; }
    public String getBody() { return body; }
    public int getPlaceholderCount() { return placeholderCount; }
    public String getBspTemplateId() { return bspTemplateId; }
    public String getBspContentSid() { return bspContentSid; }
    public String getStatus() { return status; }
    public String getRejectionReason() { return rejectionReason; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }

    public void setName(String v) { this.name = v; }
    public void setLocale(String v) { this.locale = v; }
    public void setCategory(String v) { this.category = v; }
    public void setBody(String v) { this.body = v; }
    public void setPlaceholderCount(int v) { this.placeholderCount = v; }
    public void setBspTemplateId(String v) { this.bspTemplateId = v; }
    public void setBspContentSid(String v) { this.bspContentSid = v; }
    public void setStatus(String v) { this.status = v; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
    public void setSubmittedAt(OffsetDateTime v) { this.submittedAt = v; }
    public void setApprovedAt(OffsetDateTime v) { this.approvedAt = v; }
    public void setLastSyncAt(OffsetDateTime v) { this.lastSyncAt = v; }
    public String getHeaderKind() { return headerKind; }
    public void setHeaderKind(String v) { this.headerKind = v; }
    public String getButtonsJson() { return buttonsJson; }
    public void setButtonsJson(String v) { this.buttonsJson = v; }
    public String getSamplesJson() { return samplesJson; }
    public void setSamplesJson(String v) { this.samplesJson = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { this.contentHash = v; }
}
