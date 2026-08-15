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
 * {@code directory_online_bancas} — bancas available online, feeding the picker's
 * "online" group and quick-pick ranking (design §4). The only arg is
 * {@code openNow}; no identity flows in. Read-safe (S2).
 */
@Component("networkDirectoryOnlineBancasTool")
public class DirectoryOnlineBancasTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    private final HubBrokerClient hub;
    private final NetworkToolSupport support;

    public DirectoryOnlineBancasTool(HubBrokerClient hub, NetworkToolSupport support) {
        this.hub = hub;
        this.support = support;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        Boolean openNow = asBool(input == null ? null : input.get("openNow"));
        BrokerResponse resp = hub.onlineBancas(openNow, support.sessionRef(ctx));
        if (!resp.ok()) {
            return ToolResults.error(resp.error(), support.userSafeReason(resp.error()));
        }
        Map<String, Object> out = new LinkedHashMap<>(resp.body());
        out.put("status", "OK");
        return out;
    }

    private static Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
