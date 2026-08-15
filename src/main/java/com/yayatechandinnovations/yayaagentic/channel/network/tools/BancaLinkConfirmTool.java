package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext.LinkState;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code banca_link_confirm} — establish the player↔banca vinculo via the hub
 * broker {@code link} op (mirrors the portal {@code confirm-link}), design §4.
 * Runs ONLY after an explicit approved confirm frame (it is {@code confirmable});
 * it is the transition PENDING→ACTIVE, so it is NOT banca-gated. On success it
 * commits the now-ACTIVE banca context.
 */
@Component("networkBancaLinkConfirmTool")
public class BancaLinkConfirmTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    private final HubBrokerClient hub;
    private final NetworkToolSupport support;
    private final WorkingMemory workingMemory;
    private final com.yayatechandinnovations.yayaagentic.channel.network.NetworkRateLimiter rateLimiter;

    public BancaLinkConfirmTool(HubBrokerClient hub, NetworkToolSupport support,
                                WorkingMemory workingMemory,
                                com.yayatechandinnovations.yayaagentic.channel.network.NetworkRateLimiter rateLimiter) {
        this.hub = hub;
        this.support = support;
        this.workingMemory = workingMemory;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        if (!rateLimiter.allowLinkConfirm(ctx.sessionId().value())) {
            return ToolResults.error(HubBrokerClient.BrokerError.RATE_LIMITED,
                    "Has intentado vincular varias veces hoy. Intenta de nuevo más tarde.");
        }
        BancaContext pending = BancaContext.read(workingMemory, ctx.sessionId()).orElse(null);
        // installCode comes from the PENDING context set by banca_context_set,
        // else the confirm frame's arg — never free model text.
        String installCode = pending != null ? pending.installCode()
                : (input == null ? null : str(input.get("installCode")));
        if (installCode == null || installCode.isBlank()) {
            return ToolResults.error(HubBrokerClient.BrokerError.BAD_REQUEST,
                    "No hay una banca pendiente para vincular.");
        }
        String phone = support.requireVerifiedPhone(ctx);

        BrokerResponse resp = hub.playSession("link", installCode, phone, support.sessionRef(ctx));
        if (!resp.ok()) {
            return ToolResults.error(resp.error(), support.userSafeReason(resp.error()));
        }

        String name = pending != null && pending.displayName() != null
                ? pending.displayName() : str(resp.body().getOrDefault("name", installCode));
        String orgId = pending != null ? pending.orgId() : str(resp.body().get("orgId"));
        BancaContext.commit(workingMemory, ctx.sessionId(),
                new BancaContext(installCode, orgId, name, LinkState.ACTIVE, Instant.now()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("linked", "ACTIVE");
        out.put("banca", Map.of("code", installCode, "name", name));
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
