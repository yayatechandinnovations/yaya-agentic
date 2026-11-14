package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;

import java.util.Map;
import java.util.Optional;

/**
 * Input to {@link Authenticator} and a piece of context handed to
 * {@link ProfileResolver} chains.
 *
 * <p>{@code channel} is the surface the request came in on, set by the entry
 * point that received it (a {@code TurnChannel} value) — never read from the
 * request. Null when the caller did not say; the engine then falls back to the
 * session's own channel.</p>
 */
public record AuthContext(
        Ids.TenantId tenant,
        Map<String, String> headers,
        Optional<Principal> alreadyVerified,
        String channel
) {
    public AuthContext(Ids.TenantId tenant, Map<String, String> headers, Optional<Principal> alreadyVerified) {
        this(tenant, headers, alreadyVerified, null);
    }

    public AuthContext onChannel(String channel) {
        return new AuthContext(tenant, headers, alreadyVerified, channel);
    }
}
