package com.yayatechandinnovations.yayaagentic.engine.handoff;

/** A refused hand-off, by the stable code the sessions API answers with. */
public class HandoffException extends RuntimeException {

    public static final String SESSION_NOT_FOUND = "session_not_found";
    public static final String NOT_ALLOWED = "handoff_not_allowed";
    public static final String EXISTS = "handoff_exists";
    public static final String SOURCE_TOO_OLD = "source_too_old";

    private final String code;

    public HandoffException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
