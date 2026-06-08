package com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit;

/**
 * Thrown when the login rate limiter denies an attempt. Carries both the
 * audit-only axis ({@code "username"} or {@code "ip"}) and the wait time
 * for the {@code Retry-After} header.
 */
public class RateLimitedException extends RuntimeException {

    private final String axis;
    private final long retryAfterSeconds;

    public RateLimitedException(String axis, long retryAfterSeconds) {
        super("rate_limited:" + axis);
        this.axis = axis;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String axis() { return axis; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
