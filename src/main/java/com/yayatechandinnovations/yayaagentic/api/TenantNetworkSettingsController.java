package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkEntryPointProvisioner;
import com.yayatechandinnovations.yayaagentic.config.TenantNetworkSettingsChangedEvent;
import com.yayatechandinnovations.yayaagentic.config.TenantNetworkSettingsService;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.NetworkPlayerProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.TenantNetworkSettingsEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantNetworkSettingsRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

/**
 * Admin CRUD for the per-tenant network-feature settings — the one screen that
 * turns {@code player_network_v1} on and toggles play/claim at runtime (no env
 * vars, no restart). PUT is the master switch: enabling seeds the profile and
 * auto-provisions the chat entry point; disabling pauses it. Every write publishes
 * a {@link TenantNetworkSettingsChangedEvent} so the engine's cached settings are
 * evicted before the next turn.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/network-settings")
public class TenantNetworkSettingsController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final TenantNetworkSettingsRepository repo;
    private final TenantNetworkSettingsService settings;
    private final ApplicationEventPublisher events;
    private final NetworkPlayerProfileBootstrap bootstrap;
    private final NetworkEntryPointProvisioner provisioner;
    private final YayaAgenticProperties props;

    public TenantNetworkSettingsController(TenantGuard tenantGuard,
                                           WorkspaceAccessHelper workspaceAccess,
                                           TenantNetworkSettingsRepository repo,
                                           TenantNetworkSettingsService settings,
                                           ApplicationEventPublisher events,
                                           NetworkPlayerProfileBootstrap bootstrap,
                                           NetworkEntryPointProvisioner provisioner,
                                           YayaAgenticProperties props) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.repo = repo;
        this.settings = settings;
        this.events = events;
        this.bootstrap = bootstrap;
        this.provisioner = provisioner;
        this.props = props;
    }

    @GetMapping
    public NetworkSettingsResponse get(@PathVariable("tenantId") String tenantId,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        TenantNetworkSettingsService.Effective eff = settings.settings(tenantId);
        ChatEntryPoint ep = provisioner.find(new Ids.TenantId(tenantId)).orElse(null);
        return NetworkSettingsResponse.of(eff, ep, props.publicBaseUrl());
    }

    @PutMapping
    @Transactional
    public NetworkSettingsResponse update(@PathVariable("tenantId") String tenantId,
                                          @RequestBody NetworkSettingsBody body,
                                          ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);

        TenantNetworkSettingsEntity entity = repo.findByTenantId(tenantId)
                .orElseGet(() -> new TenantNetworkSettingsEntity(tenantId));
        entity.setEnabled(body.enabled());
        entity.setPlayEnabled(body.playEnabled());
        entity.setClaimEnabled(body.claimEnabled());
        entity.setHubBaseUrl(blankToNull(body.hubBaseUrl()));
        entity.setHubCredentialRef(blankToNull(body.hubCredentialRef()));

        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        ChatEntryPoint ep = null;
        if (body.enabled()) {
            // Master switch ON: idempotent profile seed + auto-provision the front
            // door. play/claim visibility is still gated by the flags at runtime.
            bootstrap.seedInto(tenant);
            ep = provisioner.ensure(tenant);
            entity.setEntryPointId(ep.id().value());
        }
        repo.save(entity);
        events.publishEvent(new TenantNetworkSettingsChangedEvent(tenantId));
        if (!body.enabled()) {
            provisioner.pauseIfPresent(tenant);
            ep = provisioner.find(tenant).orElse(null);
        }
        // Re-read effective post-save so the response reflects the new state.
        return NetworkSettingsResponse.of(settings.settings(tenantId), ep, props.publicBaseUrl());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /* ------------------------------- DTOs -------------------------------- */

    public record NetworkSettingsBody(
            boolean enabled, boolean playEnabled, boolean claimEnabled,
            String hubBaseUrl, String hubCredentialRef) {}

    public record NetworkSettingsResponse(
            boolean enabled, boolean playEnabled, boolean claimEnabled,
            String hubBaseUrl, String hubCredentialRef,
            String entryPointHandle, boolean entryPointActive, String chatUrl) {

        static NetworkSettingsResponse of(TenantNetworkSettingsService.Effective eff,
                                          ChatEntryPoint ep, String publicBaseUrl) {
            String handle = ep == null ? null : ep.handle();
            boolean active = ep != null
                    && ep.status() == com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus.ACTIVE;
            String chatUrl = (handle != null && publicBaseUrl != null && !publicBaseUrl.isBlank())
                    ? publicBaseUrl.replaceAll("/+$", "") + "/e/" + handle : null;
            return new NetworkSettingsResponse(eff.enabled(), eff.playEnabled(), eff.claimEnabled(),
                    eff.hubBaseUrl(), eff.hubCredentialRef(), handle, active, chatUrl);
        }
    }
}
