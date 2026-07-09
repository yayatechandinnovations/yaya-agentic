package com.yayatechandinnovations.yayasales.orders.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.Ref;
import com.yayatechandinnovations.yayasales.inventory.ReservationKind;
import com.yayatechandinnovations.yayasales.inventory.ReserveResult;
import com.yayatechandinnovations.yayasales.orders.CheckoutDelivery;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.outbox.Outbox;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The aggregate's home: creation, FSM transitions with their event rows, and
 * the money-side reactions (paid / cancelled / refunded). The checkout SAGA
 * ({@link CheckoutSaga}) drives it; the {@link SettlementCoordinator} calls
 * {@link #onTenderSettled} when payments reports money arrived.
 */
@Service
public class DefaultOrderService implements OrderService {

    private final OrderRepository orders;
    private final OrderEventRepository events;
    private final TenderService tenders;
    private final StoreCreditService storeCredit;
    private final InventoryLedger ledger;
    private final StoreDirectory stores;
    private final Outbox outbox;
    private final ObjectMapper json;
    // Provider, not a direct dependency: the port is implemented by fulfillment,
    // which itself consumes orders — direct wiring would knot the bean graph.
    private final ObjectProvider<CheckoutDelivery> delivery;

    public DefaultOrderService(OrderRepository orders, OrderEventRepository events,
                               TenderService tenders, StoreCreditService storeCredit,
                               InventoryLedger ledger, StoreDirectory stores,
                               Outbox outbox, ObjectMapper json,
                               ObjectProvider<CheckoutDelivery> delivery) {
        this.orders = orders;
        this.events = events;
        this.tenders = tenders;
        this.storeCredit = storeCredit;
        this.ledger = ledger;
        this.stores = stores;
        this.outbox = outbox;
        this.json = json;
        this.delivery = delivery;
    }

    // ── stored shapes ─────────────────────────────────────────────────────────

    record StoredLine(String sku, String title, long qty, long unitPriceMinor,
                      List<String> reservationIds) {}

    record PricingSnapshot(long subtotalMinor, long discountMinor, long taxMinor,
                           boolean taxInclusive, long shippingMinor, long grandMinor) {

        static PricingSnapshot of(PricedQuote quote) {
            return new PricingSnapshot(quote.subtotal().amountMinor(),
                    quote.discountTotal().amountMinor(), quote.taxTotal().amountMinor(),
                    quote.taxInclusive(), quote.shippingTotal().amountMinor(),
                    quote.grandTotal().amountMinor());
        }
    }

    /** The §8.2 delivery snapshot: chosen method + promised window + slot + validated ship-to. */
    record StoredDelivery(String methodId, String kind, String label, long priceMinor,
                          Instant promisedFrom, Instant promisedTo, String carrierId,
                          String slotId, Address shipTo) {}

    // ── creation (called by the saga, its own tx) ────────────────────────────

    @Transactional
    public OrderView createFromCheckout(StoreId store, ChannelId channel, RegionId region,
                                        String customerRef, String currency,
                                        List<StoredLine> lines, PricedQuote quote,
                                        StoredDelivery delivery) {
        OrderEntity order = new OrderEntity(Ids.newId(), store.value(), channel.value(),
                region.value(), customerRef, currency, OrderView.Source.CHECKOUT.name(),
                write(lines), write(PricingSnapshot.of(quote)));
        if (delivery != null) {
            order.setDeliveryJson(write(delivery));
        }
        orders.save(order);
        events.save(new OrderEventEntity(order.getId(), "order.created", "checkout",
                null, OrderStatus.PENDING_PAYMENT.name(), null));
        outbox.append(store, "order", order.getId(), "order.created",
                "{\"orderId\":\"" + order.getId() + "\"}");
        return toView(order);
    }

    // ── draft orders (§8.1 — assembled cold, made real by send) ─────────────

    @Transactional
    public OrderView createDraft(StoreId store, ChannelId channel, RegionId region,
                                 String customerRef, String currency, List<StoredLine> lines,
                                 PricedQuote quote, String actor, String eventData) {
        OrderEntity order = OrderEntity.draft(Ids.newId(), store.value(), channel.value(),
                region.value(), customerRef, currency, write(lines),
                write(PricingSnapshot.of(quote)));
        orders.save(order);
        events.save(new OrderEventEntity(order.getId(), "draft_order.created", actor,
                null, OrderStatus.DRAFT.name(), eventData));
        return toView(order);
    }

    @Transactional
    public OrderView updateDraft(OrderId id, List<StoredLine> lines, PricedQuote quote,
                                 String actor, String eventData) {
        OrderEntity order = locked(id);
        requireDraft(order);
        order.setLinesJson(write(lines));
        order.setPricingJson(write(PricingSnapshot.of(quote)));
        events.save(new OrderEventEntity(order.getId(), "draft_order.updated", actor,
                OrderStatus.DRAFT.name(), OrderStatus.DRAFT.name(), eventData));
        return toView(order);
    }

    /**
     * §8.1 — the moment a draft touches reality: FIRM holds with the checkout
     * TTL, the fresh quote snapshotted, DRAFT → PENDING_PAYMENT. An
     * insufficient line throws {@link DraftStockInsufficientException} and the
     * ROLLBACK undoes the sibling holds too — the draft stays intact, nothing
     * is held.
     */
    @Transactional
    public OrderView prepareDraftSend(OrderId id, LocationId location, Duration firmTtl,
                                      PricedQuote requote, String customerRef, String actor) {
        OrderEntity order = locked(id);
        requireDraft(order);
        if (customerRef != null) {
            order.setCustomerRef(customerRef);
        }

        List<StoredLine> reserved = new ArrayList<>();
        for (StoredLine line : readLines(order)) {
            ReserveResult result = ledger.reserve(new VariantId(line.sku()), location,
                    line.qty(), ReservationKind.FIRM, Ref.order(order.getId()), firmTtl);
            if (result instanceof ReserveResult.Insufficient insufficient) {
                throw new DraftStockInsufficientException(
                        new VariantId(line.sku()), insufficient.available());
            }
            reserved.add(new StoredLine(line.sku(), line.title(), line.qty(),
                    line.unitPriceMinor(), List.of(((ReserveResult.Held) result).id().value())));
        }

        order.setLinesJson(write(reserved));
        order.setPricingJson(write(PricingSnapshot.of(requote)));
        order.transition(OrderStatus.PENDING_PAYMENT);
        events.save(new OrderEventEntity(order.getId(), "draft_order.sent", actor,
                OrderStatus.DRAFT.name(), OrderStatus.PENDING_PAYMENT.name(), null));
        outbox.append(new StoreId(order.getStoreId()), "order", order.getId(),
                "draft_order.sent", "{\"orderId\":\"" + order.getId()
                        + "\",\"grandTotalMinor\":" + requote.grandTotal().amountMinor()
                        + ",\"currency\":\"" + order.getCurrency() + "\"}");
        return toView(order);
    }

    private static void requireDraft(OrderEntity order) {
        if (order.status() != OrderStatus.DRAFT) {
            throw new ApiErrors.ConflictException(
                    "order " + order.getId() + " is " + order.getStatus() + " — not an editable draft");
        }
    }

    // ── reads ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderView> order(OrderId id) {
        return orders.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> ordersOf(StoreId store, Optional<OrderStatus> status) {
        List<OrderEntity> rows = status
                .map(s -> orders.findByStoreIdAndStatusOrderByCreatedAtDesc(store.value(), s.name()))
                .orElseGet(() -> orders.findByStoreIdOrderByCreatedAtDesc(store.value()));
        return rows.stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Event> eventsOf(OrderId id) {
        return events.findByOrderIdOrderByAtAscIdAsc(id.value()).stream()
                .map(e -> new Event(e.getType(), e.getActor(), e.getFromState(),
                        e.getToState(), e.getAt()))
                .toList();
    }

    // ── transitions ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public OrderView cancel(OrderId id, String actor) {
        OrderEntity order = locked(id);
        String from = order.getStatus();
        order.transition(OrderStatus.CANCELLED);

        // Compensations (§8.2): void what was begun, give credit back, free the stock.
        for (TenderView tender : tenders.tendersOf(id)) {
            if (tender.status() == TenderView.Status.PENDING) {
                tenders.voidTender(tender.id(), actor);
            } else if (tender.status() == TenderView.Status.SETTLED
                    && tender.kind() == TenderKind.STORE_CREDIT) {
                storeCredit.issue(new StoreId(order.getStoreId()), order.getCustomerRef(),
                        tender.amount(), "checkout cancelled", order.getId());
            }
        }
        releaseHolds(order);
        releaseSlot(order);

        events.save(new OrderEventEntity(order.getId(), "order.cancelled", actor,
                from, OrderStatus.CANCELLED.name(), null));
        outbox.append(new StoreId(order.getStoreId()), "order", order.getId(),
                "order.cancelled", "{\"orderId\":\"" + order.getId() + "\"}");
        return toView(order);
    }

    @Transactional
    public OrderView markAuthFailed(OrderId id, String reason) {
        OrderEntity order = locked(id);
        String from = order.getStatus();
        order.transition(OrderStatus.AUTH_FAILED);
        events.save(new OrderEventEntity(order.getId(), "payment.auth_failed", "psp",
                from, OrderStatus.AUTH_FAILED.name(),
                "{\"reason\":" + quote(reason) + "}"));
        return toView(order);
    }

    /** Settlement reaction: PAID once the settled sum covers the grand total (§8.2 steps 6–8). */
    @Transactional
    public void onTenderSettled(OrderId id) {
        OrderEntity order = locked(id);
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            return;   // late/duplicate settle of an already-paid or cancelled order
        }
        PricingSnapshot pricing = readPricing(order);
        long settled = tenders.settledTotal(id, order.getCurrency()).amountMinor();
        if (settled < pricing.grandMinor()) {
            order.setPaymentState("PARTIALLY_SETTLED");
            return;
        }

        order.transition(OrderStatus.PAID);
        order.setPaymentState("PAID");

        // Deduction point (§5.3, decision 2): ON_PAYMENT commits the holds into
        // SALE movements now; ON_FULFILLMENT committed them at dispatch (§8.6) —
        // a COD order ships before it settles.
        StorePolicies policies = stores.policiesOf(new StoreId(order.getStoreId()));
        if (policies.deductionPoint() == StorePolicies.DeductionPoint.ON_PAYMENT) {
            commitHolds(order);
        }

        events.save(new OrderEventEntity(order.getId(), "order.paid", "payments",
                OrderStatus.PENDING_PAYMENT.name(), OrderStatus.PAID.name(), null));
        outbox.append(new StoreId(order.getStoreId()), "order", order.getId(), "order.paid",
                "{\"orderId\":\"" + order.getId() + "\",\"grandTotalMinor\":" + pricing.grandMinor()
                        + ",\"currency\":\"" + order.getCurrency() + "\"}");

        // COD catch-up: the goods may already be out (or delivered) — the FSM
        // could not leave PENDING_PAYMENT until the money settled.
        statusFollowsFulfillment(order, order.getFulfillmentState());
    }

    @Override
    @Transactional
    public OrderView updateFulfillment(OrderId id, FulfillmentState state, String actor) {
        OrderEntity order = locked(id);
        String from = order.getFulfillmentState();
        order.setFulfillmentState(state.name());
        statusFollowsFulfillment(order, state.name());
        events.save(new OrderEventEntity(order.getId(), "order.fulfillment", actor,
                from, state.name(), null));
        return toView(order);
    }

    /** The order FSM follows the fulfillment sub-state where it legally can (§8.1/§8.6). */
    private void statusFollowsFulfillment(OrderEntity order, String fulfillmentState) {
        OrderStatus target = switch (fulfillmentState) {
            case "FULFILLING" -> OrderStatus.FULFILLING;
            case "PARTIALLY_FULFILLED" -> OrderStatus.PARTIALLY_FULFILLED;
            case "FULFILLED" -> OrderStatus.FULFILLED;
            default -> null;
        };
        if (target != null && order.status() != target && order.status().canTransition(target)) {
            order.transition(target);
        }
    }

    @Override
    @Transactional
    public OrderView refund(OrderId id, Money amount, boolean toStoreCredit, String actor) {
        OrderEntity order = locked(id);
        String currency = order.getCurrency();
        long settled = tenders.settledTotal(id, currency).amountMinor();
        long refundedBefore = tenders.refundedTotal(id, currency).amountMinor();
        Money effective = amount != null ? amount
                : new Money(settled - refundedBefore, currency);

        tenders.refund(new StoreId(order.getStoreId()), id, effective, toStoreCredit,
                order.getCustomerRef(), actor);

        long refundedAfter = refundedBefore + effective.amountMinor();
        String from = order.getStatus();
        OrderStatus target = refundedAfter >= settled
                ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
        if (order.status() != target) {
            order.transition(target);
        }
        order.setPaymentState(target.name());

        events.save(new OrderEventEntity(order.getId(), "payment.refunded", actor,
                from, target.name(),
                "{\"amountMinor\":" + effective.amountMinor() + ",\"toStoreCredit\":" + toStoreCredit + "}"));
        outbox.append(new StoreId(order.getStoreId()), "order", order.getId(), "payment.refunded",
                "{\"orderId\":\"" + order.getId() + "\",\"amountMinor\":" + effective.amountMinor() + "}");
        return toView(order);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void commitHolds(OrderEntity order) {
        for (StoredLine line : readLines(order)) {
            for (String reservationId : line.reservationIds()) {
                ledger.commit(new ReservationId(reservationId));
            }
        }
    }

    private void releaseHolds(OrderEntity order) {
        for (StoredLine line : readLines(order)) {
            for (String reservationId : line.reservationIds()) {
                ledger.release(new ReservationId(reservationId));
            }
        }
    }

    /** §8.2 comp: a cancelled order gives its delivery slot's capacity back. Idempotent. */
    private void releaseSlot(OrderEntity order) {
        StoredDelivery stored = readDelivery(order);
        if (stored == null || stored.slotId() == null) {
            return;
        }
        CheckoutDelivery port = delivery.getIfAvailable();
        if (port != null) {
            port.releaseSlot(new SlotId(stored.slotId()), new OrderId(order.getId()));
        }
    }

    private OrderEntity locked(OrderId id) {
        return orders.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", id.value()));
    }

    OrderView toView(OrderEntity order) {
        PricingSnapshot pricing = readPricing(order);
        String currency = order.getCurrency();
        List<OrderView.Line> lines = readLines(order).stream()
                .map(l -> new OrderView.Line(new VariantId(l.sku()), l.title(), l.qty(),
                        new Money(l.unitPriceMinor(), currency), l.reservationIds()))
                .toList();
        StoredDelivery storedDelivery = readDelivery(order);
        OrderView.Delivery deliveryView = storedDelivery == null ? null
                : new OrderView.Delivery(new DeliveryMethodId(storedDelivery.methodId()),
                        storedDelivery.kind(), storedDelivery.label(),
                        new Money(storedDelivery.priceMinor(), currency),
                        storedDelivery.promisedFrom(), storedDelivery.promisedTo(),
                        storedDelivery.carrierId() == null ? null : new CarrierId(storedDelivery.carrierId()),
                        storedDelivery.slotId() == null ? null : new SlotId(storedDelivery.slotId()),
                        storedDelivery.shipTo());
        return new OrderView(new OrderId(order.getId()), new StoreId(order.getStoreId()),
                new ChannelId(order.getChannelId()), new RegionId(order.getRegionId()),
                order.getCustomerRef(), currency,
                OrderView.Source.valueOf(order.getSource()), order.status(),
                OrderView.PaymentState.valueOf(order.getPaymentState()),
                order.getFulfillmentState(), lines,
                new OrderView.Totals(new Money(pricing.subtotalMinor(), currency),
                        new Money(pricing.discountMinor(), currency),
                        new Money(pricing.taxMinor(), currency), pricing.taxInclusive(),
                        new Money(pricing.shippingMinor(), currency),
                        new Money(pricing.grandMinor(), currency)),
                deliveryView, order.getCreatedAt());
    }

    private List<StoredLine> readLines(OrderEntity order) {
        try {
            return json.readValue(order.getLinesJson(), new TypeReference<List<StoredLine>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt order lines_json", e);
        }
    }

    private PricingSnapshot readPricing(OrderEntity order) {
        try {
            return json.readValue(order.getPricingJson(), PricingSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt order pricing_json", e);
        }
    }

    private StoredDelivery readDelivery(OrderEntity order) {
        if (order.getDeliveryJson() == null) {
            return null;
        }
        try {
            return json.readValue(order.getDeliveryJson(), StoredDelivery.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt order delivery_json", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable order field", e);
        }
    }

    private String quote(String raw) {
        try {
            return json.writeValueAsString(raw == null ? "" : raw);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
