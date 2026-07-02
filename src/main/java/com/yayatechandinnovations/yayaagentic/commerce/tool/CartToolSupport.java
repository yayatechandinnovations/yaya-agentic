package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.Cart;
import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;

import java.util.Map;

/**
 * Shared behaviour for the managed cart tools: resolve currency from the
 * session's {@link CommerceConfig}, mutate the {@link ManagedCart}, emit the
 * {@code cart} UiHint, and return only a compact summary to the LLM (never the
 * line-by-line contents — the UI renders those, #8). See §8, §10.3.
 */
abstract class CartToolSupport implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    protected final CommerceRuntime runtime;
    protected final ManagedCart cart;
    protected final CommercePayloads payloads;

    protected CartToolSupport(CommerceRuntime runtime, ManagedCart cart, CommercePayloads payloads) {
        this.runtime = runtime;
        this.cart = cart;
        this.payloads = payloads;
    }

    protected String currency(CommerceConfig cfg) {
        if (cfg.currency() == null || cfg.currency().isBlank()) {
            throw new ToolException("commerce currency is not configured for this profile");
        }
        return cfg.currency();
    }

    protected Map<String, Object> respond(ExecutionContext ctx, Cart c) {
        runtime.emit(ctx, CommercePayloads.CART, payloads.cart(c));
        runtime.persistCart(ctx, c);   // durable write-through (§7.3)
        return Map.of(
                "itemCount", c.itemCount(),
                "subtotalMinor", c.subtotal().amountMinor(),
                "currency", c.currency());
    }
}
