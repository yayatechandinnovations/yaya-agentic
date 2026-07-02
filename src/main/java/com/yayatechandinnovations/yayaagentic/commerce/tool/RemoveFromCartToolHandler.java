package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/** {@code remove_from_cart} — drops a line and re-emits the {@code cart}. */
@Component(CommerceTools.REMOVE_FROM_CART_BEAN)
public class RemoveFromCartToolHandler extends CartToolSupport {

    public RemoveFromCartToolHandler(CommerceRuntime runtime, ManagedCart cart, CommercePayloads payloads) {
        super(runtime, cart, payloads);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        CommerceConfig cfg = runtime.config(ctx);
        return respond(ctx, cart.remove(ctx.sessionId(), currency(cfg), Args.str(input, "lineId")));
    }
}
