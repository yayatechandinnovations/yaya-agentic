package com.yayatechandinnovations.yayaagentic.demos.retail;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code track_shipment(orderId)} — returns the carrier, tracking number,
 * status and eta. For not-yet-shipped orders the carrier fields are null
 * and the tool responds with a {@code stage:"not_shipped"} hint so the
 * LLM can phrase a useful answer without inventing dates.
 */
@Component("trackShipmentTool")
public class TrackShipmentToolHandler implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    public static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"orderId\"],\"properties\":{"
                    + "\"orderId\":{\"type\":\"string\",\"description\":\"Order id, e.g. ORD-1042.\"}"
                    + "}}";

    public static final String OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"orderId\":{\"type\":\"string\"},"
                    + "\"stage\":{\"type\":\"string\"},"
                    + "\"carrier\":{\"type\":\"string\"},"
                    + "\"trackingNumber\":{\"type\":\"string\"},"
                    + "\"eta\":{\"type\":\"string\"},"
                    + "\"not_found\":{\"type\":\"boolean\"}}}";

    private final MockOrderStore orders;

    public TrackShipmentToolHandler(MockOrderStore orders) {
        this.orders = orders;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String orderId = String.valueOf(input == null ? "" : input.getOrDefault("orderId", ""));
        var maybe = orders.find(orderId);
        if (maybe.isEmpty()) {
            return Map.of("orderId", orderId, "not_found", true);
        }
        var order = maybe.get();
        String stage = switch (order.status()) {
            case "PLACED" -> "not_shipped";
            case "SHIPPED" -> "in_transit";
            case "DELIVERED" -> "delivered";
            case "RETURNED" -> "returned";
            case "CANCELLED" -> "cancelled";
            default -> "unknown";
        };
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("orderId", order.orderId());
        out.put("stage", stage);
        out.put("carrier", order.carrier());
        out.put("trackingNumber", order.trackingNumber());
        out.put("eta", order.eta() == null ? null : order.eta().toString());
        return out;
    }
}
