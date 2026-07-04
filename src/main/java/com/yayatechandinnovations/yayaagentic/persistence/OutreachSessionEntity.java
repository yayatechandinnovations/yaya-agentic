package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row of {@code outreach_sessions} (V64) — the durable identity→session
 * index. Maps a recipient key (e.g. {@code principal:op-42}) to the coherent
 * outreach session they own on a chat entry point, so repeat messages reuse one
 * thread (§6, delivery-target decision).
 */
@Entity
@Table(name = "outreach_sessions")
public class OutreachSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "entry_point_id", nullable = false, length = 64)
    private String entryPointId;

    @Column(name = "recipient_key", nullable = false, length = 255)
    private String recipientKey;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "profile_id", nullable = false, length = 128)
    private String profileId;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected OutreachSessionEntity() {}

    public OutreachSessionEntity(String tenantId, String entryPointId, String recipientKey,
                                 String sessionId, String profileId, int profileVersion) {
        this.tenantId = tenantId;
        this.entryPointId = entryPointId;
        this.recipientKey = recipientKey;
        this.sessionId = sessionId;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEntryPointId() { return entryPointId; }
    public String getRecipientKey() { return recipientKey; }
    public String getSessionId() { return sessionId; }
    public String getProfileId() { return profileId; }
    public int getProfileVersion() { return profileVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
