package com.yayatechandinnovations.yayaagentic.operator_auth;

import java.util.Arrays;

/**
 * Submitted credentials passed through the authenticator chain.
 *
 * <p>{@code password} is a {@code char[]}, not a {@code String}, so it can
 * be zeroed by the chain after the request completes — {@code String} would
 * intern in the JVM string pool. Call {@link #clear()} exactly once,
 * regardless of authentication outcome.</p>
 */
public record OperatorCredentials(
        String username,
        char[] password,
        String clientIp,
        String userAgent,
        String attemptId
) {
    public void clear() {
        if (password != null) Arrays.fill(password, '\0');
    }
}
