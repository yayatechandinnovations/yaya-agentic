package com.yayatechandinnovations.yayaagentic.channel.network;

import com.yayatechandinnovations.yayaagentic.config.TenantNetworkSettingsService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.NetworkPlayerProfileBootstrap;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The runtime play/claim gate for player_network_v1 (enablement UX §3). */
class NetworkCapabilityGateTest {

    private final TenantNetworkSettingsService settings = mock(TenantNetworkSettingsService.class);
    private final NetworkCapabilityGate gate = new NetworkCapabilityGate(settings);
    private final Ids.TenantId tenant = new Ids.TenantId("t1");
    private final Ids.ProfileId network = NetworkPlayerProfileBootstrap.PLAYER_NETWORK_PROFILE;

    private void stub(boolean enabled, boolean play, boolean claim) {
        when(settings.settings(anyString()))
                .thenReturn(new TenantNetworkSettingsService.Effective(enabled, play, claim, null, null, null));
        when(settings.settings(org.mockito.ArgumentMatchers.<Ids.TenantId>any()))
                .thenReturn(new TenantNetworkSettingsService.Effective(enabled, play, claim, null, null, null));
    }

    @Test
    void disabledFeatureHidesBothPlayAndClaim() {
        stub(false, true, true); // play/claim flags irrelevant while disabled
        assertEquals(Set.of(NetworkPlayerProfileBootstrap.CAP_PLAY, NetworkPlayerProfileBootstrap.CAP_CLAIM),
                gate.disabledCapabilities(tenant, network));
    }

    @Test
    void enabledWithPlayOnlyHidesClaim() {
        stub(true, true, false);
        assertEquals(Set.of(NetworkPlayerProfileBootstrap.CAP_CLAIM),
                gate.disabledCapabilities(tenant, network));
    }

    @Test
    void enabledWithBothOnHidesNothing() {
        stub(true, true, true);
        assertTrue(gate.disabledCapabilities(tenant, network).isEmpty());
    }

    @Test
    void nonNetworkProfileIsUntouched() {
        stub(false, false, false);
        assertTrue(gate.disabledCapabilities(tenant, new Ids.ProfileId("hello-world", 1)).isEmpty());
    }
}
