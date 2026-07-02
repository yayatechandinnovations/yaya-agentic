package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * {@code get_product} — resolves one product (access-gated) and paints an
 * expanded {@code product_card} UiHint. Returns only a found/not-found signal to
 * the LLM. See {@code ecommerce-chat-design.md} §8, §10.1.
 */
@Component(CommerceTools.GET_PRODUCT_BEAN)
public class GetProductToolHandler implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    private final CommerceRuntime runtime;
    private final CommercePayloads payloads;

    public GetProductToolHandler(CommerceRuntime runtime, CommercePayloads payloads) {
        this.runtime = runtime;
        this.payloads = payloads;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        CommerceConfig cfg = runtime.config(ctx);
        String productId = Args.str(input, "productId");
        Optional<Product> found = runtime.lookup(ctx, cfg).find(new Ids.ProductId(productId));

        if (found.isEmpty()) {
            return Map.of("productId", productId == null ? "" : productId, "found", false);
        }
        Product product = found.get();
        runtime.emit(ctx, CommercePayloads.PRODUCT_CARD, payloads.productCard(product));
        return Map.of("productId", product.id().value(), "title", product.title(), "found", true);
    }
}
