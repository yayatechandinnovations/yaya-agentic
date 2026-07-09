package com.yayatechandinnovations.yayasales.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryMethodKind;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryStatus;
import com.yayatechandinnovations.yayasales.fulfillment.RateRule;
import com.yayatechandinnovations.yayasales.fulfillment.Shipments;
import com.yayatechandinnovations.yayasales.fulfillment.ShippingConfig;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.orders.CheckoutService;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutCommand;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.stores.ChannelView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §6 row 14 / §7.5 / §14 — the reports reconcile EXACTLY to the ledgers they
 * project: sales = the paid orders' snapshots minus the refund ledger,
 * valuation = on-hand × moving-average cost, margin = revenue − COGS drawn at
 * each SALE movement, low-stock = the reorder-point view, and COD
 * reconciliation ties courier deliveries to settled COD tenders — flagging
 * cash without a delivery and deliveries without cash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReportingIT {

    static final Address DOORSTEP = new Address("Calle Duarte 12", null,
            "Santo Domingo", null, "10100", "DO");

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
        r.add("yaya.sales.delivery.otp-echo-enabled", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CheckoutService checkout;
    @Autowired OrderService orders;
    @Autowired TenderService tenders;
    @Autowired CartService carts;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin inventory;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;
    @Autowired ShippingConfig shipping;
    @Autowired Shipments shipments;
    @Autowired DeliveryFleet fleet;

    StoreId store;
    LocationId location;
    VariantId sku;

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef("tenant-s6-reports"), "Report Store", "DOP",
                StorePolicies.defaults()).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Coffee 1kg", null, "grocery", null, null, null,
                        ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("COF-1", "1kg",
                                Money.of(10_000, "DOP"), null, 1_000, null))));
        sku = product.variants().get(0).id();
        // Receipts at a KNOWN cost: 10 @ 4 000 → avg 4 000 (§7.5).
        ledger.receive(sku, location, 10, Money.of(4_000, "DOP"), null);
    }

    // ── sales / valuation / margin / low-stock ──────────────────────────────

    @Test
    void salesValuationMarginAndLowStockReconcileToTheLedgers() throws Exception {
        // One sale via checkout + settle: 2 × 10 000 revenue, 2 × 4 000 COGS.
        OrderView paid = codOrder(2, null, null);
        assertThat(paid.paymentState()).isEqualTo(OrderView.PaymentState.PAID);
        // One partial refund of 5 000 to store credit.
        orders.refund(paid.id(), Money.of(5_000, "DOP"), true, "op:test");

        JsonNode sales = report("/v1/sales/admin/reports/sales");
        assertThat(sales.get("ordersCount").asLong()).isEqualTo(1);
        assertThat(sales.get("grossMinor").asLong()).isEqualTo(20_000);
        assertThat(sales.get("refundsMinor").asLong()).isEqualTo(5_000);
        assertThat(sales.get("netMinor").asLong()).isEqualTo(15_000);
        assertThat(sales.get("currency").asText()).isEqualTo("DOP");

        // 8 left on hand at the 4 000 moving average.
        JsonNode valuation = report("/v1/sales/admin/reports/inventory-valuation");
        assertThat(valuation.get("totalMinor").asLong()).isEqualTo(8 * 4_000);
        assertThat(valuation.get("lines").get(0).get("onHand").asLong()).isEqualTo(8);
        assertThat(valuation.get("lines").get(0).get("avgCostMinor").asLong()).isEqualTo(4_000);

        // Margin = 20 000 revenue − 8 000 COGS (the SALE movements' drawn cost).
        JsonNode margin = report("/v1/sales/admin/reports/margin");
        assertThat(margin.get("revenueMinor").asLong()).isEqualTo(20_000);
        assertThat(margin.get("cogsMinor").asLong()).isEqualTo(8_000);
        assertThat(margin.get("marginMinor").asLong()).isEqualTo(12_000);

        // Low stock is the reorder-point view: available 8 ≤ point 10.
        inventory.setReorder(sku, location, 10, 20);
        JsonNode lowStock = report("/v1/sales/admin/reports/low-stock");
        assertThat(lowStock.get("levels")).hasSize(1);
        assertThat(lowStock.get("levels").get(0).get("sku").get("value").asText())
                .isEqualTo(sku.value());
    }

    // ── COD reconciliation (§8.3, §15: cash collected vs goods delivered) ───

    @Test
    void codReconciliationTracksCourierCashAndFlagsBothMismatchDirections() throws Exception {
        localDeliveryConfigured();
        DeliveryFleet.Credential courier = approvedCourier("+18095551000");

        // Matched: delivered AND collected — 22 000 (goods 20 000 + delivery 2 000).
        OrderView matched = codOrder(2, DOORSTEP, method);
        String matchedAssignment = assignedShipment(matched, courier.agent());
        fleet.updateStatus(new Ids.AssignmentId(matchedAssignment), courier.agent(),
                DeliveryStatus.OUT_FOR_DELIVERY, null);
        fleet.collectCod(new Ids.AssignmentId(matchedAssignment), courier.agent(), 22_000);
        fleet.updateStatus(new Ids.AssignmentId(matchedAssignment), courier.agent(),
                DeliveryStatus.DELIVERED, Map.of("signature", "J.P."));

        // Mismatch A: COD marked settled with NO delivered shipment anywhere.
        OrderView phantom = codOrder(1, null, null);   // settle without any shipment

        // Mismatch B: goods handed over, cash never marked collected.
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-3");
        carts.add(cart.id(), sku, 1);
        OrderView unpaidDelivery = ((CheckoutOutcome.Placed) checkout.checkout(new CheckoutCommand(
                cart.id(), TenderKind.COD, false, null, null, "shopper-3", Ids.newId(),
                DOORSTEP, method, null))).order();
        String looseAssignment = assignedShipment(unpaidDelivery, courier.agent());
        fleet.updateStatus(new Ids.AssignmentId(looseAssignment), courier.agent(),
                DeliveryStatus.DELIVERED, null);

        JsonNode report = report("/v1/sales/admin/reports/cod-reconciliation");

        JsonNode courierRow = report.get("couriers").get(0);
        assertThat(report.get("couriers")).hasSize(1);
        assertThat(courierRow.get("legalName").asText()).isEqualTo("Maria Gomez");
        assertThat(courierRow.get("ordersDelivered").asLong()).isEqualTo(2);
        assertThat(courierRow.get("codSettledMinor").asLong()).isEqualTo(22_000);

        JsonNode settledWithoutDelivery = report.get("mismatches").get("settledWithoutDelivery");
        assertThat(settledWithoutDelivery).hasSize(1);
        assertThat(settledWithoutDelivery.get(0).get("orderId").asText())
                .isEqualTo(phantom.id().value());
        assertThat(settledWithoutDelivery.get(0).get("amountMinor").asLong()).isEqualTo(10_000);

        JsonNode deliveredWithoutSettlement = report.get("mismatches")
                .get("deliveredWithoutSettlement");
        assertThat(deliveredWithoutSettlement).hasSize(1);
        assertThat(deliveredWithoutSettlement.get(0).get("orderId").asText())
                .isEqualTo(unpaidDelivery.id().value());
        assertThat(deliveredWithoutSettlement.get(0).get("amountMinor").asLong())
                .isEqualTo(12_000);
    }

    // ── seeding plumbing ─────────────────────────────────────────────────────

    DeliveryMethodId method;

    private void localDeliveryConfigured() {
        var zone = shipping.addZone(store, "Santo Domingo",
                List.of(new ShippingConfig.RegionMatch("DO", null, "10")));
        method = shipping.addMethod(store, DeliveryMethodKind.LOCAL_DELIVERY, "Own fleet").id();
        shipping.addRate(store, zone.id(), method, null, null,
                new RateRule.Flat(Money.of(2_000, "DOP")), Money.zero("DOP"),
                new ShippingConfig.TransitEstimate(0, 1));
    }

    /** COD checkout (optionally with local delivery) — settled unless a ship-to rides along. */
    private OrderView codOrder(long qty, Address shipTo, DeliveryMethodId deliveryMethod) {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-1");
        carts.add(cart.id(), sku, qty);
        OrderView order = ((CheckoutOutcome.Placed) checkout.checkout(new CheckoutCommand(
                cart.id(), TenderKind.COD, false, null, null, "shopper-1", Ids.newId(),
                shipTo, deliveryMethod, null))).order();
        if (shipTo == null) {
            tenders.settle(tenders.tendersOf(order.id()).get(0).id(), "op:manual-settle", null);
        }
        return orders.order(order.id()).orElseThrow();
    }

    /** Dispatch the order's single shipment and assign it to the courier. */
    private String assignedShipment(OrderView order, Ids.AgentId agent) {
        Shipments.ShipmentView shipment = shipments.create(order.id(), List.of(), null);
        shipments.pick(shipment.id(), "op:test");
        shipments.pack(shipment.id(), new Shipments.Parcel(1_000, 30, 20, 15), "op:test");
        shipments.dispatch(shipment.id(), null, null, "op:test");
        return fleet.assign(shipment.id(), Optional.of(agent), "op:test").id().value();
    }

    /** Invite → register → OTP → KYC docs → approval, all through the fleet API. */
    private DeliveryFleet.Credential approvedCourier(String phone) {
        var invite = fleet.mintInvite(store, "op:test", null);
        var registration = fleet.register(invite.token(), new DeliveryFleet.AgentRegistration(
                "Maria Gomez", DOORSTEP, phone,
                new DeliveryFleet.Vehicle("MOTORCYCLE", "Yamaha", "FZ", "M998877"),
                new DeliveryFleet.IdDocument("CEDULA", "001-1111111-2")));
        fleet.verifyPhone(phone, registration.otpEcho());
        fleet.submitDocuments(registration.agent(), List.of(
                new DeliveryFleet.DocumentUpload(DeliveryFleet.DocKind.PHOTO_ID_FRONT, "aWQ="),
                new DeliveryFleet.DocumentUpload(DeliveryFleet.DocKind.VEHICLE_REGISTRATION,
                        "dmVo")));
        return fleet.approve(registration.agent(), "op:test");
    }

    private JsonNode report(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)
                        .header("X-Yaya-Store-Id", store.value())
                        .with(jwt().jwt(j -> j.subject("op-1").claim("roles", List.of("owner")))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
