package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Tenant-facing "is WhatsApp provisioning available on this instance?"
 * probe. Lets the Flutter sender screen render an actionable banner BEFORE
 * the operator submits a sender and trips a 409 they might miss.
 *
 * <p>The response is deliberately minimal — {@code submitsEnabled} is the
 * only signal. BSP-specific details (account SIDs, webhook URLs) never
 * leave the platform tier; the per-design "tenants never see the BSP"
 * invariant from §7 + §2's third corollary holds at the API boundary.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/whatsapp")
public class WhatsAppReadinessController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final ProviderCredsResolver credsResolver;

    public WhatsAppReadinessController(TenantGuard tenantGuard,
                                       WorkspaceAccessHelper workspaceAccess,
                                       ProviderCredsResolver credsResolver) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.credsResolver = credsResolver;
    }

    @GetMapping("/readiness")
    public ReadinessResponse readiness(@PathVariable("tenantId") String tenantId,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return new ReadinessResponse(credsResolver.isReady());
    }

    /**
     * @param submitsEnabled {@code true} iff sender registration can hit
     *                       the upstream platform integration. When
     *                       {@code false}, the admin UI surfaces a banner
     *                       and disables the Submit button — the operator
     *                       can still build draft senders, but cannot send
     *                       them for review.
     */
    public record ReadinessResponse(boolean submitsEnabled) {}
}
