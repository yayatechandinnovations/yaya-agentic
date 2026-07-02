package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.Cart;
import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code add_to_cart} — the input schema has NO price field; the qty and product
 * come from the model, but the price is re-derived from the authoritative catalog
 * inside {@link ManagedCart} (non-negotiable #1). Emits the updated {@code cart}.
 */
@Component(CommerceTools.ADD_TO_CART_BEAN)
public class AddToCartToolHandler extends CartToolSupport {

    public AddToCartToolHandler(CommerceRuntime runtime, ManagedCart cart, CommercePayloads payloads) {
        super(runtime, cart, payloads);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        CommerceConfig cfg = runtime.config(ctx);
        String currency = currency(cfg);
        Cart updated = cart.add(
                ctx.sessionId(), currency,
                new Ids.ProductId(Args.str(input, "productId")),
                Args.str(input, "variantId"),
                Args.intOr(input, "qty", 1),
                runtime.lookup(ctx, cfg));
        return respond(ctx, updated);
    }
}
