package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;

/**
 * Thrown by the access guard when a user has no membership in the requested
 * workspace OR the workspace does not exist. The controller advice maps it
 * to 404 with the SAME body as a genuine "tenant does not exist" — see
 * TRD §3.2 and the threat-model row in design §11.
 *
 * <p>Never expose the discriminator (does-not-exist vs no-membership) to
 * the client. The {@code requestingUserId} is for audit logging only.</p>
 */
public class WorkspaceNotFoundException extends RuntimeException {

    private final TenantId workspaceId;
    private final UserId requestingUserId;

    public WorkspaceNotFoundException(TenantId workspaceId, UserId requestingUserId) {
        super("workspace_not_found");
        this.workspaceId = workspaceId;
        this.requestingUserId = requestingUserId;
    }

    public TenantId workspaceId() { return workspaceId; }
    public UserId requestingUserId() { return requestingUserId; }
}
