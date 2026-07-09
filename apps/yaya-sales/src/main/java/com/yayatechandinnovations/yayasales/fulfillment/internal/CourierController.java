package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryStatus;
import com.yayatechandinnovations.yayasales.fulfillment.Shipments;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.platform.Ids.AssignmentId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.ShipmentId;
import com.yayatechandinnovations.yayasales.platform.auth.Caller;
import com.yayatechandinnovations.yayasales.platform.auth.CourierTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §10.4 — the courier's working surface, driven from the yayaagenticchat
 * courier screen with the yaya-sales-issued agent token (never through
 * yaya-agentic — the fourth caller class, §15). {@code /me} answers for any
 * registered agent (so a pending courier sees where it stands); everything
 * operational passes the APPROVED gate (§14).
 */
@RestController
@RequestMapping("/v1/sales/delivery")
public class CourierController {

    private final DefaultDeliveryFleet fleet;
    private final Shipments shipments;
    private final OrderService orders;
    private final TenderService tenders;

    public CourierController(DefaultDeliveryFleet fleet, Shipments shipments,
                             OrderService orders, TenderService tenders) {
        this.fleet = fleet;
        this.shipments = shipments;
        this.orders = orders;
        this.tenders = tenders;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Caller.Courier courier = caller(request);
        DeliveryFleet.AgentView agent = fleet.agent(courier.agent()).orElseThrow();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", agent.id().value());
        response.put("legalName", agent.legalName());
        response.put("status", agent.status().name());
        response.put("phoneVerified", agent.phoneVerified());
        response.put("documents", agent.documents());
        if (agent.rejectReason() != null) {
            response.put("reason", agent.rejectReason());
        }
        return response;
    }

    @GetMapping("/assignments")
    public List<Map<String, Object>> assignments(HttpServletRequest request) {
        Caller.Courier courier = caller(request);
        fleet.requireApproved(courier.agent());
        return fleet.assignmentsOf(courier.agent()).stream()
                .filter(a -> a.status() == DeliveryFleet.AssignmentStatus.ASSIGNED
                        || a.status() == DeliveryFleet.AssignmentStatus.ACCEPTED)
                .map(this::assignmentPayload)
                .toList();
    }

    @PostMapping("/assignments/{id}/accept")
    public Map<String, Object> accept(HttpServletRequest request, @PathVariable String id) {
        Caller.Courier courier = caller(request);
        fleet.requireApproved(courier.agent());
        return assignmentPayload(fleet.accept(new AssignmentId(id), courier.agent()));
    }

    @PutMapping("/assignments/{id}/status")
    public Map<String, Object> updateStatus(HttpServletRequest request, @PathVariable String id,
                                            @Valid @RequestBody UpdateStatus body) {
        Caller.Courier courier = caller(request);
        fleet.requireApproved(courier.agent());
        return assignmentPayload(fleet.updateStatus(new AssignmentId(id), courier.agent(),
                body.status(), body.proof()));
    }

    /** §8.3/§8.9: the doorstep capture — settles the COD tender, flips PAID. */
    @PostMapping("/assignments/{id}/collect-cod")
    public Map<String, Object> collectCod(HttpServletRequest request, @PathVariable String id,
                                          @Valid @RequestBody CollectCod body) {
        Caller.Courier courier = caller(request);
        fleet.requireApproved(courier.agent());
        DeliveryFleet.CodCollection collected = fleet.collectCod(new AssignmentId(id),
                courier.agent(), body.amountMinor());
        return Map.of("orderId", collected.orderId(),
                "amountMinor", collected.amountMinor(),
                "currency", collected.currency(),
                "status", collected.alreadyCollected() ? "ALREADY_COLLECTED" : "COLLECTED");
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Map<String, Object> assignmentPayload(DeliveryFleet.AssignmentView assignment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentId", assignment.id().value());
        payload.put("status", assignment.status().name());
        payload.put("assignedAt", assignment.assignedAt().toString());

        shipments.shipment(new ShipmentId(assignment.shipment().value())).ifPresent(shipment -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("shipmentId", shipment.id().value());
            s.put("orderId", shipment.order().value());
            s.put("kind", shipment.kind().name());
            s.put("deliveryStatus", shipment.deliveryStatus() == null
                    ? null : shipment.deliveryStatus().name());
            s.put("promisedFrom", shipment.promisedFrom() == null
                    ? null : shipment.promisedFrom().toString());
            s.put("promisedTo", shipment.promisedTo() == null
                    ? null : shipment.promisedTo().toString());
            payload.put("shipment", s);

            orders.order(new OrderId(shipment.order().value())).ifPresent(order -> {
                OrderView.Delivery delivery = order.delivery();
                if (delivery != null && delivery.shipTo() != null) {
                    payload.put("shipTo", delivery.shipTo());
                }
                tenders.tendersOf(order.id()).stream()
                        .filter(t -> t.kind() == TenderKind.COD
                                && t.status() == TenderView.Status.PENDING)
                        .findFirst()
                        .ifPresent(cod -> payload.put("codDueMinor", cod.amount().amountMinor()));
            });
        });
        return payload;
    }

    private static Caller.Courier caller(HttpServletRequest request) {
        Object attribute = request.getAttribute(CourierTokenFilter.COURIER_CALLER);
        if (!(attribute instanceof Caller.Courier courier)) {
            throw new IllegalStateException("courier filter did not run");
        }
        return courier;
    }

    public record UpdateStatus(@NotNull DeliveryStatus status, Map<String, Object> proof) {}

    public record CollectCod(long amountMinor) {}
}
