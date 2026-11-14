package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row = "for tenant T, trust HS256-signed JWTs whose {@code iss}
 * claim equals {@code iss}; verify the signature against the HMAC key
 * recovered by AES-GCM-decrypting (secret_ciphertext, secret_iv) with
 * the master key from yaya.agentic.secrets.encryption-key."
 *
 * <p>Written by the {@code /v1/admin/tenants/{id}/jwt-secrets} CRUD,
 * which takes plaintext from the operator and ciphertexts before
 * persisting. Read by {@code SharedSecretJwtAuthenticator} which
 * decrypts on each cache load. The entity itself is crypto-free —
 * encryption happens at the controller layer (mirrors how the rest of
 * the persistence layer stays Jackson-free).</p>
 */
@Entity
@Table(name = "tenant_jwt_secrets")
public class TenantJwtSecretEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 512)
    private String iss;

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String secretCiphertext;

    @Column(name = "secret_iv", nullable = false, length = 64)
    private String secretIv;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String audiences = "[]";

    // Nullable: the label a profile's metadata.principalKinds is matched against
    // for principals this issuer verified.
    @Column(name = "principal_kind", length = 128)
    private String principalKind;

    // Nullable JSON object {"claim": "value"}: claims every token must carry to be
    // accepted for THIS tenant (pins an issuer several tenants share).
    @Column(name = "required_claims_json", columnDefinition = "TEXT")
    private String requiredClaimsJson;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected TenantJwtSecretEntity() {}

    public TenantJwtSecretEntity(String tenantId, String iss,
                                 String secretCiphertext, String secretIv,
                                 String audiencesJson) {
        this.tenantId = tenantId;
        this.iss = iss;
        this.secretCiphertext = secretCiphertext;
        this.secretIv = secretIv;
        this.audiences = audiencesJson == null ? "[]" : audiencesJson;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getIss() { return iss; }
    public String getSecretCiphertext() { return secretCiphertext; }
    public String getSecretIv() { return secretIv; }
    public String getAudiencesJson() { return audiences; }
    public String getPrincipalKind() { return principalKind; }
    public String getRequiredClaimsJson() { return requiredClaimsJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setIss(String v) { this.iss = v; }
    public void setSecretCiphertext(String v) { this.secretCiphertext = v; }
    public void setSecretIv(String v) { this.secretIv = v; }
    public void setPrincipalKind(String v) { this.principalKind = (v == null || v.isBlank()) ? null : v.trim(); }
    public void setRequiredClaimsJson(String v) { this.requiredClaimsJson = (v == null || v.isBlank()) ? null : v; }
    public void setAudiencesJson(String v) { this.audiences = v == null ? "[]" : v; }
}
