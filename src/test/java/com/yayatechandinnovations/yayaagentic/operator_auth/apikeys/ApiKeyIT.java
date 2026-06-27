package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport.ProvisionedClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end coverage of the API-keys feature against the §15 acceptance list.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.user-accounts.enabled=true",
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "yaya.agentic.api-keys.max-active-per-workspace=3",
        "yaya.agentic.api-keys.cache-ttl-seconds=60"
})
class ApiKeyIT {

    @Autowired WebTestClient base;
    @Autowired UserAccountsTestSupport users;
    @Autowired LoginRateLimiter loginRateLimiter;
    @Autowired FailedBearerRateLimiter bearerRateLimiter;
    @Autowired ApiKeyCache cache;
    @Autowired ApiKeyRepository repo;
    @Autowired SecretGenerator secrets;
    @Autowired ObjectMapper json;

    private ProvisionedClient alice;     // OWNER of her default workspace
    private ProvisionedClient bob;       // OWNER of a different workspace
    private String aliceWs;
    private String bobWs;

    @BeforeEach
    void setUp() {
        loginRateLimiter.resetAll();
        bearerRateLimiter.resetAll();
        cache.clear();
        alice = users.registerVerifyAndLogin(base, "alice-" + UUID.randomUUID() + "@t.io", "Aa1!Pass-strong-xyz", "Alice");
        bob = users.registerVerifyAndLogin(base, "bob-" + UUID.randomUUID() + "@t.io", "Bb2!Pass-strong-xyz", "Bob");
        aliceWs = alice.user().defaultWorkspaceCode();
        bobWs = bob.user().defaultWorkspaceCode();
    }

    // ------------------------------------------------------------------
    // Mint + show-once + list/get never echo the secret
    // ------------------------------------------------------------------

    @Test
    void owner_mints_member_key_secret_returned_once_then_never_again() {
        JsonNode created = createKey(alice, aliceWs, "ci-runner", "MEMBER", null);
        String secret = created.get("secret").asText();
        String keyId = created.get("id").asText();
        assertThat(secret).startsWith("yk_live_").hasSize(40);
        assertThat(created.get("keyPrefix").asText()).isEqualTo(secret.substring(0, 11));
        assertThat(created.get("role").asText()).isEqualTo("MEMBER");

        // List: prefix + metadata, never the secret.
        alice.client().get().uri("/v1/admin/workspaces/{w}/api-keys", aliceWs)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].keyPrefix").isEqualTo(secret.substring(0, 11))
                .jsonPath("$[0].secret").doesNotExist()
                .jsonPath("$[0].createdBy").isEqualTo("Alice")
                .jsonPath("$[0].status").isEqualTo("active");

        // Get one: still no secret.
        alice.client().get().uri("/v1/admin/workspaces/{w}/api-keys/{k}", aliceWs, keyId)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.secret").doesNotExist()
                .jsonPath("$.id").isEqualTo(keyId);
    }

    // ------------------------------------------------------------------
    // Bearer authn + workspace pinning
    // ------------------------------------------------------------------

    @Test
    void valid_bearer_reaches_pinned_workspace_resources() {
        String secret = createKey(alice, aliceWs, "reader", "MEMBER", null).get("secret").asText();
        bearer(secret).get().uri("/v1/admin/profiles?tenant={t}", aliceWs)
                .exchange().expectStatus().isOk();
    }

    @Test
    void bearer_for_workspace_A_is_404_against_workspace_B() {
        String secret = createKey(alice, aliceWs, "reader", "MEMBER", null).get("secret").asText();
        bearer(secret).get().uri("/v1/admin/profiles?tenant={t}", bobWs)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void member_key_against_admin_endpoint_is_403_insufficient_role() {
        JsonNode target = createKey(alice, aliceWs, "victim", "MEMBER", null);
        String memberSecret = createKey(alice, aliceWs, "weak", "MEMBER", null).get("secret").asText();
        // Revoke requires ADMIN; a MEMBER key is forbidden.
        bearer(memberSecret).method(HttpMethod.DELETE)
                .uri("/v1/admin/workspaces/{w}/api-keys/{k}", aliceWs, target.get("id").asText())
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void key_cannot_mint_a_key_even_at_owner_role() {
        String adminSecret = createKey(alice, aliceWs, "admin-key", "ADMIN", null).get("secret").asText();
        bearer(adminSecret).post().uri("/v1/admin/workspaces/{w}/api-keys", aliceWs)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "spawned", "role", "MEMBER"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void csrf_is_skipped_for_bearer_requests() {
        JsonNode target = createKey(alice, aliceWs, "rename-me", "MEMBER", null);
        String adminSecret = createKey(alice, aliceWs, "admin-key", "ADMIN", null).get("secret").asText();
        // PATCH with no X-XSRF-TOKEN — would be 403 for a cookie session, but
        // bearer requests are CSRF-exempt by construction.
        bearer(adminSecret).patch()
                .uri("/v1/admin/workspaces/{w}/api-keys/{k}", aliceWs, target.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "renamed"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("renamed");
    }

    @Test
    void bearer_wins_when_a_cookie_is_also_present() {
        String secret = createKey(alice, aliceWs, "reader", "MEMBER", null).get("secret").asText();
        base.get().uri("/v1/admin/profiles?tenant={t}", aliceWs)
                .header("Authorization", "Bearer " + secret)
                .cookie("YAYA_SESSION", "garbage-session-value")
                .exchange().expectStatus().isOk();
    }

    // ------------------------------------------------------------------
    // Revocation + expiry + malformed + query-string
    // ------------------------------------------------------------------

    @Test
    void revoked_key_is_rejected() {
        JsonNode created = createKey(alice, aliceWs, "doomed", "MEMBER", null);
        String secret = created.get("secret").asText();
        // Warm the path.
        bearer(secret).get().uri("/v1/admin/profiles?tenant={t}", aliceWs).exchange().expectStatus().isOk();

        alice.client().method(HttpMethod.DELETE)
                .uri("/v1/admin/workspaces/{w}/api-keys/{k}", aliceWs, created.get("id").asText())
                .exchange().expectStatus().isNoContent();

        bearer(secret).get().uri("/v1/admin/profiles?tenant={t}", aliceWs)
                .exchange().expectStatus().isUnauthorized();

        // Status now reflects revoked.
        alice.client().get().uri("/v1/admin/workspaces/{w}/api-keys/{k}", aliceWs, created.get("id").asText())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("revoked");
    }

    @Test
    void revoke_is_idempotent() {
        JsonNode created = createKey(alice, aliceWs, "doomed", "MEMBER", null);
        String url = "/v1/admin/workspaces/" + aliceWs + "/api-keys/" + created.get("id").asText();
        alice.client().method(HttpMethod.DELETE).uri(url).exchange().expectStatus().isNoContent();
        alice.client().method(HttpMethod.DELETE).uri(url).exchange().expectStatus().isNoContent();
    }

    @Test
    void expired_key_is_rejected_and_row_is_kept() {
        // Crafted directly: the create endpoint refuses a past expiry.
        String secret = secrets.generate();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        repo.save(new ApiKeyEntity(id, aliceWs, "expired", secrets.prefixOf(secret),
                secrets.sha256(secret), "MEMBER",
                UUID.fromString(alice.user().userId()), now.minusDays(2), now.minusDays(1)));

        bearer(secret).get().uri("/v1/admin/profiles?tenant={t}", aliceWs)
                .exchange().expectStatus().isUnauthorized();

        assertThat(repo.findById(id)).as("expired row is left in place for forensics").isPresent();
    }

    @Test
    void malformed_bearer_is_401() {
        bearer("yk_live_short").get().uri("/v1/admin/profiles?tenant={t}", aliceWs)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void wrong_environment_marker_is_rejected() {
        bearer("yk_test_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345").get()
                .uri("/v1/admin/profiles?tenant={t}", aliceWs)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void api_key_in_query_string_is_400() {
        base.get().uri("/v1/admin/profiles?tenant={t}&api_key=secret", aliceWs)
                .exchange().expectStatus().isBadRequest();
    }

    // ------------------------------------------------------------------
    // Cap + role bounding + isolation
    // ------------------------------------------------------------------

    @Test
    void per_workspace_cap_returns_409_on_overflow() {
        // cap = 3 (test property).
        createKey(alice, aliceWs, "cap-1", "MEMBER", null);
        createKey(alice, aliceWs, "cap-2", "MEMBER", null);
        createKey(alice, aliceWs, "cap-3", "MEMBER", null);
        alice.client().post().uri("/v1/admin/workspaces/{w}/api-keys", aliceWs)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "k4-over", "role", "MEMBER"))
                .exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.error").isEqualTo("api_key_cap_reached");
    }

    @Test
    void invalid_role_is_rejected() {
        alice.client().post().uri("/v1/admin/workspaces/{w}/api-keys", aliceWs)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "bad-role", "role", "PLATFORM_ADMIN"))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void bob_cannot_list_alice_keys() {
        createKey(alice, aliceWs, "alice-key", "MEMBER", null);
        bob.client().get().uri("/v1/admin/workspaces/{w}/api-keys", aliceWs)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void rate_limit_locks_out_after_too_many_failed_bearer_attempts() {
        // 20/min budget (default). 21 failures from this IP → lockout (429) for
        // all bearer requests, including an otherwise-valid one.
        String valid = createKey(alice, aliceWs, "valid", "MEMBER", null).get("secret").asText();
        for (int i = 0; i < 21; i++) {
            bearer("yk_live_badbadbadbadbadbadbadbadbadbad0").get()
                    .uri("/v1/admin/profiles?tenant={t}", aliceWs)
                    .exchange();
        }
        bearer(valid).get().uri("/v1/admin/profiles?tenant={t}", aliceWs)
                .exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private JsonNode createKey(ProvisionedClient as, String ws, String name, String role, String expiresAt) {
        var body = new java.util.HashMap<String, Object>();
        body.put("name", name);
        body.put("role", role);
        if (expiresAt != null) body.put("expiresAt", expiresAt);
        byte[] out = as.client().post().uri("/v1/admin/workspaces/{w}/api-keys", ws)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody();
        try {
            return json.readTree(out);
        } catch (Exception e) {
            throw new AssertionError("bad create response", e);
        }
    }

    private WebTestClient bearer(String secret) {
        return base.mutate().defaultHeader("Authorization", "Bearer " + secret).build();
    }
}
