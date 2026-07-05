package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Managed product authoring at scale (Pillar A): a DATABASE catalog's products
 * are created/paged/edited/archived as single rows through
 * {@code /v1/admin/product-catalogs/{id}/products}, bulk-imported, exported to
 * CSV, and a legacy INLINE catalog converts to managed. Editing one product is a
 * single-row write, not a catalog version bump. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class ProductAdminIT {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "product-admin-test";

    // A unique managed catalog per test — the containerized DB is shared across
    // methods, so isolating the catalog id keeps product counts deterministic.
    private String CATALOG;

    @BeforeEach
    void setup() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        var tenant = new AdminDtos.TenantRequest(
                TENANT, "Product Admin Test", "https://pa-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(tenant)
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private void createManagedCatalog(String id) {
        CATALOG = id;
        Map<String, Object> catalog = Map.of(
                "tenant", TENANT, "id", id, "displayName", "Managed Catalog",
                "currency", "DOP", "locationKind", "DATABASE", "location", Map.of());
        client.post().uri("/v1/admin/product-catalogs")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(catalog)
                .exchange().expectStatus().isOk();
    }

    @Test
    void createEditArchivePagesAndExports() {
        createManagedCatalog("cat-crud");
        // Create a product (minor units — the client already converted the decimal).
        Map<String, Object> soap = product("p_soap", "Castor Oil Soap", 899, 20);
        client.post().uri(productsUri())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(soap)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.productId").isEqualTo("p_soap")
                .jsonPath("$.priceMinor").isEqualTo(899)
                .jsonPath("$.currency").isEqualTo("DOP")
                .jsonPath("$.availability").isEqualTo("IN_STOCK");

        int versionBefore = catalogVersion();

        // Edit one field — single-row PUT, no catalog version bump.
        Map<String, Object> edited = product("p_soap", "Castor Oil Soap XL", 1099, 3);
        client.put().uri(productUri("p_soap"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(edited)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Castor Oil Soap XL")
                .jsonPath("$.priceMinor").isEqualTo(1099)
                .jsonPath("$.availability").isEqualTo("LOW");   // derived from inventory 3

        // Catalog config version is unchanged — product edits are operational, not config.
        assertThat(catalogVersion()).isEqualTo(versionBefore);

        // Add a second, then page with size 1.
        client.post().uri(productsUri())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(product("p_oil", "Hair Oil", 500, 10))
                .exchange().expectStatus().isOk();

        client.get().uri(productsUri() + "&page=0&size=1&sort=TITLE")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(2)
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].productId").isEqualTo("p_soap");  // title sort: "Castor..." first

        // Archive removes it from the default (ACTIVE) list.
        client.post().uri(actionUri("p_oil", "archive"))
                .exchange().expectStatus().is2xxSuccessful();
        client.get().uri(productsUri())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.total").isEqualTo(1);

        // CSV export includes archived rows (lossless round-trip) with integer minor units.
        String csv = new String(client.get().uri(base() + "/export.csv?tenant=" + TENANT)
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody());
        assertThat(csv).contains("product_id,title").contains("p_soap").contains("1099");
    }

    @Test
    void bulkUpsertReportsRowErrors() {
        createManagedCatalog("cat-bulk");
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(product("b1", "Good One", 100, 5));
        rows.add(product(null, "Missing Id", 200, 5));   // invalid → row error
        client.post().uri(base() + "/bulk?tenant=" + TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("products", rows))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.upserted").isEqualTo(1)
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].index").isEqualTo(1);
    }

    @Test
    void inlineCatalogConvertsToManaged() {
        // Seed a legacy INLINE catalog with one product...
        Map<String, Object> inlineProduct = Map.of(
                "id", Map.of("value", "legacy_1"), "title", "Legacy Item",
                "price", Map.of("amountMinor", 750, "currency", "DOP"),
                "imageUrls", List.of(), "attributes", Map.of(), "variants", List.of(),
                "availability", "IN_STOCK");
        client.post().uri("/v1/admin/product-catalogs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tenant", TENANT, "id", "legacy-cat", "displayName", "Legacy",
                        "currency", "DOP", "locationKind", "INLINE",
                        "location", Map.of("products", List.of(inlineProduct))))
                .exchange().expectStatus().isOk();

        // ...convert it: products lift into the table, location flips to DATABASE.
        client.post().uri("/v1/admin/product-catalogs/legacy-cat/convert-to-managed?tenant=" + TENANT)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.locationKind").isEqualTo("DATABASE")
                .jsonPath("$.productCount").isEqualTo(1);

        client.get().uri("/v1/admin/product-catalogs/legacy-cat/products?tenant=" + TENANT)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].productId").isEqualTo("legacy_1")
                .jsonPath("$.items[0].priceMinor").isEqualTo(750);
    }

    private int catalogVersion() {
        byte[] body = client.get().uri("/v1/admin/product-catalogs/" + CATALOG + "?tenant=" + TENANT)
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("version").asInt();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String base() {
        return "/v1/admin/product-catalogs/" + CATALOG + "/products";
    }

    private String productsUri() {
        return base() + "?tenant=" + TENANT;
    }

    private String productUri(String id) {
        return base() + "/" + id + "?tenant=" + TENANT;
    }

    private String actionUri(String id, String action) {
        return base() + "/" + id + "/" + action + "?tenant=" + TENANT;
    }

    private static Map<String, Object> product(String id, String title, long priceMinor, Integer inventory) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("productId", id);
        m.put("title", title);
        m.put("priceMinor", priceMinor);
        m.put("inventory", inventory);
        m.put("attributes", Map.of("ingredient", "castor oil"));
        return m;
    }
}
