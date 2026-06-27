package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

/**
 * Key not found in the resolved workspace. Maps to 404 — never leak whether a
 * key exists in another workspace (same existence-leak rule as §6.1).
 */
public class ApiKeyNotFoundException extends RuntimeException {
    public ApiKeyNotFoundException() {
        super("api key not found");
    }
}
