package com.yayatechandinnovations.yayasales.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 exit over HTTP: a PSP (sandbox) checkout settles through the
 * signature-verified webhook — the ONLY thing that flips a PSP order PAID —
 * idempotent on the provider event id; the admin refunds; on-hand moves only
 * via ledger movements.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CheckoutHttpIT {

    static final byte[] SERVICE_SECRET = "checkout-http-it-service-secret-01".getBytes(StandardCharsets.UTF_8);
    static final String WEBHOOK_SECRET = "sandbox-webhook-secret-for-tests";
    static final String TENANT = "tenant-chk";

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
        r.add("yaya.sales.payments.sandbox.webhook-secret", () -> WEBHOOK_SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin inventory;

    StoreId store;
    LocationId location;
    VariantId sku;

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef(TENANT), "Checkout Store", "DOP",
                StorePolicies.defaults()).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Soap", null, "soap", null, null, null, ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("SOAP-H", "bar",
                                Money.of(10_000, "DOP"), null, null, null))));
        sku = product.variants().get(0).id();
        ledger.receive(sku, location, 5, Money.of(4_000, "DOP"), null);
    }

    @Test
    void pspCheckoutSettlesThroughTheVerifiedWebhookIdempotently() throws Exception {
        // Chat drives: add to cart → checkout PSP → hosted URL.
        JsonNode added = agentPost("/v1/sales/agent/cart/add",
                "{\"sku\":\"" + sku.value() + "\",\"qty\":2}");
        String cartHandle = added.get("cart").get("cartHandle").asText();

        JsonNode placed = agentPost("/v1/sales/agent/checkout",
                "{\"cartHandle\":\"" + cartHandle + "\",\"tender\":\"PSP\","
                        + "\"returnUrl\":\"https://shop.example/return\"}");
        assertThat(placed.get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(placed.get("_uiHint").get("kind").asText()).isEqualTo("checkout");
        String orderId = placed.get("orderId").asText();
        String pspRef = pspRefOf(placed.get("paymentUrl").asText());

        // Order awaits payment; stock is held, not sold.
        assertThat(agentGet("/v1/sales/agent/orders/" + orderId)
                .get("order").get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(onHand()).isEqualTo(5);

        // A forged webhook is rejected before parsing.
        String event = "{\"eventId\":\"evt-1\",\"type\":\"payment.captured\",\"orderId\":\""
                + orderId + "\",\"storeId\":\"" + store.value() + "\",\"pspRef\":\"" + pspRef + "\"}";
        mvc.perform(post("/v1/sales/webhooks/sandbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Sandbox-Signature", "forged")
                        .content(event))
                .andExpect(status().isUnauthorized());
        assertThat(agentGet("/v1/sales/agent/orders/" + orderId)
                .get("order").get("status").asText()).isEqualTo("PENDING_PAYMENT");

        // The signed webhook settles → PAID → SALE movement.
        postWebhook(event).andExpect(status().isOk());
        assertThat(agentGet("/v1/sales/agent/orders/" + orderId)
                .get("order").get("status").asText()).isEqualTo("PAID");
        assertThat(onHand()).isEqualTo(3);

        // Replaying the same provider event id is acknowledged and changes nothing.
        postWebhook(event).andExpect(status().isOk());
        assertThat(onHand()).isEqualTo(3);
        JsonNode detail = adminGet("/v1/sales/admin/orders/" + orderId);
        long captures = 0;
        for (JsonNode t : detail.get("tenders")) {
            if (t.get("status").asText().equals("SETTLED")) captures++;
        }
        assertThat(captures).isEqualTo(1);
    }

    @Test
    void adminRefundsAPaidPspOrderThroughTheProvider() throws Exception {
        JsonNode added = agentPost("/v1/sales/agent/cart/add",
                "{\"sku\":\"" + sku.value() + "\",\"qty\":1}");
        String cartHandle = added.get("cart").get("cartHandle").asText();
        JsonNode placed = agentPost("/v1/sales/agent/checkout",
                "{\"cartHandle\":\"" + cartHandle + "\",\"tender\":\"PSP\","
                        + "\"returnUrl\":\"https://shop.example/return\"}");
        String orderId = placed.get("orderId").asText();
        String pspRef = pspRefOf(placed.get("paymentUrl").asText());
        postWebhook("{\"eventId\":\"evt-r1\",\"type\":\"payment.captured\",\"orderId\":\""
                + orderId + "\",\"storeId\":\"" + store.value()
                + "\",\"pspRef\":\"" + pspRef + "\"}").andExpect(status().isOk());

        MvcResult refund = mvc.perform(operator(post("/v1/sales/admin/orders/" + orderId + "/refund"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"psp\"}"))
                .andExpect(status().isOk()).andReturn();

        JsonNode refunded = json.readTree(refund.getResponse().getContentAsString());
        assertThat(refunded.get("status").asText()).isEqualTo("REFUNDED");
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /** Sandbox hosted URLs look like {@code …/checkout/sbx_xxx?return=…}. */
    private static String pspRefOf(String paymentUrl) {
        int start = paymentUrl.indexOf("/checkout/") + "/checkout/".length();
        int end = paymentUrl.indexOf('?', start);
        return end < 0 ? paymentUrl.substring(start) : paymentUrl.substring(start, end);
    }

    private long onHand() {
        return inventory.level(sku, location).orElseThrow().onHand();
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(String body) throws Exception {
        return mvc.perform(post("/v1/sales/webhooks/sandbox")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Sandbox-Signature",
                        hmac(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), body))
                .content(body));
    }

    private JsonNode agentPost(String path, String body) throws Exception {
        MvcResult result = mvc.perform(service(post(path))
                        .header("Idempotency-Key", Ids.newId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode agentGet(String path) throws Exception {
        MvcResult result = mvc.perform(service(get(path)))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode adminGet(String path) throws Exception {
        MvcResult result = mvc.perform(operator(get(path)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder service(MockHttpServletRequestBuilder req) {
        long exp = Instant.now().getEpochSecond() + 60;
        String payload = TENANT + ".shopper-1." + exp + ".sales";
        return req.header("X-Yaya-Service-Token",
                        payload + "." + hmac(SERVICE_SECRET, payload))
                .header("X-Yaya-Store-Id", store.value());
    }

    private MockHttpServletRequestBuilder operator(MockHttpServletRequestBuilder req) {
        return req.with(jwt().jwt(j -> j.subject("op-1").claim("roles", List.of("owner"))
                        .claim("org", TENANT)))
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
