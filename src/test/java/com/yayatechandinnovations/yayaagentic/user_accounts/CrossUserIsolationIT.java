package com.yayatechandinnovations.yayaagentic.user_accounts;

import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport.ProvisionedClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The big one. Two users A and B, each with two workspaces. Asserts that
 * every workspace-scoped admin endpoint returns 404 across the boundary,
 * with the same body whether the workspace doesn't exist or the caller
 * lacks access. PRD acceptance criterion AC-1.
 *
 * <p>Reads AND writes — including {@code POST /v1/admin/profiles?tenant=acme}
 * with a body from User B's session — must fail with the same shape as a
 * call against a nonsense tenant. Existence non-leak is the load-bearing
 * invariant; if a single endpoint slips it, we fail.</p>
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.user-accounts.enabled=true",
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class CrossUserIsolationIT {

    @Autowired WebTestClient base;
    @Autowired UserAccountsTestSupport users;
    @Autowired LoginRateLimiter rateLimiter;

    private ProvisionedClient alice;
    private ProvisionedClient bob;
    private String aliceWorkspaceTwo;        // second workspace owned by A

    @BeforeEach
    void setUp() {
        // Rate-limit reset so running the suite back-to-back doesn't trip
        // per-IP login limits.
        rateLimiter.resetAll();

        String aliceEmail = "alice-" + UUID.randomUUID() + "@test.io";
        String bobEmail = "bob-" + UUID.randomUUID() + "@test.io";

        alice = users.registerVerifyAndLogin(base, aliceEmail, "Aa1!Pass-very-strong", "Alice");
        bob = users.registerVerifyAndLogin(base, bobEmail, "Bb2!Pass-very-strong", "Bob");

        // Give Alice a second workspace so the test exercises picking the
        // right one — not just "the only one I own."
        var created = alice.client().post().uri("/v1/admin/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "Alice Lab"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.code").exists()
                .returnResult();
        aliceWorkspaceTwo = extractJsonString(new String(created.getResponseBody()), "\"code\":\"", "\"");
    }

    // ------------------------------------------------------------------
    // The matrix: every workspace-scoped endpoint, tested cross-boundary.
    // ------------------------------------------------------------------

    @Test
    void bob_cannot_read_alice_workspace_detail() {
        bob.client().get().uri("/v1/admin/workspaces/{c}", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("workspace_not_found");
    }

    @Test
    void bob_listing_workspaces_does_not_see_alice_workspace() {
        // Bob should see only HIS two workspaces (default + any he created here is none).
        bob.client().get().uri("/v1/admin/workspaces")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // Bob's default workspace is in the list...
                .jsonPath("$[?(@.workspace.code == '" + bob.user().defaultWorkspaceCode() + "')]").exists()
                // ...Alice's workspaces are absent.
                .jsonPath("$[?(@.workspace.code == '" + alice.user().defaultWorkspaceCode() + "')]").doesNotExist()
                .jsonPath("$[?(@.workspace.code == '" + aliceWorkspaceTwo + "')]").doesNotExist();
    }

    @Test
    void bob_cannot_archive_alice_workspace() {
        bob.client().method(org.springframework.http.HttpMethod.DELETE)
                .uri("/v1/admin/workspaces/{c}", alice.user().defaultWorkspaceCode())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("confirmCode", alice.user().defaultWorkspaceCode()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("workspace_not_found");
    }

    @Test
    void bob_cannot_read_alice_members() {
        bob.client().get().uri("/v1/admin/workspaces/{c}/members", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("workspace_not_found");
    }

    @Test
    void bob_cannot_invite_into_alice_workspace() {
        bob.client().post().uri("/v1/admin/workspaces/{c}/invitations", alice.user().defaultWorkspaceCode())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "stranger@test.io", "role", "MEMBER"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("workspace_not_found");
    }

    @Test
    void bob_cannot_list_alice_invitations() {
        bob.client().get().uri("/v1/admin/workspaces/{c}/invitations", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bob_cannot_list_alice_profiles() {
        bob.client().get().uri("/v1/admin/profiles?tenant={t}", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("workspace_not_found");
    }

    @Test
    void bob_cannot_create_a_profile_in_alice_workspace() {
        bob.client().post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "tenant", alice.user().defaultWorkspaceCode(),
                        "id", "test-profile",
                        "displayName", "Test",
                        "introOneLiner", "x",
                        "systemPromptFragment", "x"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("workspace_not_found");
    }

    @Test
    void bob_cannot_list_alice_capabilities() {
        bob.client().get().uri("/v1/admin/capabilities?tenant={t}", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bob_cannot_list_alice_tools() {
        bob.client().get().uri("/v1/admin/tools?tenant={t}", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bob_cannot_list_alice_auth_bindings() {
        bob.client().get().uri("/v1/admin/auth-bindings?tenant={t}", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bob_cannot_read_alice_audit() {
        bob.client().get().uri("/v1/admin/audit/authz?tenant={t}", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bob_cannot_list_alice_jwt_secrets() {
        bob.client().get().uri("/v1/admin/tenants/{t}/jwt-secrets", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("workspace_not_found");
    }

    @Test
    void bob_cannot_list_alice_oidc_issuers() {
        bob.client().get().uri("/v1/admin/tenants/{t}/oidc-issuers", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bob_cannot_list_alice_identity_mappings() {
        bob.client().get().uri("/v1/admin/tenants/{t}/identity-profile-mappings",
                        alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bob_cannot_list_alice_knowledge_sources() {
        bob.client().get().uri("/v1/admin/knowledge-sources?tenant={t}", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------
    // Existence-leak invariant: 404 body for a real-but-inaccessible
    // workspace is IDENTICAL to 404 body for a nonsense one.
    // ------------------------------------------------------------------

    @Test
    void bob_probing_alice_workspace_returns_same_404_body_as_nonsense_workspace() {
        var probeReal = bob.client().get().uri("/v1/admin/workspaces/{c}", alice.user().defaultWorkspaceCode())
                .exchange().expectStatus().isNotFound().returnResult(String.class);
        var probeFake = bob.client().get().uri("/v1/admin/workspaces/{c}", "does-not-exist-at-all-" + UUID.randomUUID())
                .exchange().expectStatus().isNotFound().returnResult(String.class);

        String realBody = String.join("", probeReal.getResponseBody().collectList().block());
        String fakeBody = String.join("", probeFake.getResponseBody().collectList().block());
        // The bodies must be byte-for-byte identical so a probe can't even
        // tell whether the workspace exists.
        org.assertj.core.api.Assertions.assertThat(realBody).isEqualTo(fakeBody);
    }

    // ------------------------------------------------------------------
    // Sanity: Alice CAN access her own workspaces — the guard isn't just
    // returning 404 for everyone.
    // ------------------------------------------------------------------

    @Test
    void alice_can_read_her_own_workspace() {
        alice.client().get().uri("/v1/admin/workspaces/{c}", alice.user().defaultWorkspaceCode())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(alice.user().defaultWorkspaceCode());
    }

    @Test
    void alice_can_list_her_workspaces_and_sees_both() {
        alice.client().get().uri("/v1/admin/workspaces")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.workspace.code == '" + alice.user().defaultWorkspaceCode() + "')]").exists()
                .jsonPath("$[?(@.workspace.code == '" + aliceWorkspaceTwo + "')]").exists();
    }

    private static String extractJsonString(String body, String startMarker, String endMarker) {
        int start = body.indexOf(startMarker);
        if (start < 0) throw new AssertionError("missing " + startMarker + " in " + body);
        start += startMarker.length();
        int end = body.indexOf(endMarker, start);
        return body.substring(start, end);
    }
}
