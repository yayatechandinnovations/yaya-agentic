package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.engine.PendingUiHints;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext.LinkState;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code banca_context_set} — runtime-internal (no external backing): resolves an
 * NL statement or a validated {@code selection} to exactly one banca and sets the
 * session's banca context (design §4/§2). It is NOT a play-path tool and must NOT
 * be banca-gated (it SETS the context).
 *
 * <ul>
 *   <li><b>Ambiguous / no match</b> → emits a {@code banca_picker} ui_hint (via
 *       {@link PendingUiHints}), records the offered codes for selection-integrity
 *       validation, and returns {@code NEEDS_SELECTION}; the context stays unset.</li>
 *   <li><b>Resolved + vinculo ACTIVE</b> → commits an ACTIVE {@link BancaContext}
 *       (playCredential stays lazy).</li>
 *   <li><b>Resolved but not linked</b> → commits a PENDING context and returns a
 *       {@code _confirmRequest} the engine turns into a {@code banca_link_confirm}
 *       confirm frame (§4.5) — the tool never links on its own.</li>
 * </ul>
 */
@Component("networkBancaContextSetTool")
public class BancaContextSetTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    private final HubBrokerClient hub;
    private final NetworkToolSupport support;
    private final WorkingMemory workingMemory;
    private final PendingUiHints pendingUiHints;
    private final com.yayatechandinnovations.yayaagentic.channel.network.NetworkRateLimiter rateLimiter;

    public BancaContextSetTool(HubBrokerClient hub, NetworkToolSupport support,
                               WorkingMemory workingMemory, PendingUiHints pendingUiHints,
                               com.yayatechandinnovations.yayaagentic.channel.network.NetworkRateLimiter rateLimiter) {
        this.hub = hub;
        this.support = support;
        this.workingMemory = workingMemory;
        this.pendingUiHints = pendingUiHints;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String phone = support.requireVerifiedPhone(ctx);
        String sessionRef = support.sessionRef(ctx);
        String code = str(input == null ? null : input.get("code"));
        String query = firstNonBlank(
                str(input == null ? null : input.get("name")),
                str(input == null ? null : input.get("banca")),
                str(input == null ? null : input.get("query")));

        BrokerResponse linkedResp = hub.linkedBancas(phone, sessionRef);
        BrokerResponse onlineResp = hub.onlineBancas(null, sessionRef);
        if (!linkedResp.ok() && !onlineResp.ok()) {
            return ToolResults.error(linkedResp.error(), support.userSafeReason(linkedResp.error()));
        }
        List<Map<String, Object>> linked = items(linkedResp);
        List<Map<String, Object>> online = items(onlineResp);

        // Resolve the target banca.
        Map<String, Object> target = null;
        if (code != null) {
            target = findByCode(linked, code);
            if (target == null) target = findByCode(online, code);
        } else if (query != null) {
            List<Map<String, Object>> matches = matchByName(concat(linked, online), query);
            if (matches.size() == 1) {
                target = matches.get(0);
            } else {
                return emitPicker(ctx, linked, online); // 0 or >1 → ambiguous
            }
        } else {
            return emitPicker(ctx, linked, online); // no hint at all
        }
        if (target == null) {
            // A named/selected code we could not resolve — re-offer rather than
            // silently commit something the player didn't choose.
            return emitPicker(ctx, linked, online);
        }

        String tCode = str(target.get("code"));
        String tName = str(target.getOrDefault("name", tCode));
        String tOrg = str(firstNonNull(target.get("orgId"), target.get("org")));
        boolean isLinkedActive = isVinculoActive(linked, tCode);

        if (isLinkedActive) {
            BancaContext.commit(workingMemory, ctx.sessionId(),
                    new BancaContext(tCode, tOrg, tName, LinkState.ACTIVE, Instant.now()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "OK");
            out.put("linked", "ACTIVE");
            out.put("banca", Map.of("code", tCode, "name", tName));
            return out;
        }

        // Not linked → PENDING + a link-offer confirm frame (engine-driven, §4.5).
        BancaContext.commit(workingMemory, ctx.sessionId(),
                new BancaContext(tCode, tOrg, tName, LinkState.PENDING, Instant.now()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "NEEDS_LINK");
        out.put("linked", "NONE");
        out.put("banca", Map.of("code", tCode, "name", tName));
        // The engine's ConfirmRequestPassThrough strips this and raises a
        // banca_link_confirm confirm frame; the LLM never sees the plumbing.
        out.put("_confirmRequest", Map.of(
                "toolId", "banca_link_confirm",
                "args", Map.of("installCode", tCode),
                "summary", "¿Quieres vincularte a " + tName + " para jugar ahí?"));
        return out;
    }

    // ---- picker -------------------------------------------------------

    private Map<String, Object> emitPicker(ExecutionContext ctx,
                                           List<Map<String, Object>> linked,
                                           List<Map<String, Object>> online) {
        // Per-session picker cool-down (§6): a polite refusal, not a picker.
        if (!rateLimiter.allowPicker(ctx.sessionId().value())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "RATE_LIMITED");
            out.put("message", "Vamos un poco rápido; espera un momento e intenta de nuevo.");
            return out;
        }
        List<Map<String, Object>> linkedItems = pickerItems(linked, "ACTIVE");
        List<Map<String, Object>> onlineItems = pickerItems(online, null);
        List<Map<String, Object>> groups = new ArrayList<>();
        if (!linkedItems.isEmpty()) {
            groups.add(Map.of("id", "linked", "title", "Tus bancas", "items", linkedItems));
        }
        if (!onlineItems.isEmpty()) {
            groups.add(Map.of("id", "online", "title", "Disponibles en línea", "items", onlineItems));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", "¿En cuál banca quieres jugar?");
        payload.put("allowQuickPick", true);
        payload.put("groups", groups);
        pendingUiHints.enqueue(ctx.sessionId(), "banca_picker", payload);

        // Selection-integrity: remember exactly which codes we offered (§3.2/§6).
        List<String> offered = new ArrayList<>();
        for (Map<String, Object> it : linkedItems) if (it.get("code") != null) offered.add(str(it.get("code")));
        for (Map<String, Object> it : onlineItems) if (it.get("code") != null) offered.add(str(it.get("code")));
        BancaContext.rememberOffered(workingMemory, ctx.sessionId(), offered);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "NEEDS_SELECTION");
        // Plain-text ask travels with the picker (old clients ignore the ui_hint
        // and degrade to NL — contract §3.1). The model voices this.
        out.put("prompt", "¿En cuál banca quieres jugar?");
        return out;
    }

    private static List<Map<String, Object>> pickerItems(List<Map<String, Object>> src, String forceLinked) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> b : src) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", b.get("code"));
            item.put("name", b.getOrDefault("name", b.get("code")));
            if (b.get("city") != null) item.put("city", b.get("city"));
            if (b.get("open") != null) item.put("open", b.get("open"));
            if (b.get("juegaRemoto") != null) item.put("juegaRemoto", b.get("juegaRemoto"));
            item.put("linked", forceLinked != null ? forceLinked
                    : b.getOrDefault("linked", "NONE"));
            items.add(item);
        }
        return items;
    }

    // ---- matching helpers ---------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(BrokerResponse resp) {
        if (resp == null || !resp.ok()) return List.of();
        Map<String, Object> body = resp.body();
        for (String key : List.of("bancas", "items", "linked", "results")) {
            Object v = body.get(key);
            if (v instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                return out;
            }
        }
        return List.of();
    }

    private static Map<String, Object> findByCode(List<Map<String, Object>> src, String code) {
        for (Map<String, Object> b : src) {
            if (code.equalsIgnoreCase(str(b.get("code")))) return b;
        }
        return null;
    }

    private static List<Map<String, Object>> matchByName(List<Map<String, Object>> src, String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        // Exact (case-insensitive) name/code wins outright.
        List<Map<String, Object>> exact = new ArrayList<>();
        for (Map<String, Object> b : src) {
            String name = str(b.get("name"));
            String code = str(b.get("code"));
            if ((name != null && name.toLowerCase(Locale.ROOT).equals(q))
                    || (code != null && code.toLowerCase(Locale.ROOT).equals(q))) {
                exact.add(b);
            }
        }
        if (!exact.isEmpty()) return dedupeByCode(exact);
        // Otherwise, substring contains.
        List<Map<String, Object>> contains = new ArrayList<>();
        for (Map<String, Object> b : src) {
            String name = str(b.get("name"));
            if (name != null && name.toLowerCase(Locale.ROOT).contains(q)) contains.add(b);
        }
        return dedupeByCode(contains);
    }

    private static List<Map<String, Object>> dedupeByCode(List<Map<String, Object>> src) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> b : src) {
            String code = str(b.get("code"));
            if (code != null) byCode.putIfAbsent(code, b);
        }
        return new ArrayList<>(byCode.values());
    }

    private static boolean isVinculoActive(List<Map<String, Object>> linked, String code) {
        Map<String, Object> b = findByCode(linked, code);
        if (b == null) return false;
        Object v = firstNonNull(b.get("linked"), b.get("vinculo"), b.get("linkStatus"));
        return v != null && "ACTIVE".equalsIgnoreCase(String.valueOf(v));
    }

    private static List<Map<String, Object>> concat(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        List<Map<String, Object>> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Object firstNonNull(Object... vs) {
        for (Object v : vs) if (v != null) return v;
        return null;
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
