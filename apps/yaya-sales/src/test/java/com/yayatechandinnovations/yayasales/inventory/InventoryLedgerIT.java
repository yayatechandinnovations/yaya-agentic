package com.yayatechandinnovations.yayasales.inventory;

import com.yayatechandinnovations.yayasales.inventory.internal.ReservationSweeper;
import com.yayatechandinnovations.yayasales.inventory.internal.StockMovementRepository;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.MovementId;
import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxRepository;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.LocationView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The §14 correctness bar for the core module: no oversell under concurrency,
 * ledger ≡ summary always, soft-hold expiry + checkout revalidation, exactly
 * one SALE per commit, moving-average valuation.
 */
@SpringBootTest
@Testcontainers
class InventoryLedgerIT {

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
        r.add("yaya.sales.outbox.poll-enabled", () -> "false");
        r.add("yaya.sales.inventory.sweep-enabled", () -> "false");
    }

    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin admin;
    @Autowired ReservationSweeper sweeper;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;
    @Autowired StockMovementRepository movements;
    @Autowired OutboxRepository outbox;

    StoreId store;
    LocationId location;

    @BeforeEach
    void newStore() {
        StoreView created = storeService.create(new TenantRef("tenant-t"), "IT Store", "USD",
                StorePolicies.defaults());
        store = created.id();
        location = storeDirectory.locationsOf(store).get(0).id();
    }

    private VariantId sku() {
        return new VariantId(Ids.newId());
    }

    // ── receive / valuation / adjust / transfer ─────────────────────────────

    @Test
    void receiveBuildsOnHandFromTheLedgerWithMovingAverageCost() {
        VariantId sku = sku();

        ledger.receive(sku, location, 10, Money.of(500, "USD"), null);
        ledger.receive(sku, location, 10, Money.of(700, "USD"), null);

        StockLevel level = admin.level(sku, location).orElseThrow();
        assertThat(level.onHand()).isEqualTo(20);
        assertThat(level.available()).isEqualTo(20);
        // (10·500 + 10·700) / 20 = 600
        assertThat(level.avgCost()).isEqualTo(Money.of(600, "USD"));

        assertThat(admin.ledger(sku, java.util.Optional.of(location)))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.kind()).isEqualTo(MovementKind.RECEIPT));
        assertLedgerMatchesSummary(sku);
    }

    @Test
    void adjustRequiresAReasonAndMovesOnHand() {
        VariantId sku = sku();
        ledger.receive(sku, location, 5, Money.of(100, "USD"), null);

        ledger.adjust(sku, location, -2, "cycle count: two missing");
        assertThat(admin.level(sku, location).orElseThrow().onHand()).isEqualTo(3);

        assertThatThrownBy(() -> ledger.adjust(sku, location, -1, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertLedgerMatchesSummary(sku);
    }

    @Test
    void transferMovesStockAndCostBetweenLocations() {
        VariantId sku = sku();
        LocationId second = storeService.addLocation(store, "Shop", LocationView.Kind.SHOP, true).id();
        ledger.receive(sku, location, 10, Money.of(400, "USD"), null);

        List<MovementId> ids = ledger.transfer(sku, location, second, 4);

        assertThat(ids).hasSize(2);
        assertThat(admin.level(sku, location).orElseThrow().onHand()).isEqualTo(6);
        StockLevel dest = admin.level(sku, second).orElseThrow();
        assertThat(dest.onHand()).isEqualTo(4);
        assertThat(dest.avgCost()).isEqualTo(Money.of(400, "USD"));
        assertLedgerMatchesSummary(sku);

        assertThatThrownBy(() -> ledger.transfer(sku, location, second, 100))
                .isInstanceOf(ApiErrors.ConflictException.class);
    }

    // ── reserve / commit / release (§5.2, §5.3) ──────────────────────────────

    @Test
    void reserveHoldsAvailabilityNotOnHand() {
        VariantId sku = sku();
        ledger.receive(sku, location, 5, Money.of(100, "USD"), null);

        ReserveResult result = ledger.reserve(sku, location, 2, ReservationKind.SOFT,
                Ref.cart("cart-1"), Duration.ofMinutes(10));

        assertThat(result).isInstanceOf(ReserveResult.Held.class);
        StockLevel level = admin.level(sku, location).orElseThrow();
        assertThat(level.onHand()).isEqualTo(5);
        assertThat(level.reserved()).isEqualTo(2);
        assertThat(level.available()).isEqualTo(3);
    }

    @Test
    void insufficientStockReportsWhatIsAvailable() {
        VariantId sku = sku();
        ledger.receive(sku, location, 2, Money.of(100, "USD"), null);

        ReserveResult result = ledger.reserve(sku, location, 5, ReservationKind.SOFT,
                Ref.cart("cart-1"), Duration.ofMinutes(10));

        assertThat(result).isInstanceOfSatisfying(ReserveResult.Insufficient.class,
                r -> assertThat(r.available()).isEqualTo(2));
    }

    @Test
    void backorderAllowPermitsNegativeAvailability() {
        VariantId sku = sku();
        ledger.receive(sku, location, 1, Money.of(100, "USD"), null);
        admin.setBackorderPolicy(sku, location, StorePolicies.BackorderPolicy.ALLOW);

        ReserveResult result = ledger.reserve(sku, location, 3, ReservationKind.SOFT,
                Ref.cart("cart-1"), Duration.ofMinutes(10));

        assertThat(result).isInstanceOf(ReserveResult.Held.class);
        assertThat(admin.level(sku, location).orElseThrow().available()).isEqualTo(-2);
    }

    @Test
    void commitPostsExactlyOneSaleMovementAndIsIdempotent() {
        VariantId sku = sku();
        ledger.receive(sku, location, 5, Money.of(300, "USD"), null);
        ReserveResult.Held held = (ReserveResult.Held) ledger.reserve(sku, location, 2,
                ReservationKind.FIRM, Ref.order("order-9"), Duration.ofMinutes(30));

        MovementId first = ledger.commit(held.id());
        MovementId replay = ledger.commit(held.id());   // retried settle — §14 single-settle

        assertThat(replay).isEqualTo(first);
        StockLevel level = admin.level(sku, location).orElseThrow();
        assertThat(level.onHand()).isEqualTo(3);
        assertThat(level.reserved()).isZero();

        List<MovementView> sales = admin.ledger(sku, java.util.Optional.of(location)).stream()
                .filter(m -> m.kind() == MovementKind.SALE).toList();
        assertThat(sales).hasSize(1);
        assertThat(sales.get(0).qtyDelta()).isEqualTo(-2);
        assertThat(sales.get(0).unitCost()).isEqualTo(Money.of(300, "USD"));   // COGS at avg cost
        assertThat(sales.get(0).refId()).isEqualTo("order-9");
        assertLedgerMatchesSummary(sku);
    }

    @Test
    void releaseRecoversAvailabilityAndIsIdempotent() {
        VariantId sku = sku();
        ledger.receive(sku, location, 5, Money.of(100, "USD"), null);
        ReserveResult.Held held = (ReserveResult.Held) ledger.reserve(sku, location, 4,
                ReservationKind.SOFT, Ref.cart("cart-2"), Duration.ofMinutes(10));

        ledger.release(held.id());
        ledger.release(held.id());   // no double give-back

        StockLevel level = admin.level(sku, location).orElseThrow();
        assertThat(level.reserved()).isZero();
        assertThat(level.available()).isEqualTo(5);
    }

    // ── the single hottest correctness path (§5.2, §14 no-oversell) ─────────

    @Test
    void concurrentReserversNeverOversellTheLastUnit() throws Exception {
        VariantId sku = sku();
        ledger.receive(sku, location, 1, Money.of(100, "USD"), null);

        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger held = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        for (int i = 0; i < contenders; i++) {
            pool.submit(() -> {
                start.await();
                ReserveResult result = ledger.reserve(sku, location, 1, ReservationKind.FIRM,
                        Ref.cart("race"), Duration.ofMinutes(5));
                if (result instanceof ReserveResult.Held) held.incrementAndGet();
                else insufficient.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(held.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(contenders - 1);
        assertThat(admin.level(sku, location).orElseThrow().available()).isZero();
        assertLedgerMatchesSummary(sku);
    }

    // ── soft holds, sweep, revalidation (decision 6, §8.2 step 2) ───────────

    @Test
    void expiredSoftHoldsAreSweptAndAvailabilityRecovers() {
        VariantId sku = sku();
        ledger.receive(sku, location, 3, Money.of(100, "USD"), null);
        ledger.reserve(sku, location, 2, ReservationKind.SOFT, Ref.cart("abandoned"),
                Duration.ofMillis(-1));   // already lapsed

        int swept = sweeper.sweepExpired();

        assertThat(swept).isGreaterThanOrEqualTo(1);
        assertThat(admin.level(sku, location).orElseThrow().available()).isEqualTo(3);
    }

    @Test
    void revalidatePromotesALiveHoldToFirm() {
        VariantId sku = sku();
        ledger.receive(sku, location, 3, Money.of(100, "USD"), null);
        ReserveResult.Held soft = (ReserveResult.Held) ledger.reserve(sku, location, 1,
                ReservationKind.SOFT, Ref.cart("c1"), Duration.ofMinutes(10));

        ReserveResult result = ledger.revalidate(soft.id(), Duration.ofMinutes(30));

        assertThat(result).isInstanceOfSatisfying(ReserveResult.Held.class, r -> {
            assertThat(r.id()).isEqualTo(soft.id());              // same hold stands
            assertThat(r.expiresAt()).isAfter(soft.expiresAt());  // extended to checkout TTL
        });
        assertThat(admin.level(sku, location).orElseThrow().reserved()).isEqualTo(1);
    }

    @Test
    void revalidateReReservesALapsedHoldAgainstLiveStock() {
        VariantId sku = sku();
        ledger.receive(sku, location, 2, Money.of(100, "USD"), null);
        ReserveResult.Held lapsed = (ReserveResult.Held) ledger.reserve(sku, location, 1,
                ReservationKind.SOFT, Ref.cart("slow-shopper"), Duration.ofMillis(-1));

        ReserveResult result = ledger.revalidate(lapsed.id(), Duration.ofMinutes(30));

        assertThat(result).isInstanceOfSatisfying(ReserveResult.Held.class,
                r -> assertThat(r.id()).isNotEqualTo(lapsed.id()));   // a NEW firm hold
        assertThat(admin.level(sku, location).orElseThrow().reserved()).isEqualTo(1);
    }

    @Test
    void revalidateFailsWhenTheStockIsGone() {
        VariantId sku = sku();
        ledger.receive(sku, location, 1, Money.of(100, "USD"), null);
        ReserveResult.Held lapsed = (ReserveResult.Held) ledger.reserve(sku, location, 1,
                ReservationKind.SOFT, Ref.cart("slow"), Duration.ofMillis(-1));
        sweeper.sweepExpired();
        // Someone else takes the last unit while the shopper dawdled.
        ledger.reserve(sku, location, 1, ReservationKind.FIRM, Ref.cart("fast"), Duration.ofMinutes(30));

        ReserveResult result = ledger.revalidate(lapsed.id(), Duration.ofMinutes(30));

        assertThat(result).isInstanceOfSatisfying(ReserveResult.Insufficient.class,
                r -> assertThat(r.available()).isZero());
    }

    // ── reorder signal + randomized ledger ≡ summary ────────────────────────

    @Test
    void crossingTheReorderPointEmitsStockLow() {
        VariantId sku = sku();
        ledger.receive(sku, location, 10, Money.of(100, "USD"), null);
        admin.setReorder(sku, location, 3, 20);
        long eventsBefore = outbox.count();

        ledger.adjust(sku, location, -7, "sold at POS, syncing");   // available 10 → 3 (== point)

        assertThat(outbox.count()).isEqualTo(eventsBefore + 1);

        // Falling further does not re-fire (edge-triggered, not spammy).
        ledger.adjust(sku, location, -1, "again");
        assertThat(outbox.count()).isEqualTo(eventsBefore + 1);
    }

    @Test
    void ledgerEqualsSummaryAfterARandomizedMovementStorm() {
        VariantId sku = sku();
        Random rng = new Random(42);
        long expectedOnHand = 0;

        for (int i = 0; i < 40; i++) {
            switch (rng.nextInt(3)) {
                case 0 -> {
                    int qty = rng.nextInt(20) + 1;
                    ledger.receive(sku, location, qty, Money.of(rng.nextInt(900) + 100L, "USD"), null);
                    expectedOnHand += qty;
                }
                case 1 -> {
                    int qty = rng.nextInt(5) + 1;
                    ledger.restock(sku, location, qty, null);
                    expectedOnHand += qty;
                }
                default -> {
                    long available = admin.level(sku, location).map(StockLevel::available).orElse(0L);
                    if (available > 0) {
                        long qty = rng.nextLong(available) + 1;
                        ledger.adjust(sku, location, -qty, "storm");
                        expectedOnHand -= qty;
                    }
                }
            }
        }

        StockLevel level = admin.level(sku, location).orElseThrow();
        assertThat(level.onHand()).isEqualTo(expectedOnHand);
        assertLedgerMatchesSummary(sku);
    }

    private void assertLedgerMatchesSummary(VariantId sku) {
        for (LocationView loc : storeDirectory.locationsOf(store)) {
            long summaryOnHand = admin.level(sku, loc.id()).map(StockLevel::onHand).orElse(0L);
            long ledgerSum = movements.sumDeltas(sku.value(), loc.id().value());
            assertThat(summaryOnHand)
                    .as("ledger ≡ summary for %s @ %s", sku.value(), loc.label())
                    .isEqualTo(ledgerSum);
        }
    }
}
