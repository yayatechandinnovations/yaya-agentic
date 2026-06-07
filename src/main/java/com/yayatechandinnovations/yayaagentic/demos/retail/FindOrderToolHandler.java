package com.yayatechandinnovations.yayaagentic.demos.retail;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code find_order(orderId)} — returns the structured order body, or a
 * structured {@code not_found} payload if the id doesn't exist. AuthZ
 * (ownership) runs upstream in the Authorizer chain; by the time we land
 * here the caller is allowed to see the order.
 */
@Component("findOrderTool")
public class FindOrderToolHandler implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    public static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"orderId\"],\"properties\":{"
                    + "\"orderId\":{\"type\":\"string\",\"description\":\"Order id, e.g. ORD-1042.\"}"
                    + "}}";

    public static final String OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"orderId\":{\"type\":\"string\"},"
                    + "\"status\":{\"type\":\"string\"},"
                    + "\"items\":{\"type\":\"array\"},"
                    + "\"total\":{\"type\":\"number\"},"
                    + "\"not_found\":{\"type\":\"boolean\"}}}";

    private final MockOrderStore orders;

    public FindOrderToolHandler(MockOrderStore orders) {
        this.orders = orders;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String orderId = String.valueOf(input == null ? "" : input.getOrDefault("orderId", ""));
        return orders.find(orderId)
                .map(orders::asMap)
                .orElseGet(() -> Map.of(
                        "orderId", orderId,
                        "not_found", true));
    }
}
