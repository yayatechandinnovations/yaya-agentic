package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;

/**
 * The resolved tuple every workspace-scoped admin handler operates against.
 * Built by {@code WorkspaceAccessGuard.require(...)} from the inbound
 * request's workspace path/body/query param plus the authenticated user.
 */
public record WorkspaceContext(
        TenantId workspaceId,
        UserId requestingUserId,
        Role role
) {

    /**
     * Exchange attribute holding the {@link TenantId} an API-key request is
     * pinned to (stamped by {@code ApiKeyAuthFilter}). When present,
     * {@code WorkspaceAccessHelper} short-circuits the membership lookup: the
     * key's stored role IS the role. See api-keys-design §5.1, §6.1.
     */
    public static final String PINNED_ATTR = WorkspaceContext.class.getName() + ".pinnedWorkspaceId";

    /** Exchange attribute holding the {@link Role} carried by the API key. */
    public static final String PINNED_ROLE_ATTR = WorkspaceContext.class.getName() + ".pinnedRole";
}
