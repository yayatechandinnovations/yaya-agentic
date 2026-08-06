package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One report against a post ({@code chat-experience-design.md} §8.2). */
@Entity
@Table(name = "forum_reports")
public class ForumReportEntity {

    public static final String RESOLUTION_DISMISSED = "DISMISSED";
    public static final String RESOLUTION_UPHELD = "UPHELD";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "post_id", nullable = false, length = 64)
    private String postId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution", length = 20)
    private String resolution;

    protected ForumReportEntity() {}

    public ForumReportEntity(String id, String postId, UUID accountId, String reason) {
        this.id = id;
        this.postId = postId;
        this.accountId = accountId;
        this.reason = reason;
    }

    public String getId() { return id; }
    public String getPostId() { return postId; }
    public UUID getAccountId() { return accountId; }
    public String getReason() { return reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public String getResolution() { return resolution; }

    public void resolve(String resolution) {
        this.resolvedAt = OffsetDateTime.now();
        this.resolution = resolution;
    }
}
