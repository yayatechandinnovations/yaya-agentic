package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row per inbound request denied by the origin allowlist (§5).
 * Tenant id is nullable so an unknown-tenant attempt still gets a trace.
 */
@Entity
@Table(name = "audit_inbound_origin_denied")
public class AuditInboundOriginDeniedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "principal", length = 255)
    private String principal;

    @Column(name = "origin")
    private String origin;

    @Column(name = "path")
    private String path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected", columnDefinition = "jsonb", nullable = false)
    private String expectedJson = "[]";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuditInboundOriginDeniedEntity() {}

    public AuditInboundOriginDeniedEntity(String tenantId, String principal,
                                          String origin, String path,
                                          String expectedJson) {
        this.tenantId = tenantId;
        this.principal = principal;
        this.origin = origin;
        this.path = path;
        this.expectedJson = expectedJson == null ? "[]" : expectedJson;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPrincipal() { return principal; }
    public String getOrigin() { return origin; }
    public String getPath() { return path; }
    public String getExpectedJson() { return expectedJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
