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
}
