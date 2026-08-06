package com.yayatechandinnovations.yayaagentic.forum;

import com.yayatechandinnovations.yayaagentic.persistence.ForumPostEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ForumPostRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReactionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReportRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumSettingsRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReactionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReportEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ForumSettingsEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The forum domain ({@code chat-experience-design.md} §8): per-entry-point
 * feed, single-level replies, fixed-set reactions, reports with auto-hide.
 * Forum content is a human surface — it never feeds an LLM prompt (§8.2).
 */
@Service
public class ForumService {

    public static final Set<String> ALLOWED_REACTIONS =
            Set.of("👍", "❤️", "🎉",
                   "😂", "😮", "🙏");

    static final int MAX_BODY_CHARS = 2_000;
    static final int MAX_LINKS = 3;
    static final int AUTO_HIDE_REPORTS = 5;
    static final int PAGE_SIZE = 20;
    static final Duration EDIT_WINDOW = Duration.ofMinutes(15);

    private static final Pattern LINK = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);

    public static class ForumException extends RuntimeException {
        private final String code;

        public ForumException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    private final ForumPostRepository posts;
    private final ForumReactionRepository reactions;
    private final ForumReportRepository reports;
    private final ForumSettingsRepository settings;

    public ForumService(ForumPostRepository posts,
                        ForumReactionRepository reactions,
                        ForumReportRepository reports,
                        ForumSettingsRepository settings) {
        this.posts = posts;
        this.reactions = reactions;
        this.reports = reports;
        this.settings = settings;
    }

    /* ------------------------------ settings -------------------------------- */

    @Transactional(readOnly = true)
    public boolean isEnabled(String entryPointId) {
        return settings.findById(entryPointId).map(ForumSettingsEntity::isEnabled).orElse(false);
    }

    @Transactional
    public void setEnabled(String tenantId, String entryPointId, boolean enabled) {
        ForumSettingsEntity row = settings.findById(entryPointId)
                .orElseGet(() -> new ForumSettingsEntity(entryPointId, tenantId, enabled));
        row.setEnabled(enabled);
        settings.save(row);
    }

    /* ------------------------------- posting -------------------------------- */

    @Transactional
    public ForumPostEntity postAsUser(String tenantId, String entryPointId, UUID accountId,
                                      String displayName, String body, String replyTo) {
        return post(tenantId, entryPointId, ForumPostEntity.AUTHOR_USER, accountId, null,
                displayName, body, replyTo, false);
    }

    /** Trusted integration sender ({@code system-messaging} trust, §8.1). */
    @Transactional
    public ForumPostEntity postAsSender(String tenantId, String entryPointId, String senderId,
                                        String displayName, String body, boolean pinned) {
        ForumPostEntity created = post(tenantId, entryPointId, ForumPostEntity.AUTHOR_SYSTEM,
                null, senderId, displayName, body, null, pinned);
        return created;
    }

    @Transactional
    public ForumPostEntity postAsOperator(String tenantId, String entryPointId,
                                          String displayName, String body, boolean pinned) {
        return post(tenantId, entryPointId, ForumPostEntity.AUTHOR_OPERATOR, null, null,
                displayName, body, null, pinned);
    }

    private ForumPostEntity post(String tenantId, String entryPointId, String kind,
                                 UUID accountId, String senderId, String displayName,
                                 String body, String replyTo, boolean pinned) {
        String clean = validateBody(body);
        ForumPostEntity parent = null;
        if (replyTo != null) {
            parent = posts.findById(replyTo)
                    .filter(p -> p.getEntryPointId().equals(entryPointId))
                    .filter(ForumPostEntity::isVisible)
                    .orElseThrow(() -> new ForumException("not_found", "no such post"));
            if (parent.isReply()) {
                // Single-level threading (§8.1): replies to replies flatten to
                // the top-level parent.
                replyTo = parent.getReplyTo();
                parent = posts.findById(replyTo).orElse(parent);
            }
        }
        String id = "fp_" + UUID.randomUUID().toString().replace("-", "");
        ForumPostEntity created = new ForumPostEntity(id, tenantId, entryPointId, kind,
                accountId, senderId,
                displayName == null || displayName.isBlank() ? "Member" : displayName.trim(),
                clean, replyTo);
        created.setPinned(pinned);
        created = posts.save(created);
        if (parent != null) {
            parent.bumpReplyCount(1);
            posts.save(parent);
        }
        return created;
    }

    @Transactional
    public ForumPostEntity editOwn(String postId, UUID accountId, String body) {
        ForumPostEntity p = requireOwn(postId, accountId);
        if (p.getCreatedAt() != null
                && p.getCreatedAt().plus(EDIT_WINDOW).isBefore(OffsetDateTime.now())) {
            throw new ForumException("edit_window_closed", "this post can no longer be edited");
        }
        p.edit(validateBody(body));
        return posts.save(p);
    }

    @Transactional
    public void deleteOwn(String postId, UUID accountId) {
        ForumPostEntity p = requireOwn(postId, accountId);
        p.softDelete();
        posts.save(p);
        if (p.getReplyTo() != null) {
            posts.findById(p.getReplyTo()).ifPresent(parent -> {
                parent.bumpReplyCount(-1);
                posts.save(parent);
            });
        }
    }

    /* ------------------------------- reading -------------------------------- */

    public record FeedPage(List<ForumPostEntity> posts, String nextCursor) {}

    @Transactional(readOnly = true)
    public FeedPage feed(String entryPointId, String cursor) {
        List<ForumPostEntity> page;
        var pageable = PageRequest.of(0, PAGE_SIZE + 1);
        if (cursor == null || cursor.isBlank()) {
            page = posts.feed(entryPointId, pageable);
        } else {
            int sep = cursor.lastIndexOf('|');
            if (sep <= 0) return new FeedPage(List.of(), null);
            try {
                page = posts.feedAfter(entryPointId,
                        OffsetDateTime.parse(cursor.substring(0, sep)),
                        cursor.substring(sep + 1), pageable);
            } catch (RuntimeException e) {
                return new FeedPage(List.of(), null);
            }
        }
        String next = null;
        if (page.size() > PAGE_SIZE) {
            page = page.subList(0, PAGE_SIZE);
            ForumPostEntity last = page.get(page.size() - 1);
            next = last.getCreatedAt() + "|" + last.getId();
        }
        return new FeedPage(page, next);
    }

    @Transactional(readOnly = true)
    public List<ForumPostEntity> replies(String entryPointId, String postId) {
        // Existence is scoped to the entry point — a foreign post id is a 404.
        posts.findById(postId)
                .filter(p -> p.getEntryPointId().equals(entryPointId))
                .filter(ForumPostEntity::isVisible)
                .orElseThrow(() -> new ForumException("not_found", "no such post"));
        return posts.replies(postId, PageRequest.of(0, 200));
    }

    /* ------------------------------ reactions ------------------------------- */

    @Transactional
    public ForumPostEntity react(String entryPointId, String postId, UUID accountId, String emoji) {
        if (!ALLOWED_REACTIONS.contains(emoji)) {
            throw new ForumException("bad_reaction", "that reaction is not available");
        }
        ForumPostEntity p = requireVisible(entryPointId, postId);
        var key = new ForumReactionEntity.Key(postId, accountId, emoji);
        if (reactions.existsById(key)) return p;
        reactions.save(new ForumReactionEntity(postId, accountId, emoji));
        p.applyReaction(emoji, 1);
        return posts.save(p);
    }

    @Transactional
    public ForumPostEntity unreact(String entryPointId, String postId, UUID accountId, String emoji) {
        ForumPostEntity p = requireVisible(entryPointId, postId);
        var key = new ForumReactionEntity.Key(postId, accountId, emoji);
        if (!reactions.existsById(key)) return p;
        reactions.deleteById(key);
        p.applyReaction(emoji, -1);
        return posts.save(p);
    }

    /* ------------------------------- reports -------------------------------- */

    /** Report a post; at {@link #AUTO_HIDE_REPORTS} unresolved reports the post
     *  auto-hides pending moderator review (§8.2). Idempotent per account. */
    @Transactional
    public void report(String entryPointId, String postId, UUID accountId, String reason) {
        ForumPostEntity p = requireVisible(entryPointId, postId);
        if (reports.findByPostIdAndAccountIdAndResolvedAtIsNull(postId, accountId).isPresent()) {
            return;
        }
        String id = "fr_" + UUID.randomUUID().toString().replace("-", "");
        reports.save(new ForumReportEntity(id, postId, accountId,
                reason == null || reason.isBlank() ? null
                        : reason.trim().substring(0, Math.min(reason.trim().length(), 500))));
        p.bumpReportCount();
        if (reports.countByPostIdAndResolvedAtIsNull(postId) >= AUTO_HIDE_REPORTS) {
            p.setHidden(true);
        }
        posts.save(p);
    }

    /* ------------------------------ moderation ------------------------------ */

    @Transactional(readOnly = true)
    public List<ForumPostEntity> adminPosts(String entryPointId) {
        return posts.findByEntryPointIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                entryPointId, PageRequest.of(0, 200));
    }

    @Transactional(readOnly = true)
    public List<ForumReportEntity> openReports() {
        return reports.findByResolvedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 200));
    }

    @Transactional
    public Optional<ForumPostEntity> moderate(String tenantId, String postId,
                                              Boolean hidden, Boolean pinned, boolean delete) {
        return posts.findById(postId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .map(p -> {
                    if (delete) {
                        p.softDelete();
                    } else {
                        if (hidden != null) {
                            p.setHidden(hidden);
                            // A moderator decision resolves the open reports.
                            for (ForumReportEntity r : reports.findByPostIdAndResolvedAtIsNull(postId)) {
                                r.resolve(hidden ? ForumReportEntity.RESOLUTION_UPHELD
                                        : ForumReportEntity.RESOLUTION_DISMISSED);
                                reports.save(r);
                            }
                        }
                        if (pinned != null) p.setPinned(pinned);
                    }
                    return posts.save(p);
                });
    }

    /* ------------------------------- helpers -------------------------------- */

    private ForumPostEntity requireVisible(String entryPointId, String postId) {
        return posts.findById(postId)
                .filter(p -> p.getEntryPointId().equals(entryPointId))
                .filter(ForumPostEntity::isVisible)
                .orElseThrow(() -> new ForumException("not_found", "no such post"));
    }

    private ForumPostEntity requireOwn(String postId, UUID accountId) {
        return posts.findById(postId)
                .filter(p -> accountId.equals(p.getAccountId()))
                .filter(ForumPostEntity::isVisible)
                .orElseThrow(() -> new ForumException("not_found", "no such post"));
    }

    private static String validateBody(String body) {
        String clean = body == null ? "" : body.trim();
        if (clean.isEmpty()) {
            throw new ForumException("empty_body", "write something first");
        }
        if (clean.length() > MAX_BODY_CHARS) {
            throw new ForumException("too_long",
                    "posts are limited to " + MAX_BODY_CHARS + " characters");
        }
        Matcher m = LINK.matcher(clean);
        int links = 0;
        while (m.find()) links++;
        if (links > MAX_LINKS) {
            throw new ForumException("too_many_links",
                    "posts are limited to " + MAX_LINKS + " links");
        }
        return clean;
    }
}
