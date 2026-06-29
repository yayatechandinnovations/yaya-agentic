package com.yayatechandinnovations.yayaagentic.channel.chat;

/**
 * Per-entry-point authentication policy ({@code chat-channel-design.md} §6, §8).
 * Drives whether the app must obtain a verified token before — or may upgrade
 * during — a session. Modelled as an enum (not a sealed type) because the
 * variants carry no per-variant data; the issuer / resolver detail lives on
 * {@link AuthConfig}.
 */
public enum AuthMode {
    /** No token ever. Sessions run as a minted anonymous principal. */
    ANONYMOUS_ONLY,
    /** A verified JWT is required before a session can start. */
    AUTH_REQUIRED,
    /** Start anonymous; allow an in-session upgrade to a verified principal. */
    AUTH_OPTIONAL;

    public static AuthMode parse(String raw) {
        return raw == null || raw.isBlank() ? ANONYMOUS_ONLY : valueOf(raw.trim().toUpperCase());
    }

    public boolean requiresTokenToStart() {
        return this == AUTH_REQUIRED;
    }

    public boolean allowsAnonymousStart() {
        return this == ANONYMOUS_ONLY || this == AUTH_OPTIONAL;
    }
}
