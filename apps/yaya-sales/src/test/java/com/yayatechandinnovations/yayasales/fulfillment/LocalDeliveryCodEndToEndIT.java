package com.yayatechandinnovations.yayasales.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxEntity;
import com.yayatechandinnovations.yayasales.platform.outbox.OutboxRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The S5 exit criterion (§12.4), end to end and fully offline: a COD +
 * LOCAL_DELIVERY checkout with a slot places PENDING_PAYMENT; the shipment
 * pick/pack/dispatches (ON_FULFILLMENT: the SALE movements commit at dispatch,
 * ledger ≡ summary); a courier self-registers → OTP → KYC docs → merchant
 * approval; the approved courier accepts the assignment, goes out for
 * delivery, collects the cash — the tender settles and the order pays EXACTLY
 * once (a retried collect is a no-op) — and marks DELIVERED with proof; the
 * outbox carries the whole §13 story. A courier from another store can touch
 * none of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LocalDeliveryCodEndToEndIT {

    static final byte[] SERVICE_SECRET = "e2e-service-secret-000000000000001".getBytes(StandardCharsets.UTF_8);
    static final String TENANT = "tenant-e2e";
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
        r.add("yaya.sales.auth.service-secrets." + TENANT,
                () -> Base64.getEncoder().encodeToString(SERVICE_SECRET));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ShippingConfig shipping;
    @Autowired Shipments shipments;
    @Autowired DeliveryFleet fleet;
    @Autowired CheckoutService checkout;
    @Autowired OrderService orders;
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
    DeliveryMethodId method;
    SlotId slot;

    @BeforeEach
    void storefront() {
        // The deduction-point override (decision 2): stock leaves at DISPATCH.
        store = storeService.create(new TenantRef(TENANT), "E2E Store", "DOP",
                new StorePolicies(StorePolicies.DeductionPoint.ON_FULFILLMENT,
                        StorePolicies.BackorderPolicy.DENY, 600, 1800)).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Coffee 1kg", null, "grocery", null, null, null,
                        ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("COF-1", "1kg",
                                Money.of(10_000, "DOP"), null, 1_000, null))));
        sku = product.variants().get(0).id();
        ledger.receive(sku, location, 5, Money.of(4_000, "DOP"), null);

        var zone = shipping.addZone(store, "Santo Domingo",
                List.of(new ShippingConfig.RegionMatch("DO", null, "10")));
        method = shipping.addMethod(store, DeliveryMethodKind.LOCAL_DELIVERY, "Own fleet").id();
        shipping.addRate(store, zone.id(), method, null, null,
                new RateRule.Flat(Money.of(2_000, "DOP")), Money.zero("DOP"),
                new ShippingConfig.TransitEstimate(0, 1));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        slot = shipping.addSlot(store, location, method, start,
                start.plus(2, ChronoUnit.HOURS), 1).id();
    }

    @Test
    void codLocalDeliveryFlowsFromChatCheckoutToCollectedCashAndProofOfDelivery() throws Exception {
        // ── 1. the shopper: delivery options in chat, then a COD checkout ────
        CartView cart = cartWith(2);   // goods 20 000

        JsonNode options = agentJson(agentPost("/v1/sales/agent/delivery/options")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cartHandle\":\"" + cart.id().value() + "\",\"address\":"
                        + "{\"line1\":\"Calle Duarte 12\",\"city\":\"Santo Domingo\","
                        + "\"postalCode\":\"10100\",\"country\":\"DO\"}}"));
        assertThat(options.get("_uiHint").get("kind").asText()).isEqualTo("delivery_options");
        assertThat(options.get("options").get(0).get("promisedTo").asText()).isNotBlank();

        CheckoutOutcome outcome = checkout.checkout(new CheckoutCommand(cart.id(), TenderKind.COD,
                false, null, null, "shopper-1", Ids.newId(), DOORSTEP, method, slot));
        OrderView order = ((CheckoutOutcome.Placed) outcome).order();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.totals().shipping()).isEqualTo(Money.of(2_000, "DOP"));
        assertThat(order.totals().grand()).isEqualTo(Money.of(22_000, "DOP"));
        assertThat(order.delivery().slot()).isEqualTo(slot);
        // ON_FULFILLMENT: nothing sold yet — held, not moved.
        assertThat(onHand()).isEqualTo(5);
        assertThat(reserved()).isEqualTo(2);

        // ── 2. fulfillment: pick → pack → dispatch commits the SALE ─────────
        Shipments.ShipmentView shipment = shipments.create(order.id(), List.of(), null);
        assertThat(shipment.kind()).isEqualTo(DeliveryMethodKind.LOCAL_DELIVERY);
        shipments.pick(shipment.id(), "op:test");
        shipments.pack(shipment.id(), new Shipments.Parcel(2_000, 30, 20, 15), "op:test");
        shipments.dispatch(shipment.id(), null, null, "op:test");

        assertThat(onHand()).isEqualTo(3);   // SALE movements committed at dispatch
        assertThat(reserved()).isZero();
        assertThat(inventory.ledger(sku, Optional.of(location)).stream()
                .filter(m -> m.kind() == MovementKind.SALE)).hasSize(1);
        assertThat(sumOfMovements()).isEqualTo(onHand());   // ledger ≡ summary
        OrderView fulfilled = orders.order(order.id()).orElseThrow();
        assertThat(fulfilled.fulfillmentState()).isEqualTo("FULFILLED");
        assertThat(fulfilled.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);   // money still due

        // ── 3. the courier joins: invite → register → OTP → docs → approval ─
        String courierToken = onboardCourier(store, "+18095551000");

        // ── 4. assignment + the courier's day ────────────────────────────────
        fleet.assign(shipment.id(), Optional.empty(), "op:test");
        JsonNode assignments = courierJson(get("/v1/sales/delivery/assignments"), courierToken);
        assertThat(assignments).hasSize(1);
        String assignmentId = assignments.get(0).get("assignmentId").asText();
        assertThat(assignments.get(0).get("codDueMinor").asLong()).isEqualTo(22_000);
        assertThat(assignments.get(0).get("shipTo").get("line1").asText())
                .isEqualTo("Calle Duarte 12");

        mvc.perform(courier(post("/v1/sales/delivery/assignments/" + assignmentId + "/accept"),
                courierToken)).andExpect(status().isOk());
        mvc.perform(courier(put("/v1/sales/delivery/assignments/" + assignmentId + "/status"),
                        courierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OUT_FOR_DELIVERY\"}"))
                .andExpect(status().isOk());
        assertThat(shipments.shipment(shipment.id()).orElseThrow().deliveryStatus())
                .isEqualTo(DeliveryStatus.OUT_FOR_DELIVERY);

        // ── 5. COD collected at the door: wrong amount refused, right amount
        //       settles, a retry is a no-op (§14 single-settle) ───────────────
        mvc.perform(courier(post("/v1/sales/delivery/assignments/" + assignmentId + "/collect-cod"),
                        courierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":20000}"))
                .andExpect(status().isUnprocessableEntity());

        JsonNode collected = courierJson(post("/v1/sales/delivery/assignments/" + assignmentId
                + "/collect-cod").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":22000}"), courierToken);
        assertThat(collected.get("status").asText()).isEqualTo("COLLECTED");

        OrderView paid = orders.order(order.id()).orElseThrow();
        assertThat(paid.paymentState()).isEqualTo(OrderView.PaymentState.PAID);
        assertThat(paid.status()).isEqualTo(OrderStatus.FULFILLED);   // FSM caught up on settle
        assertThat(onHand()).isEqualTo(3);   // no double SALE — committed at dispatch

        JsonNode retried = courierJson(post("/v1/sales/delivery/assignments/" + assignmentId
                + "/collect-cod").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":22000}"), courierToken);
        assertThat(retried.get("status").asText()).isEqualTo("ALREADY_COLLECTED");
        assertThat(inventory.ledger(sku, Optional.of(location)).stream()
                .filter(m -> m.kind() == MovementKind.SALE)).hasSize(1);
        assertThat(orders.eventsOf(order.id()).stream()
                .filter(e -> "order.paid".equals(e.type()))).hasSize(1);

        // ── 6. delivered, with proof ─────────────────────────────────────────
        mvc.perform(courier(put("/v1/sales/delivery/assignments/" + assignmentId + "/status"),
                        courierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\",\"proof\":{\"signature\":\"J.P.\","
                                + "\"photo\":\"blob-ref-1\"}}"))
                .andExpect(status().isOk());
        Shipments.ShipmentView delivered = shipments.shipment(shipment.id()).orElseThrow();
        assertThat(delivered.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivered.deliveredAt()).isNotNull();
        assertThat(delivered.proof().get("signature")).isEqualTo("J.P.");

        // ── 7. "where's my order?" through the agent surface ─────────────────
        JsonNode tracking = agentJson(agentGet("/v1/sales/agent/orders/"
                + order.id().value() + "/track"));
        assertThat(tracking.get("_uiHint").get("kind").asText()).isEqualTo("delivery_tracking");
        assertThat(tracking.get("tracking").get("shipments").get(0).get("deliveryStatus").asText())
                .isEqualTo("DELIVERED");
        assertThat(tracking.get("tracking").get("shipments").get(0).get("timeline"))
                .isNotEmpty();

        // ── 8. the §13 outbox story ──────────────────────────────────────────
        List<String> events = outbox.findAll().stream().map(OutboxEntity::getEventType).toList();
        assertThat(events).contains("delivery_agent.registered", "delivery.assigned",
                "shipment.dispatched", "shipment.out_for_delivery", "cod.collected",
                "order.paid", "shipment.delivered");

        // ── 9. least privilege: another store's courier touches nothing ─────
        StoreId otherStore = storeService.create(new TenantRef("tenant-other"), "Other", "DOP",
                StorePolicies.defaults()).id();
        String intruderToken = onboardCourier(otherStore, "+18095551001");
        assertThat(courierJson(get("/v1/sales/delivery/assignments"), intruderToken)).isEmpty();
        mvc.perform(courier(post("/v1/sales/delivery/assignments/" + assignmentId + "/accept"),
                intruderToken)).andExpect(status().isNotFound());
        mvc.perform(courier(post("/v1/sales/delivery/assignments/" + assignmentId + "/collect-cod"),
                        intruderToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":22000}"))
                .andExpect(status().isNotFound());
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /** Invite → register (public) → OTP verify → encrypted docs → approve. */
    private String onboardCourier(StoreId courierStore, String phone) throws Exception {
        String invite = fleet.mintInvite(courierStore, "op:test", null).token();
        MvcResult registered = mvc.perform(post("/v1/sales/delivery/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteToken":"%s","legalName":"Maria Gomez",
                                 "legalAddress":{"line1":"Av. Independencia 44","city":"Santo Domingo","country":"DO"},
                                 "phone":"%s",
                                 "vehicle":{"kind":"MOTORCYCLE","make":"Yamaha","model":"FZ","plate":"M998877"},
                                 "idDoc":{"type":"CEDULA","number":"001-1111111-2"}}
                                """.formatted(invite, phone)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode registration = json.readTree(registered.getResponse().getContentAsString());
        String agentId = registration.get("agentId").asText();

        MvcResult verified = mvc.perform(post("/v1/sales/delivery/agents/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"otp\":\""
                                + registration.get("otp").asText() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String registrationToken = json.readTree(verified.getResponse().getContentAsString())
                .get("courierToken").asText();

        mvc.perform(courier(post("/v1/sales/delivery/agents/" + agentId + "/documents"),
                        registrationToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documents\":["
                                + "{\"kind\":\"PHOTO_ID_FRONT\",\"contentBase64\":\"aWQ=\"},"
                                + "{\"kind\":\"VEHICLE_REGISTRATION\",\"contentBase64\":\"dmVo\"}]}"))
                .andExpect(status().isOk());

        return fleet.approve(new Ids.AgentId(agentId), "op:test").token();
    }

    private CartView cartWith(long qty) {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-1");
        carts.add(cart.id(), sku, qty);
        return cart;
    }

    private long onHand() {
        return inventory.level(sku, location).orElseThrow().onHand();
    }

    private long reserved() {
        return inventory.level(sku, location).orElseThrow().reserved();
    }

    private long sumOfMovements() {
        return inventory.ledger(sku, Optional.of(location)).stream()
                .mapToLong(m -> m.qtyDelta()).sum();
    }

    private JsonNode courierJson(MockHttpServletRequestBuilder req, String token) throws Exception {
        MvcResult result = mvc.perform(courier(req, token))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder courier(MockHttpServletRequestBuilder req,
                                                         String token) {
        return req.header("Authorization", "Bearer " + token);
    }

    private JsonNode agentJson(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult result = mvc.perform(req).andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder agentPost(String path) {
        return agentHeaders(post(path));
    }

    private MockHttpServletRequestBuilder agentGet(String path) {
        return agentHeaders(get(path));
    }

    /** The exact service-token wire format yaya-agentic's HttpToolDispatcher mints. */
    private MockHttpServletRequestBuilder agentHeaders(MockHttpServletRequestBuilder req) {
        long exp = Instant.now().getEpochSecond() + 60;
        String payload = TENANT + ".shopper-1." + exp + ".sales";
        return req.header("X-Yaya-Service-Token", payload + "." + hmac(SERVICE_SECRET, payload))
                .header("X-Yaya-Store-Id", store.value());
    }

    private static String hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
