package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import java.util.UUID;

/**
 * Typed user identifier. Prevents cross-wiring with other UUID-bearing types.
 */
public record UserId(UUID value) {

    public UserId {
        if (value == null) throw new IllegalArgumentException("UserId.value must not be null");
    }

    public static UserId of(UUID value) {
        return new UserId(value);
    }

    public static UserId parse(String s) {
        return new UserId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
