package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "principal_subject", length = 255)
    private String principalSubject;

    @Column(name = "profile_id", nullable = false, length = 128)
    private String profileId;

    @Column(name = "profile_version", nullable = false)
    private Integer profileVersion;

    @Column(length = 64)
    private String channel;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    protected SessionEntity() {}

    public SessionEntity(UUID id, String tenantId, String principalSubject,
                         String profileId, int profileVersion, String channel,
                         OffsetDateTime createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.principalSubject = principalSubject;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.channel = channel;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPrincipalSubject() { return principalSubject; }
    public String getProfileId() { return profileId; }
    public Integer getProfileVersion() { return profileVersion; }
    public String getChannel() { return channel; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }

    public void setEndedAt(OffsetDateTime v) { this.endedAt = v; }
}
