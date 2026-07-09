package com.yayatechandinnovations.yayasales.orders;

import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.MovementKind;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutCommand;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
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

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §8.2/§14 — the checkout saga end to end: COD places without a PSP, on-hand
 * decrements via a SALE movement at the deduction point, retries re-attach,
 * compensation leaves no orphan reservation or phantom order, split tenders
 * balance exactly, and refunds route to store credit.
 */
@SpringBootTest
@Testcontainers
class CheckoutSagaIT {

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

    @Autowired CheckoutService checkout;
    @Autowired OrderService orders;
    @Autowired TenderService tenders;
    @Autowired StoreCreditService storeCredit;
    @Autowired CartService carts;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin inventory;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;

    StoreId store;
    LocationId location;
    VariantId sku;

    @BeforeEach
    void storefront() {
        newStore(StorePolicies.defaults());
    }

    private void newStore(StorePolicies policies) {
        store = storeService.create(new TenantRef("tenant-s3"), "Saga Store", "DOP", policies).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Soap", null, "soap", null, null, null, ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("SOAP-1", "bar",
                                Money.of(10_000, "DOP"), null, null, null))));
        sku = product.variants().get(0).id();
        ledger.receive(sku, location, 5, Money.of(4_000, "DOP"), null);
    }

    private CartView cartWith(long qty, String customer) {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), customer);
        carts.add(cart.id(), sku, qty);
        return cart;
    }

    private CheckoutCommand cod(CartView cart, String key) {
        return new CheckoutCommand(cart.id(), TenderKind.COD, false, null, null,
                "shopper-1", key);
    }

    private long onHand() {
        return inventory.level(sku, location).orElseThrow().onHand();
    }

    private long reserved() {
        return inventory.level(sku, location).orElseThrow().reserved();
    }

    // ── the S3 exit criterion: COD places without a PSP ─────────────────────

    @Test
    void codOrderPlacesSettlesAndDeductsThroughTheLedger() {
        CartView cart = cartWith(2, "shopper-1");

        CheckoutOutcome outcome = checkout.checkout(cod(cart, Ids.newId()));

        assertThat(outcome).isInstanceOf(CheckoutOutcome.Placed.class);
        OrderView order = ((CheckoutOutcome.Placed) outcome).order();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.totals().grand()).isEqualTo(Money.of(20_000, "DOP"));
        // Placement holds availability but moves no stock (§5.3).
        assertThat(onHand()).isEqualTo(5);
        assertThat(reserved()).isEqualTo(2);
        // The cart became the order.
        assertThat(carts.cart(cart.id()).orElseThrow().lines()).isEmpty();

        // Courier/merchant marks the cash collected → tender settles → PAID.
        TenderView codTender = tenders.tendersOf(order.id()).get(0);
        tenders.settle(codTender.id(), "courier:test", null);

        OrderView paid = orders.order(order.id()).orElseThrow();
        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(onHand()).isEqualTo(3);   // SALE movement at ON_PAYMENT deduction point
        assertThat(reserved()).isZero();
        List<?> sales = inventory.ledger(sku, Optional.of(location)).stream()
                .filter(m -> m.kind() == MovementKind.SALE).toList();
        assertThat(sales).hasSize(1);
    }

    @Test
    void codSettleIsIdempotent() {
        CartView cart = cartWith(1, "shopper-1");
        OrderView order = ((CheckoutOutcome.Placed) checkout.checkout(cod(cart, Ids.newId()))).order();
        TenderView tender = tenders.tendersOf(order.id()).get(0);

        tenders.settle(tender.id(), "courier:a", null);
        tenders.settle(tender.id(), "courier:a", null);   // retried collect — §14 single-settle

        assertThat(onHand()).isEqualTo(4);
        assertThat(inventory.ledger(sku, Optional.of(location)).stream()
                .filter(m -> m.kind() == MovementKind.SALE)).hasSize(1);
        assertThat(orders.order(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void retriedCheckoutReattachesToTheSameOrder() {
        CartView cart = cartWith(2, "shopper-1");
        String key = Ids.newId();

        OrderView first = ((CheckoutOutcome.Placed) checkout.checkout(cod(cart, key))).order();
        OrderView replay = ((CheckoutOutcome.Placed) checkout.checkout(cod(cart, key))).order();

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(orders.ordersOf(store, Optional.empty())).hasSize(1);
        assertThat(reserved()).isEqualTo(2);   // no double reservation
    }

    @Test
    void reusedKeyWithADifferentRequestIsRejected() {
        CartView cart = cartWith(1, "shopper-1");
        String key = Ids.newId();
        checkout.checkout(cod(cart, key));

        CartView other = cartWith(1, "shopper-1");
        assertThatThrownBy(() -> checkout.checkout(cod(other, key)))
                .isInstanceOf(ApiErrors.ConflictException.class);
    }

    // ── compensation (§14: no orphan reservation, no phantom order) ─────────

    @Test
    void failedAuthorizationCompensatesCompletely() {
        CartView cart = cartWith(2, "shopper-1");

        assertThatThrownBy(() -> checkout.checkout(new CheckoutCommand(cart.id(),
                TenderKind.PSP, false, "https://shop.example/return?sandbox=fail",
                null, "shopper-1", Ids.newId())))
                .isInstanceOf(ApiErrors.UnprocessableException.class);

        assertThat(reserved()).isZero();                       // holds released
        assertThat(onHand()).isEqualTo(5);                     // nothing sold
        List<OrderView> all = orders.ordersOf(store, Optional.empty());
        assertThat(all).singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(OrderStatus.CANCELLED));
        // The cart survives for a retry (its holds re-reserve on revalidation).
        assertThat(carts.cart(cart.id()).orElseThrow().lines()).hasSize(1);
    }

    @Test
    void lapsedHoldLosesToLiveStockAtRevalidation() {
        // Stock 1 total for this scenario.
        ledger.adjust(sku, location, -4, "narrow to one unit");
        CartView slow = cartWith(1, "slow-shopper");
        // The slow shopper's hold lapses: release it exactly as the sweeper
        // would (no time travel in tests) — availability recovers.
        carts.peek(slow.id()).lines().get(0).reservationIds()
                .forEach(id -> ledger.release(new Ids.ReservationId(id)));

        CartView fast = cartWith(1, "fast-shopper");
        CheckoutOutcome fastOutcome = checkout.checkout(cod(fast, Ids.newId()));
        assertThat(fastOutcome).isInstanceOf(CheckoutOutcome.Placed.class);

        CheckoutOutcome slowOutcome = checkout.checkout(cod(slow, Ids.newId()));
        assertThat(slowOutcome).isInstanceOfSatisfying(CheckoutOutcome.Insufficient.class,
                r -> assertThat(r.available()).isZero());
        assertThat(reserved()).isEqualTo(1);   // exactly the fast shopper's unit
    }

    // ── split tender + store credit (§8.3, §14 balance) ─────────────────────

    @Test
    void storeCreditSplitsWithCodAndBalancesExactly() {
        storeCredit.issue(store, "shopper-1", Money.of(6_000, "DOP"), "welcome", null);
        CartView cart = cartWith(1, "shopper-1");   // grand 10 000

        CheckoutOutcome outcome = checkout.checkout(new CheckoutCommand(cart.id(),
                TenderKind.COD, true, null, null, "shopper-1", Ids.newId()));

        OrderView order = ((CheckoutOutcome.Placed) outcome).order();
        List<TenderView> orderTenders = tenders.tendersOf(order.id());
        assertThat(orderTenders).hasSize(2);
        assertThat(orderTenders).anySatisfy(t -> {
            assertThat(t.kind()).isEqualTo(TenderKind.STORE_CREDIT);
            assertThat(t.amount()).isEqualTo(Money.of(6_000, "DOP"));
            assertThat(t.status()).isEqualTo(TenderView.Status.SETTLED);
        });
        assertThat(storeCredit.balance(store, "shopper-1", "DOP").isZero()).isTrue();
        assertThat(orders.order(order.id()).orElseThrow().paymentState())
                .isEqualTo(OrderView.PaymentState.PARTIALLY_SETTLED);

        // Courier collects the 4 000 remainder → settled sum == grand → PAID (§14).
        TenderView codTender = orderTenders.stream()
                .filter(t -> t.kind() == TenderKind.COD).findFirst().orElseThrow();
        assertThat(codTender.amount()).isEqualTo(Money.of(4_000, "DOP"));
        tenders.settle(codTender.id(), "courier:test", null);

        assertThat(orders.order(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(tenders.settledTotal(order.id(), "DOP")).isEqualTo(Money.of(10_000, "DOP"));
    }

    @Test
    void storeCreditCoveringEverythingPaysInstantly() {
        storeCredit.issue(store, "shopper-1", Money.of(50_000, "DOP"), "welcome", null);
        CartView cart = cartWith(2, "shopper-1");   // grand 20 000

        CheckoutOutcome outcome = checkout.checkout(new CheckoutCommand(cart.id(),
                TenderKind.COD, true, null, null, "shopper-1", Ids.newId()));

        assertThat(outcome).isInstanceOf(CheckoutOutcome.Paid.class);
        OrderView order = ((CheckoutOutcome.Paid) outcome).order();
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(onHand()).isEqualTo(3);   // SALE committed instantly
        assertThat(storeCredit.balance(store, "shopper-1", "DOP"))
                .isEqualTo(Money.of(30_000, "DOP"));
    }

    @Test
    void cancellingASplitOrderReissuesTheRedeemedCredit() {
        storeCredit.issue(store, "shopper-1", Money.of(6_000, "DOP"), "welcome", null);
        CartView cart = cartWith(1, "shopper-1");
        OrderView order = ((CheckoutOutcome.Placed) checkout.checkout(new CheckoutCommand(
                cart.id(), TenderKind.COD, true, null, null, "shopper-1", Ids.newId()))).order();

        orders.cancel(order.id(), "shopper-request");

        assertThat(orders.order(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reserved()).isZero();
        assertThat(storeCredit.balance(store, "shopper-1", "DOP"))
                .isEqualTo(Money.of(6_000, "DOP"));   // credit came back
    }

    // ── refunds (§8.3: store credit is the default refund target) ───────────

    @Test
    void refundsRouteToStoreCreditAndTrackPartiality() {
        CartView cart = cartWith(2, "shopper-1");
        OrderView order = ((CheckoutOutcome.Placed) checkout.checkout(cod(cart, Ids.newId()))).order();
        tenders.settle(tenders.tendersOf(order.id()).get(0).id(), "courier:test", null);

        OrderView partial = orders.refund(order.id(), Money.of(5_000, "DOP"), true, "op:test");
        assertThat(partial.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(storeCredit.balance(store, "shopper-1", "DOP"))
                .isEqualTo(Money.of(5_000, "DOP"));

        OrderView full = orders.refund(order.id(), null, true, "op:test");   // the remainder
        assertThat(full.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(storeCredit.balance(store, "shopper-1", "DOP"))
                .isEqualTo(Money.of(20_000, "DOP"));

        assertThatThrownBy(() -> orders.refund(order.id(), Money.of(1, "DOP"), true, "op:test"))
                .isInstanceOf(ApiErrors.UnprocessableException.class);   // nothing left to refund
    }

    // ── deduction point ON_FULFILLMENT (decision 2 override) ────────────────

    @Test
    void onFulfillmentStoresDeferTheSaleMovementToDispatch() {
        newStore(new StorePolicies(StorePolicies.DeductionPoint.ON_FULFILLMENT,
                StorePolicies.BackorderPolicy.DENY, 600, 1800));
        CartView cart = cartWith(1, "shopper-1");
        OrderView order = ((CheckoutOutcome.Placed) checkout.checkout(cod(cart, Ids.newId()))).order();

        tenders.settle(tenders.tendersOf(order.id()).get(0).id(), "courier:test", null);

        assertThat(orders.order(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(onHand()).isEqualTo(5);      // no SALE yet — commits at dispatch (S5)
        assertThat(reserved()).isEqualTo(1);    // the hold still guards availability
    }
}
