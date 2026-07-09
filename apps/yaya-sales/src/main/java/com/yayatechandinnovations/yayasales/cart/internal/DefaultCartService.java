package com.yayatechandinnovations.yayasales.cart.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.cart.CartResult;
import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.catalog.ProductView;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.Ref;
import com.yayatechandinnovations.yayasales.inventory.ReservationKind;
import com.yayatechandinnovations.yayasales.inventory.ReserveResult;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.CartId;
import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.pricing.CartSnapshot;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
import com.yayatechandinnovations.yayasales.pricing.PricingContext;
import com.yayatechandinnovations.yayasales.pricing.PricingEngine;
import com.yayatechandinnovations.yayasales.stores.ChannelView;
import com.yayatechandinnovations.yayasales.stores.LocationView;
import com.yayatechandinnovations.yayasales.stores.RegionView;
import com.yayatechandinnovations.yayasales.stores.SellingContext;
import com.yayatechandinnovations.yayasales.stores.SellingContextService;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DefaultCartService implements CartService {

    private final CartRepository carts;
    private final CatalogService catalog;
    private final InventoryLedger ledger;
    private final PricingEngine pricing;
    private final StoreDirectory stores;
    private final SellingContextService sellingContext;
    private final ObjectMapper json;

    public DefaultCartService(CartRepository carts, CatalogService catalog, InventoryLedger ledger,
                              PricingEngine pricing, StoreDirectory stores,
                              SellingContextService sellingContext, ObjectMapper json) {
        this.carts = carts;
        this.catalog = catalog;
        this.ledger = ledger;
        this.pricing = pricing;
        this.stores = stores;
        this.sellingContext = sellingContext;
        this.json = json;
    }

    @Override
    @Transactional
    public CartView open(StoreId store, ChannelView.Kind channel, Optional<RegionId> regionHint,
                         String customerRef) {
        SellingContext ctx = sellingContext.resolve(store, channel, regionHint);
        CartEntity cart = new CartEntity(Ids.newId(), store.value(),
                ctx.channel().id().value(), ctx.region().id().value(),
                customerRef, ctx.region().currency(), "[]");
        carts.save(cart);
        return toView(cart);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CartView> cart(CartId id) {
        return carts.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional
    public CartResult add(CartId id, VariantId sku, long qty) {
        if (qty < 1) throw new IllegalArgumentException("qty must be >= 1");
        CartEntity cart = require(id);
        Merchandise merch = resolveSellable(cart, sku);

        // Grow-by-delta: the existing holds stand; only the increment is at risk.
        ReserveResult held = ledger.reserve(sku, defaultLocation(cart), qty,
                ReservationKind.SOFT, Ref.cart(cart.getId()), softTtl(cart));
        if (held instanceof ReserveResult.Insufficient insufficient) {
            return new CartResult.Insufficient(sku, insufficient.available(), toView(cart));
        }

        List<StoredLine> lines = readLines(cart);
        StoredLine existing = lines.stream()
                .filter(l -> l.sku().equals(sku.value())).findFirst().orElse(null);
        List<String> reservations = new ArrayList<>(
                existing == null ? List.of() : existing.reservationIds());
        reservations.add(((ReserveResult.Held) held).id().value());

        StoredLine updated = new StoredLine(sku.value(), merch.title(), merch.label(),
                (existing == null ? 0 : existing.qty()) + qty,
                merch.priceMinor(), reservations);
        lines.removeIf(l -> l.sku().equals(sku.value()));
        lines.add(updated);
        cart.setLinesJson(write(lines));
        return new CartResult.Ok(toView(cart));
    }

    @Override
    @Transactional
    public CartResult setQty(CartId id, VariantId sku, long qty) {
        if (qty < 0) throw new IllegalArgumentException("qty must be >= 0");
        CartEntity cart = require(id);
        List<StoredLine> lines = readLines(cart);
        StoredLine line = lines.stream()
                .filter(l -> l.sku().equals(sku.value())).findFirst()
                .orElseThrow(() -> new ApiErrors.NotFoundException("cart line", sku.value()));

        if (qty == 0) {
            return new CartResult.Ok(remove(id, sku));
        }
        if (qty > line.qty()) {
            long delta = qty - line.qty();
            ReserveResult held = ledger.reserve(sku, defaultLocation(cart), delta,
                    ReservationKind.SOFT, Ref.cart(cart.getId()), softTtl(cart));
            if (held instanceof ReserveResult.Insufficient insufficient) {
                return new CartResult.Insufficient(sku, insufficient.available(), toView(cart));
            }
            List<String> reservations = new ArrayList<>(line.reservationIds());
            reservations.add(((ReserveResult.Held) held).id().value());
            replaceLine(lines, line.withQty(qty).withReservations(reservations));
        } else if (qty < line.qty()) {
            // Shrinking always succeeds: release everything, re-hold the smaller qty.
            line.reservationIds().forEach(r -> ledger.release(new ReservationId(r)));
            ReserveResult held = ledger.reserve(sku, defaultLocation(cart), qty,
                    ReservationKind.SOFT, Ref.cart(cart.getId()), softTtl(cart));
            List<String> reservations = held instanceof ReserveResult.Held h
                    ? List.of(h.id().value()) : List.of();
            replaceLine(lines, line.withQty(qty).withReservations(reservations));
        }
        cart.setLinesJson(write(lines));
        return new CartResult.Ok(toView(cart));
    }

    @Override
    @Transactional
    public CartView remove(CartId id, VariantId sku) {
        CartEntity cart = require(id);
        List<StoredLine> lines = readLines(cart);
        lines.stream()
                .filter(l -> l.sku().equals(sku.value()))
                .flatMap(l -> l.reservationIds().stream())
                .forEach(r -> ledger.release(new ReservationId(r)));
        lines.removeIf(l -> l.sku().equals(sku.value()));
        cart.setLinesJson(write(lines));
        return toView(cart);
    }

    @Override
    @Transactional
    public CartView clear(CartId id) {
        CartEntity cart = require(id);
        readLines(cart).stream()
                .flatMap(l -> l.reservationIds().stream())
                .forEach(r -> ledger.release(new ReservationId(r)));
        cart.setLinesJson("[]");
        return toView(cart);
    }

    @Override
    @Transactional(readOnly = true)
    public PricedQuote quote(CartId id, String couponCode) {
        return quote(id, couponCode, null);
    }

    @Override
    @Transactional(readOnly = true)
    public PricedQuote quote(CartId id, String couponCode, Money shipping) {
        CartEntity cart = require(id);
        RegionView region = sellingContext.region(new RegionId(cart.getRegionId()))
                .orElseThrow(() -> new IllegalStateException("cart region vanished"));

        List<CartSnapshot.Line> lines = readLines(cart).stream()
                .map(l -> new CartSnapshot.Line(new VariantId(l.sku()), l.qty(),
                        new Money(l.unitPriceMinor(), cart.getCurrency()), l.title()))
                .toList();
        return pricing.quote(new CartSnapshot(new StoreId(cart.getStoreId()), lines),
                new PricingContext(new RegionId(cart.getRegionId()),
                        new ChannelId(cart.getChannelId()), cart.getCurrency(),
                        region.tax(), null, couponCode,
                        shipping == null ? Money.zero(cart.getCurrency()) : shipping));
    }

    @Override
    @Transactional
    public CartView merge(CartId from, CartId into) {
        CartEntity source = require(from);
        CartEntity target = require(into);
        if (!source.getStoreId().equals(target.getStoreId())) {
            throw new ApiErrors.UnprocessableException("carts belong to different stores");
        }
        for (StoredLine line : readLines(source)) {
            add(into, new VariantId(line.sku()), line.qty());   // sum quantities (module Q resolved: sum)
        }
        clear(from);
        return toView(require(into));
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutHandoff peek(CartId id) {
        CartEntity cart = require(id);
        List<CheckoutHandoff.HandoffLine> lines = readLines(cart).stream()
                .map(l -> new CheckoutHandoff.HandoffLine(new VariantId(l.sku()), l.title(),
                        l.qty(), l.unitPriceMinor(), List.copyOf(l.reservationIds())))
                .toList();
        return new CheckoutHandoff(toView(cart), lines);
    }

    @Override
    @Transactional
    public void handOff(CartId id) {
        require(id).setLinesJson("[]");   // holds intentionally NOT released — the order owns them
    }

    // ── internals ────────────────────────────────────────────────────────────

    private record Merchandise(String title, String label, long priceMinor) {}

    private Merchandise resolveSellable(CartEntity cart, VariantId sku) {
        ProductView product = catalog.productOfVariant(sku)
                .filter(p -> p.store().value().equals(cart.getStoreId()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("variant", sku.value()));
        if (product.status() != ProductStatus.ACTIVE) {
            throw new ApiErrors.UnprocessableException("product is not for sale: " + product.title());
        }
        ProductView.VariantView variant = product.variants().stream()
                .filter(v -> v.id().equals(sku)).findFirst().orElseThrow();
        if (!variant.price().currency().equals(cart.getCurrency())) {
            throw new ApiErrors.UnprocessableException("variant is priced in "
                    + variant.price().currency() + " but the cart's region sells in "
                    + cart.getCurrency());
        }
        return new Merchandise(product.title(), variant.label(), variant.price().amountMinor());
    }

    private LocationId defaultLocation(CartEntity cart) {
        return stores.locationsOf(new StoreId(cart.getStoreId())).stream()
                .filter(LocationView::sellable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("store has no sellable location"))
                .id();
    }

    private Duration softTtl(CartEntity cart) {
        return Duration.ofSeconds(stores.policiesOf(new StoreId(cart.getStoreId()))
                .softHoldTtlSeconds());
    }

    private CartEntity require(CartId id) {
        return carts.findById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("cart", id.value()));
    }

    private static void replaceLine(List<StoredLine> lines, StoredLine updated) {
        lines.removeIf(l -> l.sku().equals(updated.sku()));
        lines.add(updated);
    }

    private CartView toView(CartEntity cart) {
        List<CartView.Line> lines = readLines(cart).stream()
                .map(l -> new CartView.Line(new VariantId(l.sku()), l.title(), l.variantLabel(),
                        l.qty(), new Money(l.unitPriceMinor(), cart.getCurrency())))
                .toList();
        return new CartView(new CartId(cart.getId()), new StoreId(cart.getStoreId()),
                new ChannelId(cart.getChannelId()), new RegionId(cart.getRegionId()),
                cart.getCustomerRef(), cart.getCurrency(), lines, cart.getUpdatedAt());
    }

    record StoredLine(String sku, String title, String variantLabel, long qty,
                      long unitPriceMinor, List<String> reservationIds) {

        StoredLine withQty(long newQty) {
            return new StoredLine(sku, title, variantLabel, newQty, unitPriceMinor, reservationIds);
        }

        StoredLine withReservations(List<String> newReservations) {
            return new StoredLine(sku, title, variantLabel, qty, unitPriceMinor, newReservations);
        }
    }

    private List<StoredLine> readLines(CartEntity cart) {
        try {
            return new ArrayList<>(json.readValue(cart.getLinesJson(),
                    new TypeReference<List<StoredLine>>() {}));
        } catch (Exception e) {
            throw new IllegalStateException("corrupt cart lines_json", e);
        }
    }

    private String write(List<StoredLine> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable cart lines", e);
        }
    }
}
