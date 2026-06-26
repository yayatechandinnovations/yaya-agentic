package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code channel_bindings}. Routing + session-policy live as JSON
 * so the polymorphic {@code RoutingRule} sealed type round-trips without
 * subclass tables. The service layer converts to/from the typed
 * {@code ChannelBinding} record at the boundary.
 */
@Entity
@Table(name = "channel_bindings")
public class ChannelBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "endpoint_kind", nullable = false, length = 32)
    private String endpointKind;

    @Column(name = "endpoint_id", nullable = false, length = 64)
    private String endpointId;

    @Column(name = "profile_id", nullable = false, length = 128)
    private String profileId;

    @Column(name = "profile_version")
    private Integer profileVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "routing_json", columnDefinition = "jsonb", nullable = false)
    private String routingJson = "{\"kind\":\"MatchAny\"}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "session_policy_json", columnDefinition = "jsonb", nullable = false)
    private String sessionPolicyJson;

    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deactivated_at")
    private OffsetDateTime deactivatedAt;

    protected ChannelBindingEntity() {}

    public ChannelBindingEntity(String tenantId, String endpointKind, String endpointId,
                                String profileId, Integer profileVersion,
                                String routingJson, String sessionPolicyJson,
                                int priority, String status) {
        this.tenantId = tenantId;
        this.endpointKind = endpointKind;
        this.endpointId = endpointId;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.routingJson = routingJson;
        this.sessionPolicyJson = sessionPolicyJson;
        this.priority = priority;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEndpointKind() { return endpointKind; }
    public String getEndpointId() { return endpointId; }
    public String getProfileId() { return profileId; }
    public Integer getProfileVersion() { return profileVersion; }
    public String getRoutingJson() { return routingJson; }
    public String getSessionPolicyJson() { return sessionPolicyJson; }
    public int getPriority() { return priority; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDeactivatedAt() { return deactivatedAt; }

    public void setProfileId(String v) { this.profileId = v; }
    public void setProfileVersion(Integer v) { this.profileVersion = v; }
    public void setRoutingJson(String v) { this.routingJson = v; }
    public void setSessionPolicyJson(String v) { this.sessionPolicyJson = v; }
    public void setPriority(int v) { this.priority = v; }
    public void setStatus(String v) { this.status = v; }
    public void setDeactivatedAt(OffsetDateTime v) { this.deactivatedAt = v; }
}
