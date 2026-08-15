package com.yayatechandinnovations.yayaagentic.config;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.TenantNetworkSettingsEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantNetworkSettingsRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Live, per-tenant read of the network-feature settings — the runtime-mutable
 * successor to the {@code yaya.agentic.network.*} boot config. The DB row (when
 * present) wins; anything it leaves unset falls back to the env
 * {@link YayaAgenticProperties} defaults, so both the admin-driven and the
 * env-driven enablement paths coexist.
 *
 * <p>Cached per tenant and evicted on {@link TenantNetworkSettingsChangedEvent},
 * so an operator toggling play/claim in the console takes effect on the next turn
 * with no restart (mirrors the {@code OidcAuthenticator} cache pattern).
 */
@Service
public class TenantNetworkSettingsService {

    /** Effective settings after DB-over-env resolution. */
    public record Effective(
            boolean enabled,
            boolean playEnabled,
            boolean claimEnabled,
            String hubBaseUrl,
            String hubCredentialRef,
            String entryPointId
    ) {}

    private final TenantNetworkSettingsRepository repo;
    private final YayaAgenticProperties props;
    private final ConcurrentHashMap<String, Effective> cache = new ConcurrentHashMap<>();

    public TenantNetworkSettingsService(TenantNetworkSettingsRepository repo,
                                        YayaAgenticProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public Effective settings(Ids.TenantId tenant) {
        return settings(tenant.value());
    }

    public Effective settings(String tenantId) {
        return cache.computeIfAbsent(tenantId, this::load);
    }

    @EventListener
    public void onChanged(TenantNetworkSettingsChangedEvent event) {
        cache.remove(event.tenantId());
    }

    private Effective load(String tenantId) {
        YayaAgenticProperties.Network net = props.network();
        boolean envSeeded = net.seedTenant() != null && net.seedTenant().trim().equals(tenantId);
        String envHubUrl = net.hub() == null ? null : net.hub().baseUrl();
        String envCredRef = net.hub() == null ? null : net.hub().credentialRef();

        TenantNetworkSettingsEntity row = repo.findByTenantId(tenantId).orElse(null);
        if (row == null) {
            // No admin row → env-only. Enabled iff this is the env seed tenant.
            return new Effective(envSeeded,
                    net.capabilities().playEnabled(), net.capabilities().claimEnabled(),
                    envHubUrl, envCredRef, null);
        }
        return new Effective(
                row.isEnabled() || envSeeded,
                row.isPlayEnabled(),
                row.isClaimEnabled(),
                firstNonBlank(row.getHubBaseUrl(), envHubUrl),
                firstNonBlank(row.getHubCredentialRef(), envCredRef),
                row.getEntryPointId());
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
