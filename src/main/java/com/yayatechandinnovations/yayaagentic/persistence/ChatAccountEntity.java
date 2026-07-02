package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row of {@code chat_accounts} (V60) — a phone-verified chat identity. The
 * {@code name} is self-asserted (typed by the user, never verified); only the
 * phone carries the platform's Twilio verification. Keyed by E.164 phone and
 * tenant-agnostic, mirroring the platform-signed device pass it backs.
 */
@Entity
@Table(name = "chat_accounts")
public class ChatAccountEntity {

    @Id
    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    protected ChatAccountEntity() {}

    public ChatAccountEntity(String phoneE164, String name) {
        this.phoneE164 = phoneE164;
        this.name = name;
    }

    @PreUpdate
    void touch() { this.updatedAt = OffsetDateTime.now(); }

    public String getPhoneE164() { return phoneE164; }
    public String getName() { return name; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
}
