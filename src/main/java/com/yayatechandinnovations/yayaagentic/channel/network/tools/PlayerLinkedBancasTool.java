package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code player_linked_bancas} — the player's vinculos + directory card fields,
 * feeding the picker's "linked" group (design §4). Phone is the session's verified
 * phone, never a model arg. Read-safe (S2).
 */
@Component("networkPlayerLinkedBancasTool")
public class PlayerLinkedBancasTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    private final HubBrokerClient hub;
    private final NetworkToolSupport support;

    public PlayerLinkedBancasTool(HubBrokerClient hub, NetworkToolSupport support) {
        this.hub = hub;
        this.support = support;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String phone = support.requireVerifiedPhone(ctx);
        BrokerResponse resp = hub.linkedBancas(support.tenantId(ctx), phone, support.sessionRef(ctx));
        if (!resp.ok()) {
            return ToolResults.error(resp.error(), support.userSafeReason(resp.error()));
        }
        Map<String, Object> out = new LinkedHashMap<>(resp.body());
        out.put("status", "OK");
        return out;
    }
}
