package com.yayatechandinnovations.yayaagentic.operator_auth.web;

import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.RateLimitedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Normalises auth-endpoint errors into the same {@code {error, message}}
 * shape the admin API uses, so clients can render either consistently.
 * Scoped to {@link AuthController} so it doesn't conflict with
 * {@code AdminExceptionHandler}.
 */
@RestControllerAdvice(basePackageClasses = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handle(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(new ApiError(
                status.name(),
                ex.getReason() == null ? status.getReasonPhrase() : ex.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handle(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiError(
                HttpStatus.BAD_REQUEST.name(),
                ex.getMessage() == null ? "bad request" : ex.getMessage()));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiError> handle(RateLimitedException ex) {
        // Same generic message every time — never leak which axis tripped.
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(new ApiError(
                        HttpStatus.TOO_MANY_REQUESTS.name(),
                        "Too many login attempts. Try again later."));
    }

    public record ApiError(String error, String message) {}
}
