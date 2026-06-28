package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

/**
 * Operator supplied an explicit workspace code that is already in use.
 * Controller advice maps this to 409 with {@code workspace_code_taken} so
 * the UI can keep the form open and show the field-level error.
 */
public class WorkspaceCodeConflictException extends RuntimeException {

    private final String requestedCode;

    public WorkspaceCodeConflictException(String requestedCode) {
        super("That workspace code is already taken. Pick another.");
        this.requestedCode = requestedCode;
    }

    public String requestedCode() {
        return requestedCode;
    }
}
