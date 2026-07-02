package com.yayatechandinnovations.yayaagentic.commerce.cart;

import com.yayatechandinnovations.yayaagentic.commerce.Cart;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductLookup;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The managed (yaya-agentic-owned) cart — the optional fallback for merchants
 * with only a product feed and no cart API. Mutations re-derive every price from
 * the authoritative {@link ProductLookup}; the caller supplies only
 * {@code productId + qty}, never a price (non-negotiable #1). The merchant-owned
 * HTTP path is the default (§8); this Bean cart proves the {@code cart} UiHint +
 * price-integrity spine with no external dependency. See
 * {@code ecommerce-chat-design.md} §8, rollout phase 1.
 */
@Component
public class ManagedCart {

    private final CartStore store;

    public ManagedCart(CartStore store) {
        this.store = store;
    }

    public Cart view(Ids.SessionId session, String currency) {
        return store.load(session).orElseGet(() -> Cart.empty(currency, Instant.now()));
    }

    public Cart add(Ids.SessionId session, String currency,
                    Ids.ProductId productId, String variantId, int qty,
                    ProductLookup lookup) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");

        Product product = lookup.find(productId)
                .orElseThrow(() -> new IllegalArgumentException("unknown product: " + productId.value()));
        Money unitPrice = priceOf(product, variantId);
        if (!unitPrice.currency().equals(currency.toUpperCase())) {
            throw new IllegalArgumentException(
                    "product currency " + unitPrice.currency() + " != cart currency " + currency);
        }

        List<Cart.CartLine> lines = new ArrayList<>(view(session, currency).lines());
        String lineId = lineId(productId, variantId);
        int existingIdx = indexOf(lines, lineId);
        int newQty = existingIdx >= 0 ? lines.get(existingIdx).qty() + qty : qty;
        Cart.CartLine line = new Cart.CartLine(
                lineId, productId.value(), variantId, product.title(),
                firstImage(product), newQty, unitPrice, unitPrice.times(newQty));
        if (existingIdx >= 0) lines.set(existingIdx, line); else lines.add(line);

        return persist(session, currency, lines);
    }

    public Cart setQuantity(Ids.SessionId session, String currency, String lineId, int qty) {
        List<Cart.CartLine> lines = new ArrayList<>(view(session, currency).lines());
        int idx = indexOf(lines, lineId);
        if (idx < 0) throw new IllegalArgumentException("no such cart line: " + lineId);
        if (qty <= 0) {
            lines.remove(idx);
        } else {
            Cart.CartLine prev = lines.get(idx);
            lines.set(idx, new Cart.CartLine(prev.lineId(), prev.productId(), prev.variantId(),
                    prev.title(), prev.image(), qty, prev.unitPrice(), prev.unitPrice().times(qty)));
        }
        return persist(session, currency, lines);
    }

    public Cart remove(Ids.SessionId session, String currency, String lineId) {
        List<Cart.CartLine> lines = new ArrayList<>(view(session, currency).lines());
        lines.removeIf(l -> l.lineId().equals(lineId));
        return persist(session, currency, lines);
    }

    public Cart clear(Ids.SessionId session, String currency) {
        Cart empty = Cart.empty(currency, Instant.now());
        store.save(session, empty);
        return empty;
    }

    private Cart persist(Ids.SessionId session, String currency, List<Cart.CartLine> lines) {
        Money subtotal = lines.stream()
                .map(Cart.CartLine::lineTotal)
                .reduce(Money.zero(currency), Money::plus);
        Cart cart = new Cart(null, currency.toUpperCase(), List.copyOf(lines), subtotal, Instant.now());
        store.save(session, cart);
        return cart;
    }

    private static Money priceOf(Product product, String variantId) {
        if (variantId == null || variantId.isBlank()) return product.price();
        return product.variants().stream()
                .filter(v -> v.variantId().equals(variantId))
                .map(Product.Variant::price)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown variant " + variantId + " for product " + product.id().value()));
    }

    private static String firstImage(Product product) {
        return product.imageUrls().isEmpty() ? null : product.imageUrls().get(0);
    }

    private static String lineId(Ids.ProductId productId, String variantId) {
        return variantId == null || variantId.isBlank()
                ? productId.value()
                : productId.value() + ":" + variantId;
    }

    private static int indexOf(List<Cart.CartLine> lines, String lineId) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).lineId().equals(lineId)) return i;
        }
        return -1;
    }
}
