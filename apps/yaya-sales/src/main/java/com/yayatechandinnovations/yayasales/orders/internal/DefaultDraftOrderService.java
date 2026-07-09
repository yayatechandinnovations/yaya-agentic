package com.yayatechandinnovations.yayasales.orders.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.catalog.ProductView;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.DraftOrderService;
import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.idempotency.IdempotencyStore;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §8.1 — the draft-order orchestrator. Like the checkout saga, {@code send} is
 * deliberately NOT one transaction: the PSP call must never run inside a DB
 * transaction (§5.7). The stock-touching prepare is its own transaction (a
 * losing line rolls the whole reservation set back), the tender step reuses
 * the saga's {@link TenderStep}, and a tender failure compensates exactly like
 * checkout — no orphan reservation, no phantom pending order.
 */
@Service
class DefaultDraftOrderService implements DraftOrderService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDraftOrderService.class);

    private final DefaultOrderService orders;
    private final CatalogService catalog;
    private final SellingContextService sellingContext;
    private final StoreDirectory stores;
    private final PricingEngine pricing;
    private final TenderStep tenderStep;
    private final CheckoutOutcomes outcomes;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;

    DefaultDraftOrderService(DefaultOrderService orders, CatalogService catalog,
                             SellingContextService sellingContext, StoreDirectory stores,
                             PricingEngine pricing, TenderStep tenderStep,
                             CheckoutOutcomes outcomes, IdempotencyStore idempotency,
                             ObjectMapper json) {
        this.orders = orders;
        this.catalog = catalog;
        this.sellingContext = sellingContext;
        this.stores = stores;
        this.pricing = pricing;
        this.tenderStep = tenderStep;
        this.outcomes = outcomes;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public OrderView create(StoreId store, DraftSpec spec, String actor) {
        requireLines(spec.lines());
        SellingContext ctx = sellingContext.resolve(store,
                spec.channel() == null ? ChannelView.Kind.WEB_CHAT : spec.channel(),
                Optional.ofNullable(spec.region()));
        Priced priced = price(store, ctx.region(), ctx.channel().id().value(), spec.lines());
        return orders.createDraft(store, ctx.channel().id(), ctx.region().id(),
                spec.customerRef(), ctx.region().currency(), priced.lines(), priced.quote(),
                actor, eventData(spec.note(), priced.overrides()));
    }

    @Override
    public OrderView update(OrderId id, List<DraftLine> lines, String actor) {
        requireLines(lines);
        OrderView existing = existing(id);
        RegionView region = regionOf(existing);
        Priced priced = price(existing.store(), region, existing.channel().value(), lines);
        return orders.updateDraft(id, priced.lines(), priced.quote(), actor,
                eventData(null, priced.overrides()));
    }

    @Override
    public CheckoutOutcome send(OrderId id, SendCommand command, String actor) {
        OrderView existing = existing(id);
        StoreId store = existing.store();

        // §5.5 — a retried send re-attaches to the same outcome, not a second hold set.
        String idemKey = command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                ? null : "draft-send:" + command.idempotencyKey();
        String requestHash = hash(id, command);
        if (idemKey != null) {
            var replay = idempotency.find(store, idemKey);
            if (replay.isPresent()) {
                if (!replay.get().matches(requestHash)) {
                    throw new ApiErrors.ConflictException(
                            "Idempotency-Key reused with a different draft-send request");
                }
                return outcomes.reconstruct(replay.get().responseJson());
            }
        }

        if (existing.status() != OrderStatus.DRAFT) {
            throw new ApiErrors.ConflictException(
                    "order " + id.value() + " is " + existing.status() + " — not a sendable draft");
        }
        TenderKind tender = command.tender() == null ? TenderKind.PSP : command.tender();
        RegionView region = regionOf(existing);
        if (!region.tenders().contains(tender)) {
            throw new ApiErrors.UnprocessableException(
                    "tender " + tender + " is not offered in region " + region.name());
        }
        String customerRef = command.customerRef() != null
                ? command.customerRef() : existing.customerRef();

        // Re-quote at send time — the region's CURRENT tax policy, the draft's
        // snapshotted unit prices (overrides included) as the list price input.
        PricedQuote requote = requote(existing, region);

        Duration firmTtl = Duration.ofSeconds(stores.policiesOf(store).checkoutHoldTtlSeconds());
        OrderView sent;
        try {
            sent = orders.prepareDraftSend(id, defaultLocation(store), firmTtl, requote,
                    command.customerRef(), actor);
        } catch (DraftStockInsufficientException e) {
            return new CheckoutOutcome.Insufficient(e.sku(), e.available());
        }

        try {
            CheckoutOutcome outcome = tenderStep.collect(store, sent.id(), tender,
                    requote.grandTotal(), command.applyStoreCredit(), command.returnUrl(),
                    customerRef);
            if (idemKey != null) {
                idempotency.put(store, idemKey, requestHash, outcomes.serialize(outcome));
            }
            return outcome;
        } catch (RuntimeException e) {
            // Same compensation as checkout (§8.2/§14): the holds release, no
            // phantom PENDING_PAYMENT order lingers.
            log.warn("draft-send compensation for order {}: {}", id.value(), e.getMessage());
            try {
                orders.markAuthFailed(id, e.getMessage());
                orders.cancel(id, "draft-send-compensation");
            } catch (RuntimeException comp) {
                log.error("draft-send compensation itself failed for order {}: {}",
                        id.value(), comp.getMessage());
            }
            throw e;
        }
    }

    // ── pricing (list price unless the approved override says otherwise) ─────

    private record Priced(List<DefaultOrderService.StoredLine> lines, PricedQuote quote,
                          List<Map<String, Object>> overrides) {}

    private Priced price(StoreId store, RegionView region, String channelId,
                         List<DraftLine> draftLines) {
        List<DefaultOrderService.StoredLine> lines = new ArrayList<>();
        List<Map<String, Object>> overrides = new ArrayList<>();
        for (DraftLine draft : draftLines) {
            if (draft.qty() < 1) {
                throw new ApiErrors.UnprocessableException("line qty must be >= 1");
            }
            Merchandise merch = resolveSellable(store, region, draft.sku());
            long unit = merch.listPriceMinor();
            if (draft.unitPriceMinor() != null) {
                if (draft.unitPriceMinor() < 0) {
                    throw new ApiErrors.UnprocessableException("price override must be >= 0");
                }
                unit = draft.unitPriceMinor();
                overrides.add(Map.of("sku", draft.sku().value(),
                        "listPriceMinor", merch.listPriceMinor(),
                        "unitPriceMinor", unit));
            }
            lines.add(new DefaultOrderService.StoredLine(draft.sku().value(), merch.title(),
                    draft.qty(), unit, List.of()));
        }
        return new Priced(lines, quoteOf(store, region, channelId, lines), overrides);
    }

    private PricedQuote requote(OrderView order, RegionView region) {
        List<DefaultOrderService.StoredLine> lines = order.lines().stream()
                .map(l -> new DefaultOrderService.StoredLine(l.sku().value(), l.title(),
                        l.qty(), l.unitPrice().amountMinor(), l.reservationIds()))
                .toList();
        return quoteOf(order.store(), region, order.channel().value(), lines);
    }

    private PricedQuote quoteOf(StoreId store, RegionView region, String channelId,
                                List<DefaultOrderService.StoredLine> lines) {
        String currency = region.currency();
        List<CartSnapshot.Line> snapshot = lines.stream()
                .map(l -> new CartSnapshot.Line(new VariantId(l.sku()), l.qty(),
                        new Money(l.unitPriceMinor(), currency), l.title()))
                .toList();
        return pricing.quote(new CartSnapshot(store, snapshot),
                new PricingContext(region.id(), new ChannelId(channelId),
                        currency, region.tax(), null, null, Money.zero(currency)));
    }

    private record Merchandise(String title, long listPriceMinor) {}

    private Merchandise resolveSellable(StoreId store, RegionView region, VariantId sku) {
        ProductView product = catalog.productOfVariant(sku)
                .filter(p -> p.store().equals(store))
                .orElseThrow(() -> new ApiErrors.NotFoundException("variant", sku.value()));
        if (product.status() != ProductStatus.ACTIVE) {
            throw new ApiErrors.UnprocessableException(
                    "product is not for sale: " + product.title());
        }
        ProductView.VariantView variant = product.variants().stream()
                .filter(v -> v.id().equals(sku)).findFirst().orElseThrow();
        if (!variant.price().currency().equals(region.currency())) {
            throw new ApiErrors.UnprocessableException("variant is priced in "
                    + variant.price().currency() + " but the region sells in "
                    + region.currency());
        }
        return new Merchandise(product.title(), variant.price().amountMinor());
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private OrderView existing(OrderId id) {
        return orders.order(id)
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", id.value()));
    }

    private RegionView regionOf(OrderView order) {
        return sellingContext.region(order.region())
                .orElseThrow(() -> new IllegalStateException("order region vanished"));
    }

    private LocationId defaultLocation(StoreId store) {
        return stores.locationsOf(store).stream()
                .filter(LocationView::sellable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("store has no sellable location"))
                .id();
    }

    private static void requireLines(List<DraftLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ApiErrors.UnprocessableException("a draft order needs at least one line");
        }
    }

    /** The one-off price is an auditable decision — it lives in the event log. */
    private String eventData(String note, List<Map<String, Object>> overrides) {
        if (note == null && overrides.isEmpty()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (note != null) {
            data.put("note", note);
        }
        if (!overrides.isEmpty()) {
            data.put("priceOverrides", overrides);
        }
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable draft event data", e);
        }
    }

    private static String hash(OrderId id, SendCommand command) {
        String material = id.value() + "|" + command.tender() + "|" + command.applyStoreCredit()
                + "|" + command.returnUrl() + "|" + command.customerRef();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
