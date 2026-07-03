package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * JPA row for a {@link com.yayatechandinnovations.yayaagentic.startflow.StartFlow}.
 * Mirrors {@link ProfileEntity}: composite {@code (tenant_id, id, version)} key,
 * sealed-type collections stored as {@code jsonb} strings. See
 * {@code docs/design/session-start-flows.md} §10.
 */
@Entity
@Table(name = "session_start_flows")
@IdClass(StartFlowEntity.PK.class)
public class StartFlowEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(length = 128)
    private String id;

    @Id
    private Integer version;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps_json", columnDefinition = "jsonb", nullable = false)
    private String stepsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "greeting_json", columnDefinition = "jsonb", nullable = false)
    private String greetingJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_json", columnDefinition = "jsonb", nullable = false)
    private String policyJson = "{}";

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected StartFlowEntity() {}

    public StartFlowEntity(String tenantId, String id, int version, String name) {
        this.tenantId = tenantId;
        this.id = id;
        this.version = version;
        this.name = name;
    }

    public String getTenantId() { return tenantId; }
    public String getId() { return id; }
    public Integer getVersion() { return version; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getStepsJson() { return stepsJson; }
    public String getGreetingJson() { return greetingJson; }
    public String getPolicyJson() { return policyJson; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String v) { this.name = v; }
    public void setDescription(String v) { this.description = v; }
    public void setStepsJson(String v) { this.stepsJson = v; }
    public void setGreetingJson(String v) { this.greetingJson = v; }
    public void setPolicyJson(String v) { this.policyJson = v; }
    public void setStatus(String v) { this.status = v; }

    public static class PK implements Serializable {
        private String tenantId;
        private String id;
        private Integer version;

        public PK() {}
        public PK(String tenantId, String id, Integer version) {
            this.tenantId = tenantId; this.id = id; this.version = version;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(id, pk.id)
                    && Objects.equals(version, pk.version);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, id, version); }
    }
}
