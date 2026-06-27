package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

/**
 * Raised when a workspace already holds the maximum number of active keys
 * ({@code yaya.agentic.api-keys.max-active-per-workspace}, default 100). The
 * 101st create returns 409 pointing at the cap (§10, §14 D2).
 */
public class KeyCapExceededException extends RuntimeException {
    private final int cap;

    public KeyCapExceededException(int cap) {
        super("workspace has reached the maximum of " + cap + " active API keys");
        this.cap = cap;
    }

    public int cap() { return cap; }
}
