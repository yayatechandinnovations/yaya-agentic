package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

/**
 * Per-workspace grant tier. Strict subset semantics: any role can do
 * everything every weaker role can. {@link #PLATFORM_ADMIN} is the only
 * value not persisted in {@code user_workspace_memberships} — it is
 * synthesised by the access guard when {@code users.is_platform_admin} is
 * true.
 *
 * <p>See {@code docs/specs/user-accounts/PRD.md} §4.2 for the read/write
 * matrix and {@code docs/specs/user-accounts/UI-UX.md} §9 for the
 * UI gating implications.</p>
 */
public enum Role {

    VIEWER(0),
    MEMBER(1),
    ADMIN(2),
    OWNER(3),
    PLATFORM_ADMIN(Integer.MAX_VALUE);

    private final int tier;

    Role(int tier) { this.tier = tier; }

    /** True iff this role grants every capability of {@code other}. */
    public boolean atLeast(Role other) {
        return this.tier >= other.tier;
    }
}
