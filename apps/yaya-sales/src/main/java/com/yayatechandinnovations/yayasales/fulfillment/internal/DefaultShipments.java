package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.fulfillment.Carrier;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryMethodKind;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryStatus;
import com.yayatechandinnovations.yayasales.fulfillment.Shipments;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;
import com.yayatechandinnovations.yayasales.platform.Ids.ShipmentId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.outbox.Outbox;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.LocationView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * §8.6–8.7. Dispatch is the stock-coupling point: on {@code ON_FULFILLMENT}
 * stores it commits each shipped line's reservations → SALE movements (the
 * ledger stays truthful); on {@code ON_PAYMENT} stores the movement happened
 * at capture and dispatch is bookkeeping. Every delivery advance appends an
 * immutable tracking event, moves {@link DeliveryStatus} monotonically, and
 * emits the §13 outbox event the shopper's chat hears about.
 */
@Service
public class DefaultShipments implements Shipments {

    private final ShipmentRepository shipments;
    private final TrackingEventRepository trackingEvents;
    private final CarrierRepository carriers;
    private final List<Carrier> carrierImpls;
    private final OrderService orders;
    private final TenderService tenders;
    private final InventoryLedger ledger;
    private final StoreDirectory stores;
    private final Outbox outbox;
    private final ObjectMapper json;

    public DefaultShipments(ShipmentRepository shipments, TrackingEventRepository trackingEvents,
                            CarrierRepository carriers, List<Carrier> carrierImpls,
                            OrderService orders, TenderService tenders, InventoryLedger ledger,
                            StoreDirectory stores, Outbox outbox, ObjectMapper json) {
        this.shipments = shipments;
        this.trackingEvents = trackingEvents;
        this.carriers = carriers;
        this.carrierImpls = carrierImpls;
        this.orders = orders;
        this.tenders = tenders;
        this.ledger = ledger;
        this.stores = stores;
        this.outbox = outbox;
        this.json = json;
    }

    // ── create + pick/pack/dispatch (§8.6) ───────────────────────────────────

    @Override
    @Transactional
    public ShipmentView create(OrderId orderId, List<Line> lines, LocationId location) {
        OrderView order = orders.order(orderId)
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", orderId.value()));
        requireShippable(order);

        Map<String, OrderView.Line> orderLines = order.lines().stream()
                .collect(Collectors.toMap(l -> l.sku().value(), Function.identity()));
        List<Line> effective = lines == null || lines.isEmpty()
                ? order.lines().stream().map(l -> new Line(l.sku(), l.qty())).toList()
                : lines;

        Set<String> alreadyShipped = shippedSkus(orderId);
        for (Line line : effective) {
            OrderView.Line orderLine = orderLines.get(line.sku().value());
            if (orderLine == null) {
                throw new ApiErrors.UnprocessableException(
                        "sku " + line.sku().value() + " is not on order " + orderId.value());
            }
            // v1 splits at LINE granularity: a line ships whole because its
            // reservations commit whole at dispatch (§8.6). Splitting one
            // line's qty across shipments would need hold splitting — vNext.
            if (line.qty() != orderLine.qty()) {
                throw new ApiErrors.UnprocessableException("line " + line.sku().value()
                        + " must ship its full quantity (" + orderLine.qty() + ") in v1");
            }
            if (alreadyShipped.contains(line.sku().value())) {
                throw new ApiErrors.ConflictException(
                        "sku " + line.sku().value() + " is already on a shipment");
            }
        }

        LocationId effectiveLocation = location != null ? location
                : defaultLocation(order.store());
        OrderView.Delivery delivery = order.delivery();
        ShipmentEntity shipment = new ShipmentEntity(Ids.newId(), orderId.value(),
                order.store().value(), effectiveLocation.value(),
                delivery == null ? null : delivery.method().value(),
                delivery == null ? DeliveryMethodKind.SHIPPING.name() : delivery.kind(),
                delivery == null || delivery.carrier() == null ? null : delivery.carrier().value(),
                null, write(effective),
                delivery == null || delivery.slot() == null ? null : delivery.slot().value(),
                delivery == null ? null : delivery.promisedFrom(),
                delivery == null ? null : delivery.promisedTo());
        shipments.save(shipment);
        return toView(shipment);
    }

    @Override
    @Transactional
    public ShipmentView pick(ShipmentId id, String actor) {
        ShipmentEntity shipment = locked(id);
        shipment.transition(Status.PICKING);
        return toView(shipment);
    }

    @Override
    @Transactional
    public ShipmentView pack(ShipmentId id, Parcel parcel, String actor) {
        ShipmentEntity shipment = locked(id);
        shipment.transition(Status.PACKED);
        if (parcel != null) {
            shipment.setParcelJson(write(parcel));
        }
        return toView(shipment);
    }

    @Override
    @Transactional
    public ShipmentView dispatch(ShipmentId id, String trackingNumber, String trackingUrl,
                                 String actor) {
        ShipmentEntity shipment = locked(id);
        shipment.transition(Status.DISPATCHED);
        shipment.setDispatchedAt(Instant.now());

        if (shipment.getCarrierId() != null) {
            Carrier.Label label = carrierFor(shipment.getCarrierId()).createLabel(
                    new Carrier.ShipmentInput(new ShipmentId(shipment.getId()),
                            trackingNumber, trackingUrl));
            shipment.setTracking(label.trackingNumber(), label.trackingUrl());
            shipment.setLabelRef(label.labelRef());
        } else {
            shipment.setTracking(trackingNumber, trackingUrl);
        }

        DeliveryStatus initial;
        if (DeliveryMethodKind.PICKUP.name().equals(shipment.getMethodKind())) {
            initial = DeliveryStatus.READY_FOR_PICKUP;
            // The counter handshake (§8.7): the shopper shows this to collect.
            shipment.setPickupOtp(Tokens.newOtp());
        } else {
            initial = DeliveryStatus.LABEL_CREATED;
        }
        shipment.setDeliveryStatus(initial);
        appendEvent(shipment, initial, "dispatched", null, Instant.now());

        // Stock coupling (§8.6, decision 2): ON_FULFILLMENT commits the lines'
        // holds → SALE movements NOW; ON_PAYMENT stores committed at capture.
        StorePolicies policies = stores.policiesOf(new StoreId(shipment.getStoreId()));
        OrderView order = orders.order(new OrderId(shipment.getOrderId())).orElseThrow();
        if (policies.deductionPoint() == StorePolicies.DeductionPoint.ON_FULFILLMENT) {
            Set<String> shippedNow = readLines(shipment).stream()
                    .map(l -> l.sku().value()).collect(Collectors.toSet());
            order.lines().stream()
                    .filter(l -> shippedNow.contains(l.sku().value()))
                    .flatMap(l -> l.reservationIds().stream())
                    .forEach(r -> ledger.commit(new ReservationId(r)));
        }

        outbox.append(new StoreId(shipment.getStoreId()), "shipment", shipment.getId(),
                "shipment.dispatched", "{\"shipmentId\":\"" + shipment.getId()
                        + "\",\"orderId\":\"" + shipment.getOrderId() + "\"}");

        orders.updateFulfillment(new OrderId(shipment.getOrderId()),
                aggregateFulfillment(order), actor);
        return toView(shipment);
    }

    // ── delivery advances (§8.7) ─────────────────────────────────────────────

    @Override
    @Transactional
    public ShipmentView advanceDelivery(ShipmentId id, DeliveryStatus status,
                                        Map<String, Object> proof, String description,
                                        String location, Instant occurredAt, String actor) {
        ShipmentEntity shipment = locked(id);
        if (shipment.status() != Status.DISPATCHED) {
            throw new ApiErrors.ConflictException(
                    "shipment " + id.value() + " is not dispatched yet");
        }
        DeliveryStatus current = shipment.deliveryStatus();
        if (current != null && (current.terminal() || status.rank() <= current.rank())) {
            throw new ApiErrors.ConflictException("delivery status cannot go "
                    + current + " → " + status);
        }
        if (status == DeliveryStatus.PICKED_UP) {
            Object otp = proof == null ? null : proof.get("otp");
            if (otp == null || !Objects.equals(shipment.getPickupOtp(), String.valueOf(otp))) {
                throw new ApiErrors.UnprocessableException("pickup requires the correct OTP");
            }
        }

        shipment.setDeliveryStatus(status);
        if (proof != null && !proof.isEmpty()) {
            shipment.setProofJson(write(proof));
        }
        if (status == DeliveryStatus.DELIVERED || status == DeliveryStatus.PICKED_UP) {
            shipment.setDeliveredAt(occurredAt == null ? Instant.now() : occurredAt);
        }
        appendEvent(shipment, status, description, location,
                occurredAt == null ? Instant.now() : occurredAt);
        emitDeliveryEvent(shipment, status);
        return toView(shipment);
    }

    // ── reads ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentView> shipment(ShipmentId id) {
        return shipments.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentView> byTracking(CarrierId carrier, String trackingNumber) {
        return shipments.findFirstByCarrierIdAndTrackingNumber(carrier.value(), trackingNumber)
                .map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipmentView> shipmentsOf(OrderId order) {
        return shipments.findByOrderIdOrderByCreatedAtAsc(order.value())
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrackingEventView> timelineOf(ShipmentId id) {
        return trackingEvents.findByShipmentIdOrderByOccurredAtAscRecordedAtAsc(id.value()).stream()
                .map(e -> new TrackingEventView(e.getId(), new ShipmentId(e.getShipmentId()),
                        DeliveryStatus.valueOf(e.getStatus()), e.getDescription(),
                        e.getLocation(), e.getOccurredAt(), e.getRecordedAt()))
                .toList();
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * PAID (or already fulfilling) ships; PENDING_PAYMENT ships ONLY when the
     * money is collected on/after delivery — COD at the door, MANUAL transfer
     * awaiting confirm (§8.3). A PSP order never leaves before capture.
     */
    private void requireShippable(OrderView order) {
        switch (order.status()) {
            case PAID, FULFILLING, PARTIALLY_FULFILLED -> { }
            case PENDING_PAYMENT -> {
                boolean collectOnDelivery = tenders.tendersOf(order.id()).stream()
                        .anyMatch(t -> t.status() == TenderView.Status.PENDING
                                && (t.kind() == TenderKind.COD || t.kind() == TenderKind.MANUAL));
                if (!collectOnDelivery) {
                    throw new ApiErrors.UnprocessableException(
                            "order " + order.id().value() + " is not paid");
                }
            }
            default -> throw new ApiErrors.UnprocessableException(
                    "order " + order.id().value() + " is " + order.status() + " — cannot ship");
        }
    }

    private Set<String> shippedSkus(OrderId order) {
        Set<String> skus = new HashSet<>();
        for (ShipmentEntity shipment : shipments.findByOrderIdOrderByCreatedAtAsc(order.value())) {
            readLines(shipment).forEach(l -> skus.add(l.sku().value()));
        }
        return skus;
    }

    /** All order lines on DISPATCHED shipments → FULFILLED; some → PARTIALLY_FULFILLED. */
    private OrderService.FulfillmentState aggregateFulfillment(OrderView order) {
        Set<String> dispatched = new HashSet<>();
        for (ShipmentEntity shipment : shipments.findByOrderIdOrderByCreatedAtAsc(order.id().value())) {
            if (shipment.status() == Status.DISPATCHED) {
                readLines(shipment).forEach(l -> dispatched.add(l.sku().value()));
            }
        }
        boolean all = order.lines().stream().allMatch(l -> dispatched.contains(l.sku().value()));
        return all ? OrderService.FulfillmentState.FULFILLED
                : OrderService.FulfillmentState.PARTIALLY_FULFILLED;
    }

    private void emitDeliveryEvent(ShipmentEntity shipment, DeliveryStatus status) {
        String eventType = switch (status) {
            case OUT_FOR_DELIVERY -> "shipment.out_for_delivery";
            case DELIVERED, PICKED_UP -> "shipment.delivered";
            case FAILED -> "delivery.failed";
            default -> null;
        };
        if (eventType != null) {
            outbox.append(new StoreId(shipment.getStoreId()), "shipment", shipment.getId(),
                    eventType, "{\"shipmentId\":\"" + shipment.getId()
                            + "\",\"orderId\":\"" + shipment.getOrderId()
                            + "\",\"status\":\"" + status.name() + "\"}");
        }
    }

    private void appendEvent(ShipmentEntity shipment, DeliveryStatus status, String description,
                             String location, Instant occurredAt) {
        trackingEvents.save(new TrackingEventEntity(Ids.newId(), shipment.getId(), status.name(),
                description, location, occurredAt));
    }

    private Carrier carrierFor(String carrierId) {
        CarrierEntity row = carriers.findById(carrierId)
                .orElseThrow(() -> new ApiErrors.NotFoundException("carrier", carrierId));
        return carrierImpls.stream()
                .filter(c -> c.kind().equals(row.getKind()))
                .findFirst()
                .orElseThrow(() -> new ApiErrors.UnprocessableException(
                        "no carrier implementation for kind " + row.getKind()));
    }

    private LocationId defaultLocation(StoreId store) {
        return stores.locationsOf(store).stream()
                .filter(LocationView::sellable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("store has no sellable location"))
                .id();
    }

    private ShipmentEntity locked(ShipmentId id) {
        return shipments.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("shipment", id.value()));
    }

    ShipmentView toView(ShipmentEntity shipment) {
        return new ShipmentView(new ShipmentId(shipment.getId()),
                new OrderId(shipment.getOrderId()), new StoreId(shipment.getStoreId()),
                new LocationId(shipment.getLocationId()),
                shipment.getMethodId() == null ? null : new DeliveryMethodId(shipment.getMethodId()),
                DeliveryMethodKind.valueOf(shipment.getMethodKind()),
                shipment.getCarrierId() == null ? null : new CarrierId(shipment.getCarrierId()),
                shipment.getServiceLevel(), readLines(shipment),
                shipment.getParcelJson() == null ? null : read(shipment.getParcelJson(), Parcel.class),
                shipment.status(), shipment.deliveryStatus(),
                shipment.getTrackingNumber(), shipment.getTrackingUrl(),
                shipment.getSlotId() == null ? null : new SlotId(shipment.getSlotId()),
                shipment.getPromisedFrom(), shipment.getPromisedTo(), shipment.getPickupOtp(),
                shipment.getProofJson() == null ? Map.of()
                        : read(shipment.getProofJson(), new TypeReference<Map<String, Object>>() {}),
                shipment.getDispatchedAt(), shipment.getDeliveredAt(), shipment.getCreatedAt());
    }

    private List<Line> readLines(ShipmentEntity shipment) {
        try {
            return json.readValue(shipment.getLinesJson(), new TypeReference<List<StoredLine>>() {})
                    .stream().map(l -> new Line(new VariantId(l.sku()), l.qty())).toList();
        } catch (Exception e) {
            throw new IllegalStateException("corrupt shipment lines_json", e);
        }
    }

    record StoredLine(String sku, long qty) {}

    private String write(Object value) {
        if (value instanceof List<?> lines) {
            try {
                return json.writeValueAsString(lines.stream()
                        .map(l -> new StoredLine(((Line) l).sku().value(), ((Line) l).qty()))
                        .toList());
            } catch (Exception e) {
                throw new IllegalStateException("unserializable shipment lines", e);
            }
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable shipment field", e);
        }
    }

    private <T> T read(String raw, Class<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt shipment json", e);
        }
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt shipment json", e);
        }
    }
}
