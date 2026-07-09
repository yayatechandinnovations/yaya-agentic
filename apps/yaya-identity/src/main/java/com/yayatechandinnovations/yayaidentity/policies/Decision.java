package com.yayatechandinnovations.yayaidentity.policies;

/**
 * A PDP decision (design §7, SPI §15). Mirrors the agent's {@code AuthzDecision}:
 * a {@code Deny} carries a user-safe reason AND an internal audit reason, and the
 * two MUST differ (non-negotiable #3) — enforced here at construction.
 */
public sealed interface Decision permits Decision.Permit, Decision.Deny {

    record Permit(String obligationsJson) implements Decision {}

    record Deny(String userSafeReason, String auditReason) implements Decision {
        public Deny {
            if (userSafeReason == null || userSafeReason.isBlank()
                    || auditReason == null || auditReason.isBlank()) {
                throw new IllegalArgumentException("a Deny requires both a userSafeReason and an auditReason (#3)");
            }
            if (userSafeReason.equals(auditReason)) {
                throw new IllegalArgumentException(
                        "userSafeReason and auditReason must differ (#3): the user-safe reason must not "
                                + "leak the internal audit reason");
            }
        }
    }

    default boolean isPermit() {
        return this instanceof Permit;
    }
}
