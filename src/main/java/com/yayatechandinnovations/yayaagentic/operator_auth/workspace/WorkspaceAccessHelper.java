package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
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
        // API-key requests are pre-pinned to one workspace by the filter; the
        // key's stored role IS the role — no membership row to consult (§6.1).
        // Checked before the feature gate so it is independent of legacy mode.
        if (OperatorContext.current(exchange)
                .map(op -> op.source() == Operator.Source.API_KEY).orElse(false)) {
            return requireApiKey(tenant, exchange, minRole);
        }
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

    /**
     * Pinned-workspace path for API-key operators (§6.1). A request for any
     * workspace other than the pinned one returns 404 (existence non-leak),
     * never 403; an insufficient key role returns 403.
     */
    private String requireApiKey(String tenant, ServerWebExchange exchange, Role minRole) {
        TenantId pinned = exchange.getAttribute(WorkspaceContext.PINNED_ATTR);
        Role keyRole = exchange.getAttribute(WorkspaceContext.PINNED_ROLE_ATTR);
        if (pinned == null || keyRole == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "api_key_context_missing");
        }
        // Platform-scope key (gap B): reaches ANY workspace at any role —
        // is_platform_admin here comes solely from the key row's
        // platform_scope column stamped by ApiKeyAuthFilter, never from the
        // creator's account.
        boolean platformKey = OperatorContext.current(exchange)
                .map(OperatorIdentity::isPlatformAdmin).orElse(false);
        // No explicit workspace on the request → default to the pinned one.
        if (tenant != null && !tenant.isBlank()) {
            TenantId requested = workspaces.resolveToId(tenant)
                    .orElseThrow(() -> new WorkspaceNotFoundException(new TenantId(tenant), null));
            if (platformKey) {
                return requested.value();
            }
            if (!requested.equals(pinned)) {
                throw new WorkspaceNotFoundException(requested, null);   // 404, not 403
            }
        }
        if (!platformKey && !keyRole.atLeast(minRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "insufficient_role");
        }
        return pinned.value();
    }

    /**
     * True for a platform-admin operator and for a platform-scope API key —
     * the callers that may act across workspaces.
     */
    public boolean isPlatformAdmin(ServerWebExchange exchange) {
        return OperatorContext.current(exchange).map(OperatorIdentity::isPlatformAdmin).orElse(false);
    }

    /**
     * Gate for surfaces that are not workspace-scoped at all.
     *
     * @throws ResponseStatusException 401 without an operator, 403 for anyone
     *         who is not a platform admin
     */
    public void requirePlatformAdmin(ServerWebExchange exchange) {
        if (OperatorContext.current(exchange).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no_operator_on_exchange");
        }
        if (!isPlatformAdmin(exchange)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "platform_admin_required");
        }
    }

    /** Audit identity of the caller: the operator's source and subject. */
    public String actorOf(ServerWebExchange exchange) {
        return OperatorContext.current(exchange)
                .map(op -> op.source().name().toLowerCase(java.util.Locale.ROOT) + ":" + op.subject())
                .orElse("unknown");
    }

    private static String legacyDefault(String tenant) {
        return (tenant == null || tenant.isBlank()) ? "default" : tenant;
    }
}
