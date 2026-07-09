package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** The §8.9 invite gate — single-use, expiring; only the token's SHA-256 is stored. */
@Entity
@Table(name = "delivery_agent_invites")
public class DeliveryAgentInviteEntity {

    @Id
    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DeliveryAgentInviteEntity() {}

    public DeliveryAgentInviteEntity(String tokenHash, String storeId, String createdBy,
                                     Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.storeId = storeId;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public String getTokenHash() { return tokenHash; }
    public String getStoreId() { return storeId; }
    public String getCreatedBy() { return createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }

    public boolean usable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant at) { this.usedAt = at; }
}
