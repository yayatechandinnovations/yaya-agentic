package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.PhoneToPrincipalResolverRegistry;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Lists the {@link
 * com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.PhoneToPrincipalResolver}
 * beans registered on this deployment, so the admin console can populate the
 * resolver dropdown on the channel-binding form.
 *
 * <p>Resolvers are deployment-scoped (not tenant-scoped) in v1 — they're
 * Spring beans, configured at boot. The tenant ID is required on the path so
 * future per-tenant resolver registration (out of scope here) doesn't require
 * a route change.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §10.1.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/phone-principal-resolvers")
public class PhonePrincipalResolverController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final PhoneToPrincipalResolverRegistry registry;

    public PhonePrincipalResolverController(TenantGuard tenantGuard,
                                            WorkspaceAccessHelper workspaceAccess,
                                            PhoneToPrincipalResolverRegistry registry) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.registry = registry;
    }

    @GetMapping
    public List<ResolverSummary> list(@PathVariable("tenantId") String tenantId,
                                      ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return registry.all().stream()
                .map(r -> new ResolverSummary(r.id(), r.description()))
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .toList();
    }

    public record ResolverSummary(String id, String description) {}
}
