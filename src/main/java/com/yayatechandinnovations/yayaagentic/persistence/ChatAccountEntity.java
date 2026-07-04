package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code chat_accounts} — a phone-verified chat identity
 * ({@code chat-account-self-service-design.md} §5). Keyed by a stable UUID so the
 * account survives a phone change; the {@code phoneE164} is a mutable, unique
 * attribute (verified by Twilio), and {@code name} is self-asserted (the user
 * typed it, never verified). Tenant-agnostic, mirroring the platform-signed
 * device pass it backs.
 *
 * <p>{@code passGen} is the device-pass revocation generation: a pass is honoured
 * only while its {@code gen} claim equals this value, so bumping it revokes every
 * outstanding pass at once (delete, phone change, sign-out-everywhere).
 */
@Entity
@Table(name = "chat_accounts")
public class ChatAccountEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "pass_gen", nullable = false)
    private int passGen;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected ChatAccountEntity() {}

    public ChatAccountEntity(UUID id, String phoneE164, String name) {
        this.id = id;
        this.phoneE164 = phoneE164;
        this.name = name;
        this.status = STATUS_ACTIVE;
        this.passGen = 1;
    }

    @PreUpdate
    void touch() { this.updatedAt = OffsetDateTime.now(); }

    public UUID getId() { return id; }
    public String getPhoneE164() { return phoneE164; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public int getPassGen() { return passGen; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }

    public void setName(String name) { this.name = name; }
    public void setPhoneE164(String phoneE164) { this.phoneE164 = phoneE164; }
    public void setStatus(String status) { this.status = status; }
    public void bumpPassGen() { this.passGen += 1; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
