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

    @Column(name = "summary_text")
    private String summaryText;

    @Column(name = "summary_through_turn_idx")
    private Integer summaryThroughTurnIdx;

    @Column(name = "summary_updated_at")
    private OffsetDateTime summaryUpdatedAt;

    @Column(name = "auth_token_ciphertext")
    private String authTokenCiphertext;

    @Column(name = "auth_token_iv", length = 64)
    private String authTokenIv;

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

    public String getSummaryText() { return summaryText; }
    public Integer getSummaryThroughTurnIdx() { return summaryThroughTurnIdx; }
    public OffsetDateTime getSummaryUpdatedAt() { return summaryUpdatedAt; }

    public String getAuthTokenCiphertext() { return authTokenCiphertext; }
    public String getAuthTokenIv() { return authTokenIv; }

    public void setSummaryText(String v) { this.summaryText = v; }
    public void setSummaryThroughTurnIdx(Integer v) { this.summaryThroughTurnIdx = v; }
    public void setSummaryUpdatedAt(OffsetDateTime v) { this.summaryUpdatedAt = v; }

    public void setAuthTokenCiphertext(String v) { this.authTokenCiphertext = v; }
    public void setAuthTokenIv(String v) { this.authTokenIv = v; }
}
