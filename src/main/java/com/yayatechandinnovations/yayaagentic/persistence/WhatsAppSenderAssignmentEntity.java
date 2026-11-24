package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A workspace a platform sender is assigned to (whatsapp-shared-sender-design.md
 * §4.2, V99). {@code label} is the business name recipients see and only a
 * platform admin sets it; {@code nonMemberReply} is the workspace's own.
 */
@Entity
@Table(name = "whatsapp_sender_assignments")
public class WhatsAppSenderAssignmentEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String ENDED = "ENDED";

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "label", nullable = false, length = 60)
    private String label;

    @Column(name = "non_member_reply")
    private String nonMemberReply;

    @Column(name = "daily_outbound_cap", nullable = false)
    private int dailyOutboundCap;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ACTIVE;

    @Column(name = "assigned_by", nullable = false, length = 128)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "ended_by", length = 128)
    private String endedBy;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    protected WhatsAppSenderAssignmentEntity() {}

    public WhatsAppSenderAssignmentEntity(String id, String senderId, String tenantId, String label,
                                          int dailyOutboundCap, String assignedBy) {
        this.id = id;
        this.senderId = senderId;
        this.tenantId = tenantId;
        this.label = label;
        this.dailyOutboundCap = dailyOutboundCap;
        this.assignedBy = assignedBy;
        this.assignedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getSenderId() { return senderId; }
    public String getTenantId() { return tenantId; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public String getNonMemberReply() { return nonMemberReply; }
    public void setNonMemberReply(String v) { this.nonMemberReply = v; }
    public int getDailyOutboundCap() { return dailyOutboundCap; }
    public void setDailyOutboundCap(int v) { this.dailyOutboundCap = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getAssignedBy() { return assignedBy; }
    public OffsetDateTime getAssignedAt() { return assignedAt; }
    public String getEndedBy() { return endedBy; }
    public void setEndedBy(String v) { this.endedBy = v; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(OffsetDateTime v) { this.endedAt = v; }

    public boolean isLive() { return !ENDED.equals(status); }
}
