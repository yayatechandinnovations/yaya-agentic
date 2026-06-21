package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

/**
 * Lifecycle states for a {@link User}. Matches the {@code users.status}
 * CHECK constraint in V20.
 */
public enum UserStatus {

    /** Signed up, awaiting email verification. Cannot log in. */
    PENDING_VERIFICATION,

    /** Verified (or auto-provisioned by a trusted IdP). Can log in. */
    ACTIVE,

    /** Disabled by a platform admin. Cannot log in; data retained. */
    SUSPENDED,

    /** Soft-deleted. PII scrub after 30 days; FK rows retained. */
    DELETED
}
