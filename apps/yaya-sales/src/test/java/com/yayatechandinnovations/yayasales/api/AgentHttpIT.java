package com.yayatechandinnovations.yayasales.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.catalog.ProductView;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §10.2/§15 over HTTP: the agent API takes ONLY platform service tokens (the
 * exact HMAC wire format yaya-agentic mints), scopes them to the owning
 * tenant's stores, and answers with {@code _uiHint}-framed bodies the engine
 * passes through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AgentHttpIT {

    static final byte[] SECRET = "agent-http-it-shared-secret-000001".getBytes(StandardCharsets.UTF_8);
    static final String TENANT = "tenant-agent";

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
                () -> Base64.getEncoder().encodeToString(SECRET));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;

    StoreId store;
    String productId;
    String variantId;

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef(TENANT), "Agent Store", "DOP",
                StorePolicies.defaults()).id();
        ProductView product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Castor soap", "Cold pressed", "soap", null, null, null,
                        ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("CS-200", "200g",
                                Money.of(35_000, "DOP"), null, null, null))));
        productId = product.id().value();
        variantId = product.variants().get(0).id().value();
        ledger.receive(product.variants().get(0).id(),
                storeDirectory.locationsOf(store).get(0).id(), 5, Money.of(20_000, "DOP"), null);
    }

    // ── the trust boundary ───────────────────────────────────────────────────

    @Test
    void rejectsCallsWithoutAServiceToken() throws Exception {
        mvc.perform(post("/v1/sales/agent/products/search")
                        .header("X-Yaya-Store-Id", store.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAForgedServiceToken() throws Exception {
        String forged = mint("wrong-secret-material".getBytes(StandardCharsets.UTF_8), TENANT);
        mvc.perform(post("/v1/sales/agent/products/search")
                        .header("X-Yaya-Service-Token", forged)
                        .header("X-Yaya-Store-Id", store.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherTenantsTokenCannotTouchThisStore() throws Exception {
        // tenant exists and has a valid secret? No — unknown tenant fails verification (401).
        // The sharper case is a VALID token for a different tenant: 403, existence not leaked.
        StoreId otherStore = storeService.create(new TenantRef("tenant-other"), "Other", "DOP",
                StorePolicies.defaults()).id();
        mvc.perform(post("/v1/sales/agent/products/search")
                        .header("X-Yaya-Service-Token", mint(SECRET, TENANT))
                        .header("X-Yaya-Store-Id", otherStore.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ── the storefront flow the chat drives ──────────────────────────────────

    @Test
    void searchProductCartFlowCarriesUiHintFrames() throws Exception {
        JsonNode search = postJson("/v1/sales/agent/products/search",
                "{\"query\":\"castor\"}");
        assertThat(search.get("count").asInt()).isEqualTo(1);
        assertThat(search.get("_uiHint").get("kind").asText()).isEqualTo("product_catalog");

        JsonNode product = getJson("/v1/sales/agent/products/" + productId);
        assertThat(product.get("_uiHint").get("kind").asText()).isEqualTo("product_card");
        assertThat(product.get("product").get("variants").get(0).get("available").asLong())
                .isEqualTo(5);   // live availability (§7.2)

        JsonNode added = postJson("/v1/sales/agent/cart/add",
                "{\"sku\":\"" + variantId + "\",\"qty\":2}");
        assertThat(added.get("_uiHint").get("kind").asText()).isEqualTo("cart");
        String cartHandle = added.get("cart").get("cartHandle").asText();
        assertThat(added.get("cart").get("grandTotalMinor").asLong()).isEqualTo(70_000);

        // Live availability reflects the soft hold immediately.
        JsonNode after = getJson("/v1/sales/agent/products/" + productId);
        assertThat(after.get("product").get("variants").get(0).get("available").asLong())
                .isEqualTo(3);

        JsonNode insufficient = postJson("/v1/sales/agent/cart/add",
                "{\"cartHandle\":\"" + cartHandle + "\",\"sku\":\"" + variantId + "\",\"qty\":9}");
        assertThat(insufficient.get("insufficient").get("available").asLong()).isEqualTo(3);

        JsonNode tenders = getJson("/v1/sales/agent/tenders?cartHandle=" + cartHandle);
        assertThat(tenders.get("_uiHint").get("kind").asText()).isEqualTo("tender_options");
        assertThat(tenders.get("tenders")).isNotEmpty();   // seeded region offers PSP/COD/…

        JsonNode cleared = postJson("/v1/sales/agent/cart/clear",
                "{\"cartHandle\":\"" + cartHandle + "\"}");
        assertThat(cleared.get("cart").get("lines")).isEmpty();
    }

    @Test
    void manifestServesImportableToolDescriptors() throws Exception {
        JsonNode manifest = getJson("/v1/sales/agent/manifest");
        assertThat(manifest.get("tools")).isNotEmpty();
        JsonNode checkoutless = manifest.get("tools");
        for (JsonNode tool : checkoutless) {
            String url = tool.get("handler").get("http").get("urlTemplate").asText();
            assertThat(url).startsWith("/v1/sales/agent/");   // path-only invariant (§11.1)
            assertThat(tool.get("handler").get("http").get("authForwarding").asText())
                    .isEqualTo("SERVICE_TOKEN");
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private JsonNode postJson(String path, String body) throws Exception {
        MvcResult result = mvc.perform(authed(post(path))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mvc.perform(authed(get(path)))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder req) {
        return req.header("X-Yaya-Service-Token", mint(SECRET, TENANT))
                .header("X-Yaya-Store-Id", store.value());
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
