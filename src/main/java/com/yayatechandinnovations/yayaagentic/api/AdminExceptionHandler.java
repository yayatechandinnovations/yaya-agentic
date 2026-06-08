package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Translates admin-layer exceptions into a stable {@code {error, message}}
 * JSON shape so the Flutter UI and CLI clients can render them consistently.
 */
@RestControllerAdvice(basePackageClasses = AdminController.class)
public class AdminExceptionHandler {

    @ExceptionHandler(AdminApiException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(AdminApiException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(new AdminDtos.ApiError(
                ex.code(),
                ex.getReason() == null ? status.getReasonPhrase() : ex.getReason()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(new AdminDtos.ApiError(
                status.name(),
                ex.getReason() == null ? status.getReasonPhrase() : ex.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new AdminDtos.ApiError(
                HttpStatus.BAD_REQUEST.name(),
                ex.getMessage() == null ? "bad request" : ex.getMessage()));
    }
}
