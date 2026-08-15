package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code player_recent_plays} — merged recent plays with banca labels +
 * {@code sources[]} (design §4). Optional {@code limit} arg (bounded). Coverage
 * contract applies (§4.1).
 */
@Component("networkPlayerRecentPlaysTool")
public class PlayerRecentPlaysTool extends AbstractPersonalReadTool {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    public PlayerRecentPlaysTool(HubBrokerClient hub, NetworkToolSupport support) {
        super(hub, support);
    }

    @Override
    protected BrokerResponse fetch(String phoneE164, String sessionRef, Map<String, Object> input) {
        return hub.playerTickets(phoneE164, readLimit(input), sessionRef);
    }

    private static int readLimit(Map<String, Object> input) {
        Object v = input.get("limit");
        if (v == null) return DEFAULT_LIMIT;
        try {
            int n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v));
            return n > 0 && n <= MAX_LIMIT ? n : DEFAULT_LIMIT;
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}
