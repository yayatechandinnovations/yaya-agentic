package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Email verification token. PK is sha256(rawToken); the raw token appears
 * only in the outbound mail body.
 */
@Entity
@Table(name = "user_email_verification_tokens")
public class EmailVerificationTokenEntity {

    @Id
    @Column(name = "token_hash", nullable = false)
    private byte[] tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected EmailVerificationTokenEntity() {}

    public EmailVerificationTokenEntity(byte[] tokenHash, UUID userId,
                                        OffsetDateTime expiresAt, OffsetDateTime createdAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public byte[] getTokenHash() { return tokenHash; }
    public UUID getUserId() { return userId; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setConsumedAt(OffsetDateTime consumedAt) { this.consumedAt = consumedAt; }
}
