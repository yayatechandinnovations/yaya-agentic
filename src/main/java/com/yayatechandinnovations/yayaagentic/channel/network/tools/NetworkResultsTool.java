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
 * {@code network_results} — lottery draw results ("¿qué salió hoy/ayer?").
 * Results are lottery-wide (identical across bancas), so the hub proxies one
 * install's public, non-digital results feed — no identity, no org principal.
 * Args (all optional): {@code date} (YYYY-MM-DD; defaults to today),
 * {@code lottery} (id / short name / name to narrow to one lottery), and
 * {@code installCode} to pin a specific banca as the source. Read-safe (S2).
 */
@Component("networkResultsTool")
public class NetworkResultsTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    private final HubBrokerClient hub;
    private final NetworkToolSupport support;

    public NetworkResultsTool(HubBrokerClient hub, NetworkToolSupport support) {
        this.hub = hub;
        this.support = support;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String date = asString(input == null ? null : input.get("date"));
        String lottery = asString(input == null ? null : input.get("lottery"));
        String installCode = asString(input == null ? null : input.get("installCode"));
        BrokerResponse resp = hub.results(
                support.tenantId(ctx), date, lottery, installCode, support.sessionRef(ctx));
        if (!resp.ok()) {
            return ToolResults.error(resp.error(), support.userSafeReason(resp.error()));
        }
        Map<String, Object> out = new LinkedHashMap<>(resp.body());
        out.putIfAbsent("status", "OK");
        return out;
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
