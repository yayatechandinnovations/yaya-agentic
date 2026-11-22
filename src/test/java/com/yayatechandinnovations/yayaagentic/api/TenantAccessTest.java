package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.ApiKeyCache;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.FailedBearerRateLimiter;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport.ProvisionedClient;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Who may read and change a tenant (docs/design/tenant-registry-design.md §3):
 * VIEWER+ of the workspace reads it, ADMIN+ changes its settings, and only a
 * platform admin creates one, lists them all or changes a lifecycle. Another
 * workspace's tenant is 404, never 403. Also the provisioning sequence a setup
 * script uses: bootstrap login → create tenant → ADMIN key → read with the key.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.user-accounts.enabled=true",
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class TenantAccessTest {

    @Autowired WebTestClient base;
    @Autowired UserAccountsTestSupport users;
    @Autowired LoginRateLimiter loginRateLimiter;
    @Autowired FailedBearerRateLimiter bearerRateLimiter;
    @Autowired ApiKeyCache cache;
    @Autowired ObjectMapper json;

    private ProvisionedClient alice;
    private ProvisionedClient bob;
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

    @Test
    void a_viewer_key_reads_its_own_tenant_and_nothing_else() {
        WebTestClient viewer = bearer(mintKey(alice.client(), aliceWs, "VIEWER"));

        viewer.get().uri("/v1/admin/tenants/{t}", aliceWs).exchange().expectStatus().isOk();
        viewer.get().uri("/v1/admin/tenants/{t}/health", aliceWs).exchange().expectStatus().isOk();
        assertThat(listedIds(viewer)).containsExactly(aliceWs);

        viewer.get().uri("/v1/admin/tenants/{t}", bobWs).exchange().expectStatus().isNotFound();
        viewer.get().uri("/v1/admin/tenants/{t}/health", bobWs).exchange().expectStatus().isNotFound();
        viewer.put().uri("/v1/admin/tenants/{t}", bobWs).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(settings("Taken over")).exchange().expectStatus().isNotFound();
        viewer.patch().uri("/v1/admin/tenants/{t}", bobWs).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rename("Taken over")).exchange().expectStatus().isNotFound();
        viewer.post().uri("/v1/admin/tenants/{t}/suspend", bobWs).exchange().expectStatus().isForbidden();
        viewer.post().uri("/v1/admin/tenants/{t}/archive", bobWs).exchange().expectStatus().isForbidden();

        // Its own tenant: reading is not changing.
        viewer.patch().uri("/v1/admin/tenants/{t}", aliceWs).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rename("Renamed by a viewer")).exchange().expectStatus().isForbidden();

        bob.client().get().uri("/v1/admin/tenants/{t}", bobWs).exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantResponse.class)
                .value(t -> assertThat(t.status()).isEqualTo("ACTIVE"));
    }

    @Test
    void a_workspace_admin_changes_its_own_tenant_but_not_its_lifecycle_or_anyone_elses() {
        alice.client().put().uri("/v1/admin/tenants/{t}", aliceWs).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(settings("Alice's shop")).exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantResponse.class)
                .value(t -> {
                    assertThat(t.displayName()).isEqualTo("Alice's shop");
                    assertThat(t.hostBaseUrl()).isEqualTo("https://alice.example");
                });
        WebTestClient adminKey = bearer(mintKey(alice.client(), aliceWs, "ADMIN"));
        adminKey.patch().uri("/v1/admin/tenants/{t}", aliceWs).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rename("Alice's shop, by key")).exchange().expectStatus().isOk();

        assertThat(listedIds(alice.client())).contains(aliceWs).doesNotContain(bobWs);
        alice.client().put().uri("/v1/admin/tenants/{t}", bobWs).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(settings("Taken over")).exchange().expectStatus().isNotFound();
        alice.client().post().uri("/v1/admin/tenants/{t}/suspend", aliceWs).exchange().expectStatus().isForbidden();
        alice.client().post().uri("/v1/admin/tenants/{t}/resume", aliceWs).exchange().expectStatus().isForbidden();
        alice.client().post().uri("/v1/admin/tenants/{t}/archive", aliceWs).exchange().expectStatus().isForbidden();
        adminKey.post().uri("/v1/admin/tenants/{t}/suspend", aliceWs).exchange().expectStatus().isForbidden();
        alice.client().post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTenant("alice-second-" + shortId())).exchange().expectStatus().isForbidden();

        // A clone into a workspace she cannot see is refused before anything is read.
        alice.client().post().uri("/v1/admin/tenants/{t}/profiles/{p}/{v}/clone", aliceWs, "any-profile", 1)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("destinationTenant", bobWs))
                .exchange().expectStatus().isNotFound();

        bob.client().get().uri("/v1/admin/tenants/{t}", bobWs).exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantResponse.class)
                .value(t -> assertThat(t.displayName()).isNotEqualTo("Taken over"));
    }

    /**
     * What {@code agentic/setup.py} does on a fresh deployment: sign in as the
     * bootstrap operator, create the tenant, mint an ADMIN key for its
     * workspace, and from then on use only the key.
     */
    @Test
    void the_platform_admin_creates_a_tenant_mints_its_admin_key_and_the_key_reads_it() {
        // 1. Any GET mints the XSRF-TOKEN cookie (the call itself is 401).
        var first = base.get().uri("/v1/admin/tenants").exchange().returnResult(byte[].class);
        assertThat(first.getStatus().value()).isEqualTo(401);
        String xsrf = TestAuthDance.readCookie(first.getResponseHeaders(), "XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        // 2. Bootstrap login with the cookie and the matching header.
        var login = base.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", xsrf).header("X-XSRF-TOKEN", xsrf)
                .bodyValue(Map.of("username", "admin", "password", "admin"))
                .exchange().expectStatus().isOk()
                .returnResult(byte[].class);
        String session = TestAuthDance.readCookie(login.getResponseHeaders(), "YAYA_SESSION");
        assertThat(session).isNotNull();
        WebTestClient operator = base.mutate()
                .defaultCookie("XSRF-TOKEN", xsrf).defaultCookie("YAYA_SESSION", session)
                .defaultHeader("X-XSRF-TOKEN", xsrf).build();

        // 3. The tenant.
        String tenant = "setup-" + shortId();
        operator.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTenant(tenant)).exchange().expectStatus().isCreated();

        // 4. An ADMIN key for its workspace — the secret is shown once.
        String secret = mintKey(operator, tenant, "ADMIN");
        assertThat(secret).startsWith("yk_");

        // 5. The key alone reads (and may change) its tenant, and sees only it.
        WebTestClient key = bearer(secret);
        key.get().uri("/v1/admin/tenants/{t}", tenant).exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantResponse.class)
                .value(t -> {
                    assertThat(t.id()).isEqualTo(tenant);
                    assertThat(t.status()).isEqualTo("ACTIVE");
                });
        key.patch().uri("/v1/admin/tenants/{t}", tenant).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rename("Set up by script")).exchange().expectStatus().isOk();
        assertThat(listedIds(key)).containsExactly(tenant);
        key.get().uri("/v1/admin/tenants/{t}", aliceWs).exchange().expectStatus().isNotFound();
        key.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTenant("setup-by-key-" + shortId())).exchange().expectStatus().isForbidden();

        // The platform admin lists every workspace and owns the lifecycle.
        assertThat(listedIds(operator)).contains(tenant, aliceWs, bobWs);
        operator.post().uri("/v1/admin/tenants/{t}/suspend", tenant).exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantResponse.class)
                .value(t -> assertThat(t.status()).isEqualTo("SUSPENDED"));
        operator.post().uri("/v1/admin/tenants/{t}/resume", tenant).exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantResponse.class)
                .value(t -> assertThat(t.status()).isEqualTo("ACTIVE"));
    }

    /* ------------------------------ helpers ------------------------------ */

    private String mintKey(WebTestClient as, String ws, String role) {
        byte[] out = as.post().uri("/v1/admin/workspaces/{w}/api-keys", ws)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "key-" + role.toLowerCase(), "role", role))
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody();
        try {
            return json.readTree(out).get("secret").asText();
        } catch (Exception e) {
            throw new AssertionError("bad api-key response", e);
        }
    }

    private List<String> listedIds(WebTestClient as) {
        byte[] out = as.get().uri("/v1/admin/tenants").exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        try {
            List<String> ids = new ArrayList<>();
            for (JsonNode t : json.readTree(out)) ids.add(t.get("id").asText());
            return ids;
        } catch (Exception e) {
            throw new AssertionError("bad tenant list", e);
        }
    }

    private WebTestClient bearer(String secret) {
        return base.mutate().defaultHeader("Authorization", "Bearer " + secret).build();
    }

    private static AdminDtos.TenantRequest newTenant(String id) {
        return new AdminDtos.TenantRequest(id, "Setup " + id, "https://" + id + ".example",
                List.of(), List.of(), true, null, null, Map.of());
    }

    private static AdminDtos.TenantRequest settings(String displayName) {
        return new AdminDtos.TenantRequest(null, displayName, "https://alice.example",
                List.of(), List.of(), true, null, null, Map.of());
    }

    private static AdminDtos.TenantRequest rename(String displayName) {
        return new AdminDtos.TenantRequest(null, displayName, null, null, null, null, null, null, null);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
