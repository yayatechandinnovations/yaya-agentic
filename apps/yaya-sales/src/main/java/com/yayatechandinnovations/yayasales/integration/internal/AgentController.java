package com.yayatechandinnovations.yayasales.integration.internal;

import com.yayatechandinnovations.yayasales.cart.CartResult;
import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.catalog.ProductView;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.platform.Ids.CartId;
import com.yayatechandinnovations.yayasales.platform.Ids.ProductId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
import com.yayatechandinnovations.yayasales.stores.ChannelView;
import com.yayatechandinnovations.yayasales.stores.RegionView;
import com.yayatechandinnovations.yayasales.stores.SellingContextService;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The agent-facing surface (design §10.2) — what yaya-agentic's {@code sales.*}
 * HTTP tools call. Availability shown is live {@code available()} (§7.2);
 * every response carries a {@code _uiHint} frame so the chat renders chrome,
 * not prose. S2 ships browse + cart + tenders; checkout arrives with S3.
 */
@RestController
@RequestMapping("/v1/sales/agent")
public class AgentController {

    private final CatalogService catalog;
    private final CartService carts;
    private final InventoryAdmin inventory;
    private final SellingContextService sellingContext;

    public AgentController(CatalogService catalog, CartService carts,
                           InventoryAdmin inventory, SellingContextService sellingContext) {
        this.catalog = catalog;
        this.carts = carts;
        this.inventory = inventory;
        this.sellingContext = sellingContext;
    }

    // ── browse ───────────────────────────────────────────────────────────────

    @PostMapping("/products/search")
    public Map<String, Object> search(HttpServletRequest request, @RequestBody(required = false) Search body) {
        StoreView store = store(request);
        String query = body == null || body.query() == null
                ? "" : body.query().toLowerCase(Locale.ROOT).trim();

        List<Map<String, Object>> results = catalog.productsOfStore(store.id()).stream()
                .filter(p -> p.status() == ProductStatus.ACTIVE)
                .filter(p -> query.isEmpty()
                        || p.title().toLowerCase(Locale.ROOT).contains(query)
                        || (p.description() != null
                            && p.description().toLowerCase(Locale.ROOT).contains(query))
                        || (p.category() != null
                            && p.category().toLowerCase(Locale.ROOT).contains(query)))
                .limit(body == null || body.limit() == null ? 12 : Math.min(body.limit(), 50))
                .map(p -> productPayload(store, p))
                .toList();

        return UiHints.framed(Map.of("results", results, "count", results.size()),
                "product_catalog", Map.of("products", results));
    }

    @GetMapping("/products/{id}")
    public Map<String, Object> product(HttpServletRequest request, @PathVariable String id) {
        StoreView store = store(request);
        ProductView product = catalog.product(new ProductId(id))
                .filter(p -> p.store().equals(store.id()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("product", id));
        Map<String, Object> payload = productPayload(store, product);
        return UiHints.framed(Map.of("product", payload), "product_card", payload);
    }

    // ── cart (§10.2) ─────────────────────────────────────────────────────────

    @PostMapping("/cart/add")
    public Map<String, Object> add(HttpServletRequest request, @Valid @RequestBody CartAdd body) {
        StoreView store = store(request);
        CartId cartId = body.cartHandle() == null || body.cartHandle().isBlank()
                ? carts.open(store.id(), channelKind(body.channel()),
                        Optional.ofNullable(body.regionId()).map(RegionId::new), principal(request)).id()
                : new CartId(body.cartHandle());

        CartResult result = carts.add(cartId, new VariantId(body.sku()),
                body.qty() == null ? 1 : body.qty());
        return cartResponse(result, cartId);
    }

    @PostMapping("/cart/set-qty")
    public Map<String, Object> setQty(HttpServletRequest request, @Valid @RequestBody CartSetQty body) {
        store(request);
        CartResult result = carts.setQty(new CartId(body.cartHandle()),
                new VariantId(body.sku()), body.qty());
        return cartResponse(result, new CartId(body.cartHandle()));
    }

    @PostMapping("/cart/remove")
    public Map<String, Object> remove(HttpServletRequest request, @Valid @RequestBody CartLineRef body) {
        store(request);
        CartView cart = carts.remove(new CartId(body.cartHandle()), new VariantId(body.sku()));
        return cartResponse(new CartResult.Ok(cart), cart.id());
    }

    @PostMapping("/cart/view")
    public Map<String, Object> view(HttpServletRequest request, @Valid @RequestBody CartRef body) {
        store(request);
        CartView cart = carts.cart(new CartId(body.cartHandle()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("cart", body.cartHandle()));
        return cartResponse(new CartResult.Ok(cart), cart.id());
    }

    @PostMapping("/cart/clear")
    public Map<String, Object> clear(HttpServletRequest request, @Valid @RequestBody CartRef body) {
        store(request);
        CartView cart = carts.clear(new CartId(body.cartHandle()));
        return cartResponse(new CartResult.Ok(cart), cart.id());
    }

    // ── tenders (§6.1: which means of payment this context offers) ──────────

    @GetMapping("/tenders")
    public Map<String, Object> tenders(HttpServletRequest request, @RequestParam String cartHandle) {
        store(request);
        CartView cart = carts.cart(new CartId(cartHandle))
                .orElseThrow(() -> new ApiErrors.NotFoundException("cart", cartHandle));
        RegionView region = sellingContext.region(cart.region())
                .orElseThrow(() -> new IllegalStateException("cart region vanished"));
        List<String> tenders = region.tenders().stream().map(Enum::name).toList();
        return UiHints.framed(Map.of("tenders", tenders, "currency", region.currency()),
                "tender_options", Map.of("tenders", tenders, "currency", region.currency()));
    }

    // ── payload builders ─────────────────────────────────────────────────────

    private Map<String, Object> productPayload(StoreView store, ProductView product) {
        List<Map<String, Object>> variants = product.variants().stream()
                .map(v -> {
                    Map<String, Object> variant = new LinkedHashMap<>();
                    variant.put("id", v.id().value());
                    variant.put("sku", v.sku());
                    variant.put("label", v.label() == null ? "" : v.label());
                    variant.put("priceMinor", v.price().amountMinor());
                    variant.put("currency", v.price().currency());
                    variant.put("available", inventory.availableForSale(store.id(), v.id()));
                    return variant;
                })
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", product.id().value());
        payload.put("title", product.title());
        payload.put("description", product.description() == null ? "" : product.description());
        payload.put("category", product.category() == null ? "" : product.category());
        payload.put("media", product.media());
        payload.put("variants", variants);
        return payload;
    }

    private Map<String, Object> cartResponse(CartResult result, CartId cartId) {
        return switch (result) {
            case CartResult.Ok ok -> {
                PricedQuote quote = carts.quote(ok.cart().id(), null);
                Map<String, Object> cart = cartPayload(ok.cart(), quote);
                yield UiHints.framed(Map.of("cart", cart), "cart", cart);
            }
            case CartResult.Insufficient insufficient -> {
                PricedQuote quote = carts.quote(cartId, null);
                Map<String, Object> cart = cartPayload(insufficient.cart(), quote);
                yield UiHints.framed(Map.of(
                        "cart", cart,
                        "insufficient", Map.of("sku", insufficient.sku().value(),
                                "available", insufficient.available())),
                        "cart", cart);
            }
        };
    }

    private Map<String, Object> cartPayload(CartView cart, PricedQuote quote) {
        List<Map<String, Object>> lines = cart.lines().stream()
                .map(l -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("sku", l.sku().value());
                    line.put("title", l.title());
                    line.put("label", l.variantLabel() == null ? "" : l.variantLabel());
                    line.put("qty", l.qty());
                    line.put("unitPriceMinor", l.unitPrice().amountMinor());
                    return line;
                })
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cartHandle", cart.id().value());
        payload.put("currency", cart.currency());
        payload.put("lines", lines);
        payload.put("subtotalMinor", quote.subtotal().amountMinor());
        payload.put("discountMinor", quote.discountTotal().amountMinor());
        payload.put("taxMinor", quote.taxTotal().amountMinor());
        payload.put("taxInclusive", quote.taxInclusive());
        payload.put("grandTotalMinor", quote.grandTotal().amountMinor());
        return payload;
    }

    private static StoreView store(HttpServletRequest request) {
        return (StoreView) request.getAttribute(AgentApiGuard.STORE_ATTRIBUTE);
    }

    /** The verified shopper subject when PRINCIPAL_TOKEN was forwarded; null while anonymous. */
    private static String principal(HttpServletRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth instanceof org.springframework.security.oauth2.server.resource.authentication
                .JwtAuthenticationToken jwt) {
            return jwt.getToken().getSubject();
        }
        return null;
    }

    private static ChannelView.Kind channelKind(String raw) {
        if (raw == null || raw.isBlank()) return ChannelView.Kind.WEB_CHAT;
        try {
            return ChannelView.Kind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown channel: " + raw);
        }
    }

    public record Search(String query, Integer limit) {}
    public record CartAdd(String cartHandle, @NotBlank String sku, Long qty,
                          String channel, String regionId) {}
    public record CartSetQty(@NotBlank String cartHandle, @NotBlank String sku, long qty) {}
    public record CartLineRef(@NotBlank String cartHandle, @NotBlank String sku) {}
    public record CartRef(@NotBlank String cartHandle) {}
}
