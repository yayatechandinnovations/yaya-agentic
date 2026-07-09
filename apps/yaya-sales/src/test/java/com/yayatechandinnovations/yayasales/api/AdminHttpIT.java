package com.yayatechandinnovations.yayasales.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1 exit criteria over HTTP: an operator creates a store, authors a product,
 * receives stock, and reads on-hand from the ledger — and an anonymous caller
 * gets 401 (trust boundary §15).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminHttpIT {

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

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(get("/v1/sales/admin/stores").param("tenantId", "t"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/sales/admin/stores").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void operatorAuthorsAndReceivesStockEndToEnd() throws Exception {
        // 1. store (a default location is seeded)
        JsonNode store = postJson("/v1/sales/admin/stores", """
                {"tenantId":"tenant-http","displayName":"HTTP Store","defaultCurrency":"DOP"}
                """, null);
        String storeId = store.get("id").get("value").asText();

        JsonNode locations = getJson("/v1/sales/admin/locations", storeId);
        assertThat(locations).hasSize(1);
        String locationId = locations.get(0).get("id").get("value").asText();

        // 2. catalog + product
        JsonNode catalog = postJson("/v1/sales/admin/catalogs",
                "{\"displayName\":\"Main\"}", storeId);
        String catalogId = catalog.get("id").get("value").asText();

        JsonNode product = postJson("/v1/sales/admin/catalogs/" + catalogId + "/products", """
                {"title":"Castor soap","status":"ACTIVE",
                 "variants":[{"sku":"CS-200","label":"200g",
                              "price":{"amountMinor":35000,"currency":"DOP"}}]}
                """, storeId);
        String sku = product.get("variants").get(0).get("id").get("value").asText();

        // 3. receive stock → on-hand comes from the ledger
        mvc.perform(authed(post("/v1/sales/admin/inventory/" + sku + "/receive"), storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"" + locationId
                                + "\",\"qty\":12,\"unitCostMinor\":20000,\"currency\":\"DOP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementId").isNotEmpty());

        JsonNode levels = getJson("/v1/sales/admin/inventory", storeId);
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0).get("onHand").asLong()).isEqualTo(12);
        assertThat(levels.get(0).get("avgCost").get("amountMinor").asLong()).isEqualTo(20000);

        JsonNode ledger = getJson("/v1/sales/admin/inventory/" + sku + "/ledger", storeId);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).get("kind").asText()).isEqualTo("RECEIPT");
    }

    private JsonNode postJson(String path, String body, String storeId) throws Exception {
        MvcResult result = mvc.perform(authed(post(path), storeId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path, String storeId) throws Exception {
        MvcResult result = mvc.perform(authed(get(path), storeId))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** An operator JWT (roles claim) + the acting store header. */
    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder req, String storeId) {
        req.with(jwt().jwt(j -> j.subject("op-1").claim("roles", List.of("owner"))));
        if (storeId != null) {
            req.header("X-Yaya-Store-Id", storeId);
        }
        return req;
    }
}
