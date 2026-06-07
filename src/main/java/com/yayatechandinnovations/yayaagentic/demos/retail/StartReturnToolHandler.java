package com.yayatechandinnovations.yayaagentic.demos.retail;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code start_return(orderId, reason, items?)} — opens a return for an
 * existing order. Marked {@code confirmable:true} in its {@link
 * com.yayatechandinnovations.yayaagentic.tool.ToolPolicy} so the engine
 * pauses with a {@code UiHint("confirm", …)} before dispatching.
 * <p>
 * Refuses to operate on orders that aren't {@code DELIVERED} yet — the
 * deny path here is a business rule, not a policy denial; we surface it
 * as a {@link ToolException} which the engine reports as a FAILED tool
 * result with the message so the LLM can paraphrase.
 */
@Component("startReturnTool")
public class StartReturnToolHandler implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    public static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"orderId\",\"reason\"],\"properties\":{"
                    + "\"orderId\":{\"type\":\"string\",\"description\":\"Order id, e.g. ORD-1042.\"},"
                    + "\"reason\":{\"type\":\"string\",\"description\":\"Why the customer is returning.\"},"
                    + "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                    + "\"description\":\"Optional SKUs to return; omit for the full order.\"}"
                    + "}}";

    public static final String OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"returnId\":{\"type\":\"string\"},"
                    + "\"orderId\":{\"type\":\"string\"},"
                    + "\"status\":{\"type\":\"string\"},"
                    + "\"reason\":{\"type\":\"string\"}}}";

    private final MockOrderStore orders;

    public StartReturnToolHandler(MockOrderStore orders) {
        this.orders = orders;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String orderId = String.valueOf(input == null ? "" : input.getOrDefault("orderId", ""));
        String reason = String.valueOf(input == null ? "" : input.getOrDefault("reason", ""));
        List<String> items = (List<String>) (input == null ? List.of()
                : input.getOrDefault("items", List.of()));

        var order = orders.find(orderId).orElseThrow(
                () -> new ToolException("no such order: " + orderId));

        if (!"DELIVERED".equals(order.status())) {
            throw new ToolException("returns can only start once an order is delivered; "
                    + orderId + " is currently " + order.status());
        }

        var rr = orders.startReturn(orderId, reason, items);
        return Map.of(
                "returnId", rr.returnId(),
                "orderId", rr.orderId(),
                "status", "OPENED",
                "reason", rr.reason());
    }
}
