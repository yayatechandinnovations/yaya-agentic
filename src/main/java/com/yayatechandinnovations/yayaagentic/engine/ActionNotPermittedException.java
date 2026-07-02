package com.yayatechandinnovations.yayaagentic.engine;

/**
 * Thrown when a client-initiated action ({@code POST /sessions/{id}/actions}) is
 * refused before dispatch: the tool is not in the session profile's capabilities
 * or is not {@code clientInvocable}. Both cases map to a 403 with no detail —
 * existence of a tool outside the profile is never leaked (non-negotiable #2).
 * See {@code ecommerce-chat-design.md} §9.
 */
public class ActionNotPermittedException extends RuntimeException {
    public ActionNotPermittedException(String auditMessage) {
        super(auditMessage);
    }
}
