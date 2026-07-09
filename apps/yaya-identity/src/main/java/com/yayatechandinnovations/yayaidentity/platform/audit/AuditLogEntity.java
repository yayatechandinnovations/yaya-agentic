package com.yayatechandinnovations.yayaidentity.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id")
    private String orgId;

    private String actor;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    private String subject;
    private String action;
    private String resource;
    private String decision;

    @Column(name = "user_reason")
    private String userReason;

    @Column(name = "audit_reason")
    private String auditReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json")
    private String detailJson;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected AuditLogEntity() {}

    public AuditLogEntity(AuditEntry e, Instant at) {
        this.orgId = e.orgId();
        this.actor = e.actor();
        this.eventType = e.eventType();
        this.subject = e.subject();
        this.action = e.action();
        this.resource = e.resource();
        this.decision = e.decision() == null ? null : e.decision().name();
        this.userReason = e.userReason();
        this.auditReason = e.auditReason();
        this.detailJson = e.detailJson();
        this.at = at;
    }

    public Long getId() { return id; }
    public String getOrgId() { return orgId; }
    public String getEventType() { return eventType; }
    public String getSubject() { return subject; }
    public String getAction() { return action; }
    public String getResource() { return resource; }
    public String getDecision() { return decision; }
    public String getUserReason() { return userReason; }
    public String getAuditReason() { return auditReason; }
    public Instant getAt() { return at; }
}
