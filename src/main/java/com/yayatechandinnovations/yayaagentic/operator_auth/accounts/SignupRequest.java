package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

/**
 * What an anonymous visitor sends to {@code POST /v1/auth/signup}.
 * Password is a {@code char[]} rather than {@link String} so it can be
 * zeroed after argon2 hashing — mirrors {@code OperatorCredentials}.
 */
public record SignupRequest(
        String email,
        String displayName,
        char[] password
) {
}
