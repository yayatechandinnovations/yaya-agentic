package com.yayatechandinnovations.yayaagentic.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-layer error with a stable, machine-readable code (e.g. {@code
 * unknown_tenant}, {@code bad_host_base_url}). The Flutter admin UI binds
 * field-level errors against these codes — they must not drift.
 */
public class AdminApiException extends ResponseStatusException {

    private final String code;

    public AdminApiException(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
    }

    public String code() { return code; }

    public static AdminApiException badRequest(String code, String message) {
        return new AdminApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static AdminApiException notFound(String code, String message) {
        return new AdminApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static AdminApiException conflict(String code, String message) {
        return new AdminApiException(HttpStatus.CONFLICT, code, message);
    }

    public static AdminApiException forbidden(String code, String message) {
        return new AdminApiException(HttpStatus.FORBIDDEN, code, message);
    }
}
