package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.orders.CheckoutService;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutCommand;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.ChannelView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision 10 / §14 — no slot oversell: concurrent bookings on the last unit
 * of capacity yield exactly one winner (the same guarded pattern as stock);
 * book/release are idempotent per order; the checkout saga books the slot and
 * cancellation gives the capacity back.
 */
@SpringBootTest
@Testcontainers
class DeliverySlotsIT {

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

    @Autowired DeliverySlots slots;
    @Autowired ShippingConfig shipping;
    @Autowired CheckoutService checkout;
    @Autowired OrderService orders;
    @Autowired CartService carts;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin inventory;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;

    static final Address CITY = new Address("Calle 1", null, "Santo Domingo", null, "10100", "DO");

    StoreId store;
    LocationId location;
    VariantId sku;
    DeliveryMethodId method;

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef("tenant-slots"), "Slot Store", "DOP",
                StorePolicies.defaults()).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Cake", null, "food", null, null, null, ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("CAKE-1", "whole",
                                Money.of(15_000, "DOP"), null, 1_000, null))));
        sku = product.variants().get(0).id();
        ledger.receive(sku, location, 50, Money.of(6_000, "DOP"), null);

        var zone = shipping.addZone(store, "City",
                List.of(new ShippingConfig.RegionMatch("DO", null, null)));
        method = shipping.addMethod(store, DeliveryMethodKind.LOCAL_DELIVERY, "Same day").id();
        shipping.addRate(store, zone.id(), method, null, null,
                new RateRule.Flat(Money.of(1_000, "DOP")), Money.zero("DOP"),
                new ShippingConfig.TransitEstimate(0, 1));
    }

    private SlotId slotWithCapacity(int capacity) {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return shipping.addSlot(store, location, method, start, start.plus(2, ChronoUnit.HOURS),
                capacity).id();
    }

    // ── §14: no slot oversell ────────────────────────────────────────────────

    @Test
    void concurrentBookingsOnTheLastSlotYieldExactlyOneWinner() throws Exception {
        SlotId slot = slotWithCapacity(1);

        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger booked = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        for (int i = 0; i < contenders; i++) {
            String orderId = "order-" + i;
            pool.submit(() -> {
                start.await();
                try {
                    slots.book(slot, new OrderId(orderId));
                    booked.incrementAndGet();
                } catch (ApiErrors.ConflictException e) {
                    refused.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(booked.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(contenders - 1);
        assertThat(available()).isEmpty();   // fully booked
    }

    @Test
    void bookAndReleaseAreIdempotentPerOrder() {
        SlotId slot = slotWithCapacity(2);
        OrderId order = new OrderId("order-idem");

        slots.book(slot, order);
        slots.book(slot, order);   // a retried checkout re-attaches
        assertThat(bookedOf(slot)).isEqualTo(1);

        slots.release(slot, order);
        slots.release(slot, order);   // double compensation releases once
        assertThat(bookedOf(slot)).isEqualTo(0);

        slots.release(slot, new OrderId("never-booked"));   // no-op, never negative
        assertThat(bookedOf(slot)).isEqualTo(0);
    }

    // ── the saga integration (§8.2 steps 2b + comp) ──────────────────────────

    @Test
    void checkoutBooksTheSlotAndCancellationReleasesIt() {
        SlotId slot = slotWithCapacity(1);
        CartView cart = cartWith(1);

        CheckoutOutcome outcome = checkout.checkout(command(cart, slot));
        OrderView order = ((CheckoutOutcome.Placed) outcome).order();
        assertThat(order.delivery().slot()).isEqualTo(slot);
        assertThat(bookedOf(slot)).isEqualTo(1);

        // The slot is full: the next shopper's checkout fails AND compensates —
        // cancelled order, released holds, no capacity leak.
        CartView late = cartWith(1);
        assertThatThrownBy(() -> checkout.checkout(command(late, slot)))
                .isInstanceOf(ApiErrors.ConflictException.class);
        List<OrderView> all = orders.ordersOf(store, Optional.empty());
        assertThat(all).hasSize(2);
        assertThat(all).anySatisfy(o ->
                assertThat(o.status()).isEqualTo(com.yayatechandinnovations.yayasales.orders.OrderStatus.CANCELLED));
        assertThat(reserved()).isEqualTo(1);   // only the winner's unit stays held

        // Cancelling the winner gives the capacity back.
        orders.cancel(order.id(), "shopper-request");
        assertThat(bookedOf(slot)).isEqualTo(0);
        assertThat(reserved()).isZero();
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private CheckoutCommand command(CartView cart, SlotId slot) {
        return new CheckoutCommand(cart.id(), TenderKind.COD, false, null, null,
                "shopper-1", Ids.newId(), CITY, method, slot);
    }

    private CartView cartWith(long qty) {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-1");
        carts.add(cart.id(), sku, qty);
        return cart;
    }

    private List<DeliverySlots.SlotView> available() {
        return slots.available(store, method, location, Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS));
    }

    private int bookedOf(SlotId slot) {
        return available().stream()
                .filter(s -> s.id().equals(slot))
                .map(DeliverySlots.SlotView::booked)
                .findFirst()
                // full slots drop out of available(); a capacity-1 slot that
                // vanished is booked exactly to capacity
                .orElse(1);
    }

    private long reserved() {
        return inventory.level(sku, location).orElseThrow().reserved();
    }
}
