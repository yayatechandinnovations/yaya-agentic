package com.yayatechandinnovations.yayasales.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.orders.CheckoutService;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutCommand;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §8.7/§10.3/§14 — the carrier tracking webhook: signature-verified first,
 * idempotent on the carrier's event id (a replay appends ONE TrackingEvent and
 * advances DeliveryStatus once), and monotonic (a late lower-rank event never
 * walks the shipment backwards).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CarrierWebhookIT {

    static final String SECRET = "carrier-webhook-secret-0001";

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ShippingConfig shipping;
    @Autowired Shipments shipments;
    @Autowired CheckoutService checkout;
    @Autowired CartService carts;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;

    StoreId store;
    CarrierId carrier;
    Shipments.ShipmentView shipment;   // DISPATCHED, tracking TRK-1

    @BeforeEach
    void dispatchedShipment() {
        store = storeService.create(new TenantRef("tenant-carrier"), "Carrier Store", "DOP",
                StorePolicies.defaults()).id();
        LocationId location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Lamp", null, "decor", null, null, null, ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("LAMP-1", "std",
                                Money.of(30_000, "DOP"), null, 1_500, null))));
        VariantId sku = product.variants().get(0).id();
        ledger.receive(sku, location, 5, Money.of(9_000, "DOP"), null);

        carrier = shipping.addCarrier(store, "MANUAL", "Hand courier", SECRET).id();
        var zone = shipping.addZone(store, "DO",
                List.of(new ShippingConfig.RegionMatch("DO", null, null)));
        var method = shipping.addMethod(store, DeliveryMethodKind.SHIPPING, "Standard");
        shipping.addRate(store, zone.id(), method.id(), carrier, null,
                new RateRule.Flat(Money.of(4_000, "DOP")), Money.zero("DOP"),
                new ShippingConfig.TransitEstimate(1, 3));

        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-1");
        carts.add(cart.id(), sku, 1);
        CheckoutOutcome outcome = checkout.checkout(new CheckoutCommand(cart.id(), TenderKind.COD,
                false, null, null, "shopper-1", Ids.newId(),
                new Address("Calle 2", null, "Santiago", null, "51000", "DO"), method.id(), null));
        OrderView order = ((CheckoutOutcome.Placed) outcome).order();

        Shipments.ShipmentView draft = shipments.create(order.id(), List.of(), null);
        shipments.pick(draft.id(), "op:test");
        shipments.pack(draft.id(), new Shipments.Parcel(1_500, 30, 20, 20), "op:test");
        shipment = shipments.dispatch(draft.id(), "TRK-1", "https://track.example/TRK-1", "op:test");
        assertThat(shipment.deliveryStatus()).isEqualTo(DeliveryStatus.LABEL_CREATED);
    }

    @Test
    void replayedCarrierEventAppendsOneTrackingEventAndAdvancesOnce() throws Exception {
        String body = event("evt-1", "IN_TRANSIT", "left the depot");

        mvc.perform(signed(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(signed(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        List<Shipments.TrackingEventView> timeline = shipments.timelineOf(shipment.id());
        assertThat(timeline).filteredOn(e -> e.status() == DeliveryStatus.IN_TRANSIT).hasSize(1);
        assertThat(shipments.shipment(shipment.id()).orElseThrow().deliveryStatus())
                .isEqualTo(DeliveryStatus.IN_TRANSIT);
    }

    @Test
    void aLateLowerRankEventNeverRegressesTheShipment() throws Exception {
        mvc.perform(signed(event("evt-a", "OUT_FOR_DELIVERY", null))).andExpect(status().isOk());
        // A fresh event id carrying an already-passed status: acked as stale, no event.
        mvc.perform(signed(event("evt-b", "IN_TRANSIT", "late scan"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stale"));

        assertThat(shipments.shipment(shipment.id()).orElseThrow().deliveryStatus())
                .isEqualTo(DeliveryStatus.OUT_FOR_DELIVERY);
        assertThat(shipments.timelineOf(shipment.id()))
                .noneMatch(e -> e.status() == DeliveryStatus.IN_TRANSIT);
    }

    @Test
    void badSignatureIsRejectedAndProcessesNothing() throws Exception {
        String body = event("evt-x", "IN_TRANSIT", null);
        mvc.perform(post("/v1/sales/webhooks/carrier/" + carrier.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Carrier-Signature", "deadbeef")
                        .content(body))
                .andExpect(status().isForbidden());
        assertThat(shipments.shipment(shipment.id()).orElseThrow().deliveryStatus())
                .isEqualTo(DeliveryStatus.LABEL_CREATED);
    }

    @Test
    void unknownCarrierIs404AndMissingSecretIs503() throws Exception {
        mvc.perform(post("/v1/sales/webhooks/carrier/no-such-carrier")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        CarrierId unconfigured = shipping.addCarrier(store, "MANUAL", "No webhook", null).id();
        mvc.perform(post("/v1/sales/webhooks/carrier/" + unconfigured.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private String event(String eventId, String status, String description) throws Exception {
        var node = json.createObjectNode()
                .put("eventId", eventId)
                .put("trackingNumber", "TRK-1")
                .put("status", status);
        if (description != null) {
            node.put("description", description);
        }
        return json.writeValueAsString(node);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(String body) {
        return post("/v1/sales/webhooks/carrier/" + carrier.value())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Carrier-Signature", hmacHex(SECRET, body))
                .content(body);
    }

    private static String hmacHex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
