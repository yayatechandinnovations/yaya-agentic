package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One row = "requests bearing an {@code X-Yaya-Service-Token} for tenant T
 * verify their HMAC against the key recovered by AES-GCM-decrypting
 * (secret_ciphertext, secret_iv) with the master key."
 *
 * <p>Written by {@code /v1/admin/tenants/{id}/service-token-secret}, read by
 * {@code ServiceTokenAuthenticator} (DB-first, config-map fallback). Like
 * {@link TenantJwtSecretEntity}, the entity is crypto-free — encryption
 * happens at the controller layer, decryption at the authenticator.</p>
 */
@Entity
@Table(name = "tenant_service_token_secrets")
public class TenantServiceTokenSecretEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String secretCiphertext;

    @Column(name = "secret_iv", nullable = false, length = 64)
    private String secretIv;

    @Column(name = "rotated_at", nullable = false)
    private OffsetDateTime rotatedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected TenantServiceTokenSecretEntity() {}

    public TenantServiceTokenSecretEntity(String tenantId, String secretCiphertext,
                                          String secretIv, OffsetDateTime rotatedAt) {
        this.tenantId = tenantId;
        this.secretCiphertext = secretCiphertext;
        this.secretIv = secretIv;
        this.rotatedAt = rotatedAt;
    }

    public String getTenantId() { return tenantId; }
    public String getSecretCiphertext() { return secretCiphertext; }
    public String getSecretIv() { return secretIv; }
    public OffsetDateTime getRotatedAt() { return rotatedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void rotate(String secretCiphertext, String secretIv, OffsetDateTime rotatedAt) {
        this.secretCiphertext = secretCiphertext;
        this.secretIv = secretIv;
        this.rotatedAt = rotatedAt;
    }
}
