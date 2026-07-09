package com.yayatechandinnovations.yayasales.platform;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.idempotency.IdempotencyStore;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxPublisher;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S0 exit criteria (design §12.4): the app boots on its OWN {@code yaya_sales}
 * schema, the idempotency store replays instead of re-executing, and the
 * outbox commits atomically with the state change and relays at-least-once.
 */
@SpringBootTest
@Testcontainers
class PlatformIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_sales");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.sales.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        // The IT drives the relay by hand for determinism.
        r.add("yaya.sales.outbox.poll-enabled", () -> "false");
    }

    @TestConfiguration
    static class CapturingPublisherConfig {
        @Bean
        CapturingPublisher capturingPublisher() {
            return new CapturingPublisher();
        }
    }

    /** Registered publisher: captures deliveries, can fail the next one on demand. */
    static class CapturingPublisher implements OutboxPublisher {
        final List<OutboxEvent> received = new CopyOnWriteArrayList<>();
        volatile boolean failNext = false;

        @Override
        public void publish(OutboxEvent event) {
            if (failNext) {
                failNext = false;
                throw new RuntimeException("transient downstream failure");
            }
            received.add(event);
        }
    }

    @Autowired IdempotencyStore idempotency;
    @Autowired OutboxRelay outbox;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapturingPublisher publisher;

    @BeforeEach
    void drainOutbox() {
        // Order independence: no test observes another test's PENDING rows.
        outbox.publishPending();
        publisher.received.clear();
    }

    @Test
    void migratesIntoOwnSchemaOnly() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'yaya_sales' ORDER BY table_name
                """, String.class);

        assertThat(tables).contains("idempotency_keys", "outbox", "flyway_schema_history");

        Integer publicTables = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, Integer.class);
        assertThat(publicTables).isZero();
    }

    @Test
    void idempotencyReplaysTheFirstResponse() {
        StoreId store = new StoreId(Ids.newId());

        assertThat(idempotency.find(store, "chk-1")).isEmpty();
        idempotency.put(store, "chk-1", "hash-a", "{\"orderId\":\"o-1\"}");

        Optional<IdempotencyStore.Stored> replay = idempotency.find(store, "chk-1");
        assertThat(replay).isPresent();
        // jsonb round-trip normalizes whitespace; the *content* must replay exactly.
        assertThat(replay.get().responseJson()).isEqualToIgnoringWhitespace("{\"orderId\":\"o-1\"}");
        assertThat(replay.get().matches("hash-a")).isTrue();
        // Same key, different body — the caller bug the endpoint must 409 on.
        assertThat(replay.get().matches("hash-b")).isFalse();

        // Scoped per store: another store's identical key is a different entry.
        assertThat(idempotency.find(new StoreId(Ids.newId()), "chk-1")).isEmpty();
    }

    @Test
    void outboxAppendRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> outbox.append(new StoreId("s1"), "order", "o-1", "order.paid", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void outboxRelaysAtLeastOnceAndMarksPublished() {
        StoreId store = new StoreId(Ids.newId());
        tx.executeWithoutResult(status ->
                outbox.append(store, "order", "o-42", "order.paid", "{\"orderId\":\"o-42\"}"));

        int published = outbox.publishPending();

        assertThat(published).isEqualTo(1);
        assertThat(publisher.received).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("order.paid");
            assertThat(e.storeId()).isEqualTo(store.value());
            assertThat(e.aggregateId()).isEqualTo("o-42");
        });

        // Drained: a second sweep republishes nothing.
        publisher.received.clear();
        assertThat(outbox.publishPending()).isZero();
        assertThat(publisher.received).isEmpty();
    }

    @Test
    void failedPublishStaysPendingForRetry() {
        StoreId store = new StoreId(Ids.newId());
        tx.executeWithoutResult(status ->
                outbox.append(store, "shipment", "sh-1", "shipment.dispatched", "{}"));

        publisher.failNext = true;
        assertThat(outbox.publishPending()).isZero();      // attempt fails → row stays PENDING
        assertThat(publisher.received).isEmpty();

        assertThat(outbox.publishPending()).isEqualTo(1);  // retry succeeds → PUBLISHED
        assertThat(publisher.received).singleElement()
                .satisfies(e -> assertThat(e.eventType()).isEqualTo("shipment.dispatched"));
    }
}
