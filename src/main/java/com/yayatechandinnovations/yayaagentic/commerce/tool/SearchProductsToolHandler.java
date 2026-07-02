package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.search.ProductQuery;
import com.yayatechandinnovations.yayaagentic.commerce.search.ProductSearchResult;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code search_products} — searches the profile's catalogs and paints a
 * {@code product_catalog} UiHint (items + facets). The LLM gets only a compact
 * count back, so it captions ("Here's our body-care range — tap any to see
 * more") rather than narrating products or prices (#1, #8). See §8, §10.2.
 */
@Component(CommerceTools.SEARCH_PRODUCTS_BEAN)
public class SearchProductsToolHandler implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    private final CommerceRuntime runtime;
    private final CommercePayloads payloads;

    public SearchProductsToolHandler(CommerceRuntime runtime, CommercePayloads payloads) {
        this.runtime = runtime;
        this.payloads = payloads;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        CommerceConfig cfg = runtime.config(ctx);
        ProductQuery query = new ProductQuery(
                Args.str(input, "query"),
                Args.str(input, "category"),
                Args.strMap(input, "attributes"),
                Args.longOrNull(input, "priceMax"),
                Args.intOr(input, "page", 0),
                20);

        ProductSearchResult result = runtime.search().search(
                ctx.principal().tenant(), cfg.catalogs(), ctx.principal(), runtime.authz(ctx), query);

        runtime.emit(ctx, CommercePayloads.PRODUCT_CATALOG, payloads.catalog(result));

        return Map.of(
                "count", result.total(),
                "shown", result.items().size(),
                "hasMore", result.hasMore(),
                "echo", result.echo() == null ? "" : result.echo());
    }
}
