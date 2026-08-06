package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One activated device of a chat account ({@code chat-experience-design.md} §5).
 * Holds the SHA-256 of the per-device secret (the secret itself is returned once
 * at enrollment and lives only on the device), the per-device PIN policy, and
 * the revocation state that backs single-device sign-out. The pass {@code gen}
 * on the account stays the account-wide kill switch; {@code revokedAt} here is
 * the per-device one.
 */
@Entity
@Table(name = "chat_account_devices")
@IdClass(ChatAccountDeviceEntity.Key.class)
public class ChatAccountDeviceEntity {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Id
    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "device_secret_hash", nullable = false, length = 64)
    private String deviceSecretHash;

    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "pin_required", nullable = false)
    private boolean pinRequired = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected ChatAccountDeviceEntity() {}

    public ChatAccountDeviceEntity(UUID accountId, String deviceId, String deviceSecretHash, String label) {
        this.accountId = accountId;
        this.deviceId = deviceId;
        this.deviceSecretHash = deviceSecretHash;
        this.label = label;
        this.lastSeenAt = OffsetDateTime.now();
    }

    public UUID getAccountId() { return accountId; }
    public String getDeviceId() { return deviceId; }
    public String getDeviceSecretHash() { return deviceSecretHash; }
    public String getLabel() { return label; }
    public boolean isPinRequired() { return pinRequired; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }

    public boolean isRevoked() { return revokedAt != null; }

    public void setDeviceSecretHash(String deviceSecretHash) { this.deviceSecretHash = deviceSecretHash; }
    public void setLabel(String label) { this.label = label; }
    public void setPinRequired(boolean pinRequired) { this.pinRequired = pinRequired; }
    public void touchLastSeen() { this.lastSeenAt = OffsetDateTime.now(); }
    public void revoke() { this.revokedAt = OffsetDateTime.now(); }
    public void unrevoke() { this.revokedAt = null; }

    public static class Key implements Serializable {
        private UUID accountId;
        private String deviceId;

        public Key() {}
        public Key(UUID accountId, String deviceId) {
            this.accountId = accountId;
            this.deviceId = deviceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(accountId, key.accountId)
                    && Objects.equals(deviceId, key.deviceId);
        }

        @Override
        public int hashCode() { return Objects.hash(accountId, deviceId); }
    }
}
