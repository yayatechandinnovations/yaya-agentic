package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryStatus;
import com.yayatechandinnovations.yayasales.fulfillment.Shipments;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.platform.Ids.AgentId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.ShipmentId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fulfillment admin (design §10.1): create shipments from an order (partial /
 * split at line granularity), advance pick→pack→dispatch, manually advance
 * delivery status (local/pickup/manual carriers), and assign local deliveries
 * to the fleet. Shipments of another store 404 — existence is not leaked.
 */
@RestController
@RequestMapping("/v1/sales/admin")
public class ShipmentAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final Shipments shipments;
    private final DeliveryFleet fleet;
    private final OrderService orders;

    public ShipmentAdminController(Shipments shipments, DeliveryFleet fleet, OrderService orders) {
        this.shipments = shipments;
        this.fleet = fleet;
        this.orders = orders;
    }

    @PostMapping("/orders/{orderId}/shipments")
    @ResponseStatus(HttpStatus.CREATED)
    public Shipments.ShipmentView create(@RequestHeader(STORE_HEADER) String storeId,
                                         @PathVariable String orderId,
                                         @RequestBody(required = false) CreateShipment body) {
        // Another store's order is a 404 BEFORE anything is created.
        orders.order(new OrderId(orderId))
                .filter(o -> o.store().value().equals(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", orderId));
        List<Shipments.Line> lines = body == null || body.lines() == null ? List.of()
                : body.lines().stream()
                        .map(l -> new Shipments.Line(new VariantId(l.sku()), l.qty()))
                        .toList();
        return shipments.create(new OrderId(orderId), lines,
                body == null || body.locationId() == null ? null : new LocationId(body.locationId()));
    }

    @GetMapping("/orders/{orderId}/shipments")
    public List<Shipments.ShipmentView> ofOrder(@RequestHeader(STORE_HEADER) String storeId,
                                                @PathVariable String orderId) {
        return shipments.shipmentsOf(new OrderId(orderId)).stream()
                .filter(s -> s.store().value().equals(storeId))
                .toList();
    }

    @PostMapping("/shipments/{id}/pick")
    public Shipments.ShipmentView pick(@RequestHeader(STORE_HEADER) String storeId,
                                       @PathVariable String id, Authentication auth) {
        requireOwned(id, storeId);
        return shipments.pick(new ShipmentId(id), actor(auth));
    }

    @PostMapping("/shipments/{id}/pack")
    public Shipments.ShipmentView pack(@RequestHeader(STORE_HEADER) String storeId,
                                       @PathVariable String id,
                                       @RequestBody(required = false) Pack body,
                                       Authentication auth) {
        requireOwned(id, storeId);
        return shipments.pack(new ShipmentId(id), body == null ? null : body.parcel(), actor(auth));
    }

    @PostMapping("/shipments/{id}/dispatch")
    public Shipments.ShipmentView dispatch(@RequestHeader(STORE_HEADER) String storeId,
                                           @PathVariable String id,
                                           @RequestBody(required = false) Dispatch body,
                                           Authentication auth) {
        requireOwned(id, storeId);
        return shipments.dispatch(new ShipmentId(id),
                body == null ? null : body.trackingNumber(),
                body == null ? null : body.trackingUrl(), actor(auth));
    }

    @PutMapping("/shipments/{id}/delivery-status")
    public Shipments.ShipmentView deliveryStatus(@RequestHeader(STORE_HEADER) String storeId,
                                                 @PathVariable String id,
                                                 @RequestBody AdvanceDelivery body,
                                                 Authentication auth) {
        requireOwned(id, storeId);
        return shipments.advanceDelivery(new ShipmentId(id), body.status(), body.proof(),
                body.description(), body.location(), Instant.now(), actor(auth));
    }

    @GetMapping("/shipments/{id}/tracking")
    public List<Shipments.TrackingEventView> tracking(@RequestHeader(STORE_HEADER) String storeId,
                                                      @PathVariable String id) {
        requireOwned(id, storeId);
        return shipments.timelineOf(new ShipmentId(id));
    }

    /** §8.9 assignment: explicit agent, or auto — any APPROVED agent of the store. */
    @PostMapping("/shipments/{id}/assign")
    public DeliveryFleet.AssignmentView assign(@RequestHeader(STORE_HEADER) String storeId,
                                               @PathVariable String id,
                                               @RequestBody(required = false) Assign body,
                                               Authentication auth) {
        requireOwned(id, storeId);
        return fleet.assign(new ShipmentId(id),
                body == null || body.agentId() == null ? Optional.empty()
                        : Optional.of(new AgentId(body.agentId())),
                actor(auth));
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void requireOwned(String shipmentId, String storeId) {
        shipments.shipment(new ShipmentId(shipmentId))
                .filter(s -> s.store().value().equals(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("shipment", shipmentId));
    }

    private static String actor(Authentication auth) {
        return auth == null ? "operator" : "op:" + auth.getName();
    }

    public record CreateShipment(List<LineBody> lines, String locationId) {}

    public record LineBody(String sku, long qty) {}

    public record Pack(Shipments.Parcel parcel) {}

    public record Dispatch(String trackingNumber, String trackingUrl) {}

    public record AdvanceDelivery(@NotNull DeliveryStatus status, Map<String, Object> proof,
                                  String description, String location) {}

    public record Assign(String agentId) {}
}
