package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import org.springframework.stereotype.Component;

/**
 * {@code player_overview} — cross-banca points/cash/open-ticket/unclaimed-prize
 * counts, per banca, with {@code sources[]} (design §4). Points are org-scoped and
 * never pooled: the per-banca breakdown IS the answer (§4.1).
 */
@Component("networkPlayerOverviewTool")
public class PlayerOverviewTool extends AbstractPersonalReadTool {

    public PlayerOverviewTool(HubBrokerClient hub, NetworkToolSupport support) {
        super(hub, support);
    }

    @Override
    protected BrokerResponse fetch(String phoneE164, String sessionRef, java.util.Map<String, Object> input) {
        return hub.playerOverview(phoneE164, sessionRef);
    }
}
