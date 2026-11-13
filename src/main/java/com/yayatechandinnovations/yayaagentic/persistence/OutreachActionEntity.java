package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row of {@code outreach_actions} (V88) — an action button a proactive
 * message offered (whatsapp-ops-integration-design.md §6.4). The id is the
 * capability the button carries ({@code act:<id>} on WhatsApp, {@code actionId}
 * in chat).
 */
@Entity
@Table(name = "outreach_actions")
public class OutreachActionEntity {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "outreach_id", nullable = false, length = 64)
    private String outreachId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "label", nullable = false, length = 64)
    private String label;

    @Column(name = "style", nullable = false, length = 16)
    private String style;

    @Column(name = "send_text")
    private String sendText;

    @Column(name = "tool_id", length = 128)
    private String toolId;

    @Column(name = "args_json")
    private String argsJson;

    @Column(name = "ask_json")
    private String askJson;

    @Column(name = "owner_subject", length = 255)
    private String ownerSubject;

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "consumed_by", length = 255)
    private String consumedBy;

    @Column(name = "result_json")
    private String resultJson;

    protected OutreachActionEntity() {}

    public OutreachActionEntity(String id, String tenantId, String outreachId, int position,
                                String kind, String label, String style, OffsetDateTime expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.outreachId = outreachId;
        this.position = position;
        this.kind = kind;
        this.label = label;
        this.style = style;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getOutreachId() { return outreachId; }
    public int getPosition() { return position; }
    public String getKind() { return kind; }
    public String getLabel() { return label; }
    public String getStyle() { return style; }
    public String getSendText() { return sendText; }
    public void setSendText(String v) { this.sendText = v; }
    public String getToolId() { return toolId; }
    public void setToolId(String v) { this.toolId = v; }
    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String v) { this.argsJson = v; }
    public String getAskJson() { return askJson; }
    public void setAskJson(String v) { this.askJson = v; }
    public String getOwnerSubject() { return ownerSubject; }
    public void setOwnerSubject(String v) { this.ownerSubject = v; }
    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String v) { this.recipientPhone = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public String getConsumedBy() { return consumedBy; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String v) { this.resultJson = v; }
}
