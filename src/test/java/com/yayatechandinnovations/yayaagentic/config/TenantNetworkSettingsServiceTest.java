package com.yayatechandinnovations.yayaagentic.config;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties.Network;
import com.yayatechandinnovations.yayaagentic.persistence.TenantNetworkSettingsEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantNetworkSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantNetworkSettingsServiceTest {

    private YayaAgenticProperties props(String seedTenant, String envHub, boolean envPlay) {
        Network net = new Network(seedTenant,
                new Network.Hub(envHub, "ENV_REF", null),
                new Network.Capabilities(envPlay, false),
                Network.RateLimits.defaults());
        return new YayaAgenticProperties(null, null, null, null, null, null, null, null,
                null, null, null, net);
    }

    @Test
    void noRowFallsBackToEnv_enabledOnlyForSeedTenant() {
        var repo = mock(TenantNetworkSettingsRepository.class);
        when(repo.findByTenantId(eq("t1"))).thenReturn(Optional.empty());
        var svc = new TenantNetworkSettingsService(repo, props("t1", "https://env-hub", true));

        var eff = svc.settings("t1");
        assertTrue(eff.enabled());          // t1 is the env seed tenant
        assertTrue(eff.playEnabled());      // from env capabilities
        assertEquals("https://env-hub", eff.hubBaseUrl());
        assertEquals("ENV_REF", eff.hubCredentialRef());
    }

    @Test
    void noRowNonSeedTenantIsDisabled() {
        var repo = mock(TenantNetworkSettingsRepository.class);
        when(repo.findByTenantId(eq("other"))).thenReturn(Optional.empty());
        var svc = new TenantNetworkSettingsService(repo, props("t1", "https://env-hub", true));
        assertFalse(svc.settings("other").enabled());
    }

    @Test
    void rowOverridesEnvAndUnsetHubFallsBack() {
        var repo = mock(TenantNetworkSettingsRepository.class);
        TenantNetworkSettingsEntity row = new TenantNetworkSettingsEntity("t2");
        row.setEnabled(true);
        row.setPlayEnabled(true);
        row.setClaimEnabled(true);
        // hubBaseUrl left null → should fall back to env
        when(repo.findByTenantId(eq("t2"))).thenReturn(Optional.of(row));
        var svc = new TenantNetworkSettingsService(repo, props(null, "https://env-hub", false));

        var eff = svc.settings("t2");
        assertTrue(eff.enabled());
        assertTrue(eff.playEnabled());
        assertTrue(eff.claimEnabled());
        assertEquals("https://env-hub", eff.hubBaseUrl()); // env fallback
    }

    @Test
    void cacheEvictedOnChangeEvent() {
        var repo = mock(TenantNetworkSettingsRepository.class);
        var svc = new TenantNetworkSettingsService(repo, props(null, "https://env-hub", false));

        TenantNetworkSettingsEntity off = new TenantNetworkSettingsEntity("t3");
        when(repo.findByTenantId(eq("t3"))).thenReturn(Optional.of(off));
        assertFalse(svc.settings("t3").playEnabled()); // cached: play off

        TenantNetworkSettingsEntity on = new TenantNetworkSettingsEntity("t3");
        on.setEnabled(true);
        on.setPlayEnabled(true);
        when(repo.findByTenantId(eq("t3"))).thenReturn(Optional.of(on));

        // Without eviction the stale cache still says off.
        assertFalse(svc.settings("t3").playEnabled());
        svc.onChanged(new TenantNetworkSettingsChangedEvent("t3"));
        assertTrue(svc.settings("t3").playEnabled()); // reloaded
    }
}
