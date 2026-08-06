package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** One account's emoji reaction on a post ({@code chat-experience-design.md} §8.1). */
@Entity
@Table(name = "forum_reactions")
@IdClass(ForumReactionEntity.Key.class)
public class ForumReactionEntity {

    @Id
    @Column(name = "post_id", nullable = false, length = 64)
    private String postId;

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Id
    @Column(name = "emoji", nullable = false, length = 16)
    private String emoji;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ForumReactionEntity() {}

    public ForumReactionEntity(String postId, UUID accountId, String emoji) {
        this.postId = postId;
        this.accountId = accountId;
        this.emoji = emoji;
    }

    public String getPostId() { return postId; }
    public UUID getAccountId() { return accountId; }
    public String getEmoji() { return emoji; }

    public static class Key implements Serializable {
        private String postId;
        private UUID accountId;
        private String emoji;

        public Key() {}
        public Key(String postId, UUID accountId, String emoji) {
            this.postId = postId;
            this.accountId = accountId;
            this.emoji = emoji;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(postId, key.postId)
                    && Objects.equals(accountId, key.accountId)
                    && Objects.equals(emoji, key.emoji);
        }

        @Override
        public int hashCode() { return Objects.hash(postId, accountId, emoji); }
    }
}
