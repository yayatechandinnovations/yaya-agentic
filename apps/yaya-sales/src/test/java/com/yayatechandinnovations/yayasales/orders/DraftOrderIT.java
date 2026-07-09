package com.yayatechandinnovations.yayasales.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.MovementKind;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.DraftOrderService.DraftLine;
import com.yayatechandinnovations.yayasales.orders.DraftOrderService.DraftSpec;
import com.yayatechandinnovations.yayasales.orders.DraftOrderService.SendCommand;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §8.1 — draft orders: assembled cold (no holds, override prices honored),
 * made real only by send (FIRM holds + re-quote + tender, the same "tap to
 * pay" outcome shapes as checkout), an insufficient line failing the send
 * without touching the draft, and the agent surface framing it all as
 * {@code checkout} ui hints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DraftOrderIT {

    static final byte[] SERVICE_SECRET =
            "draft-order-it-service-secret-0001".getBytes(StandardCharsets.UTF_8);
    static final String TENANT = "tenant-s6-drafts";

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
        r.add("yaya.sales.auth.service-secrets." + TENANT,
                () -> Base64.getEncoder().encodeToString(SERVICE_SECRET));
    }

    @Autowired DraftOrderService drafts;
    @Autowired OrderService orders;
    @Autowired TenderService tenders;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin inventory;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    StoreId store;
    LocationId location;
    VariantId sku;

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef(TENANT), "Draft Store", "DOP",
                StorePolicies.defaults()).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Soap", null, "soap", null, null, null, ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("SOAP-1", "bar",
                                Money.of(10_000, "DOP"), null, null, null))));
        sku = product.variants().get(0).id();
        ledger.receive(sku, location, 5, Money.of(4_000, "DOP"), null);
    }

    private OrderView draft(long qty, Long overrideMinor) {
        return drafts.create(store, new DraftSpec(null, null, "shopper-1",
                List.of(new DraftLine(sku, qty, overrideMinor)), "chat deal"), "op:test");
    }

    private SendCommand cod() {
        return new SendCommand(TenderKind.COD, false, null, null, Ids.newId());
    }

    private long onHand() {
        return inventory.level(sku, location).orElseThrow().onHand();
    }

    private long reserved() {
        return inventory.level(sku, location).orElseThrow().reserved();
    }

    // ── assembled cold: overrides price, nothing is held ────────────────────

    @Test
    void draftPricesFromTheOverrideAndHoldsNothing() {
        OrderView draft = draft(2, 8_000L);   // list 10 000, approved one-off 8 000

        assertThat(draft.source()).isEqualTo(OrderView.Source.DRAFT);
        assertThat(draft.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(draft.totals().grand()).isEqualTo(Money.of(16_000, "DOP"));
        assertThat(draft.lines().get(0).reservationIds()).isEmpty();
        assertThat(reserved()).isZero();                       // §8.1: no holds while DRAFT
        assertThat(orders.eventsOf(draft.id()))
                .anySatisfy(e -> assertThat(e.type()).isEqualTo("draft_order.created"));
        // Listing rides the existing orders surface.
        assertThat(orders.ordersOf(store, Optional.of(OrderStatus.DRAFT)))
                .extracting(OrderView::id).contains(draft.id());
    }

    @Test
    void updateRepricesWhileDraftAndIsRejectedAfterSend() {
        OrderView draft = draft(1, null);
        assertThat(draft.totals().grand()).isEqualTo(Money.of(10_000, "DOP"));

        OrderView updated = drafts.update(draft.id(),
                List.of(new DraftLine(sku, 3, null)), "op:test");
        assertThat(updated.totals().grand()).isEqualTo(Money.of(30_000, "DOP"));
        assertThat(reserved()).isZero();

        drafts.send(draft.id(), cod(), "op:test");
        assertThatThrownBy(() -> drafts.update(draft.id(),
                List.of(new DraftLine(sku, 1, null)), "op:test"))
                .isInstanceOf(ApiErrors.ConflictException.class);
    }

    // ── send: THE transition (§8.1) — reserve now, quote now, tender now ────

    @Test
    void sendReservesQuotesAndPlacesCodThenSettlesThroughTheLedger() {
        OrderView draft = draft(2, 8_000L);

        CheckoutOutcome outcome = drafts.send(draft.id(), cod(), "op:test");

        assertThat(outcome).isInstanceOf(CheckoutOutcome.Placed.class);
        OrderView sent = ((CheckoutOutcome.Placed) outcome).order();
        assertThat(sent.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(sent.totals().grand()).isEqualTo(Money.of(16_000, "DOP"));
        assertThat(sent.lines().get(0).reservationIds()).hasSize(1);
        assertThat(reserved()).isEqualTo(2);                   // held only NOW
        assertThat(onHand()).isEqualTo(5);                     // nothing sold yet
        assertThat(orders.eventsOf(draft.id()))
                .anySatisfy(e -> assertThat(e.type()).isEqualTo("draft_order.sent"));

        // The shopper pays the courier → tender settles → PAID + SALE movement.
        TenderView codTender = tenders.tendersOf(sent.id()).get(0);
        tenders.settle(codTender.id(), "courier:test", null);

        OrderView paid = orders.order(draft.id()).orElseThrow();
        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(onHand()).isEqualTo(3);
        assertThat(reserved()).isZero();
        assertThat(inventory.ledger(sku, Optional.of(location)).stream()
                .filter(m -> m.kind() == MovementKind.SALE)).hasSize(1);
    }

    @Test
    void sendWithPspHandsBackTheTapToPayUrl() {
        OrderView draft = draft(1, null);

        CheckoutOutcome outcome = drafts.send(draft.id(),
                new SendCommand(TenderKind.PSP, false, "https://shop.example/return",
                        null, Ids.newId()),
                "op:test");

        assertThat(outcome).isInstanceOfSatisfying(CheckoutOutcome.HostedUrl.class, hosted -> {
            assertThat(hosted.url()).isNotBlank();
            assertThat(hosted.order().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        });
        assertThat(reserved()).isEqualTo(1);
    }

    @Test
    void insufficientStockFailsTheSendAndLeavesTheDraftIntact() {
        OrderView draft = draft(9, null);   // only 5 exist

        CheckoutOutcome outcome = drafts.send(draft.id(), cod(), "op:test");

        assertThat(outcome).isInstanceOfSatisfying(CheckoutOutcome.Insufficient.class, r -> {
            assertThat(r.sku()).isEqualTo(sku);
            assertThat(r.available()).isEqualTo(5);
        });
        OrderView untouched = orders.order(draft.id()).orElseThrow();
        assertThat(untouched.status()).isEqualTo(OrderStatus.DRAFT);   // still editable
        assertThat(reserved()).isZero();                               // rollback freed the holds
        assertThat(tenders.tendersOf(draft.id())).isEmpty();
    }

    @Test
    void retriedSendReattachesInsteadOfReReserving() {
        OrderView draft = draft(2, null);
        SendCommand command = cod();

        OrderView first = ((CheckoutOutcome.Placed) drafts.send(draft.id(), command, "op:test")).order();
        OrderView replay = ((CheckoutOutcome.Placed) drafts.send(draft.id(), command, "op:test")).order();

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(reserved()).isEqualTo(2);                   // no double holds
        assertThat(tenders.tendersOf(draft.id())).hasSize(1);
    }

    @Test
    void cancelWhileDraftReleasesNothingBecauseNothingWasHeld() {
        OrderView draft = draft(3, null);

        OrderView cancelled = orders.cancel(draft.id(), "op:test");

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reserved()).isZero();
        assertThat(onHand()).isEqualTo(5);
    }

    @Test
    void failedPspAuthorizationCompensatesLikeCheckout() {
        OrderView draft = draft(2, null);

        assertThatThrownBy(() -> drafts.send(draft.id(),
                new SendCommand(TenderKind.PSP, false,
                        "https://shop.example/return?sandbox=fail", null, Ids.newId()),
                "op:test"))
                .isInstanceOf(ApiErrors.UnprocessableException.class);

        assertThat(reserved()).isZero();                       // no orphan reservation
        assertThat(orders.order(draft.id()).orElseThrow().status())
                .isEqualTo(OrderStatus.CANCELLED);             // no phantom pending order
    }

    // ── the agent surface: the exit-criterion "tap to pay" conversation ─────

    @Test
    void agentBuildsADraftWithAnOverrideAndSendsItAsACheckoutUiHint() throws Exception {
        JsonNode built = agentJson(post("/v1/sales/agent/draft-orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"sku\":\"" + sku.value()
                        + "\",\"qty\":2,\"unitPriceMinor\":8000}],\"note\":\"chat bundle\"}"));
        assertThat(built.get("_uiHint").get("kind").asText()).isEqualTo("draft_order");
        assertThat(built.get("draftOrder").get("grandTotalMinor").asLong()).isEqualTo(16_000);
        String draftId = built.get("draftOrder").get("draftOrderId").asText();
        assertThat(reserved()).isZero();

        JsonNode sent = agentJson(post("/v1/sales/agent/draft-orders/" + draftId + "/send")
                .header("Idempotency-Key", Ids.newId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tender\":\"COD\"}"));
        assertThat(sent.get("_uiHint").get("kind").asText()).isEqualTo("checkout");
        assertThat(sent.get("_uiHint").get("payload").get("kind").asText()).isEqualTo("placed");
        assertThat(sent.get("status").asText()).isEqualTo("PLACED");
        assertThat(reserved()).isEqualTo(2);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private JsonNode agentJson(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult result = mvc.perform(req
                        .header("X-Yaya-Service-Token", mint(SERVICE_SECRET, TENANT))
                        .header("X-Yaya-Store-Id", store.value()))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** The exact format yaya-agentic's HttpToolDispatcher mints. */
    private static String mint(byte[] secret, String tenant) {
        long exp = Instant.now().getEpochSecond() + 60;
        String payload = tenant + ".shopper-1." + exp + ".sales";
        return payload + "." + hmac(secret, payload);
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
