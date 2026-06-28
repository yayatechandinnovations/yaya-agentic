package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.AccountTokenException;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.LastOwnerException;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.PasswordPolicyException;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.ApiKeyNotFoundException;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.KeyCapExceededException;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.RoleExceedsCreatorException;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceCodeConflictException;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceNotFoundException;
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

    /**
     * Existence non-leak: identical body for "workspace doesn't exist" and
     * "you have no membership." See TRD §3.2.
     */
    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(WorkspaceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new AdminDtos.ApiError(
                "workspace_not_found",
                "Workspace not found."));
    }

    /** Key not found in the resolved workspace — same 404 existence-non-leak. */
    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(ApiKeyNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new AdminDtos.ApiError(
                "api_key_not_found",
                "API key not found."));
    }

    /** Per-workspace active-key cap reached (§14 D2). */
    @ExceptionHandler(KeyCapExceededException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(KeyCapExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new AdminDtos.ApiError(
                "api_key_cap_reached",
                ex.getMessage()));
    }

    /** Requested key role exceeds the creator's own tier (§6.2). */
    @ExceptionHandler(RoleExceedsCreatorException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(RoleExceedsCreatorException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new AdminDtos.ApiError(
                "role_exceeds_creator",
                ex.getMessage()));
    }

    @ExceptionHandler(AccountTokenException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(AccountTokenException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(new AdminDtos.ApiError(
                "token_invalid",
                "This link is no longer valid. Request a new one."));
    }

    @ExceptionHandler(PasswordPolicyException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(PasswordPolicyException ex) {
        return ResponseEntity.badRequest().body(new AdminDtos.ApiError(
                "password_policy",
                ex.getMessage()));
    }

    /**
     * Demote or remove would leave a workspace without an OWNER. The
     * service raises this with a user-safe reason already populated; we
     * surface it verbatim.
     */
    @ExceptionHandler(LastOwnerException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(LastOwnerException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new AdminDtos.ApiError(
                "last_owner",
                ex.getMessage()));
    }

    /**
     * Operator-supplied workspace code collides with an existing one.
     * UI keeps the form open and renders the message under the code field.
     */
    @ExceptionHandler(WorkspaceCodeConflictException.class)
    public ResponseEntity<AdminDtos.ApiError> handle(WorkspaceCodeConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new AdminDtos.ApiError(
                "workspace_code_taken",
                ex.getMessage()));
    }
}
