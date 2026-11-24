package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** Audit of who assigned, relabelled, paused or ended a platform-sender assignment (V99). */
@Entity
@Table(name = "whatsapp_sender_assignment_events")
public class WhatsAppSenderAssignmentEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb", nullable = false)
    private String detailJson = "{}";

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected WhatsAppSenderAssignmentEventEntity() {}

    public WhatsAppSenderAssignmentEventEntity(String senderId, String tenantId, String action,
                                               String detailJson, String actor) {
        this.senderId = senderId;
        this.tenantId = tenantId;
        this.action = action;
        this.detailJson = detailJson == null || detailJson.isBlank() ? "{}" : detailJson;
        this.actor = actor;
        this.occurredAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getSenderId() { return senderId; }
    public String getTenantId() { return tenantId; }
    public String getAction() { return action; }
    public String getDetailJson() { return detailJson; }
    public String getActor() { return actor; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
