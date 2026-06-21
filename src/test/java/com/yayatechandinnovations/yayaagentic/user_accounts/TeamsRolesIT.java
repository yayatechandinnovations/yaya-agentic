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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Invitations + role transitions. Covers PRD AC-4 (invite → accept →
 * write → demote → write blocked) and the ADMIN-vs-OWNER nuance: ADMIN
 * cannot promote to OWNER or demote an existing OWNER.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.user-accounts.enabled=true",
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class TeamsRolesIT {

    @Autowired WebTestClient base;
    @Autowired UserAccountsTestSupport users;
    @Autowired LoginRateLimiter rateLimiter;

    private ProvisionedClient alice;
    private String aliceWorkspace;

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        String aliceEmail = "alice-" + UUID.randomUUID() + "@test.io";
        alice = users.registerVerifyAndLogin(base, aliceEmail, "Aa1!Pass-very-strong", "Alice");
        aliceWorkspace = alice.user().defaultWorkspaceCode();
    }

    // ------------------------------------------------------------------
    // Signed-in accept path
    // ------------------------------------------------------------------

    @Test
    void invite_then_signed_in_accept_grants_membership_and_enables_writes() {
        // Bob registers first so he's signed in for the accept.
        String bobEmail = "bob-" + UUID.randomUUID() + "@test.io";
        ProvisionedClient bob = users.registerVerifyAndLogin(base, bobEmail, "Bb2!Pass-very-strong", "Bob");

        // Alice invites Bob as MEMBER.
        alice.client().post().uri("/v1/admin/workspaces/{c}/invitations", aliceWorkspace)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", bobEmail, "role", "MEMBER"))
                .exchange()
                .expectStatus().isCreated();

        // Bob accepts via the token in his inbox.
        String token = users.grabRawInvitationToken(bobEmail);
        bob.client().get().uri(uri -> uri.path("/v1/auth/invitations/accept").queryParam("token", token).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("accepted")
                .jsonPath("$.workspaceId").isEqualTo(aliceWorkspace)
                .jsonPath("$.role").isEqualTo("MEMBER");

        // Bob can now CREATE a profile in Alice's workspace (MEMBER write).
        bob.client().post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "tenant", aliceWorkspace,
                        "id", "bob-profile",
                        "displayName", "Bob's Profile",
                        "introOneLiner", "x",
                        "systemPromptFragment", "x"))
                .exchange()
                .expectStatus().isCreated();

        // ...but cannot manage members (needs ADMIN).
        bob.client().get().uri("/v1/admin/workspaces/{c}/invitations", aliceWorkspace)
                .exchange()
                .expectStatus().isNotFound();    // 404 — insufficient role behaves the same as no membership
    }

    // ------------------------------------------------------------------
    // Role transitions: ADMIN can manage members below it; cannot touch OWNER
    // ------------------------------------------------------------------

    @Test
    void admin_cannot_promote_member_to_owner() {
        // Set up: alice invites bob as ADMIN, carol as MEMBER.
        String bobEmail = "bob-" + UUID.randomUUID() + "@test.io";
        String carolEmail = "carol-" + UUID.randomUUID() + "@test.io";
        ProvisionedClient bob = users.registerVerifyAndLogin(base, bobEmail, "Bb2!Pass-very-strong", "Bob");
        ProvisionedClient carol = users.registerVerifyAndLogin(base, carolEmail, "Cc3!Pass-very-strong", "Carol");

        inviteAndAccept(bob, bobEmail, "ADMIN");
        inviteAndAccept(carol, carolEmail, "MEMBER");

        // Bob (ADMIN) tries to promote Carol to OWNER → 403.
        bob.client().patch().uri("/v1/admin/workspaces/{c}/members/{u}", aliceWorkspace, carol.user().userId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "OWNER"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void admin_cannot_demote_an_owner() {
        // Alice (OWNER) → promote Bob to OWNER → Bob (ADMIN-tier) tries to demote Alice.
        // To set this up cleanly: invite bob as ADMIN; alice promotes him to OWNER.
        String bobEmail = "bob-" + UUID.randomUUID() + "@test.io";
        ProvisionedClient bob = users.registerVerifyAndLogin(base, bobEmail, "Bb2!Pass-very-strong", "Bob");
        inviteAndAccept(bob, bobEmail, "ADMIN");

        // Make a third user Carol who's an ADMIN — she'll be the actor that
        // tries to demote an OWNER.
        String carolEmail = "carol-" + UUID.randomUUID() + "@test.io";
        ProvisionedClient carol = users.registerVerifyAndLogin(base, carolEmail, "Cc3!Pass-very-strong", "Carol");
        inviteAndAccept(carol, carolEmail, "ADMIN");

        // Carol (ADMIN) tries to demote Alice (OWNER) → 403.
        carol.client().patch().uri("/v1/admin/workspaces/{c}/members/{u}", aliceWorkspace, alice.user().userId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "MEMBER"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void admin_can_change_member_to_viewer_and_writes_become_blocked() {
        String bobEmail = "bob-" + UUID.randomUUID() + "@test.io";
        ProvisionedClient bob = users.registerVerifyAndLogin(base, bobEmail, "Bb2!Pass-very-strong", "Bob");
        inviteAndAccept(bob, bobEmail, "MEMBER");

        // Bob can create a profile as MEMBER.
        bob.client().post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "tenant", aliceWorkspace,
                        "id", "bob-before-demote",
                        "displayName", "Before",
                        "introOneLiner", "x",
                        "systemPromptFragment", "x"))
                .exchange()
                .expectStatus().isCreated();

        // Alice demotes Bob to VIEWER.
        alice.client().patch().uri("/v1/admin/workspaces/{c}/members/{u}", aliceWorkspace, bob.user().userId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "VIEWER"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.role").isEqualTo("VIEWER");

        // Bob can still READ profiles...
        bob.client().get().uri("/v1/admin/profiles?tenant={t}", aliceWorkspace)
                .exchange()
                .expectStatus().isOk();

        // ...but cannot WRITE anymore (VIEWER < MEMBER → 404, existence-leak rule).
        bob.client().post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "tenant", aliceWorkspace,
                        "id", "bob-after-demote",
                        "displayName", "After",
                        "introOneLiner", "x",
                        "systemPromptFragment", "x"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------
    // Revoked invitation cannot be accepted
    // ------------------------------------------------------------------

    @Test
    void revoked_invitation_cannot_be_redeemed() {
        String bobEmail = "bob-" + UUID.randomUUID() + "@test.io";
        ProvisionedClient bob = users.registerVerifyAndLogin(base, bobEmail, "Bb2!Pass-very-strong", "Bob");

        var sent = alice.client().post().uri("/v1/admin/workspaces/{c}/invitations", aliceWorkspace)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", bobEmail, "role", "MEMBER"))
                .exchange()
                .expectStatus().isCreated()
                .returnResult(String.class);
        String body = String.join("", sent.getResponseBody().collectList().block());
        String invId = extractJsonString(body, "\"id\":\"", "\"");

        alice.client().delete().uri("/v1/admin/workspaces/{c}/invitations/{id}", aliceWorkspace, invId)
                .exchange()
                .expectStatus().isNoContent();

        String token = users.grabRawInvitationToken(bobEmail);
        bob.client().get().uri(uri -> uri.path("/v1/auth/invitations/accept").queryParam("token", token).build())
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.GONE);
    }

    // ------------------------------------------------------------------
    // Anonymous accept → needs_signup
    // ------------------------------------------------------------------

    @Test
    void anonymous_accept_returns_needs_signup_with_email_and_carry_token() {
        String unknownEmail = "newcomer-" + UUID.randomUUID() + "@test.io";
        alice.client().post().uri("/v1/admin/workspaces/{c}/invitations", aliceWorkspace)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", unknownEmail, "role", "MEMBER"))
                .exchange()
                .expectStatus().isCreated();

        String token = users.grabRawInvitationToken(unknownEmail);
        base.get().uri(uri -> uri.path("/v1/auth/invitations/accept").queryParam("token", token).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("needs_signup")
                .jsonPath("$.email").isEqualTo(unknownEmail)
                .jsonPath("$.workspaceId").isEqualTo(aliceWorkspace);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void inviteAndAccept(ProvisionedClient invitee, String email, String role) {
        alice.client().post().uri("/v1/admin/workspaces/{c}/invitations", aliceWorkspace)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "role", role))
                .exchange()
                .expectStatus().isCreated();
        String token = users.grabRawInvitationToken(email);
        invitee.client().get().uri(uri -> uri.path("/v1/auth/invitations/accept").queryParam("token", token).build())
                .exchange()
                .expectStatus().isOk();
    }

    private static String extractJsonString(String body, String startMarker, String endMarker) {
        int start = body.indexOf(startMarker);
        if (start < 0) throw new AssertionError("missing " + startMarker + " in " + body);
        start += startMarker.length();
        int end = body.indexOf(endMarker, start);
        return body.substring(start, end);
    }
}
