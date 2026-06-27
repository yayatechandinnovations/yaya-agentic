package com.yayatechandinnovations.yayaagentic.channel.auth;

/**
 * Thrown by {@link AuthResolver#resolve} for transport / protocol failures
 * — timeout, 5xx, malformed response, allowlist denial, self-auth failure.
 *
 * <p>Distinct from a {@code 404 / Optional.empty()} "user not found"
 * outcome. Callers translate this exception into an
 * {@link com.yayatechandinnovations.yayaagentic.auth.AuthenticationException}
 * (or the binding's {@code on_refresh_failure} policy on a refresh path),
 * but NEVER into an anonymous downgrade — that path is reserved for
 * "user verified to not exist."
 *
 * <p>The {@code status} string is a short stable token used to populate
 * {@code auth_resolver_calls.status}: {@code "timeout"}, {@code "bad_token"},
 * {@code "self_auth_failed"}, {@code "transport_error"},
 * {@code "allowlist_denied"}, ... See design §14 for the full set.
 *
 * <p>The message MUST NOT contain JWT cleartext, bearer secrets, or any
 * other credential — the same redaction rule that applies to log lines
 * (§13 non-negotiable #1).
 */
public class AuthResolverException extends RuntimeException {

    private final String status;
    private final Integer httpStatus;

    public AuthResolverException(String status, String message) {
        this(status, message, null, null);
    }

    public AuthResolverException(String status, String message, Throwable cause) {
        this(status, message, null, cause);
    }

    public AuthResolverException(String status, String message,
                                 Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.httpStatus = httpStatus;
    }

    public String status() { return status; }
    public Integer httpStatus() { return httpStatus; }
}
