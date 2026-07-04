package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One in-flight phone-change ceremony ({@code chat-account-self-service-design.md}
 * §6.2). Persists the state between the two OTP proofs (old number, then new
 * number) so the steps can't be spoofed out of order.
 */
@Entity
@Table(name = "chat_phone_changes")
public class ChatPhoneChangeEntity {

    public static final String OLD_PENDING = "OLD_PENDING";
    public static final String NEW_PENDING = "NEW_PENDING";
    public static final String COMPLETE = "COMPLETE";
    public static final String ABORTED = "ABORTED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "old_phone", nullable = false, length = 20)
    private String oldPhone;

    @Column(name = "new_phone", nullable = false, length = 20)
    private String newPhone;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "old_verified_at")
    private OffsetDateTime oldVerifiedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected ChatPhoneChangeEntity() {}

    public ChatPhoneChangeEntity(UUID id, UUID accountId, String oldPhone, String newPhone,
                                 OffsetDateTime expiresAt) {
        this.id = id;
        this.accountId = accountId;
        this.oldPhone = oldPhone;
        this.newPhone = newPhone;
        this.state = OLD_PENDING;
        this.attempts = 0;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public String getOldPhone() { return oldPhone; }
    public String getNewPhone() { return newPhone; }
    public String getState() { return state; }
    public OffsetDateTime getOldVerifiedAt() { return oldVerifiedAt; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }

    public boolean isExpired() { return OffsetDateTime.now().isAfter(expiresAt); }

    public void setState(String state) { this.state = state; }
    public void markOldVerified() { this.oldVerifiedAt = OffsetDateTime.now(); }
    public int bumpAttempts() { return ++this.attempts; }
}
