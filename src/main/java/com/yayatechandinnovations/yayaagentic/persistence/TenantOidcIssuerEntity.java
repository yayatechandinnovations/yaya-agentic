package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row = "for tenant T, trust JWTs whose {@code iss} claim equals
 * {@code iss}; verify signatures against {@code jwks_uri}; if {@code audiences}
 * is non-empty, require the token's {@code aud} to be one of them."
 *
 * <p>Read by {@code OidcAuthenticator} (cached in-memory and invalidated by
 * {@code TenantOidcIssuersChangedEvent}); written by the
 * {@code /v1/admin/tenants/{id}/oidc-issuers} CRUD.</p>
 *
 * <p>{@code audiences} is stored as a raw JSON string for the same reason
 * the rest of the persistence layer does — the controller does the
 * {@code List<String>} ↔ JSON dance so the entity stays Jackson-free.</p>
 */
@Entity
@Table(name = "tenant_oidc_issuers")
public class TenantOidcIssuerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 512)
    private String iss;

    @Column(name = "jwks_uri", nullable = false, length = 1024)
    private String jwksUri;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String audiences = "[]";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected TenantOidcIssuerEntity() {}

    public TenantOidcIssuerEntity(String tenantId, String iss, String jwksUri, String audiencesJson) {
        this.tenantId = tenantId;
        this.iss = iss;
        this.jwksUri = jwksUri;
        this.audiences = audiencesJson == null ? "[]" : audiencesJson;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getIss() { return iss; }
    public String getJwksUri() { return jwksUri; }
    public String getAudiencesJson() { return audiences; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setIss(String v) { this.iss = v; }
    public void setJwksUri(String v) { this.jwksUri = v; }
    public void setAudiencesJson(String v) { this.audiences = v == null ? "[]" : v; }
}
