package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One member blocking another in a forum (Apple Guideline 1.2). One-way and
 * per-entry-point: the blocker stops seeing the blocked member's posts/replies.
 */
@Entity
@Table(name = "forum_blocks")
public class ForumBlockEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "entry_point_id", nullable = false, length = 64)
    private String entryPointId;

    @Column(name = "blocker_account_id", nullable = false)
    private UUID blockerAccountId;

    @Column(name = "blocked_account_id", nullable = false)
    private UUID blockedAccountId;

    @Column(name = "blocked_display", length = 255)
    private String blockedDisplay;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ForumBlockEntity() {}

    public ForumBlockEntity(String id, String tenantId, String entryPointId,
                            UUID blockerAccountId, UUID blockedAccountId, String blockedDisplay) {
        this.id = id;
        this.tenantId = tenantId;
        this.entryPointId = entryPointId;
        this.blockerAccountId = blockerAccountId;
        this.blockedAccountId = blockedAccountId;
        this.blockedDisplay = blockedDisplay;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEntryPointId() { return entryPointId; }
    public UUID getBlockerAccountId() { return blockerAccountId; }
    public UUID getBlockedAccountId() { return blockedAccountId; }
    public String getBlockedDisplay() { return blockedDisplay; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
