package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import org.springframework.stereotype.Component;

/**
 * {@code player_prizes} — cross-banca claimable-prize LIST with amounts
 * (ticketNumber, prizeDOP, claimableDOP, claimStatus per banca) + {@code sources[]}
 * (design §4). Amounts are pre-formatted display strings the model quotes verbatim
 * (§4.1); no model arithmetic.
 */
@Component("networkPlayerPrizesTool")
public class PlayerPrizesTool extends AbstractPersonalReadTool {

    public PlayerPrizesTool(HubBrokerClient hub, NetworkToolSupport support) {
        super(hub, support);
    }

    @Override
    protected BrokerResponse fetch(String tenantId, String phoneE164, String sessionRef,
                                   java.util.Map<String, Object> input) {
        return hub.playerPrizes(tenantId, phoneE164, sessionRef);
    }
}
