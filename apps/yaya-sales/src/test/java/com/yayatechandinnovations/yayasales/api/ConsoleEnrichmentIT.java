package com.yayatechandinnovations.yayasales.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console-enrichment surface: image upload → public serving (what
 * media_json/cover_url point at), the catalog cover, the customer directory
 * (contact + structured addresses), and location addresses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ConsoleEnrichmentIT {

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
    void mediaUploadsServePubliclyAndGateNonImages() throws Exception {
        String storeId = createStore("Media Store");

        byte[] png = pngBytes();
        MvcResult uploaded = mvc.perform(authed(
                        (MockHttpServletRequestBuilder) multipart("/v1/sales/admin/media")
                                .file(new MockMultipartFile("file", "soap.png", "image/png", png)),
                        storeId))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode media = json.readTree(uploaded.getResponse().getContentAsString());
        String url = media.get("url").asText();
        assertThat(url).startsWith("/v1/sales/media/");

        // Served publicly — no auth, correct type, hard caching.
        MvcResult served = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        assertThat(served.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(png);
        assertThat(served.getResponse().getHeader("Cache-Control")).contains("immutable");

        // Listing shows metadata, upload of a non-image is rejected.
        JsonNode listed = getJson("/v1/sales/admin/media", storeId);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("filename").asText()).isEqualTo("soap.png");
        mvc.perform(authed((MockHttpServletRequestBuilder) multipart("/v1/sales/admin/media")
                        .file(new MockMultipartFile("file", "run.sh", "text/x-sh", "#!/bin/sh".getBytes())),
                        storeId))
                .andExpect(status().isUnprocessableEntity());

        // Deleting from another store's context is a 404 (existence not leaked).
        String otherStore = createStore("Other Store");
        String mediaId = media.get("media").get("id").get("value").asText();
        mvc.perform(authed(delete("/v1/sales/admin/media/" + mediaId), otherStore))
                .andExpect(status().isNotFound());
    }

    @Test
    void catalogsCarryACoverImage() throws Exception {
        String storeId = createStore("Cover Store");
        JsonNode catalog = postJson("/v1/sales/admin/catalogs",
                "{\"displayName\":\"Main\"}", storeId);
        String catalogId = catalog.get("id").get("value").asText();
        assertThat(catalog.get("coverUrl").isNull()).isTrue();

        JsonNode updated = putJson("/v1/sales/admin/catalogs/" + catalogId,
                "{\"coverUrl\":\"/v1/sales/media/abc\",\"displayName\":\"Main Season\"}", storeId);
        assertThat(updated.get("coverUrl").asText()).isEqualTo("/v1/sales/media/abc");
        assertThat(updated.get("displayName").asText()).isEqualTo("Main Season");

        // Blank cover clears it; name survives when omitted.
        JsonNode cleared = putJson("/v1/sales/admin/catalogs/" + catalogId,
                "{\"coverUrl\":\"\"}", storeId);
        assertThat(cleared.get("coverUrl").isNull()).isTrue();
        assertThat(cleared.get("displayName").asText()).isEqualTo("Main Season");
    }

    @Test
    void customerDirectoryHoldsContactAndAddresses() throws Exception {
        String storeId = createStore("Customer Store");

        JsonNode created = postJson("/v1/sales/admin/customers", """
                {"displayName":"Rosa Peña","email":"rosa@example.do","phone":"+18095550101",
                 "customerRef":"user:rosa","notes":"prefers evening delivery"}
                """, storeId);
        String customerId = created.get("id").get("value").asText();
        assertThat(created.get("email").asText()).isEqualTo("rosa@example.do");

        JsonNode withAddress = postJson("/v1/sales/admin/customers/" + customerId + "/addresses", """
                {"kind":"SHIPPING","address":{"line1":"Calle 5 #12","city":"Santo Domingo",
                 "region":"Distrito Nacional","country":"DO"}}
                """, storeId);
        assertThat(withAddress.get("addresses")).hasSize(1);
        assertThat(withAddress.get("addresses").get(0).get("address").get("city").asText())
                .isEqualTo("Santo Domingo");

        // Search matches name/email/phone/ref; lookup by the ref orders carry.
        JsonNode byPhone = getJson("/v1/sales/admin/customers?q=0101", storeId);
        assertThat(byPhone).hasSize(1);
        JsonNode all = getJson("/v1/sales/admin/customers", storeId);
        assertThat(all).hasSize(1);

        // Duplicate ref within the store is a conflict.
        mvc.perform(authed(post("/v1/sales/admin/customers"), storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Dup\",\"customerRef\":\"user:rosa\"}"))
                .andExpect(status().isConflict());

        // Address removal; foreign store sees nothing.
        String addressId = withAddress.get("addresses").get(0).get("id").get("value").asText();
        JsonNode removed = json.readTree(mvc.perform(authed(
                        delete("/v1/sales/admin/customers/" + customerId + "/addresses/" + addressId),
                        storeId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(removed.get("addresses")).isEmpty();

        String otherStore = createStore("Foreign Store");
        mvc.perform(authed(get("/v1/sales/admin/customers/" + customerId), otherStore))
                .andExpect(status().isNotFound());
    }

    @Test
    void locationsCarryAnAddress() throws Exception {
        String storeId = createStore("Location Store");
        JsonNode location = postJson("/v1/sales/admin/locations", """
                {"label":"Piantini shop","kind":"SHOP",
                 "address":{"line1":"Av. Winston Churchill 25","city":"Santo Domingo","country":"DO"}}
                """, storeId);
        assertThat(location.get("address").get("line1").asText())
                .isEqualTo("Av. Winston Churchill 25");

        String locationId = location.get("id").get("value").asText();
        JsonNode updated = putJson("/v1/sales/admin/locations/" + locationId,
                "{\"label\":\"Piantini flagship\",\"sellable\":false}", storeId);
        assertThat(updated.get("label").asText()).isEqualTo("Piantini flagship");
        assertThat(updated.get("sellable").asBoolean()).isFalse();
        assertThat(updated.get("address").get("city").asText()).isEqualTo("Santo Domingo");
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private String createStore(String name) throws Exception {
        JsonNode store = postJson("/v1/sales/admin/stores",
                "{\"tenantId\":\"tenant-enrich\",\"displayName\":\"" + name
                        + "\",\"defaultCurrency\":\"DOP\"}", null);
        return store.get("id").get("value").asText();
    }

    /** Smallest valid PNG (1×1 transparent pixel). */
    private static byte[] pngBytes() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                        + "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    }

    private JsonNode postJson(String path, String body, String storeId) throws Exception {
        MvcResult result = mvc.perform(authed(post(path), storeId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode putJson(String path, String body, String storeId) throws Exception {
        MvcResult result = mvc.perform(authed(put(path), storeId)
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
        req.with(jwt().jwt(j -> j.subject("op-1").claim("roles", List.of("owner"))
                .claim("org", "tenant-enrich")));
        if (storeId != null) {
            req.header("X-Yaya-Store-Id", storeId);
        }
        return req;
    }
}
