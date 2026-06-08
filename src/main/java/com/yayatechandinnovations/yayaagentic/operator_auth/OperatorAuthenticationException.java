package com.yayatechandinnovations.yayaagentic.operator_auth;

/**
 * Thrown by an {@link OperatorAuthenticator} that recognises the request
 * but rejects the credentials. The chain short-circuits on this and the
 * controller responds 401.
 *
 * <p>The message is the {@code audit_reason} — never displayed to the
 * user (the controller always replies with a fixed generic message).
 * {@code source} carries the authenticator name (chain populates it
 * after the throw if the authenticator left it null) and lands in the
 * audit row's {@code source} column.</p>
 */
public class OperatorAuthenticationException extends RuntimeException {

    private final String source;

    public OperatorAuthenticationException(String message) {
        this(message, null, null);
    }

    public OperatorAuthenticationException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public OperatorAuthenticationException(String message, Throwable cause, String source) {
        super(message, cause);
        this.source = source;
    }

    public String source() { return source; }
}
