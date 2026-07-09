package com.yayatechandinnovations.yayasales.integration.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The outbox → shopper's-chat bridge (design §11.3, §13): post-purchase events
 * become system messages ingested through yaya-agentic's trusted-sender edge
 * ({@code system-messaging-design.md} §5), addressed {@code ByPrincipal} to the
 * order's {@code customerRef}.
 *
 * <p>At-least-once contract: a failed POST throws so the relay keeps the row
 * PENDING and retries; the {@code dedupKey} (stable per outbox row) makes the
 * redelivery idempotent on the agent side. Guest orders (no customerRef) and
 * non-shopper event types return without posting — the row must still be
 * marked PUBLISHED, so they never throw.
 */
public class AgentNotificationPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentNotificationPublisher.class);

    private final AgentNotificationProperties properties;
    // Provider, not a direct dependency: the relay materializes its publishers
    // at construction, and OrderService transitively needs the relay (Outbox) —
    // eager wiring here would close a bean cycle.
    private final ObjectProvider<OrderService> orders;
    private final ObjectMapper json;
    private final RestClient client;

    public AgentNotificationPublisher(AgentNotificationProperties properties,
                                      ObjectProvider<OrderService> orders, ObjectMapper json,
                                      RestClient.Builder clientBuilder) {
        this.properties = properties;
        this.orders = orders;
        this.json = json;
        this.client = clientBuilder.baseUrl(properties.agentBaseUrl()).build();
    }

    @Override
    public void publish(OutboxEvent event) {
        String message = messageFor(event);
        if (message == null) {
            return;
        }
        String customerRef = customerRefOf(event);
        if (customerRef == null || customerRef.isBlank()) {
            log.debug("outbox event {} ({}) has no resolvable customerRef — guest order, skipping",
                    event.id(), event.eventType());
            return;
        }
        ingest(event, customerRef, message);
    }

    /* --------------------------- event mapping --------------------------- */

    /** Short, human, storefront voice. Order id short-form only — never prices. */
    private String messageFor(OutboxEvent event) {
        String order = shortOrderId(event);
        return switch (event.eventType()) {
            case "order.paid" -> "✅ Your order #" + order + " is confirmed — we're getting it ready.";
            case "shipment.dispatched" -> "📦 Your order #" + order + " is on its way.";
            case "shipment.out_for_delivery" -> "🚚 Your order #" + order + " is out for delivery.";
            case "shipment.delivered" -> "🎉 Your order #" + order + " was delivered. Enjoy!";
            case "delivery.failed" -> "😕 We couldn't deliver your order #" + order
                    + " this time. We'll be in touch to arrange another attempt.";
            default -> null;
        };
    }

    private String shortOrderId(OutboxEvent event) {
        String orderId = orderIdOf(event);
        if (orderId == null) {
            return "unknown";
        }
        String bare = orderId.replace("-", "");
        return bare.length() <= 8 ? bare : bare.substring(0, 8);
    }

    private String orderIdOf(OutboxEvent event) {
        JsonNode payload = payloadOf(event);
        JsonNode orderId = payload.get("orderId");
        return orderId == null || orderId.isNull() ? null : orderId.asText();
    }

    /** Payload-carried customerRef wins; otherwise resolve via the order aggregate. */
    private String customerRefOf(OutboxEvent event) {
        JsonNode inline = payloadOf(event).get("customerRef");
        if (inline != null && !inline.isNull() && !inline.asText().isBlank()) {
            return inline.asText();
        }
        String orderId = orderIdOf(event);
        if (orderId == null) {
            return null;
        }
        return orders.getObject().order(new OrderId(orderId))
                .map(o -> o.customerRef())
                .orElse(null);
    }

    private JsonNode payloadOf(OutboxEvent event) {
        try {
            return json.readTree(event.payloadJson());
        } catch (Exception e) {
            // A malformed payload will never become parseable — skipping (and
            // letting the row publish) beats a poison-pill retry loop.
            log.warn("outbox event {} ({}) has unparseable payload — skipping notification",
                    event.id(), event.eventType());
            return json.createObjectNode();
        }
    }

    /* ----------------------------- ingest POST ---------------------------- */

    private void ingest(OutboxEvent event, String customerRef, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", Map.of("kind", "PRINCIPAL", "value", customerRef));
        body.put("body", message);
        body.put("correlation", Map.of(
                "originatorName", "yaya-sales",
                "originatorRef", "order:" + orderIdOf(event),
                "subject", "order " + shortOrderId(event)));
        // Stable per outbox row: an at-least-once redelivery dedupes agent-side.
        body.put("dedupKey", "yaya-sales:outbox:" + event.id());

        client.post()
                .uri("/v1/integrations/{tenantId}/senders/{senderId}/messages",
                        properties.tenantId(), properties.senderId())
                .header("Authorization", "Bearer " + properties.senderToken())
                .header("X-Nonce", UUID.randomUUID().toString())
                .header("X-Timestamp", String.valueOf(Instant.now().toEpochMilli()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();   // non-2xx throws → the row stays PENDING
        log.debug("delivered {} for order {} to the shopper's chat",
                event.eventType(), orderIdOf(event));
    }
}
