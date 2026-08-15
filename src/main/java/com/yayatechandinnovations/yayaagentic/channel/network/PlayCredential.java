package com.yayatechandinnovations.yayaagentic.channel.network;

import java.time.Instant;

/**
 * A brokered, install-vended customer token for one banca — the ONLY play
 * authority on the network entry point (yaya-agentic-network-chat-runtime-design.md
 * §1/§2). Org-bound, short-lived (~15 min), player-owned; obtained from the hub
 * broker, never derived from the chat principal.
 *
 * <p>Held only in {@link PlayCredentialCache}. Never logged, never written to
 * WorkingMemory, never returned in a tool_result or frame (§2/§6). {@link
 * #toString()} is redacted so an accidental log line cannot leak the token.
 */
public record PlayCredential(
        String baseUrl,
        String customerId,
        String customerToken,
        Instant expiresAt
) {
    /** True when the token is at/near expiry and must be re-brokered before use. */
    public boolean isExpired(Instant now) {
        return expiresAt == null || !now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        // customerToken and customerId are deliberately omitted — this type
        // must be unloggable by construction (§6 redaction).
        return "PlayCredential[baseUrl=" + baseUrl + ", customerToken=***, expiresAt=" + expiresAt + "]";
    }
}
