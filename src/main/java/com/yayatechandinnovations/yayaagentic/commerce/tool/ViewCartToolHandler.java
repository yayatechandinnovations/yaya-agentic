package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/** {@code view_cart} — re-emits the current {@code cart} snapshot. */
@Component(CommerceTools.VIEW_CART_BEAN)
public class ViewCartToolHandler extends CartToolSupport {

    public ViewCartToolHandler(CommerceRuntime runtime, ManagedCart cart, CommercePayloads payloads) {
        super(runtime, cart, payloads);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        CommerceConfig cfg = runtime.config(ctx);
        return respond(ctx, cart.view(ctx.sessionId(), currency(cfg)));
    }
}
