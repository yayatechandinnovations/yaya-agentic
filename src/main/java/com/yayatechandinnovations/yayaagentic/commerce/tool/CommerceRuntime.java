package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.commerce.Cart;
import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.ProductLookup;
import com.yayatechandinnovations.yayaagentic.commerce.search.ProductSearchService;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.engine.PendingUiHints;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceCartEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceCartRepository;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Shared plumbing for the managed commerce Bean tools: reads the session's
 * {@link CommerceConfig} (stashed in working memory at session start, §8),
 * builds an {@link AuthzContext} from the {@link ExecutionContext}, exposes an
 * access-gated {@link ProductLookup}, and enqueues render hints for the engine
 * to flush ({@code PendingUiHints}). Keeps each handler thin.
 */
@Component
public class CommerceRuntime {

    static final String CONFIG_SLOT = "commerceConfig";

    private static final Logger log = LoggerFactory.getLogger(CommerceRuntime.class);

    private final WorkingMemory memory;
    private final ObjectMapper json;
    private final ProductSearchService search;
    private final PendingUiHints pendingUiHints;
    private final CommerceCartRepository durableCarts;   // nullable in unit tests

    @Autowired
    public CommerceRuntime(WorkingMemory memory, ObjectMapper json,
                           ProductSearchService search, PendingUiHints pendingUiHints,
                           CommerceCartRepository durableCarts) {
        this.memory = memory;
        this.json = json;
        this.search = search;
        this.pendingUiHints = pendingUiHints;
        this.durableCarts = durableCarts;
    }

    /** Convenience for unit tests: no durable projection. */
    public CommerceRuntime(WorkingMemory memory, ObjectMapper json,
                           ProductSearchService search, PendingUiHints pendingUiHints) {
        this(memory, json, search, pendingUiHints, null);
    }

    public CommerceConfig config(ExecutionContext ctx) {
        Object raw = memory.get(ctx.sessionId()).get(CONFIG_SLOT);
        if (raw == null) {
            throw new ToolException("commerce is not enabled for this session");
        }
        return json.convertValue(raw, CommerceConfig.class);
    }

    public AuthzContext authz(ExecutionContext ctx) {
        return new AuthzContext(ctx.sessionId(), ctx.turnId(), ctx.traceId(), ctx.attributes());
    }

    /** Authoritative, access-gated product resolution over the session's catalogs. */
    public ProductLookup lookup(ExecutionContext ctx, CommerceConfig cfg) {
        return search.lookup(ctx.principal().tenant(), cfg.catalogs(), ctx.principal(), authz(ctx));
    }

    public ProductSearchService search() {
        return search;
    }

    public void emit(ExecutionContext ctx, String kind, Map<String, Object> payload) {
        pendingUiHints.enqueue(ctx.sessionId(), kind, payload);
    }

    /**
     * Write-through the cart to the durable projection so it survives the
     * working-memory TTL (reconnect + webhook-clear-after-TTL, §7.3). No-op when
     * no durable store is wired or the session id isn't a UUID (unit fixtures).
     */
    public void persistCart(ExecutionContext ctx, Cart cart) {
        if (durableCarts == null) return;
        UUID session = parseUuid(ctx.sessionId().value());
        if (session == null) return;
        try {
            String cartJson = json.writeValueAsString(cart);
            CommerceCartEntity row = durableCarts.findById(session)
                    .orElseGet(() -> new CommerceCartEntity(session, ctx.principal().tenant().value(), cartJson));
            row.setTenantId(ctx.principal().tenant().value());
            row.setCartJson(cartJson);
            durableCarts.save(row);
        } catch (Exception e) {
            // Durability is best-effort; the WM cart is the live source of truth.
            log.warn("failed to persist durable cart for session {}: {}",
                    ctx.sessionId().value(), e.getMessage());
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

