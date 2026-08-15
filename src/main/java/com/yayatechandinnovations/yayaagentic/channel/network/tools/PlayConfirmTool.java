package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.channel.network.PlayCredentialCache;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code play_confirm} — execute the play bound by the approved quote (design §4).
 * By the time this runs the engine has enforced the HARD preconditions
 * structurally: an ACTIVE banca context (banca gate) AND an approved confirm frame
 * for this exact quote (approved-quote gate). This tool therefore only executes.
 *
 * <p>Idempotency key = {@code (sessionId, quoteId)}: a quote is unique per confirmed
 * play, so a double-submit dedupes at the install — the (session, confirmed-action)
 * key the design's (session, callId) intends. The customer token never leaves the
 * cache (§6).
 */
@Component("networkPlayConfirmTool")
public class PlayConfirmTool extends AbstractPlayTool {

    private final com.yayatechandinnovations.yayaagentic.channel.network.NetworkRateLimiter rateLimiter;

    public PlayConfirmTool(HubBrokerClient hub, NetworkToolSupport support,
                           WorkingMemory workingMemory, PlayCredentialCache credentials,
                           com.yayatechandinnovations.yayaagentic.channel.network.NetworkRateLimiter rateLimiter) {
        super(hub, support, workingMemory, credentials);
        this.rateLimiter = rateLimiter;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        if (!rateLimiter.allowPlayConfirm(ctx.sessionId().value())) {
            return ToolResults.error(HubBrokerClient.BrokerError.RATE_LIMITED,
                    "Demasiados intentos seguidos. Espera un momento e intenta de nuevo.");
        }
        BancaContext banca = banca(ctx).orElse(null);
        Object pq = workingMemory.get(ctx.sessionId()).get(BancaContext.PENDING_QUOTE_SLOT);
        if (banca == null || !banca.isActive() || !(pq instanceof Map)) {
            // Defense in depth — the gate should have caught this.
            return ToolResults.error(HubBrokerClient.BrokerError.BAD_REQUEST,
                    "No hay una cotización aprobada para confirmar.");
        }
        Map<String, Object> pendingQuote = (Map<String, Object>) pq;
        String quoteId = String.valueOf(pendingQuote.get("quoteId"));
        String installCode = banca.installCode();
        String phone = support.requireVerifiedPhone(ctx);

        String idempotencyKey = ctx.sessionId().value() + ":" + quoteId;
        Map<String, Object> body = Map.of("quoteId", quoteId, "idempotencyKey", idempotencyKey);

        BrokerResponse resp = callWithRefresh(ctx, installCode, phone,
                cred -> hub.playSurface(cred, HttpMethod.POST, "/player/play/execute", body,
                        support.sessionRef(ctx)));
        if (!resp.ok()) {
            return ToolResults.error(resp.error(), support.userSafeReason(resp.error()));
        }

        // Consume the quote so it can't be replayed under a new confirm.
        workingMemory.remove(ctx.sessionId(), BancaContext.PENDING_QUOTE_SLOT);

        Map<String, Object> receipt = new LinkedHashMap<>(resp.body());
        receipt.remove("customerToken");
        receipt.remove("playCredential");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("banca", Map.of("code", installCode, "name", banca.displayName()));
        out.put("receipt", receipt);
        return out;
    }
}
