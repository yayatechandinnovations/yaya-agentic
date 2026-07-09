package com.yayatechandinnovations.yayaidentity.credentials;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "credentials")
public class CredentialEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "secret_ref", nullable = false)
    private String secretRef;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(nullable = false)
    private int attempts;

    protected CredentialEntity() {}

    public CredentialEntity(String id, String userId, CredentialKind kind, String secretRef,
                            Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.kind = kind.name();
        this.secretRef = secretRef;
        this.status = "ACTIVE";
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.attempts = 0;
    }

    public String getSecretRef() { return secretRef; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttempts() { return attempts; }

    public void setStatus(String status) { this.status = status; }
    public void markConsumed(Instant at) { this.status = "CONSUMED"; this.consumedAt = at; }
    public void incrementAttempts() { this.attempts++; }
}
