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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Admin catalog CRUD: an INLINE catalog with a product + cover image round-trips
 * through create → GET detail (so the console can edit-in-place), and the
 * discarded DATABASE kind is rejected. See {@code ecommerce-chat-design.md} §15.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class CatalogAdminIT {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "catalog-test";

    @BeforeEach
    void auth() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        var req = new AdminDtos.TenantRequest(
                TENANT, "Catalog Test", "https://catalog-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    @Test
    void inlineCatalogWithProductRoundTripsThroughDetail() {
        Map<String, Object> product = Map.of(
                "id", Map.of("value", "p_soap"),
                "title", "Castor Oil Soap",
                "shortDescription", "gentle",
                "price", Map.of("amountMinor", 899, "currency", "DOP"),
                "imageUrls", List.of(),
                "category", "",
                "attributes", Map.of("composition", "castor oil", "inventory", "10"),
                "variants", List.of(),
                "availability", "IN_STOCK");
        Map<String, Object> body = Map.of(
                "tenant", TENANT, "id", "body-care", "displayName", "Body Care",
                "currency", "DOP", "imageUrl", "http://img.example/cover.png",
                "locationKind", "INLINE",
                "location", Map.of("products", List.of(product)));

        client.post().uri("/v1/admin/product-catalogs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.locationKind").isEqualTo("INLINE")
                .jsonPath("$.productCount").isEqualTo(1)
                .jsonPath("$.imageUrl").isEqualTo("http://img.example/cover.png");

        // GET detail returns the full location so the admin form can rehydrate.
        client.get().uri("/v1/admin/product-catalogs/body-care?tenant=" + TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.currency").isEqualTo("DOP")
                .jsonPath("$.location.products[0].id.value").isEqualTo("p_soap")
                .jsonPath("$.location.products[0].price.amountMinor").isEqualTo(899)
                .jsonPath("$.location.products[0].attributes.composition").isEqualTo("castor oil");
    }

    @Test
    void databaseKindIsAcceptedAsManagedCatalog() {
        // The redesign promotes DATABASE to the default managed catalog (Pillar A);
        // it now creates a catalog whose products live in the products table (0 to start).
        Map<String, Object> body = Map.of(
                "tenant", TENANT, "id", "managed-store", "displayName", "Managed Store",
                "currency", "USD", "locationKind", "DATABASE", "location", Map.of());
        client.post().uri("/v1/admin/product-catalogs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.locationKind").isEqualTo("DATABASE")
                .jsonPath("$.productCount").isEqualTo(0);

        // The location's logicalName defaults to the catalog id when omitted.
        client.get().uri("/v1/admin/product-catalogs/managed-store?tenant=" + TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.location.logicalName").isEqualTo("managed-store");
    }
}
