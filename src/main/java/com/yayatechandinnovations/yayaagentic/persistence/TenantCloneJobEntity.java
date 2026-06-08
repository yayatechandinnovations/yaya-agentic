package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per cross-tenant profile clone (dry-run OR applied) per
 * docs/design/tenant-registry-design.md §7.7.
 */
@Entity
@Table(name = "tenant_clone_jobs")
public class TenantCloneJobEntity {

    public enum Status { DRY_RUN, APPLIED, FAILED }

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "source_tenant", nullable = false, length = 64)
    private String sourceTenant;

    @Column(name = "destination_tenant", nullable = false, length = 64)
    private String destinationTenant;

    @Column(name = "source_profile_id", nullable = false, length = 128)
    private String sourceProfileId;

    @Column(name = "source_version", nullable = false)
    private Integer sourceVersion;

    @Column(name = "destination_profile_id", length = 128)
    private String destinationProfileId;

    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_json", columnDefinition = "jsonb", nullable = false)
    private String planJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_json", columnDefinition = "jsonb")
    private String errorJson;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "applied_by", length = 255)
    private String appliedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected TenantCloneJobEntity() {}

    public TenantCloneJobEntity(UUID id, String sourceTenant, String destinationTenant,
                                String sourceProfileId, int sourceVersion, Status status) {
        this.id = id;
        this.sourceTenant = sourceTenant;
        this.destinationTenant = destinationTenant;
        this.sourceProfileId = sourceProfileId;
        this.sourceVersion = sourceVersion;
        this.status = status.name();
    }

    public UUID getId() { return id; }
    public String getSourceTenant() { return sourceTenant; }
    public String getDestinationTenant() { return destinationTenant; }
    public String getSourceProfileId() { return sourceProfileId; }
    public Integer getSourceVersion() { return sourceVersion; }
    public String getDestinationProfileId() { return destinationProfileId; }
    public String getStatus() { return status; }
    public String getPlanJson() { return planJson; }
    public String getErrorJson() { return errorJson; }
    public OffsetDateTime getAppliedAt() { return appliedAt; }
    public String getAppliedBy() { return appliedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setDestinationProfileId(String v) { this.destinationProfileId = v; }
    public void setStatus(Status v) { this.status = v.name(); }
    public void setPlanJson(String v) { this.planJson = v; }
    public void setErrorJson(String v) { this.errorJson = v; }
    public void setAppliedAt(OffsetDateTime v) { this.appliedAt = v; }
    public void setAppliedBy(String v) { this.appliedBy = v; }
}
