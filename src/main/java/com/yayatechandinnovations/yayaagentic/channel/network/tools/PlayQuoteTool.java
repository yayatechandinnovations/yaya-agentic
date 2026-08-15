package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

/**
 * {@code play_quote} — draft/validate a play against the committed banca's install
 * surface using the brokered credential (design §4). Returns a quote
 * {lines, amounts, draw, cutoff, balance-after}; NEVER the customer token (§6).
 *
 * <p>Writes {@code pending_quote = {quoteId, fingerprint, installCode}} so the
 * engine's approved-quote gate can bind a later {@code play_confirm} to THIS exact
 * quote. Banca-gated (requires an ACTIVE context) but not itself confirmable.
 */
@Component("networkPlayQuoteTool")
public class PlayQuoteTool extends AbstractPlayTool {

    private final ObjectMapper json;

    public PlayQuoteTool(HubBrokerClient hub, NetworkToolSupport support,
                         WorkingMemory workingMemory, PlayCredentialCache credentials,
                         ObjectMapper json) {
        super(hub, support, workingMemory, credentials);
        this.json = json;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        // The engine's banca gate guarantees an ACTIVE context before we run;
        // reading installCode from the context (never a model arg) upholds §6.
        BancaContext banca = banca(ctx).orElse(null);
        if (banca == null || !banca.isActive()) {
            return ToolResults.error(HubBrokerClient.BrokerError.NOT_LINKED,
                    "Primero elige una banca para jugar.");
        }
        String phone = support.requireVerifiedPhone(ctx);
        Map<String, Object> playArgs = playArgs(input);

        BrokerResponse resp = callWithRefresh(ctx, banca.installCode(), phone,
                cred -> playSurface(cred, HttpMethod.POST, "/player/play/quote", playArgs, ctx));
        if (!resp.ok()) {
            return ToolResults.error(resp.error(), support.userSafeReason(resp.error()));
        }

        Map<String, Object> quote = new LinkedHashMap<>(resp.body());
        // customerToken must never ride a quote result — defensive strip in case
        // an install echoes it (belt to the engine-seam suspenders, §6).
        quote.remove("customerToken");
        quote.remove("playCredential");

        String quoteId = quote.get("quoteId") != null
                ? String.valueOf(quote.get("quoteId")) : UUID.randomUUID().toString();
        quote.put("quoteId", quoteId);

        // Bind this quote for the approved-quote gate. fingerprint guards against
        // an install regenerating the quote between confirm-emit and approval.
        workingMemory.merge(ctx.sessionId(), Map.of(
                BancaContext.PENDING_QUOTE_SLOT, Map.of(
                        "quoteId", quoteId,
                        "fingerprint", fingerprint(quote),
                        "installCode", banca.installCode())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("banca", Map.of("code", banca.installCode(), "name", banca.displayName()));
        out.put("quote", quote);
        return out;
    }

    /** Only the play-shaping fields go to the install; identity never does. */
    private static Map<String, Object> playArgs(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>(input == null ? Map.of() : input);
        out.keySet().removeAll(java.util.Set.of("phone", "installCode", "code", "customerId", "customerToken"));
        return out;
    }

    private String fingerprint(Map<String, Object> quote) {
        // A stable hash of the quote content (minus the id) — the confirm gate
        // compares it so an altered quote can't be confirmed under an old id.
        Map<String, Object> forHash = new LinkedHashMap<>(quote);
        forHash.remove("quoteId");
        try {
            String canonical = json.writeValueAsString(forHash);
            return Integer.toHexString(canonical.hashCode());
        } catch (JsonProcessingException e) {
            return Integer.toHexString(forHash.hashCode());
        }
    }
}
