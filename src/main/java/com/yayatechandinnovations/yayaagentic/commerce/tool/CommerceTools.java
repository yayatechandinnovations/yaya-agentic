package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;

import java.time.Duration;
import java.util.List;

/**
 * Tool ids, bean names, JSON Schemas, and {@link ToolDescriptor} factories for
 * the managed (Bean) commerce tools — the fallback transport for merchants with
 * only a product feed (§8). The default transport remains merchant HTTP tools;
 * these prove the render + price-integrity spine with no external dependency.
 *
 * <p>Whole-tool {@code requires} is {@code none()}: fine-grained gating happens
 * per catalog inside {@code ProductSearchService} (the same split
 * {@code search_knowledge} uses). No price ever appears in an input schema —
 * that is what keeps constraint #1 enforceable.</p>
 */
public final class CommerceTools {

    private CommerceTools() {}

    public static final String SEARCH_PRODUCTS = "search_products";
    public static final String GET_PRODUCT = "get_product";
    public static final String ADD_TO_CART = "add_to_cart";
    public static final String REMOVE_FROM_CART = "remove_from_cart";
    public static final String SET_CART_QUANTITY = "set_cart_quantity";
    public static final String VIEW_CART = "view_cart";
    public static final String CLEAR_CART = "clear_cart";
    public static final String CHECKOUT = "checkout";

    // Bean names (Spring component names) for the ToolHandler dispatch.
    public static final String SEARCH_PRODUCTS_BEAN = "searchProductsTool";
    public static final String GET_PRODUCT_BEAN = "getProductTool";
    public static final String ADD_TO_CART_BEAN = "addToCartTool";
    public static final String REMOVE_FROM_CART_BEAN = "removeFromCartTool";
    public static final String SET_CART_QUANTITY_BEAN = "setCartQuantityTool";
    public static final String VIEW_CART_BEAN = "viewCartTool";
    public static final String CLEAR_CART_BEAN = "clearCartTool";
    public static final String CHECKOUT_BEAN = "checkoutTool";

    private static final String EMPTY_INPUT = "{\"type\":\"object\",\"properties\":{}}";
    private static final String OBJECT_OUTPUT = "{\"type\":\"object\"}";

    private static final String SEARCH_INPUT = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Natural-language product search."},
              "category":{"type":"string"},
              "attributes":{"type":"object","additionalProperties":{"type":"string"}},
              "priceMax":{"type":"integer","description":"Max price in minor units (cents)."},
              "page":{"type":"integer","minimum":0}}}""";

    private static final String GET_PRODUCT_INPUT = """
            {"type":"object","required":["productId"],
             "properties":{"productId":{"type":"string"}}}""";

    private static final String ADD_TO_CART_INPUT = """
            {"type":"object","required":["productId","qty"],"properties":{
              "productId":{"type":"string"},
              "variantId":{"type":"string"},
              "qty":{"type":"integer","minimum":1}}}""";

    private static final String LINE_QTY_INPUT = """
            {"type":"object","required":["lineId","qty"],"properties":{
              "lineId":{"type":"string"},"qty":{"type":"integer","minimum":0}}}""";

    private static final String LINE_INPUT = """
            {"type":"object","required":["lineId"],
             "properties":{"lineId":{"type":"string"}}}""";

    private static final String CHECKOUT_INPUT = """
            {"type":"object","properties":{
              "note":{"type":"string"},
              "fulfilment":{"type":"object"}}}""";

    /** The full set of managed commerce descriptors, for bootstrap/admin wiring. */
    public static List<ToolDescriptor> managedDescriptors() {
        return List.of(
                bean(SEARCH_PRODUCTS, SEARCH_INPUT, SEARCH_PRODUCTS_BEAN, readOnly()),
                bean(GET_PRODUCT, GET_PRODUCT_INPUT, GET_PRODUCT_BEAN, readOnly()),
                bean(ADD_TO_CART, ADD_TO_CART_INPUT, ADD_TO_CART_BEAN, mutating()),
                bean(REMOVE_FROM_CART, LINE_INPUT, REMOVE_FROM_CART_BEAN, mutating()),
                bean(SET_CART_QUANTITY, LINE_QTY_INPUT, SET_CART_QUANTITY_BEAN, mutating()),
                bean(VIEW_CART, EMPTY_INPUT, VIEW_CART_BEAN, readOnly()),
                bean(CLEAR_CART, EMPTY_INPUT, CLEAR_CART_BEAN, mutating()),
                bean(CHECKOUT, CHECKOUT_INPUT, CHECKOUT_BEAN, checkout()));
    }

    // Checkout is the money-mover: confirmable (two-phase confirm before any PSP
    // hand-off) AND tappable (a "Checkout" tap starts the confirm flow). §9, §11.
    private static ToolPolicy checkout() {
        return new ToolPolicy(Duration.ofSeconds(15), 0, false, /* confirmable */ true, /* clientInvocable */ true);
    }

    // Every commerce tool is tappable (cart edits, load-more, tap-to-open) so it
    // reaches the client-initiated action channel — clientInvocable = true (§9).
    private static ToolPolicy readOnly() {
        return new ToolPolicy(Duration.ofSeconds(10), 0, true, false, true);
    }

    private static ToolPolicy mutating() {
        // Cart mutations are not idempotent (adding twice adds twice).
        return new ToolPolicy(Duration.ofSeconds(10), 0, false, false, true);
    }

    private static ToolDescriptor bean(String id, String inputSchema, String beanName, ToolPolicy policy) {
        return new ToolDescriptor(
                new Ids.ToolId(id),
                inputSchema,
                OBJECT_OUTPUT,
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean(beanName),
                policy);
    }
}
