package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One row of {@code whatsapp_keyword_actions} (V97): a prefix a sender forwards to a tenant URL. */
@Entity
@Table(name = "whatsapp_keyword_actions")
public class WhatsAppKeywordActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "prefix", nullable = false, length = 64)
    private String prefix;

    @Column(name = "forward_url", nullable = false, length = 1024)
    private String forwardUrl;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** {@code ROUTED} or {@code REPLY} — whatsapp-shared-sender-design.md §8.2 (V99). */
    @Column(name = "scope", nullable = false, length = 8)
    private String scope = "ROUTED";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected WhatsAppKeywordActionEntity() {}

    public WhatsAppKeywordActionEntity(String tenantId, String senderId, String prefix, String forwardUrl) {
        this.tenantId = tenantId;
        this.senderId = senderId;
        this.prefix = prefix;
        this.forwardUrl = forwardUrl;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSenderId() { return senderId; }
    public String getPrefix() { return prefix; }
    public String getForwardUrl() { return forwardUrl; }
    public boolean isEnabled() { return enabled; }
    public String getScope() { return scope; }
    public void setScope(String v) { this.scope = v == null ? "ROUTED" : v; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
