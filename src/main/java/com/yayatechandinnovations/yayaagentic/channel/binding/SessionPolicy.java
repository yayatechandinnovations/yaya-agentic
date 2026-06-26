package com.yayatechandinnovations.yayaagentic.channel.binding;

import java.time.Duration;
import java.util.Set;

/**
 * Per-binding session policy. Defaults to a single 24-hour idle session
 * with channel-standard new-session keywords; operators can widen the
 * keyword set, opt into a re-engagement template, or hide the end-user
 * phone from the profile for anonymous-feedback flows (§12.1).
 *
 * @param idleTimeout                 max idle gap before the session is
 *                                    closed; capped at 168h
 * @param newSessionKeywords          phrases (case-insensitive exact match)
 *                                    that force-open a new session and are
 *                                    consumed before the engine sees them
 * @param endSessionKeywords          phrases that gracefully close a session
 *                                    after the engine emits its goodbye
 * @param greetOnReengagement         when true, re-engagement past the idle
 *                                    timeout emits a templated greeting
 *                                    before the engine's first turn
 * @param reengagementTemplate        the template id used for the
 *                                    re-engagement greet; required when
 *                                    {@code greetOnReengagement} is true
 * @param exposeEndUserPhoneToProfile {@code endUserPhone} captured to entity
 *                                    memory at session start (§12.1).
 *                                    Default {@code true} for WhatsApp; flip
 *                                    to {@code false} for anonymous-feedback
 *                                    bots that should never see the phone.
 */
public record SessionPolicy(
        Duration             idleTimeout,
        Set<String>          newSessionKeywords,
        Set<String>          endSessionKeywords,
        boolean              greetOnReengagement,
        String               reengagementTemplate,
        boolean              exposeEndUserPhoneToProfile
) {

    public static final Duration DEFAULT_IDLE = Duration.ofHours(24);
    public static final Duration MAX_IDLE     = Duration.ofHours(168);
    public static final Set<String> DEFAULT_NEW_SESSION_KEYWORDS =
            Set.of("#new", "restart", "start over");
    public static final Set<String> DEFAULT_END_SESSION_KEYWORDS =
            Set.of("bye", "done", "thanks bye");

    public SessionPolicy {
        idleTimeout = idleTimeout == null ? DEFAULT_IDLE : idleTimeout;
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        if (idleTimeout.compareTo(MAX_IDLE) > 0) {
            throw new IllegalArgumentException(
                    "idleTimeout exceeds the WhatsApp service-window limit (168h)");
        }
        newSessionKeywords = newSessionKeywords == null
                ? DEFAULT_NEW_SESSION_KEYWORDS
                : Set.copyOf(newSessionKeywords);
        endSessionKeywords = endSessionKeywords == null
                ? DEFAULT_END_SESSION_KEYWORDS
                : Set.copyOf(endSessionKeywords);
        if (greetOnReengagement
                && (reengagementTemplate == null || reengagementTemplate.isBlank())) {
            throw new IllegalArgumentException(
                    "reengagementTemplate is required when greetOnReengagement=true");
        }
    }

    public static SessionPolicy defaults() {
        return new SessionPolicy(
                DEFAULT_IDLE,
                DEFAULT_NEW_SESSION_KEYWORDS,
                DEFAULT_END_SESSION_KEYWORDS,
                false,
                null,
                true
        );
    }
}
