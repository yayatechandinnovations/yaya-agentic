package com.yayatechandinnovations.yayasales.integration.internal;

import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.fulfillment.AddressValidator;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryOption;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryQuoteEngine;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryQuoteRequest;
import com.yayatechandinnovations.yayasales.fulfillment.Shipments;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.CartId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §10.2 delivery surface for the agent: {@code delivery/options} runs the
 * §8.4 quote (server-priced, promise-windowed) and {@code orders/{id}/track}
 * answers "where's my order?" (§8.7) — both as {@code _uiHint} frames the chat
 * renders as chrome, never prose.
 */
@RestController
@RequestMapping("/v1/sales/agent")
public class AgentDeliveryController {

    private final CartService carts;
    private final AddressValidator addressValidator;
    private final DeliveryQuoteEngine quoteEngine;
    private final OrderService orders;
    private final Shipments shipments;

    public AgentDeliveryController(CartService carts, AddressValidator addressValidator,
                                   DeliveryQuoteEngine quoteEngine, OrderService orders,
                                   Shipments shipments) {
        this.carts = carts;
        this.addressValidator = addressValidator;
        this.quoteEngine = quoteEngine;
        this.orders = orders;
        this.shipments = shipments;
    }

    @PostMapping("/delivery/options")
    public Map<String, Object> options(HttpServletRequest request,
                                       @Valid @RequestBody Options body) {
        StoreView store = store(request);
        CartView cart = carts.cart(new CartId(body.cartHandle()))
                .filter(c -> c.store().equals(store.id()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("cart", body.cartHandle()));

        Address destination = addressValidator.validate(store.id(), cart.region(),
                body.address().toAddress());
        PricedQuote goods = carts.quote(cart.id(), null);
        List<DeliveryOption> options = quoteEngine.quote(new DeliveryQuoteRequest(
                store.id(), cart.region(), destination,
                cart.lines().stream()
                        .map(l -> new DeliveryQuoteRequest.Line(l.sku(), l.qty())).toList(),
                goods.subtotal().minus(goods.discountTotal())));

        List<Map<String, Object>> payload = options.stream().map(o -> {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("methodId", o.method().value());
            option.put("kind", o.kind().name());
            option.put("label", o.label());
            option.put("priceMinor", o.price().amountMinor());
            option.put("currency", o.price().currency());
            option.put("promisedFrom", o.promisedFrom().toString());
            option.put("promisedTo", o.promisedTo().toString());
            option.put("serviceLevel", o.serviceLevel() == null ? "" : o.serviceLevel());
            return option;
        }).toList();

        return UiHints.framed(Map.of("options", payload, "count", payload.size()),
                "delivery_options", Map.of("options", payload));
    }

    @GetMapping("/orders/{id}/track")
    public Map<String, Object> track(HttpServletRequest request, @PathVariable String id) {
        StoreView store = store(request);
        OrderView order = orders.order(new OrderId(id))
                .filter(o -> o.store().equals(store.id()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", id));

        List<Map<String, Object>> shipmentPayloads = shipments.shipmentsOf(order.id()).stream()
                .map(this::shipmentPayload)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id().value());
        payload.put("orderStatus", order.status().name());
        payload.put("fulfillmentState", order.fulfillmentState());
        if (order.delivery() != null) {
            payload.put("promisedFrom", order.delivery().promisedFrom() == null
                    ? null : order.delivery().promisedFrom().toString());
            payload.put("promisedTo", order.delivery().promisedTo() == null
                    ? null : order.delivery().promisedTo().toString());
        }
        payload.put("shipments", shipmentPayloads);

        return UiHints.framed(Map.of("tracking", payload), "delivery_tracking", payload);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Map<String, Object> shipmentPayload(Shipments.ShipmentView shipment) {
        List<Map<String, Object>> timeline = shipments.timelineOf(shipment.id()).stream()
                .map(e -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("status", e.status().name());
                    event.put("description", e.description() == null ? "" : e.description());
                    event.put("location", e.location() == null ? "" : e.location());
                    event.put("occurredAt", e.occurredAt().toString());
                    return event;
                })
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shipmentId", shipment.id().value());
        payload.put("kind", shipment.kind().name());
        payload.put("status", shipment.status().name());
        payload.put("deliveryStatus", shipment.deliveryStatus() == null
                ? null : shipment.deliveryStatus().name());
        payload.put("trackingNumber", shipment.trackingNumber());
        payload.put("trackingUrl", shipment.trackingUrl());
        payload.put("timeline", timeline);
        return payload;
    }

    private static StoreView store(HttpServletRequest request) {
        return (StoreView) request.getAttribute(AgentApiGuard.STORE_ATTRIBUTE);
    }

    public record Options(@NotBlank String cartHandle, @NotNull AddressBody address) {}

    public record AddressBody(String line1, String line2, String city, String region,
                              String postalCode, String country) {

        Address toAddress() {
            return new Address(line1, line2, city, region, postalCode, country);
        }
    }
}
