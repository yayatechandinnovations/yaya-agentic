package com.yayatechandinnovations.yayaidentity.policies;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.PolicyId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "policies")
public class PolicyEntity {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String effect;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_json", nullable = false)
    private String targetJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_json")
    private String conditionJson;

    @Column(nullable = false)
    private int priority;

    @Column(name = "user_reason")
    private String userReason;

    @Column(name = "audit_reason")
    private String auditReason;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PolicyEntity() {}

    public PolicyEntity(String id, String orgId, String name, Policy.Effect effect, String targetJson,
                        String conditionJson, int priority, String userReason, String auditReason,
                        Instant createdAt) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.effect = effect.name();
        this.targetJson = targetJson;
        this.conditionJson = conditionJson;
        this.priority = priority;
        this.userReason = userReason;
        this.auditReason = auditReason;
        this.active = true;
        this.createdAt = createdAt;
    }

    public Policy toDomain() {
        return new Policy(new PolicyId(id), new OrgId(orgId), name, Policy.Effect.valueOf(effect),
                targetJson, conditionJson, priority, userReason, auditReason, active);
    }

    public Policy.Effect effect() { return Policy.Effect.valueOf(effect); }
    public String getTargetJson() { return targetJson; }
    public String getConditionJson() { return conditionJson; }
    public int getPriority() { return priority; }
    public String getUserReason() { return userReason; }
    public String getAuditReason() { return auditReason; }
}
