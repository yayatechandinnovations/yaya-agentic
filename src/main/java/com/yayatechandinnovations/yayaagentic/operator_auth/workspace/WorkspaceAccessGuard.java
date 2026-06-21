package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Membership;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;

import java.util.Collection;
import java.util.Set;

/**
 * The single load-bearing predicate every admin-plane handler consults
 * before touching workspace-scoped data. See TRD §3.2 and design §4.1.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Throw {@link WorkspaceNotFoundException} (→ controller advice 404)
 *       when no membership row exists AND the caller is not a platform
 *       admin. Same body whether the workspace exists or not — existence
 *       non-leak is non-negotiable.</li>
 *   <li>Short-circuit to {@link Role#PLATFORM_ADMIN} when {@code
 *       users.is_platform_admin = true} and emit an
 *       {@code audit_user_events(kind=PLATFORM_ADMIN_ACTION)} row.</li>
 * </ul>
 * </p>
 */
public interface WorkspaceAccessGuard {

    /**
     * Returns the resolved membership iff the user can access the workspace
     * with at least the requested role. Throws otherwise.
     *
     * @throws WorkspaceNotFoundException if no membership AND not a platform admin
     */
    Membership require(UserId user, TenantId workspace, Role atLeast);

    /** Filters a candidate set to those visible to this user. Platform-admin gets the full set. */
    Set<TenantId> visible(UserId user, Collection<TenantId> candidates);
}
