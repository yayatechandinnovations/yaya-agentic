package com.yayatechandinnovations.yayaagentic.engine.banca;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Session-scoped banca context for the Corillo network entry point
 * ({@code player_network_v1}) — yaya-agentic-network-chat-runtime-design.md §2.
 *
 * <p>Held in {@link WorkingMemory} (Redis), alongside the existing
 * {@code pending_confirm}/{@code intent} slots, so it is sticky for the session
 * and cleared on session end without new storage. This class is the single home
 * for the network WM slot keys and their (de)serialization so the engine gate,
 * the {@code banca_context_set} tool, and the play tools all agree.
 *
 * <p><b>The play credential never lives here.</b> §2 lists {@code playCredential}
 * on the context, but the runtime keeps the short-lived customer token only in
 * {@code PlayCredentialCache} (never logged, never in frames/tool_results, and —
 * critically — never in WorkingMemory, whose whole snapshot is rendered into the
 * model prompt). The serialized map carries {@code playCredential: null} for
 * wire-shape fidelity only.
 */
public record BancaContext(
        String installCode,
        String orgId,
        String displayName,
        LinkState linked,
        Instant committedAt
) {

    /** Vinculo state between the player and the banca (design §2/§4). */
    public enum LinkState { ACTIVE, PENDING, NONE }

    /** WorkingMemory slot: the committed banca context (this record, as a Map). */
    public static final String SLOT = "bancaContext";

    /** The tool the engine's banca gate dispatches to surface a picker when a
     *  play-path tool is invoked with no committed context (design §4.2). Named
     *  here so the generic gate reaches the network selection flow through this
     *  one sanctioned seam rather than hard-coding a domain id inline. */
    public static final String SELECTION_TOOL_ID = "banca_context_set";

    /** WorkingMemory slot: install codes offered by the last {@code banca_picker},
     *  so a structured {@code selection} can be validated against what was actually
     *  offered — the client cannot inject an arbitrary install (design §3.2/§6). */
    public static final String LAST_PICKER_SLOT = "last_banca_picker";

    /** WorkingMemory slot: the most recent {@code play_quote} result the engine's
     *  approved-quote gate matches against (quoteId + fingerprint), design §4. */
    public static final String PENDING_QUOTE_SLOT = "pending_quote";

    /** WorkingMemory slot: a per-turn system directive (e.g. partial coverage)
     *  the prompt builder renders as an explicit instruction. Set by a tool for
     *  the current turn; cleared by {@code send()} at the next turn. Design §4.1.
     *  Read by {@code DefaultPromptBuilder.renderSuffix}. */
    public static final String COVERAGE_DIRECTIVE_SLOT = "coverage_directive";

    public boolean isActive() {
        return linked == LinkState.ACTIVE;
    }

    /** Serialize for WorkingMemory (playCredential is always null here — see the
     *  class note). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installCode", installCode);
        m.put("orgId", orgId);
        m.put("displayName", displayName);
        m.put("linked", linked == null ? LinkState.NONE.name() : linked.name());
        m.put("playCredential", null);
        m.put("committedAt", committedAt == null ? null : committedAt.toString());
        return m;
    }

    // ---- WorkingMemory access ------------------------------------------

    /** The committed context for this session, if any. */
    @SuppressWarnings("unchecked")
    public static Optional<BancaContext> read(WorkingMemory memory, Ids.SessionId session) {
        Object raw = memory.get(session).get(SLOT);
        if (!(raw instanceof Map<?, ?> m)) return Optional.empty();
        Map<String, Object> map = (Map<String, Object>) m;
        String install = str(map.get("installCode"));
        if (install == null || install.isBlank()) return Optional.empty();
        LinkState linked;
        try {
            linked = map.get("linked") == null
                    ? LinkState.NONE : LinkState.valueOf(String.valueOf(map.get("linked")));
        } catch (IllegalArgumentException ex) {
            linked = LinkState.NONE;
        }
        Instant committed = null;
        try {
            Object c = map.get("committedAt");
            if (c != null) committed = Instant.parse(String.valueOf(c));
        } catch (RuntimeException ignore) {
            // A malformed timestamp must not fail a turn — committedAt is
            // advisory display, the linked state is what the gate reads.
        }
        return Optional.of(new BancaContext(
                install, str(map.get("orgId")), str(map.get("displayName")), linked, committed));
    }

    /** True when an ACTIVE banca context is committed — the condition the engine's
     *  banca gate requires before dispatching a play-path tool. */
    public static boolean hasActiveContext(WorkingMemory memory, Ids.SessionId session) {
        return read(memory, session).map(BancaContext::isActive).orElse(false);
    }

    /** Commit (or replace) the session's banca context. */
    public static void commit(WorkingMemory memory, Ids.SessionId session, BancaContext ctx) {
        memory.merge(session, Map.of(SLOT, ctx.toMap()));
    }

    public static void clear(WorkingMemory memory, Ids.SessionId session) {
        memory.remove(session, SLOT);
    }

    // ---- offered-picker codes (selection integrity, §3.2/§6) ------------

    /** Record the install codes a {@code banca_picker} just offered. */
    public static void rememberOffered(WorkingMemory memory, Ids.SessionId session,
                                       List<String> codes) {
        memory.merge(session, Map.of(LAST_PICKER_SLOT, new ArrayList<>(codes)));
    }

    /** The codes offered by the last picker (empty when none was emitted). */
    @SuppressWarnings("unchecked")
    public static Set<String> offeredCodes(WorkingMemory memory, Ids.SessionId session) {
        Object raw = memory.get(session).get(LAST_PICKER_SLOT);
        if (!(raw instanceof List<?> list)) return Set.of();
        List<String> codes = new ArrayList<>();
        for (Object o : list) if (o != null) codes.add(String.valueOf(o));
        return Set.copyOf(codes);
    }

    /** Whether {@code code} was in the last offered picker — a structured
     *  selection for a code never offered is rejected (the client cannot inject
     *  an arbitrary install). */
    public static boolean wasOffered(WorkingMemory memory, Ids.SessionId session, String code) {
        return code != null && offeredCodes(memory, session).contains(code);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
