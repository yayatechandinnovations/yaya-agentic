package com.yayatechandinnovations.yayaagentic.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public chat front-door error with a stable machine-readable code
 * ({@code chat-channel-design.md} §7). Distinct from {@link AdminApiException}
 * because this surface is internet-facing and unauthenticated: it must never
 * leak whether a handle exists ({@link #unknownHandle()} is the single opaque
 * 404 for unknown / deactivated / draft).
 */
public class ChatApiException extends ResponseStatusException {

    private final String code;
    private final long retryAfterSeconds;

    public ChatApiException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(status, message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }

    /** Single opaque 404 — unknown, deactivated, and not-yet-published all map here. */
    public static ChatApiException unknownHandle() {
        return new ChatApiException(HttpStatus.NOT_FOUND, "not_found", "no such chat", 0);
    }

    public static ChatApiException authRequired() {
        return new ChatApiException(HttpStatus.UNAUTHORIZED, "auth_required",
                "this chat requires sign-in", 0);
    }

    public static ChatApiException rateLimited(long retryAfterSeconds) {
        return new ChatApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "too many requests, try again shortly", retryAfterSeconds);
    }

    public static ChatApiException sessionNotFound() {
        return new ChatApiException(HttpStatus.NOT_FOUND, "session_not_found",
                "this chat session is no longer active", 0);
    }

    /** This entry point requires a phone-verified device (§20.5). */
    public static ChatApiException verificationRequired() {
        return new ChatApiException(HttpStatus.FORBIDDEN, "verification_required",
                "verify your phone number to start this chat", 0);
    }

    /** Resolver-exchange entry point: the resolver has no account for this
     *  verified number (a clean 404). Fail closed under AUTH_REQUIRED. */
    public static ChatApiException resolverUnresolved() {
        return new ChatApiException(HttpStatus.FORBIDDEN, "resolver_unresolved",
                "we couldn't find an account for this number", 0);
    }
}
