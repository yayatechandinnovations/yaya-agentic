package com.yayatechandinnovations.yayaagentic.demos.retail;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mock order store for the retail-customer demo profile. Real
 * retail integration would call out via {@code ToolHandlerRef.Http} to an
 * order-management service; the goal here is to exercise the engine's
 * AuthZ + confirmable + agentic-loop paths end-to-end without depending
 * on an external system.
 * <p>
 * Three customers, eight orders. ORDs {@code 1041..1043} belong to
 * {@code cust-1} (the default playground identity), and {@code 9001..9003}
 * are <em>adversarial</em> — owned by other customers, used to demonstrate
 * the ownership-denial path. {@code 1044} is a delivered order so the
 * return tool has something to operate on.
 */
@Component
public class MockOrderStore {

    public record Order(
            String orderId,
            String customerId,
            String status,        // PLACED | SHIPPED | DELIVERED | RETURNED | CANCELLED
            String carrier,
            String trackingNumber,
            LocalDate placedOn,
            LocalDate eta,
            List<LineItem> items,
            double total
    ) {}

    public record LineItem(String sku, String name, int quantity, double unitPrice) {}

    public record Return(
            String returnId,
            String orderId,
            String customerId,
            String reason,
            List<String> skus,
            LocalDate startedOn
    ) {}

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Return> returns = new ConcurrentHashMap<>();

    public MockOrderStore() {
        seed();
    }

    private void seed() {
        put(new Order("ORD-1041", "cust-1", "SHIPPED",
                "UPS", "1Z999AA10123456784",
                LocalDate.now().minusDays(3), LocalDate.now().plusDays(2),
                List.of(new LineItem("SKU-101", "Mid-back office chair", 1, 219.00)),
                219.00));
        put(new Order("ORD-1042", "cust-1", "DELIVERED",
                "FedEx", "794612345678",
                LocalDate.now().minusDays(12), LocalDate.now().minusDays(2),
                List.of(
                        new LineItem("SKU-204", "Standing-desk converter", 1, 189.00),
                        new LineItem("SKU-401", "Mechanical keyboard (TKL)", 1, 109.00)
                ),
                298.00));
        put(new Order("ORD-1043", "cust-1", "PLACED",
                null, null,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(5),
                List.of(new LineItem("SKU-502", "Wool throw blanket", 2, 64.00)),
                128.00));

        put(new Order("ORD-2080", "cust-2", "DELIVERED",
                "UPS", "1Z999AA10999999999",
                LocalDate.now().minusDays(8), LocalDate.now().minusDays(1),
                List.of(new LineItem("SKU-700", "Ceramic pour-over kettle", 1, 89.00)),
                89.00));

        // Adversarial orders — owned by other customers. find_order /
        // track_shipment / start_return on these from cust-1 must deny.
        put(new Order("ORD-9001", "cust-2", "SHIPPED",
                "USPS", "9400110200881234567890",
                LocalDate.now().minusDays(5), LocalDate.now().plusDays(1),
                List.of(new LineItem("SKU-880", "Linen apron", 1, 38.00)),
                38.00));
        put(new Order("ORD-9002", "cust-3", "DELIVERED",
                "FedEx", "794699998877",
                LocalDate.now().minusDays(20), LocalDate.now().minusDays(15),
                List.of(new LineItem("SKU-905", "Cast-iron skillet", 1, 49.00)),
                49.00));
        put(new Order("ORD-9003", "cust-3", "PLACED",
                null, null,
                LocalDate.now(), LocalDate.now().plusDays(7),
                List.of(new LineItem("SKU-910", "Bamboo cutting board", 1, 32.00)),
                32.00));
    }

    private void put(Order o) { orders.put(o.orderId(), o); }

    public Optional<Order> find(String orderId) {
        if (orderId == null) return Optional.empty();
        return Optional.ofNullable(orders.get(orderId.toUpperCase()));
    }

    public List<Order> forCustomer(String customerId) {
        return orders.values().stream()
                .filter(o -> o.customerId().equals(customerId))
                .toList();
    }

    public Return startReturn(String orderId, String reason, List<String> skus) {
        Order order = orders.get(orderId.toUpperCase());
        if (order == null) throw new IllegalArgumentException("no such order: " + orderId);
        String returnId = "RET-" + (10_000 + returns.size() + 1);
        Return r = new Return(returnId, order.orderId(), order.customerId(),
                reason, skus == null ? List.of() : skus, LocalDate.now());
        returns.put(returnId, r);
        return r;
    }

    public Map<String, Object> asMap(Order o) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", o.orderId());
        m.put("status", o.status());
        m.put("carrier", o.carrier());
        m.put("trackingNumber", o.trackingNumber());
        m.put("placedOn", o.placedOn().toString());
        m.put("eta", o.eta() == null ? null : o.eta().toString());
        m.put("items", o.items().stream().map(i -> Map.of(
                "sku", i.sku(),
                "name", i.name(),
                "quantity", i.quantity(),
                "unitPrice", i.unitPrice()
        )).toList());
        m.put("total", o.total());
        return m;
    }
}
