package com.yayatechandinnovations.yayasales.integration.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * §11.1 / integration module §5 — the pull-based tool manifest: ready-to-import
 * {@code sales.*} HTTP tool descriptors in yaya-agentic's ToolDescriptor wire
 * shape. Path-only URLs (the tenant's host_base_url supplies the host);
 * SERVICE_TOKEN forwarding; the store id rides a header template. An operator
 * reviews and imports these — never auto-trusted.
 */
@RestController
@RequestMapping("/v1/sales/agent")
public class ManifestController {

    @GetMapping("/manifest")
    public Map<String, Object> manifest() {
        return Map.of("tools", List.of(
                tool("sales.search_products", "Search the store's catalog",
                        "POST", "/v1/sales/agent/products/search",
                        Map.of("query", Map.of("type", "string"),
                               "limit", Map.of("type", "integer")),
                        "{\"query\":\"{query}\",\"limit\":{limit}}", false),
                tool("sales.get_product", "Fetch one product with live availability",
                        "GET", "/v1/sales/agent/products/{productId}",
                        Map.of("productId", Map.of("type", "string")), null, false),
                tool("sales.cart_add", "Add a variant to the shopper's cart",
                        "POST", "/v1/sales/agent/cart/add",
                        Map.of("sku", Map.of("type", "string"),
                               "qty", Map.of("type", "integer")),
                        "{\"cartHandle\":\"{ctx.cartHandle}\",\"sku\":\"{sku}\",\"qty\":{qty}}", false),
                tool("sales.cart_set_qty", "Change a cart line's quantity",
                        "POST", "/v1/sales/agent/cart/set-qty",
                        Map.of("sku", Map.of("type", "string"),
                               "qty", Map.of("type", "integer")),
                        "{\"cartHandle\":\"{ctx.cartHandle}\",\"sku\":\"{sku}\",\"qty\":{qty}}", false),
                tool("sales.cart_remove", "Remove a line from the cart",
                        "POST", "/v1/sales/agent/cart/remove",
                        Map.of("sku", Map.of("type", "string")),
                        "{\"cartHandle\":\"{ctx.cartHandle}\",\"sku\":\"{sku}\"}", false),
                tool("sales.cart_view", "Show the cart with authoritative totals",
                        "POST", "/v1/sales/agent/cart/view",
                        Map.of(),
                        "{\"cartHandle\":\"{ctx.cartHandle}\"}", false),
                tool("sales.cart_clear", "Empty the cart",
                        "POST", "/v1/sales/agent/cart/clear",
                        Map.of(),
                        "{\"cartHandle\":\"{ctx.cartHandle}\"}", true),
                tool("sales.checkout", "Place the order and start payment (confirm first)",
                        "POST", "/v1/sales/agent/checkout",
                        Map.of("tender", Map.of("type", "string",
                                        "enum", List.of("PSP", "COD", "MANUAL", "STORE_CREDIT")),
                               "returnUrl", Map.of("type", "string")),
                        "{\"cartHandle\":\"{ctx.cartHandle}\",\"tender\":\"{tender}\","
                                + "\"returnUrl\":\"{returnUrl}\"}", true),
                tool("sales.get_order", "Check an order's status (post-purchase)",
                        "GET", "/v1/sales/agent/orders/{orderId}",
                        Map.of("orderId", Map.of("type", "string")), null, false),
                tool("sales.delivery_options",
                        "Quote the delivery methods, prices and promised windows for an address",
                        "POST", "/v1/sales/agent/delivery/options",
                        Map.of("address", Map.of("type", "object", "properties", Map.of(
                                "line1", Map.of("type", "string"),
                                "line2", Map.of("type", "string"),
                                "city", Map.of("type", "string"),
                                "region", Map.of("type", "string"),
                                "postalCode", Map.of("type", "string"),
                                "country", Map.of("type", "string")))),
                        "{\"cartHandle\":\"{ctx.cartHandle}\",\"address\":{address}}", false),
                tool("sales.track_delivery", "Track an order's delivery (\"where's my order?\")",
                        "GET", "/v1/sales/agent/orders/{orderId}/track",
                        Map.of("orderId", Map.of("type", "string")), null, false),
                tool("sales.create_draft_order",
                        "Assemble a draft order for the shopper (approved one-off prices allowed)",
                        "POST", "/v1/sales/agent/draft-orders",
                        Map.of("lines", Map.of("type", "array",
                                        "items", Map.of("type", "object", "properties", Map.of(
                                                "sku", Map.of("type", "string"),
                                                "qty", Map.of("type", "integer"),
                                                "unitPriceMinor", Map.of("type", "integer")))),
                               "note", Map.of("type", "string")),
                        "{\"lines\":{lines},\"note\":\"{note}\"}", false),
                tool("sales.send_draft_order",
                        "Send a draft order for payment — \"tap to pay\" (confirm first)",
                        "POST", "/v1/sales/agent/draft-orders/{draftOrderId}/send",
                        Map.of("draftOrderId", Map.of("type", "string"),
                               "tender", Map.of("type", "string",
                                        "enum", List.of("PSP", "COD", "MANUAL", "STORE_CREDIT")),
                               "returnUrl", Map.of("type", "string")),
                        "{\"tender\":\"{tender}\",\"returnUrl\":\"{returnUrl}\"}", true)));
    }

    private static Map<String, Object> tool(String id, String description, String method,
                                            String path, Map<String, Object> properties,
                                            String bodyTemplate, boolean confirmable) {
        Map<String, Object> http = new java.util.LinkedHashMap<>();
        http.put("method", method);
        http.put("urlTemplate", path);
        http.put("headerTemplates", Map.of(
                "X-Yaya-Store-Id", "{ctx.storeId}",
                "Idempotency-Key", "{ctx.turnId}"));
        if (bodyTemplate != null) {
            http.put("body", Map.of("contentType", "application/json", "template", bodyTemplate));
        }
        http.put("response", Map.of("jsonPath", "$"));
        http.put("authForwarding", "SERVICE_TOKEN");

        return Map.of(
                "id", id,
                "description", description,
                "inputSchema", Map.of("type", "object", "properties", properties),
                "handler", Map.of("http", http),
                "policy", Map.of("confirmable", confirmable));
    }
}
