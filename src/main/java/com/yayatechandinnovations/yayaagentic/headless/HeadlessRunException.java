package com.yayatechandinnovations.yayaagentic.headless;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A headless-run request that ends in an error response. {@code code} is the
 * stable machine-readable value a caller branches on; {@code message} is
 * caller-safe text (never a token, a question or an answer); {@code extras}
 * are further fields of the body (the allowance figures of
 * {@code quota_exceeded}).
 */
public class HeadlessRunException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extras;
    private final long retryAfterSeconds;

    private HeadlessRunException(HttpStatus status, String code, String message,
                                 Map<String, Object> extras, long retryAfterSeconds) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.extras = extras == null ? Map.of() : extras;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static HeadlessRunException unauthorized() {
        return new HeadlessRunException(HttpStatus.UNAUTHORIZED, "unauthorized",
                "the service token was not accepted", null, 0);
    }

    public static HeadlessRunException forbidden(String message) {
        return new HeadlessRunException(HttpStatus.FORBIDDEN, "forbidden", message, null, 0);
    }

    public static HeadlessRunException principalRejected(String message) {
        return new HeadlessRunException(HttpStatus.FORBIDDEN, "principal_rejected", message, null, 0);
    }

    public static HeadlessRunException profileNotPermitted() {
        return new HeadlessRunException(HttpStatus.FORBIDDEN, "profile_not_permitted",
                "this principal cannot use that profile", null, 0);
    }

    public static HeadlessRunException profileNotFound() {
        return new HeadlessRunException(HttpStatus.NOT_FOUND, "profile_not_found",
                "that profile is not installed for this tenant", null, 0);
    }

    public static HeadlessRunException runNotFound() {
        return new HeadlessRunException(HttpStatus.NOT_FOUND, "run_not_found", "no such run", null, 0);
    }

    public static HeadlessRunException quotaExceeded(String month, int monthlyMessages, long messages) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("month", month);
        extras.put("monthlyMessages", monthlyMessages);
        extras.put("messages", messages);
        return new HeadlessRunException(HttpStatus.CONFLICT, "quota_exceeded",
                "the monthly allowance of assistant messages is used up", extras, 0);
    }

    public static HeadlessRunException tokenExpiring(String message) {
        return new HeadlessRunException(HttpStatus.CONFLICT, "token_expiring", message, null, 0);
    }

    public static HeadlessRunException replay(String reason) {
        return new HeadlessRunException(HttpStatus.CONFLICT, "replay", reason, null, 0);
    }

    public static HeadlessRunException invalidRequest(String message) {
        return new HeadlessRunException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", message, null, 0);
    }

    public static HeadlessRunException noHeadlessTools() {
        return new HeadlessRunException(HttpStatus.UNPROCESSABLE_ENTITY, "no_headless_tools",
                "that profile has no tool a headless run may call", null, 0);
    }

    public static HeadlessRunException busy(String message) {
        return new HeadlessRunException(HttpStatus.TOO_MANY_REQUESTS, "busy", message, null, 30);
    }

    public static HeadlessRunException rateLimited(long retryAfterSeconds) {
        return new HeadlessRunException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "too many headless runs for this tenant in the last hour", null, retryAfterSeconds);
    }

    public static HeadlessRunException llmUnavailable() {
        return new HeadlessRunException(HttpStatus.SERVICE_UNAVAILABLE, "llm_unavailable",
                "no model is configured on this deployment", null, 0);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> extras() { return extras; }
    public long retryAfterSeconds() { return retryAfterSeconds; }

    /** The response body: {@code code}, {@code message}, then the extras. */
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        if (getMessage() != null) body.put("message", getMessage());
        body.putAll(extras);
        return body;
    }
}
