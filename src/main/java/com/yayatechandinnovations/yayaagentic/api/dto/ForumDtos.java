package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.persistence.ForumPostEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReportEntity;

import java.util.List;
import java.util.Map;

/** Wire DTOs for the community forum ({@code chat-experience-design.md} §8). */
public final class ForumDtos {

    private ForumDtos() {}

    public record PostView(String id, String authorKind, String authorDisplay,
                           String body, String replyTo, boolean pinned,
                           int replyCount, Map<String, Integer> reactions,
                           boolean edited, String createdAt, boolean mine) {
        public static PostView of(ForumPostEntity p, java.util.UUID callerAccount) {
            return new PostView(p.getId(), p.getAuthorKind(), p.getAuthorDisplay(),
                    p.getBody(), p.getReplyTo(), p.isPinned(), p.getReplyCount(),
                    p.getReactionCounts() == null ? Map.of() : p.getReactionCounts(),
                    p.getEditedAt() != null,
                    p.getCreatedAt() == null ? null : p.getCreatedAt().toString(),
                    callerAccount != null && callerAccount.equals(p.getAccountId()));
        }
    }

    public record FeedResponse(boolean enabled, List<PostView> posts, String nextCursor) {}

    public record RepliesResponse(List<PostView> replies) {}

    public record CreatePostRequest(String body, String replyTo) {}

    public record EditPostRequest(String body) {}

    public record ReportRequest(String reason) {}

    /* ---- trusted-sender surface (§8.1) ---- */

    public record SenderPostRequest(String handle, String body, String displayName,
                                    Boolean pinned) {}

    public record SenderPostResponse(String postId) {}

    /* ---- admin / moderation ---- */

    public record SettingsRequest(Boolean enabled) {}

    public record SettingsResponse(boolean enabled) {}

    public record AdminPostView(String id, String authorKind, String authorDisplay,
                                String body, String replyTo, boolean pinned, boolean hidden,
                                int replyCount, int reportCount, String createdAt) {
        public static AdminPostView of(ForumPostEntity p) {
            return new AdminPostView(p.getId(), p.getAuthorKind(), p.getAuthorDisplay(),
                    p.getBody(), p.getReplyTo(), p.isPinned(), p.isHidden(),
                    p.getReplyCount(), p.getReportCount(),
                    p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
        }
    }

    public record ModerateRequest(Boolean hidden, Boolean pinned, Boolean delete) {}

    public record OperatorPostRequest(String body, String displayName, Boolean pinned) {}

    public record ReportView(String id, String postId, String reason, String createdAt) {
        public static ReportView of(ForumReportEntity r) {
            return new ReportView(r.getId(), r.getPostId(), r.getReason(),
                    r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        }
    }

    public record ReportsResponse(List<ReportView> reports) {}
}
