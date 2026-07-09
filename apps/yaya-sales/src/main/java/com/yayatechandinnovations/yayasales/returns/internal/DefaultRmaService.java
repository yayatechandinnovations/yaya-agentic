package com.yayatechandinnovations.yayasales.returns.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.Ref;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RmaId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.outbox.Outbox;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.returns.RmaService;
import com.yayatechandinnovations.yayasales.returns.RmaStatus;
import com.yayatechandinnovations.yayasales.returns.RmaView;
import com.yayatechandinnovations.yayasales.stores.LocationView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * §8.8 — the orchestration lives here; the rules stay where they belong: the
 * stock move is the inventory ledger's, the money move is the orders refund
 * path (which reconstructs from the tender ledger and caps at the settled
 * remainder), and this service only decides WHAT comes back and HOW MUCH goes
 * out.
 */
@Service
class DefaultRmaService implements RmaService {

    /** An order whose money settled — the only thing a return can be claimed against. */
    private static final Set<OrderStatus> RETURNABLE = EnumSet.of(OrderStatus.PAID,
            OrderStatus.FULFILLING, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.FULFILLED,
            OrderStatus.PARTIALLY_REFUNDED);

    private final RmaRepository rmas;
    private final OrderService orders;
    private final InventoryLedger ledger;
    private final StoreDirectory stores;
    private final Outbox outbox;
    private final ObjectMapper json;

    DefaultRmaService(RmaRepository rmas, OrderService orders, InventoryLedger ledger,
                      StoreDirectory stores, Outbox outbox, ObjectMapper json) {
        this.rmas = rmas;
        this.orders = orders;
        this.ledger = ledger;
        this.stores = stores;
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    @Transactional
    public RmaView request(StoreId store, OrderId orderId, List<RmaView.Line> lines,
                           String reason, String actor) {
        if (lines == null || lines.isEmpty()) {
            throw new ApiErrors.UnprocessableException("an RMA needs at least one line");
        }
        OrderView order = orders.order(orderId)
                .filter(o -> o.store().equals(store))
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", orderId.value()));
        if (!RETURNABLE.contains(order.status())) {
            throw new ApiErrors.UnprocessableException(
                    "order is " + order.status() + " — returns need a paid order");
        }

        Map<String, Long> remaining = returnableRemainder(order);
        for (RmaView.Line line : lines) {
            if (line.qty() < 1) {
                throw new ApiErrors.UnprocessableException("RMA line qty must be >= 1");
            }
            Long left = remaining.get(line.sku().value());
            if (left == null) {
                throw new ApiErrors.UnprocessableException(
                        "sku " + line.sku().value() + " is not on order " + orderId.value());
            }
            if (line.qty() > left) {
                throw new ApiErrors.UnprocessableException("sku " + line.sku().value()
                        + ": " + line.qty() + " requested but only " + left
                        + " returnable (ordered minus already-RMA'd)");
            }
        }

        RmaEntity rma = new RmaEntity(Ids.newId(), orderId.value(), store.value(),
                write(lines), reason);
        rmas.save(rma);
        return toView(rma);
    }

    @Override
    @Transactional
    public RmaView authorize(RmaId id, String actor) {
        RmaEntity rma = locked(id);
        rma.transition(RmaStatus.AUTHORIZED);
        return toView(rma);
    }

    @Override
    @Transactional
    public RmaView reject(RmaId id, String reason, String actor) {
        RmaEntity rma = locked(id);
        rma.transition(RmaStatus.REJECTED);
        if (reason != null) {
            rma.setReason(reason);
        }
        return toView(rma);
    }

    @Override
    @Transactional
    public RmaView cancel(RmaId id, String actor) {
        RmaEntity rma = locked(id);
        rma.transition(RmaStatus.CANCELLED);
        return toView(rma);
    }

    @Override
    @Transactional
    public RmaView setReturnShipment(RmaId id, RmaView.ReturnShipment shipment) {
        RmaEntity rma = locked(id);
        if (rma.status() != RmaStatus.REQUESTED && rma.status() != RmaStatus.AUTHORIZED) {
            throw new ApiErrors.ConflictException(
                    "rma " + id.value() + " is " + rma.status() + " — return shipment is settled");
        }
        rma.setReturnShipmentJson(write(shipment));
        return toView(rma);
    }

    @Override
    @Transactional
    public RmaView receive(RmaId id, ReceiveCommand command, String actor) {
        RmaEntity rma = locked(id);
        if (rma.status() != RmaStatus.AUTHORIZED) {
            throw new ApiErrors.ConflictException(
                    "rma " + id.value() + " is " + rma.status() + " — only AUTHORIZED receives");
        }
        List<RmaView.Disposition> dispositions = command.dispositions();
        if (dispositions == null || dispositions.isEmpty()) {
            throw new ApiErrors.UnprocessableException("a receive needs at least one disposition");
        }

        OrderView order = orders.order(new OrderId(rma.getOrderId())).orElseThrow();
        Map<String, Long> authorized = new HashMap<>();
        for (RmaView.Line line : readLines(rma)) {
            authorized.merge(line.sku().value(), line.qty(), Long::sum);
        }
        Map<String, Long> unitPrices = new HashMap<>();
        for (OrderView.Line line : order.lines()) {
            unitPrices.put(line.sku().value(), line.unitPrice().amountMinor());
        }

        StoreId store = new StoreId(rma.getStoreId());
        LocationId location = defaultLocation(store);
        long defaultRefund = 0;
        for (RmaView.Disposition disposition : dispositions) {
            if (disposition.qty() < 1) {
                throw new ApiErrors.UnprocessableException("disposition qty must be >= 1");
            }
            long left = authorized.getOrDefault(disposition.sku().value(), 0L);
            if (disposition.qty() > left) {
                throw new ApiErrors.UnprocessableException("sku " + disposition.sku().value()
                        + ": " + disposition.qty() + " disposed but only " + left + " authorized");
            }
            authorized.put(disposition.sku().value(), left - disposition.qty());

            // Only resellable units re-enter stock; SHRINKAGE/DAMAGE stays an
            // audit fact on the RMA — the goods never come back on-hand (§8.8).
            if (disposition.kind() == RmaView.Disposition.Kind.RESTOCK) {
                ledger.restock(disposition.sku(), location, disposition.qty(),
                        new Ref(Ref.RefType.RMA, rma.getId()));
            }
            defaultRefund += unitPrices.getOrDefault(disposition.sku().value(), 0L)
                    * disposition.qty();
        }

        // The money out: returned units' paid value from the order snapshot
        // unless overridden; 0 = receive without refund (exchange flows). The
        // tender ledger caps it at the settled remainder either way.
        long refundMinor = command.amountMinor() != null ? command.amountMinor() : defaultRefund;
        if (refundMinor < 0) {
            throw new ApiErrors.UnprocessableException("refund amount must be >= 0");
        }
        rma.setDispositionsJson(write(dispositions));
        rma.setReceivedAt(Instant.now());
        rma.transition(RmaStatus.RECEIVED);

        if (refundMinor > 0) {
            orders.refund(order.id(), new Money(refundMinor, order.currency()),
                    command.toStoreCredit(), actor);
            rma.setRefundTxnRef((command.toStoreCredit() ? "store_credit:" : "original_tender:")
                    + refundMinor);
        }
        rma.transition(RmaStatus.CLOSED);

        outbox.append(store, "rma", rma.getId(), "rma.received",
                "{\"rmaId\":\"" + rma.getId() + "\",\"orderId\":\"" + rma.getOrderId()
                        + "\",\"refundMinor\":" + refundMinor + "}");
        return toView(rma);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RmaView> rma(RmaId id) {
        return rmas.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RmaView> rmasOf(StoreId store, Optional<RmaStatus> status) {
        List<RmaEntity> rows = status
                .map(s -> rmas.findByStoreIdAndStatusOrderByCreatedAtDesc(store.value(), s.name()))
                .orElseGet(() -> rmas.findByStoreIdOrderByCreatedAtDesc(store.value()));
        return rows.stream().map(this::toView).toList();
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Per SKU: ordered qty minus everything a live (non-rejected/cancelled) RMA already claims. */
    private Map<String, Long> returnableRemainder(OrderView order) {
        Map<String, Long> remaining = new HashMap<>();
        for (OrderView.Line line : order.lines()) {
            remaining.merge(line.sku().value(), line.qty(), Long::sum);
        }
        for (RmaEntity previous : rmas.findByOrderId(order.id().value())) {
            if (previous.status() == RmaStatus.REJECTED
                    || previous.status() == RmaStatus.CANCELLED) {
                continue;
            }
            for (RmaView.Line line : readLines(previous)) {
                remaining.merge(line.sku().value(), -line.qty(), Long::sum);
            }
        }
        return remaining;
    }

    private LocationId defaultLocation(StoreId store) {
        return stores.locationsOf(store).stream()
                .filter(LocationView::sellable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("store has no sellable location"))
                .id();
    }

    private RmaEntity locked(RmaId id) {
        return rmas.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("rma", id.value()));
    }

    private RmaView toView(RmaEntity rma) {
        return new RmaView(new RmaId(rma.getId()), new OrderId(rma.getOrderId()),
                new StoreId(rma.getStoreId()), rma.status(), rma.getReason(),
                readLines(rma), readShipment(rma), readDispositions(rma),
                rma.getRefundTxnRef(), rma.getReceivedAt(), rma.getCreatedAt());
    }

    private List<RmaView.Line> readLines(RmaEntity rma) {
        try {
            List<StoredLine> stored = json.readValue(rma.getLinesJson(),
                    new TypeReference<List<StoredLine>>() {});
            return stored.stream()
                    .map(l -> new RmaView.Line(new VariantId(l.sku()), l.qty()))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("corrupt rma lines_json", e);
        }
    }

    private RmaView.ReturnShipment readShipment(RmaEntity rma) {
        if (rma.getReturnShipmentJson() == null) {
            return null;
        }
        try {
            StoredShipment stored = json.readValue(rma.getReturnShipmentJson(),
                    StoredShipment.class);
            return new RmaView.ReturnShipment(stored.carrier(), stored.trackingNumber(),
                    stored.instructions());
        } catch (Exception e) {
            throw new IllegalStateException("corrupt rma return_shipment_json", e);
        }
    }

    private List<RmaView.Disposition> readDispositions(RmaEntity rma) {
        if (rma.getDispositionsJson() == null) {
            return List.of();
        }
        try {
            List<StoredDisposition> stored = json.readValue(rma.getDispositionsJson(),
                    new TypeReference<List<StoredDisposition>>() {});
            return stored.stream()
                    .map(d -> new RmaView.Disposition(new VariantId(d.sku()), d.qty(),
                            RmaView.Disposition.Kind.valueOf(d.kind())))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("corrupt rma dispositions_json", e);
        }
    }

    private String write(List<?> values) {
        try {
            return json.writeValueAsString(values.stream().map(v -> switch (v) {
                case RmaView.Line line -> new StoredLine(line.sku().value(), line.qty());
                case RmaView.Disposition d ->
                        new StoredDisposition(d.sku().value(), d.qty(), d.kind().name());
                default -> throw new IllegalStateException("unexpected rma value " + v);
            }).toList());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("unserializable rma field", e);
        }
    }

    private String write(RmaView.ReturnShipment shipment) {
        try {
            return json.writeValueAsString(new StoredShipment(shipment.carrier(),
                    shipment.trackingNumber(), shipment.instructions()));
        } catch (Exception e) {
            throw new IllegalStateException("unserializable return shipment", e);
        }
    }

    // Stored shapes stay decoupled from the view records so the JSON columns
    // never change shape by accident when the API does.
    record StoredLine(String sku, long qty) {}

    record StoredDisposition(String sku, long qty, String kind) {}

    record StoredShipment(String carrier, String trackingNumber, String instructions) {}
}
