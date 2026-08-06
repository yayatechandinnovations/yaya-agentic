package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One forum post or reply ({@code chat-experience-design.md} §8). Denormalized
 * counters (replyCount, reactionCounts, reportCount) keep the feed page a
 * single query; the reaction/report tables stay authoritative.
 */
@Entity
@Table(name = "forum_posts")
public class ForumPostEntity {

    public static final String AUTHOR_USER = "USER";
    public static final String AUTHOR_SYSTEM = "SYSTEM";
    public static final String AUTHOR_OPERATOR = "OPERATOR";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "entry_point_id", nullable = false, length = 64)
    private String entryPointId;

    @Column(name = "author_kind", nullable = false, length = 10)
    private String authorKind;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "sender_id", length = 64)
    private String senderId;

    @Column(name = "author_display", nullable = false, length = 120)
    private String authorDisplay;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "reply_to", length = 64)
    private String replyTo;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "hidden", nullable = false)
    private boolean hidden;

    @Column(name = "reply_count", nullable = false)
    private int replyCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reaction_counts", columnDefinition = "jsonb", nullable = false)
    private Map<String, Integer> reactionCounts = new HashMap<>();

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected ForumPostEntity() {}

    public ForumPostEntity(String id, String tenantId, String entryPointId, String authorKind,
                           UUID accountId, String senderId, String authorDisplay,
                           String body, String replyTo) {
        this.id = id;
        this.tenantId = tenantId;
        this.entryPointId = entryPointId;
        this.authorKind = authorKind;
        this.accountId = accountId;
        this.senderId = senderId;
        this.authorDisplay = authorDisplay;
        this.body = body;
        this.replyTo = replyTo;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEntryPointId() { return entryPointId; }
    public String getAuthorKind() { return authorKind; }
    public UUID getAccountId() { return accountId; }
    public String getSenderId() { return senderId; }
    public String getAuthorDisplay() { return authorDisplay; }
    public String getBody() { return body; }
    public String getReplyTo() { return replyTo; }
    public boolean isPinned() { return pinned; }
    public boolean isHidden() { return hidden; }
    public int getReplyCount() { return replyCount; }
    public Map<String, Integer> getReactionCounts() { return reactionCounts; }
    public int getReportCount() { return reportCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getEditedAt() { return editedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }

    public boolean isDeleted() { return deletedAt != null; }
    public boolean isVisible() { return !hidden && !isDeleted(); }
    public boolean isReply() { return replyTo != null; }

    public void edit(String body) {
        this.body = body;
        this.editedAt = OffsetDateTime.now();
    }

    public void softDelete() { this.deletedAt = OffsetDateTime.now(); }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public void bumpReplyCount(int delta) { this.replyCount = Math.max(0, this.replyCount + delta); }
    public void bumpReportCount() { this.reportCount += 1; }

    public void applyReaction(String emoji, int delta) {
        Map<String, Integer> counts = new HashMap<>(reactionCounts == null ? Map.of() : reactionCounts);
        int next = counts.getOrDefault(emoji, 0) + delta;
        if (next <= 0) {
            counts.remove(emoji);
        } else {
            counts.put(emoji, next);
        }
        this.reactionCounts = counts;
    }
}
