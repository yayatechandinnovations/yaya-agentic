package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;

import java.util.UUID;

/**
 * Resolves an {@link Operator} (the session identity) to the {@link UserId}
 * that the workspace guard needs. Centralises the bootstrap-session
 * fallback so callers don't each invent it.
 *
 * <p>Bootstrap sessions don't carry a user_id attribute today. We treat
 * them as the synthetic {@code platform-admin@local} user (seeded by
 * V21) — that user has {@code is_platform_admin = true} so the guard
 * short-circuits anyway, but having a stable UUID means FK columns and
 * audit rows have something coherent to point at.</p>
 */
public final class OperatorIdentity {

    /** Matches the UUID inserted by V21 for the synthetic platform admin. */
    public static final UserId SYNTHETIC_PLATFORM_ADMIN =
            new UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    private OperatorIdentity() {}

    /**
     * @return the typed {@link UserId} for this operator
     * @throws IllegalStateException if {@code op} is null
     */
    public static UserId userIdOf(Operator op) {
        if (op == null) throw new IllegalStateException("operator is required");
        Object attr = op.attributes() == null ? null : op.attributes().get("user_id");
        if (attr instanceof String s && !s.isBlank()) {
            try {
                return UserId.parse(s);
            } catch (IllegalArgumentException ignored) {
                // Fall through to the bootstrap path; subject is not a UUID.
            }
        }
        // Bootstrap (or any source without a user_id attribute) → synthetic.
        return SYNTHETIC_PLATFORM_ADMIN;
    }

    /** True iff the operator carries the platform-admin flag in its attributes. */
    public static boolean isPlatformAdmin(Operator op) {
        if (op == null) return false;
        Object attr = op.attributes() == null ? null : op.attributes().get("is_platform_admin");
        if (attr instanceof Boolean b) return b;
        // Bootstrap is platform-admin by construction; treat it as such even
        // when the authenticator forgot to populate the attribute.
        return op.source() == Operator.Source.BOOTSTRAP;
    }
}
