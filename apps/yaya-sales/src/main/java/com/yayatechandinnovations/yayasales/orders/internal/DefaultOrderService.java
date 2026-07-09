package com.yayatechandinnovations.yayasales.orders.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.outbox.Outbox;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public DefaultOrderService(OrderRepository orders, OrderEventRepository events,
                               TenderService tenders, StoreCreditService storeCredit,
                               InventoryLedger ledger, StoreDirectory stores,
                               Outbox outbox, ObjectMapper json) {
        this.orders = orders;
        this.events = events;
        this.tenders = tenders;
        this.storeCredit = storeCredit;
        this.ledger = ledger;
        this.stores = stores;
        this.outbox = outbox;
        this.json = json;
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

    // ── creation (called by the saga, its own tx) ────────────────────────────

    @Transactional
    public OrderView createFromCheckout(StoreId store, ChannelId channel, RegionId region,
                                        String customerRef, String currency,
                                        List<StoredLine> lines, PricedQuote quote) {
        OrderEntity order = new OrderEntity(Ids.newId(), store.value(), channel.value(),
                region.value(), customerRef, currency, OrderView.Source.CHECKOUT.name(),
                write(lines), write(PricingSnapshot.of(quote)));
        orders.save(order);
        events.save(new OrderEventEntity(order.getId(), "order.created", "checkout",
                null, OrderStatus.PENDING_PAYMENT.name(), null));
        outbox.append(store, "order", order.getId(), "order.created",
                "{\"orderId\":\"" + order.getId() + "\"}");
        return toView(order);
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
        // SALE movements now; ON_FULFILLMENT keeps them until dispatch (S5).
        StorePolicies policies = stores.policiesOf(new StoreId(order.getStoreId()));
        if (policies.deductionPoint() == StorePolicies.DeductionPoint.ON_PAYMENT) {
            commitHolds(order);
        }

        events.save(new OrderEventEntity(order.getId(), "order.paid", "payments",
                OrderStatus.PENDING_PAYMENT.name(), OrderStatus.PAID.name(), null));
        outbox.append(new StoreId(order.getStoreId()), "order", order.getId(), "order.paid",
                "{\"orderId\":\"" + order.getId() + "\",\"grandTotalMinor\":" + pricing.grandMinor()
                        + ",\"currency\":\"" + order.getCurrency() + "\"}");
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
                order.getCreatedAt());
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
