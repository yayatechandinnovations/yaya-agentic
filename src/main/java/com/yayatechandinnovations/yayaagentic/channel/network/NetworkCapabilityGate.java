package com.yayatechandinnovations.yayaagentic.channel.network;

import com.yayatechandinnovations.yayaagentic.config.TenantNetworkSettingsService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.CapabilityGate;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.NetworkPlayerProfileBootstrap;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Hides the network {@code play}/{@code claim} capabilities on
 * {@code player_network_v1} unless the tenant's live settings enable them — the
 * runtime, no-restart S3 toggle. An operator flipping play/claim in the console
 * evicts {@link TenantNetworkSettingsService}'s cache, so the tools appear or
 * disappear on the very next turn.
 */
@Component
public class NetworkCapabilityGate implements CapabilityGate {

    private final TenantNetworkSettingsService settings;

    public NetworkCapabilityGate(TenantNetworkSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public Set<Ids.CapabilityId> disabledCapabilities(Ids.TenantId tenant, Ids.ProfileId profile) {
        if (profile == null
                || !NetworkPlayerProfileBootstrap.PLAYER_NETWORK_PROFILE.value().equals(profile.value())) {
            return Set.of();
        }
        TenantNetworkSettingsService.Effective s = settings.settings(tenant);
        Set<Ids.CapabilityId> off = new HashSet<>();
        if (!(s.enabled() && s.playEnabled())) off.add(NetworkPlayerProfileBootstrap.CAP_PLAY);
        if (!(s.enabled() && s.claimEnabled())) off.add(NetworkPlayerProfileBootstrap.CAP_CLAIM);
        return off;
    }
}
