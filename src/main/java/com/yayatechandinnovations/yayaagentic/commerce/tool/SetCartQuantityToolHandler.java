package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/** {@code set_cart_quantity} — reprices a line from its unit price (qty 0 removes it). */
@Component(CommerceTools.SET_CART_QUANTITY_BEAN)
public class SetCartQuantityToolHandler extends CartToolSupport {

    public SetCartQuantityToolHandler(CommerceRuntime runtime, ManagedCart cart, CommercePayloads payloads) {
        super(runtime, cart, payloads);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        CommerceConfig cfg = runtime.config(ctx);
        return respond(ctx, cart.setQuantity(
                ctx.sessionId(), currency(cfg), Args.str(input, "lineId"), Args.intOr(input, "qty", 0)));
    }
}
