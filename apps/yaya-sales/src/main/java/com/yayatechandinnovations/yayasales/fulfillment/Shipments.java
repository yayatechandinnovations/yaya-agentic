package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.ShipmentId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §8.6–8.7 — shipments and their delivery life. An order is fulfilled by one
 * or more shipments (partial/split at line granularity in v1); DISPATCH is the
 * stock-coupling point: on {@code ON_FULFILLMENT} stores it commits the lines'
 * reservations into SALE movements (on {@code ON_PAYMENT} stores the movement
 * already happened at capture — dispatch is bookkeeping only). Every delivery
 * advance appends an immutable tracking event and emits an outbox event
 * (§13) so the shopper's chat hears about it.
 */
public interface Shipments {

    /**
     * From a paid order's lines — or a PENDING_PAYMENT order whose money is
     * collected on/after delivery (COD, MANUAL): the goods legitimately leave
     * before the tender settles (§8.3).
     */
    ShipmentView create(OrderId order, List<Line> lines, LocationId location);

    ShipmentView pick(ShipmentId id, String actor);

    ShipmentView pack(ShipmentId id, Parcel parcel, String actor);

    /** Tracking number/url are the ManualCarrier's merchant-entered values (§8.5). */
    ShipmentView dispatch(ShipmentId id, String trackingNumber, String trackingUrl, String actor);

    /**
     * §8.7 manual advance — merchant or courier, for local delivery / pickup /
     * manual carriers. PICKED_UP requires the pickup OTP in {@code proof}.
     */
    ShipmentView advanceDelivery(ShipmentId id, DeliveryStatus status, Map<String, Object> proof,
                                 String description, String location, Instant occurredAt, String actor);

    Optional<ShipmentView> shipment(ShipmentId id);

    Optional<ShipmentView> byTracking(CarrierId carrier, String trackingNumber);

    List<ShipmentView> shipmentsOf(OrderId order);

    /** The append-only §8.7 timeline — the "where's my order?" answer. */
    List<TrackingEventView> timelineOf(ShipmentId id);

    enum Status { DRAFT, PICKING, PACKED, DISPATCHED }

    record Line(VariantId sku, long qty) {}

    record Parcel(Integer weightGrams, Integer lengthCm, Integer widthCm, Integer heightCm) {}

    record ShipmentView(ShipmentId id, OrderId order, StoreId store, LocationId location,
                        DeliveryMethodId method, DeliveryMethodKind kind, CarrierId carrier,
                        String serviceLevel, List<Line> lines, Parcel parcel, Status status,
                        DeliveryStatus deliveryStatus, String trackingNumber, String trackingUrl,
                        SlotId slot, Instant promisedFrom, Instant promisedTo, String pickupOtp,
                        Map<String, Object> proof, Instant dispatchedAt, Instant deliveredAt,
                        Instant createdAt) {}

    record TrackingEventView(String id, ShipmentId shipment, DeliveryStatus status,
                             String description, String location, Instant occurredAt,
                             Instant recordedAt) {}
}
