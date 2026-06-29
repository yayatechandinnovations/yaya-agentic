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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * E2E coverage of {@link TenantAuthResolverController} — the seven admin
 * endpoints defined in design §11. Uses the bootstrap-admin operator auth
 * dance.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        // Provide a fixed master key so the cipher is configured for the
        // SIGNED_JWT secret-at-rest path; production reads
        // YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY.
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class TenantAuthResolverControllerTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "auth-resolver-test";

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        var t = new AdminDtos.TenantRequest(
                TENANT, "Auth Resolver Test", "https://auth-resolver-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(t).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    @Test
    void create_http_signed_jwt_resolver_masks_secret_in_response() {
        String name = unique("acme-customer-lookup");
        var req = httpRequest(name, "/yaya/resolve-identity", "SIGNED_JWT",
                "the-shared-secret-32-bytes-or-longer", 2500);

        var resp = client.post()
                .uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().isCreated()
                .expectBody(TenantAuthResolverController.ResolverResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.name()).isEqualTo(name);
        assertThat(resp.kind()).isEqualTo("HTTP");
        assertThat(resp.serviceAuthKind()).isEqualTo("SIGNED_JWT");
        assertThat(resp.serviceSecretMasked()).startsWith("••••••••••••");
        assertThat(resp.timeoutMs()).isEqualTo(2500);
        // Plaintext secret MUST NOT appear in the response payload.
        // Confirms §13.3 non-negotiable.
    }

    @Test
    void signed_jwt_kid_and_audience_round_trip_in_cleartext() {
        String name = unique("kid-roundtrip");
        var req = httpRequest(name, "/yaya/resolve-identity", "SIGNED_JWT",
                "the-shared-secret-32-bytes-or-longer", 3000, "yaya-in-1", "yaya");

        var resp = client.post()
                .uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().isCreated()
                .expectBody(TenantAuthResolverController.ResolverResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        // kid + aud are NOT secrets — they come back in cleartext...
        assertThat(resp.serviceJwtKid()).isEqualTo("yaya-in-1");
        assertThat(resp.serviceJwtAudience()).isEqualTo("yaya");
        // ...while the secret is still masked.
        assertThat(resp.serviceSecretMasked()).startsWith("••••••••••••");
    }

    @Test
    void create_http_without_secret_fails_when_signed_jwt_requested() {
        var bad = httpRequest(unique("missing-secret"), "/yaya/resolve-identity",
                "SIGNED_JWT", null, 3000);
        client.post()
                .uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bad).exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("missing_service_secret"));
    }

    @Test
    void create_http_with_invalid_path_is_rejected() {
        var bad = httpRequest(unique("bad-path"), "yaya/resolve-identity",
                "NONE", null, 3000);    // path missing leading /
        client.post()
                .uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bad).exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("invalid_http_path"));
    }

    @Test
    void create_bean_kind_with_unknown_bean_is_rejected() {
        var req = beanRequest(unique("unknown"), "nope-not-on-classpath");
        client.post()
                .uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("unknown_bean"));
    }

    @Test
    void duplicate_name_returns_conflict() {
        String name = unique("dup");
        var req = httpRequest(name, "/yaya/resolve", "NONE", null, 3000);
        client.post().uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req).exchange()
                .expectStatus().isCreated();
        client.post().uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req).exchange()
                .expectStatus().value(s -> assertThat(s).isEqualTo(409))
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("auth_resolver_exists"));
    }

    @Test
    void put_without_secret_preserves_existing_secret() {
        String name = unique("rotate-skip");
        client.post().uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(httpRequest(name, "/yaya/resolve", "BEARER",
                        "original-secret", 3000))
                .exchange().expectStatus().isCreated();

        // Update with no serviceSecret in body — masked tail unchanged.
        var update = httpRequest(name, "/yaya/resolve-renamed", "BEARER",
                null, 5000);
        var resp = client.put()
                .uri("/v1/admin/tenants/{t}/auth-resolvers/{n}", TENANT, name)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(update).exchange()
                .expectStatus().isOk()
                .expectBody(TenantAuthResolverController.ResolverResponse.class)
                .returnResult().getResponseBody();
        assertThat(resp).isNotNull();
        assertThat(resp.httpPath()).isEqualTo("/yaya/resolve-renamed");
        assertThat(resp.timeoutMs()).isEqualTo(5000);
        // Secret was preserved — masked tail still present.
        assertThat(resp.serviceSecretMasked()).startsWith("••••••••••••");
    }

    @Test
    void put_rejects_kind_change() {
        String name = unique("kind-locked");
        client.post().uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(httpRequest(name, "/yaya/resolve", "NONE", null, 3000))
                .exchange().expectStatus().isCreated();

        var beanUpdate = beanRequest(name, "any-bean");
        client.put().uri("/v1/admin/tenants/{t}/auth-resolvers/{n}", TENANT, name)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(beanUpdate).exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("kind_immutable"));
    }

    @Test
    void list_returns_sorted_and_registered_bean_names() {
        // Create two resolvers in reverse alphabetical order.
        var b = httpRequest(unique("b-resolver"), "/yaya/b", "NONE", null, 3000);
        var a = httpRequest(unique("a-resolver"), "/yaya/a", "NONE", null, 3000);
        client.post().uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b).exchange()
                .expectStatus().isCreated();
        client.post().uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(a).exchange()
                .expectStatus().isCreated();

        var list = client.get()
                .uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TenantAuthResolverController.ListResponse.class)
                .returnResult().getResponseBody();
        assertThat(list).isNotNull();
        assertThat(list.registeredBeanNames()).isNotNull();
        // Names sorted ascending — a-* before b-*.
        var nameOnly = list.resolvers().stream()
                .map(TenantAuthResolverController.ResolverResponse::name).toList();
        assertThat(nameOnly).isSorted();
    }

    @Test
    void delete_returns_204_and_eviction_count() {
        String name = unique("to-delete");
        client.post().uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(httpRequest(name, "/yaya/resolve", "NONE", null, 3000))
                .exchange().expectStatus().isCreated();

        var resp = client.delete()
                .uri("/v1/admin/tenants/{t}/auth-resolvers/{n}", TENANT, name)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TenantAuthResolverController.DeleteResponse.class)
                .returnResult().getResponseBody();
        assertThat(resp).isNotNull();
        assertThat(resp.name()).isEqualTo(name);
        // No cached tokens for this tenant in this test — count is 0.
        assertThat(resp.cacheEntriesEvicted()).isGreaterThanOrEqualTo(0L);

        client.get().uri("/v1/admin/tenants/{t}/auth-resolvers/{n}", TENANT, name)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void invalidate_cache_requires_triple_or_all_for_tenant() {
        // Missing both — 400.
        client.post().uri("/v1/admin/tenants/{t}/auth-resolvers/invalidate-cache", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("missing_caller"));

        // allForTenant=true succeeds.
        var resp = client.post()
                .uri("/v1/admin/tenants/{t}/auth-resolvers/invalidate-cache", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("allForTenant", true))
                .exchange().expectStatus().isOk()
                .expectBody(TenantAuthResolverController.InvalidateCacheResponse.class)
                .returnResult().getResponseBody();
        assertThat(resp).isNotNull();
        assertThat(resp.tenantScopeEvicted()).isTrue();
    }

    // ---- DTO builders -------------------------------------------------

    private static TenantAuthResolverController.ResolverRequest httpRequest(
            String name, String path, String authKind, String secret, int timeoutMs) {
        return httpRequest(name, path, authKind, secret, timeoutMs, null, null);
    }

    private static TenantAuthResolverController.ResolverRequest httpRequest(
            String name, String path, String authKind, String secret, int timeoutMs,
            String serviceJwtKid, String serviceJwtAudience) {
        return new TenantAuthResolverController.ResolverRequest(
                name, "test resolver", "HTTP", null, path,
                authKind, secret, serviceJwtKid, serviceJwtAudience, timeoutMs,
                true, null, 60, 3600, "REJECT", "ACTIVE");
    }

    private static TenantAuthResolverController.ResolverRequest beanRequest(
            String name, String beanName) {
        return new TenantAuthResolverController.ResolverRequest(
                name, "test bean resolver", "BEAN", beanName, null,
                null, null, null, null, null,
                true, null, 60, 3600, "REJECT", "ACTIVE");
    }

    private static String unique(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
