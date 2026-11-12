package com.yayatechandinnovations.yayaagentic.structured;

import org.springframework.http.HttpStatus;

/**
 * A structured-completion request that ends in an error response. {@code code}
 * is the stable machine-readable {@code error} value; {@code detail} is
 * optional, caller-safe text (never prompt, input or answer content).
 */
public class StructuredCompletionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String detail;
    private final long retryAfterSeconds;

    private StructuredCompletionException(HttpStatus status, String code, String detail, long retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static StructuredCompletionException unauthorized() {
        return new StructuredCompletionException(HttpStatus.UNAUTHORIZED, "unauthorized", null, 0);
    }

    public static StructuredCompletionException forbidden(String detail) {
        return new StructuredCompletionException(HttpStatus.FORBIDDEN, "forbidden", detail, 0);
    }

    public static StructuredCompletionException invalidRequest(String detail) {
        return new StructuredCompletionException(HttpStatus.BAD_REQUEST, "invalid_request", detail, 0);
    }

    public static StructuredCompletionException schemaViolation(String detail) {
        return new StructuredCompletionException(HttpStatus.UNPROCESSABLE_ENTITY, "schema_violation", detail, 0);
    }

    public static StructuredCompletionException rateLimited(long retryAfterSeconds) {
        return new StructuredCompletionException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", null,
                retryAfterSeconds);
    }

    public static StructuredCompletionException upstreamFailure() {
        return new StructuredCompletionException(HttpStatus.BAD_GATEWAY, "upstream_failure", null, 0);
    }

    public static StructuredCompletionException llmUnavailable() {
        return new StructuredCompletionException(HttpStatus.SERVICE_UNAVAILABLE, "llm_unavailable", null, 0);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String detail() { return detail; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
