package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

/**
 * What the {@code user-password} login strategy needs to verify an account.
 * Mirrors {@code OperatorCredentials} but typed for the user-account plane.
 */
public record UserCredentials(
        String email,
        char[] password,
        String clientIp,
        String userAgent,
        String attemptId
) {
}
