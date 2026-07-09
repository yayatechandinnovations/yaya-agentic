package com.yayatechandinnovations.yayasales.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxRelay;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox → shopper's-chat bridge (design §11.3/§13) against a fake
 * yaya-agentic ingest edge: the enabled publisher POSTs the system-message
 * envelope with the sender credential + replay headers; guest orders are
 * skipped but the row still publishes; a 5xx keeps the row PENDING for the
 * at-least-once retry.
 */
@SpringBootTest
@Testcontainers
class AgentNotificationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_sales");

    /** Fake yaya-agentic ingest edge — records every request; can 500 on demand. */
    static final HttpServer AGENT;
    static final List<Recorded> RECEIVED = new CopyOnWriteArrayList<>();
    static volatile boolean failNext = false;

    record Recorded(String method, String path, Map<String, List<String>> headers, String body) {}

    static {
        try {
            AGENT = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        AGENT.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // sun's Headers normalizes key casing — copy with lowercase keys.
            Map<String, List<String>> headers = exchange.getRequestHeaders().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().toLowerCase(), e -> List.copyOf(e.getValue())));
            RECEIVED.add(new Recorded(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), headers, body));
            byte[] response;
            int status;
            if (failNext) {
                failNext = false;
                status = 500;
                response = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                response = ("{\"id\":\"om-1\",\"disposition\":\"DELIVERED\",\"sessionIds\":[],"
                        + "\"receivedAt\":\"" + Instant.now() + "\"}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        AGENT.start();
    }

    @AfterAll
    static void stopAgent() {
        AGENT.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.sales.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        // The IT drives the relay by hand for determinism.
        r.add("yaya.sales.outbox.poll-enabled", () -> "false");
        r.add("yaya.sales.notifications.enabled", () -> "true");
        r.add("yaya.sales.notifications.agent-base-url",
                () -> "http://localhost:" + AGENT.getAddress().getPort());
        r.add("yaya.sales.notifications.tenant-id", () -> "tenant-1");
        r.add("yaya.sales.notifications.sender-id", () -> "yaya-sales-notify");
        r.add("yaya.sales.notifications.sender-token", () -> "sender-secret-token");
    }

    @Autowired OutboxRelay outbox;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void drain() {
        // Order independence: no test observes another test's PENDING rows.
        outbox.publishPending();
        RECEIVED.clear();
        failNext = false;
    }

    @Test
    void postsOutForDeliveryToTheShoppersChatWithSenderAuth() throws Exception {
        StoreId store = newStore();
        String orderId = newOrder(store, "shopper-principal-7");
        appendEvent(store, "shipment", "sh-1", "shipment.out_for_delivery",
                "{\"shipmentId\":\"sh-1\",\"orderId\":\"" + orderId
                        + "\",\"status\":\"OUT_FOR_DELIVERY\"}");

        assertThat(outbox.publishPending()).isEqualTo(1);

        assertThat(RECEIVED).hasSize(1);
        Recorded request = RECEIVED.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path())
                .isEqualTo("/v1/integrations/tenant-1/senders/yaya-sales-notify/messages");
        assertThat(header(request, "Authorization")).isEqualTo("Bearer sender-secret-token");
        assertThat(header(request, "X-Nonce")).isNotBlank();
        assertThat(Long.parseLong(header(request, "X-Timestamp")))
                .isCloseTo(Instant.now().toEpochMilli(), org.assertj.core.data.Offset.offset(60_000L));

        JsonNode body = json.readTree(request.body());
        assertThat(body.path("recipient").path("kind").asText()).isEqualTo("PRINCIPAL");
        assertThat(body.path("recipient").path("value").asText()).isEqualTo("shopper-principal-7");
        String text = body.path("body").asText();
        assertThat(text).contains("out for delivery");
        assertThat(text).contains(orderId.replace("-", "").substring(0, 8));
        assertThat(body.path("dedupKey").asText()).startsWith("yaya-sales:outbox:");
        assertThat(body.path("correlation").path("originatorName").asText()).isEqualTo("yaya-sales");
        assertThat(body.path("correlation").path("originatorRef").asText())
                .isEqualTo("order:" + orderId);

        // Drained: the redelivery sweep republishes nothing.
        RECEIVED.clear();
        assertThat(outbox.publishPending()).isZero();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void guestOrderAndNonDeliveryEventsAreSkippedButStillPublished() {
        StoreId store = newStore();
        String guestOrder = newOrder(store, null);
        appendEvent(store, "order", guestOrder, "order.paid",
                "{\"orderId\":\"" + guestOrder + "\",\"grandTotalMinor\":5000,\"currency\":\"USD\"}");
        // A non-shopper event type must not throw either — the row publishes.
        appendEvent(store, "order", guestOrder, "cod.collected",
                "{\"orderId\":\"" + guestOrder + "\",\"amountMinor\":5000}");

        assertThat(outbox.publishPending()).isEqualTo(2);
        assertThat(RECEIVED).isEmpty();
        assertThat(pendingCount()).isZero();
    }

    @Test
    void agentErrorKeepsTheRowPendingAndRetrySucceeds() {
        StoreId store = newStore();
        String orderId = newOrder(store, "shopper-principal-9");
        appendEvent(store, "shipment", "sh-9", "shipment.delivered",
                "{\"shipmentId\":\"sh-9\",\"orderId\":\"" + orderId
                        + "\",\"status\":\"DELIVERED\"}");

        failNext = true;
        assertThat(outbox.publishPending()).isZero();      // 500 → the row stays PENDING
        assertThat(pendingCount()).isEqualTo(1);

        assertThat(outbox.publishPending()).isEqualTo(1);  // retry succeeds → PUBLISHED
        assertThat(pendingCount()).isZero();
        // The fake saw the failed attempt AND the successful retry (at-least-once).
        assertThat(RECEIVED).hasSize(2);
        assertThat(RECEIVED.get(1).body()).contains("shopper-principal-9");
    }

    /* ------------------------------ fixtures ------------------------------ */

    private StoreId newStore() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO yaya_sales.stores
                    (id, tenant_id, display_name, default_currency, policies_json, created_at)
                VALUES (?, 'tenant-1', 'Notify Test Store', 'USD', '{}'::jsonb, ?)
                """, id, Timestamp.from(Instant.now()));
        return new StoreId(id);
    }

    private String newOrder(StoreId store, String customerRef) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO yaya_sales.orders
                    (id, store_id, channel_id, region_id, customer_ref, currency, source,
                     lines_json, pricing_json, status, payment_state, fulfillment_state,
                     version, created_at)
                VALUES (?, ?, 'ch-1', 'rg-1', ?, 'USD', 'CHECKOUT',
                        '[]'::jsonb, '{}'::jsonb, 'PAID', 'PAID', 'UNFULFILLED', 0, ?)
                """, id, store.value(), customerRef, Timestamp.from(Instant.now()));
        return id;
    }

    private void appendEvent(StoreId store, String aggregate, String aggregateId,
                             String eventType, String payloadJson) {
        tx.executeWithoutResult(status ->
                outbox.append(store, aggregate, aggregateId, eventType, payloadJson));
    }

    private int pendingCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM yaya_sales.outbox WHERE status = 'PENDING'", Integer.class);
        return count == null ? 0 : count;
    }

    private static String header(Recorded request, String name) {
        List<String> values = request.headers().get(name.toLowerCase());
        return values == null || values.isEmpty() ? "" : values.get(0);
    }
}
