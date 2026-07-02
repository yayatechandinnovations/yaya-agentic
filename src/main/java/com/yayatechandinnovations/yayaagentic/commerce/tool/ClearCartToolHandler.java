package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/** {@code clear_cart} — empties the cart and re-emits it. */
@Component(CommerceTools.CLEAR_CART_BEAN)
public class ClearCartToolHandler extends CartToolSupport {

    public ClearCartToolHandler(CommerceRuntime runtime, ManagedCart cart, CommercePayloads payloads) {
        super(runtime, cart, payloads);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        CommerceConfig cfg = runtime.config(ctx);
        return respond(ctx, cart.clear(ctx.sessionId(), currency(cfg)));
    }
}
