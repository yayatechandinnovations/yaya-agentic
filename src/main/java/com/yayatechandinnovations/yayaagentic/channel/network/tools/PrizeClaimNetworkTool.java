package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.channel.network.PlayCredentialCache;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext.LinkState;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code prize_claim_network} — wallet-only claim of one prize in the prize's banca
 * (design §4). The prize NAMES its banca, so the banca context is auto-committed
 * (and announced) rather than picker-selected; this tool is therefore NOT
 * banca-gated. It IS {@code confirmable} + approved-frame-gated: it runs only after
 * an approved confirm frame (callId match), exactly like {@code play_confirm}.
 *
 * <p>Ownership + wallet-only rules are enforced server-side by the install claim
 * surface; the runtime supplies the brokered credential and an idempotency key.
 * Ships behind the {@code claim} capability flag (default OFF).
 */
@Component("networkPrizeClaimTool")
public class PrizeClaimNetworkTool extends AbstractPlayTool {

    public PrizeClaimNetworkTool(HubBrokerClient hub, NetworkToolSupport support,
                                 WorkingMemory workingMemory, PlayCredentialCache credentials) {
        super(hub, support, workingMemory, credentials);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String installCode = str(input == null ? null : input.get("installCode"));
        String ticketNumber = str(input == null ? null : input.get("ticketNumber"));
        if (installCode == null || installCode.isBlank() || ticketNumber == null || ticketNumber.isBlank()) {
            return ToolResults.error(HubBrokerClient.BrokerError.BAD_REQUEST,
                    "Necesito el número de ticket y la banca del premio para reclamarlo.");
        }
        String phone = support.requireVerifiedPhone(ctx);

        // Auto-commit the prize's banca as the active context (announced in the
        // result). The prize identifies its banca; the claim surface re-checks
        // ownership server-side, so this commit is a record, not an authority.
        String name = str(input.getOrDefault("bancaName", installCode));
        BancaContext.commit(workingMemory, ctx.sessionId(),
                new BancaContext(installCode, str(input.get("orgId")), name, LinkState.ACTIVE, Instant.now()));

        String idempotencyKey = ctx.sessionId().value() + ":claim:" + installCode + ":" + ticketNumber;
        Map<String, Object> body = Map.of(
                "ticketNumber", ticketNumber,
                "idempotencyKey", idempotencyKey);

        BrokerResponse resp = callWithRefresh(ctx, installCode, phone,
                cred -> hub.playSurface(cred, HttpMethod.POST, "/player/prizes/claim", body,
                        support.sessionRef(ctx)));
        if (!resp.ok()) {
            return ToolResults.error(resp.error(), support.userSafeReason(resp.error()));
        }

        Map<String, Object> receipt = new LinkedHashMap<>(resp.body());
        receipt.remove("customerToken");
        receipt.remove("playCredential");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("banca", Map.of("code", installCode, "name", name));
        out.put("claim", receipt);
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
