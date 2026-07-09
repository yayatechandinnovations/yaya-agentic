package com.yayatechandinnovations.yayasales.purchasing;

import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.MovementKind;
import com.yayatechandinnovations.yayasales.inventory.Ref;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxEntity;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxRepository;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.purchasing.PurchasingService.NewLine;
import com.yayatechandinnovations.yayasales.purchasing.PurchasingService.ReceiveLine;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §9/§7.5 — the stock-in loop: supplier CRUD, PO lifecycle, partial receipts
 * driving PARTIALLY_RECEIVED → RECEIVED by cumulative received-vs-ordered,
 * each receipt line posting a costed RECEIPT movement that moves the §7.5
 * moving average exactly, and the guards (over-receipt, receive on a
 * cancelled PO, foreign SKU) refusing cleanly.
 */
@SpringBootTest
@Testcontainers
class PurchasingIT {

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

    @Autowired PurchasingService purchasing;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin inventory;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;
    @Autowired OutboxRepository outbox;

    StoreId store;
    LocationId location;
    VariantId sku;
    SupplierView supplier;

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef("tenant-s6-purchasing"), "PO Store", "DOP",
                StorePolicies.defaults()).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Coffee 1kg", null, "grocery", null, null, null,
                        ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("COF-1", "1kg",
                                Money.of(10_000, "DOP"), null, 1_000, null))));
        sku = product.variants().get(0).id();
        supplier = purchasing.createSupplier(store, "Finca La Loma",
                Map.of("phone", "+18095550000", "email", "ventas@laloma.do"));
    }

    private PurchaseOrderView placedPo(long qty, long unitCostMinor) {
        PurchaseOrderView po = purchasing.create(store, supplier.id(), location,
                List.of(new NewLine(sku, qty, unitCostMinor)),
                Instant.now().plus(7, ChronoUnit.DAYS));
        return purchasing.place(po.id(), "op:test");
    }

    private long onHand() {
        return inventory.level(sku, location).orElseThrow().onHand();
    }

    private Money avgCost() {
        return inventory.level(sku, location).orElseThrow().avgCost();
    }

    // ── suppliers ────────────────────────────────────────────────────────────

    @Test
    void supplierCrudWorksAndDeleteIsGuardedByHistory() {
        assertThat(purchasing.suppliersOf(store))
                .extracting(SupplierView::name).contains("Finca La Loma");

        SupplierView renamed = purchasing.updateSupplier(supplier.id(), "Finca La Loma SRL",
                Map.of("phone", "+18095550001"));
        assertThat(renamed.name()).isEqualTo("Finca La Loma SRL");
        assertThat(renamed.contact()).containsEntry("phone", "+18095550001");

        placedPo(5, 4_000);
        assertThatThrownBy(() -> purchasing.deleteSupplier(supplier.id()))
                .isInstanceOf(ApiErrors.ConflictException.class);

        SupplierView disposable = purchasing.createSupplier(store, "One-off", null);
        purchasing.deleteSupplier(disposable.id());
        assertThat(purchasing.supplier(disposable.id())).isEmpty();
    }

    // ── the lifecycle + §7.5 moving average ──────────────────────────────────

    @Test
    void partialThenFullReceiveMovesStockAndTheMovingAverageExactly() {
        // Opening stock: 5 @ 4 000 → avg 4 000.
        ledger.receive(sku, location, 5, Money.of(4_000, "DOP"), null);
        assertThat(avgCost()).isEqualTo(Money.of(4_000, "DOP"));

        PurchaseOrderView po = placedPo(10, 6_000);
        assertThat(po.status()).isEqualTo(PurchaseOrderView.Status.PLACED);

        // Partial: 5 of 10 @ 6 000 → on-hand 10, avg (5·4000 + 5·6000)/10 = 5 000.
        PurchaseOrderView partial = purchasing.receive(po.id(),
                List.of(new ReceiveLine(sku, 5, null)), "op:test");
        assertThat(partial.status()).isEqualTo(PurchaseOrderView.Status.PARTIALLY_RECEIVED);
        assertThat(partial.lines().get(0).receivedQty()).isEqualTo(5);
        assertThat(onHand()).isEqualTo(10);
        assertThat(avgCost()).isEqualTo(Money.of(5_000, "DOP"));

        // Remainder at a corrected cost: 5 @ 8 000 → avg (10·5000 + 5·8000)/15 = 6 000.
        PurchaseOrderView full = purchasing.receive(po.id(),
                List.of(new ReceiveLine(sku, 5, 8_000L)), "op:test");
        assertThat(full.status()).isEqualTo(PurchaseOrderView.Status.RECEIVED);
        assertThat(full.lines().get(0).receivedQty()).isEqualTo(10);
        assertThat(full.receipts()).hasSize(2);
        assertThat(onHand()).isEqualTo(15);
        assertThat(avgCost()).isEqualTo(Money.of(6_000, "DOP"));

        // Each receipt line is a costed RECEIPT movement with PO provenance.
        assertThat(inventory.ledger(sku, Optional.of(location)).stream()
                .filter(m -> m.kind() == MovementKind.RECEIPT)
                .filter(m -> m.refType() == Ref.RefType.PURCHASE_ORDER
                        && po.id().value().equals(m.refId())))
                .hasSize(2);
        // stock.received fired from the ledger (§13).
        assertThat(outbox.findAll().stream().map(OutboxEntity::getEventType))
                .contains("stock.received");
    }

    @Test
    void receiptsAreGuardedAgainstOverReceiptAndForeignSkus() {
        PurchaseOrderView po = placedPo(4, 5_000);
        purchasing.receive(po.id(), List.of(new ReceiveLine(sku, 3, null)), "op:test");

        // 3 already in — 2 more would exceed the ordered 4.
        assertThatThrownBy(() -> purchasing.receive(po.id(),
                List.of(new ReceiveLine(sku, 2, null)), "op:test"))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
        assertThat(onHand()).isEqualTo(3);   // the refused receipt posted nothing

        var foreign = catalog.createProduct(
                catalog.catalogsOf(store).get(0).id(),
                new ProductDraft("Tea", null, "grocery", null, null, null, ProductStatus.ACTIVE,
                        null, List.of(new ProductDraft.VariantDraft("TEA-1", "box",
                                Money.of(5_000, "DOP"), null, null, null))));
        assertThatThrownBy(() -> purchasing.receive(po.id(),
                List.of(new ReceiveLine(foreign.variants().get(0).id(), 1, 1_000L)), "op:test"))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
    }

    @Test
    void lifecycleGatesHold() {
        PurchaseOrderView draft = purchasing.create(store, supplier.id(), location,
                List.of(new NewLine(sku, 2, 5_000)), null);

        // A DRAFT po is paperwork — receiving against it is a conflict.
        assertThatThrownBy(() -> purchasing.receive(draft.id(),
                List.of(new ReceiveLine(sku, 1, null)), "op:test"))
                .isInstanceOf(ApiErrors.ConflictException.class);

        // Cancel before goods arrive is fine…
        PurchaseOrderView cancelled = purchasing.cancel(draft.id(), "op:test");
        assertThat(cancelled.status()).isEqualTo(PurchaseOrderView.Status.CANCELLED);
        assertThatThrownBy(() -> purchasing.receive(cancelled.id(),
                List.of(new ReceiveLine(sku, 1, null)), "op:test"))
                .isInstanceOf(ApiErrors.ConflictException.class);

        // …but goods already received cannot be un-bought.
        PurchaseOrderView received = placedPo(1, 5_000);
        purchasing.receive(received.id(), List.of(new ReceiveLine(sku, 1, null)), "op:test");
        assertThatThrownBy(() -> purchasing.cancel(received.id(), "op:test"))
                .isInstanceOf(ApiErrors.ConflictException.class);

        assertThat(purchasing.purchaseOrdersOf(store,
                Optional.of(PurchaseOrderView.Status.CANCELLED))).hasSize(1);
    }
}
