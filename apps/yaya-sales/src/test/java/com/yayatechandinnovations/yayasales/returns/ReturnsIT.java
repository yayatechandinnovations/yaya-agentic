package com.yayatechandinnovations.yayasales.returns;

import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.MovementKind;
import com.yayatechandinnovations.yayasales.orders.CheckoutService;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutCommand;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxEntity;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxRepository;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.returns.RmaView.Disposition;
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
 * §8.8 — the full return loop: an RMA authorized only against a paid order's
 * lines (over-quantity rejected, across RMAs), receive posting RETURN
 * movements ONLY for restockable units (damage never re-enters stock), the
 * refund defaulting to the returned value and landing as store credit
 * (order → PARTIALLY_REFUNDED), and refund-to-original working for a
 * PSP-paid (sandbox) order.
 */
@SpringBootTest
@Testcontainers
class ReturnsIT {

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

    @Autowired RmaService rmas;
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
    @Autowired OutboxRepository outbox;

    StoreId store;
    LocationId location;
    VariantId sku;

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef("tenant-s6-returns"), "Returns Store", "DOP",
                StorePolicies.defaults()).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Soap", null, "soap", null, null, null, ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("SOAP-1", "bar",
                                Money.of(10_000, "DOP"), null, null, null))));
        sku = product.variants().get(0).id();
        ledger.receive(sku, location, 10, Money.of(4_000, "DOP"), null);
    }

    private OrderView paidOrder(long qty, TenderKind tender) {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-1");
        carts.add(cart.id(), sku, qty);
        CheckoutOutcome outcome = checkout.checkout(new CheckoutCommand(cart.id(), tender,
                false, "https://shop.example/return", null, "shopper-1", Ids.newId()));
        OrderView order = switch (outcome) {
            case CheckoutOutcome.Placed placed -> placed.order();
            case CheckoutOutcome.HostedUrl hosted -> hosted.order();
            default -> throw new IllegalStateException("unexpected outcome " + outcome);
        };
        tenders.settle(tenders.tendersOf(order.id()).get(0).id(), "test:settle", null);
        return orders.order(order.id()).orElseThrow();
    }

    private long onHand() {
        return inventory.level(sku, location).orElseThrow().onHand();
    }

    private long movementSum() {
        return inventory.ledger(sku, Optional.of(location)).stream()
                .mapToLong(m -> m.qtyDelta()).sum();
    }

    // ── the S6 exit criterion: restock + store credit, auditable ────────────

    @Test
    void receiveRestocksResellableUnitsIssuesStoreCreditAndPartiallyRefundsTheOrder() {
        OrderView order = paidOrder(3, TenderKind.COD);   // grand 30 000, on-hand 7
        assertThat(onHand()).isEqualTo(7);

        // Over-quantity is rejected before anything exists.
        assertThatThrownBy(() -> rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 4)), "too many", "op:test"))
                .isInstanceOf(ApiErrors.UnprocessableException.class);

        RmaView rma = rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 2)), "wrong scent", "op:test");
        assertThat(rma.status()).isEqualTo(RmaStatus.REQUESTED);

        rmas.setReturnShipment(rma.id(), new RmaView.ReturnShipment("in-house", null,
                "Courier picks up Tuesday 2-4pm"));
        assertThat(rmas.authorize(rma.id(), "op:test").status()).isEqualTo(RmaStatus.AUTHORIZED);

        // One unit resellable, one damaged: only the RESTOCK re-enters stock.
        RmaView received = rmas.receive(rma.id(), new RmaService.ReceiveCommand(
                List.of(new Disposition(sku, 1, Disposition.Kind.RESTOCK),
                        new Disposition(sku, 1, Disposition.Kind.DAMAGE)),
                true, null), "op:test");

        assertThat(received.status()).isEqualTo(RmaStatus.CLOSED);
        assertThat(received.receivedAt()).isNotNull();
        assertThat(received.dispositions()).hasSize(2);
        assertThat(received.returnShipment().instructions()).contains("Tuesday");
        assertThat(onHand()).isEqualTo(8);                     // +1, not +2
        assertThat(movementSum()).isEqualTo(onHand());         // ledger ≡ summary (§14)
        assertThat(inventory.ledger(sku, Optional.of(location)).stream()
                .filter(m -> m.kind() == MovementKind.RETURN)).hasSize(1);

        // Refund defaulted to the returned units' paid value: 2 × 10 000.
        assertThat(storeCredit.balance(store, "shopper-1", "DOP"))
                .isEqualTo(Money.of(20_000, "DOP"));
        assertThat(orders.order(order.id()).orElseThrow().status())
                .isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(outbox.findAll().stream().map(OutboxEntity::getEventType))
                .contains("rma.received");
    }

    @Test
    void previousRmasGuardTheReturnableRemainder() {
        OrderView order = paidOrder(3, TenderKind.COD);
        RmaView first = rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 2)), "first", "op:test");

        // 2 of 3 already claimed — another 2 exceeds the remainder.
        assertThatThrownBy(() -> rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 2)), "second", "op:test"))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
        // The last unit is still returnable.
        assertThat(rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 1)), "second", "op:test").status())
                .isEqualTo(RmaStatus.REQUESTED);

        // A cancelled claim gives its quantity back to the pool.
        rmas.cancel(first.id(), "op:test");
        assertThat(rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 2)), "third", "op:test").status())
                .isEqualTo(RmaStatus.REQUESTED);
    }

    @Test
    void lifecycleGatesHold() {
        OrderView order = paidOrder(2, TenderKind.COD);
        RmaView rma = rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 1)), "gate test", "op:test");

        // Receive before authorization is a conflict.
        assertThatThrownBy(() -> rmas.receive(rma.id(), new RmaService.ReceiveCommand(
                List.of(new Disposition(sku, 1, Disposition.Kind.RESTOCK)), true, null), "op:test"))
                .isInstanceOf(ApiErrors.ConflictException.class);

        // A rejected RMA is terminal.
        rmas.reject(rma.id(), "outside the return window", "op:test");
        assertThatThrownBy(() -> rmas.authorize(rma.id(), "op:test"))
                .isInstanceOf(ApiErrors.ConflictException.class);

        // An unpaid order has nothing to return against.
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-1");
        carts.add(cart.id(), sku, 1);
        OrderView pending = ((CheckoutOutcome.Placed) checkout.checkout(new CheckoutCommand(
                cart.id(), TenderKind.COD, false, null, null, "shopper-1", Ids.newId()))).order();
        assertThatThrownBy(() -> rmas.request(store, pending.id(),
                List.of(new RmaView.Line(sku, 1)), "not paid", "op:test"))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
    }

    @Test
    void dispositionsCannotExceedTheAuthorizedQuantity() {
        OrderView order = paidOrder(2, TenderKind.COD);
        RmaView rma = rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 1)), "one back", "op:test");
        rmas.authorize(rma.id(), "op:test");

        assertThatThrownBy(() -> rmas.receive(rma.id(), new RmaService.ReceiveCommand(
                List.of(new Disposition(sku, 2, Disposition.Kind.RESTOCK)), true, null), "op:test"))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
        // The failed receive rolled back whole — still receivable.
        assertThat(rmas.rma(rma.id()).orElseThrow().status()).isEqualTo(RmaStatus.AUTHORIZED);
    }

    @Test
    void refundToTheOriginalPspTenderGoesThroughTheProvider() {
        OrderView order = paidOrder(2, TenderKind.PSP);   // sandbox authorize → settle

        RmaView rma = rmas.request(store, order.id(),
                List.of(new RmaView.Line(sku, 1)), "psp return", "op:test");
        rmas.authorize(rma.id(), "op:test");
        RmaView received = rmas.receive(rma.id(), new RmaService.ReceiveCommand(
                List.of(new Disposition(sku, 1, Disposition.Kind.RESTOCK)),
                false, null), "op:test");

        assertThat(received.status()).isEqualTo(RmaStatus.CLOSED);
        assertThat(received.refundRef()).startsWith("original_tender:");
        assertThat(tenders.refundedTotal(order.id(), "DOP"))
                .isEqualTo(Money.of(10_000, "DOP"));           // through the sandbox PSP
        assertThat(storeCredit.balance(store, "shopper-1", "DOP").isZero()).isTrue();
        assertThat(orders.order(order.id()).orElseThrow().status())
                .isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }
}
