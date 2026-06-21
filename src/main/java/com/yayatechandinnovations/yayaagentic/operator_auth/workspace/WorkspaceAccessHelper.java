package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.operator_auth.web.OperatorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * Shared "resolve tenant + run guard" helper. Used by every admin
 * controller that takes a tenant id/path-variable so the 404-not-403
 * existence-leak behavior is identical across the surface.
 *
 * <p>Gated on {@code yaya.agentic.user-accounts.enabled}: when off, the
 * legacy default-fill behavior is preserved so pre-M5a clients keep
 * working. When on, every request must carry a real tenant and the
 * caller must be a member at the requested role or higher (or platform
 * admin). See TRD §3.2.</p>
 */
@Component
public class WorkspaceAccessHelper {

    private final WorkspaceAccessGuard guard;
    private final WorkspaceService workspaces;
    private final boolean userAccountsEnabled;

    public WorkspaceAccessHelper(WorkspaceAccessGuard guard,
                                 WorkspaceService workspaces,
                                 @Value("${yaya.agentic.user-accounts.enabled:false}") boolean userAccountsEnabled) {
        this.guard = guard;
        this.workspaces = workspaces;
        this.userAccountsEnabled = userAccountsEnabled;
    }

    /**
     * Resolve a tenant string for the current request, applying the workspace
     * guard at the requested minimum role.
     *
     * @return the canonical tenant string the caller's downstream queries should use
     * @throws ResponseStatusException 400 if tenant is missing while the feature flag
     *         is on (no more "default" auto-fill)
     * @throws ResponseStatusException 401 if no operator is bound to the exchange
     * @throws WorkspaceNotFoundException if the workspace doesn't exist OR the caller
     *         has no membership at the requested role — same body for both
     */
    public String require(String tenant, ServerWebExchange exchange, Role minRole) {
        if (!userAccountsEnabled) {
            return legacyDefault(tenant);
        }
        if (tenant == null || tenant.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenant is required");
        }
        UserId userId = OperatorContext.current(exchange)
                .map(OperatorIdentity::userIdOf)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no_operator_on_exchange"));
        TenantId resolved = workspaces.resolveToId(tenant)
                .orElseThrow(() -> new WorkspaceNotFoundException(new TenantId(tenant), userId));
        guard.require(userId, resolved, minRole);
        return resolved.value();
    }

    private static String legacyDefault(String tenant) {
        return (tenant == null || tenant.isBlank()) ? "default" : tenant;
    }
}
