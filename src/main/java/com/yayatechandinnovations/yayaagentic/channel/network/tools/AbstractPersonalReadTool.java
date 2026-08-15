package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base for the cross-banca personal reads ({@code player_overview},
 * {@code player_prizes}, {@code player_recent_plays}) — design §4/§4.1.
 * Enforces the coverage contract structurally: the phone comes from the verified
 * session, the result carries {@code sources[]}, and a non-OK source both tags the
 * result {@code coverage: "partial"} and sets a per-turn model directive so the
 * answer discloses the gap. Never cached across turns — every ask re-hits the hub.
 */
abstract class AbstractPersonalReadTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    protected final HubBrokerClient hub;
    protected final NetworkToolSupport support;

    protected AbstractPersonalReadTool(HubBrokerClient hub, NetworkToolSupport support) {
        this.hub = hub;
        this.support = support;
    }

    /** The hub call for this read (phone already resolved from the session).
     *  {@code input} carries any tool args (e.g. {@code limit}); identity never
     *  comes from it. */
    protected abstract BrokerResponse fetch(String phoneE164, String sessionRef, Map<String, Object> input);

    @Override
    public final Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String phone = support.requireVerifiedPhone(ctx);
        BrokerResponse resp = fetch(phone, support.sessionRef(ctx),
                input == null ? Map.of() : input);
        if (!resp.ok()) {
            return ToolResults.error(resp.error(), support.userSafeReason(resp.error()));
        }
        Map<String, Object> out = new LinkedHashMap<>(resp.body());
        Object sources = out.get("sources");
        String coverage = support.applyCoverage(ctx, sources instanceof List<?> l ? l : List.of());
        out.put("coverage", coverage);
        out.put("status", "OK");
        return out;
    }
}
