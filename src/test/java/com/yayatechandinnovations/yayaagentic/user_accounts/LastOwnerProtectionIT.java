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
 * The workspace OWNER invariant: every workspace has at least one OWNER
 * at all times. Removing or demoting the last OWNER returns 409 with the
 * user-safe reason verbatim. PRD AC-5.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.user-accounts.enabled=true",
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class LastOwnerProtectionIT {

    @Autowired WebTestClient base;
    @Autowired UserAccountsTestSupport users;
    @Autowired LoginRateLimiter rateLimiter;

    private ProvisionedClient alice;
    private String workspace;

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        String aliceEmail = "alice-" + UUID.randomUUID() + "@test.io";
        alice = users.registerVerifyAndLogin(base, aliceEmail, "Aa1!Pass-very-strong", "Alice");
        workspace = alice.user().defaultWorkspaceCode();
    }

    @Test
    void sole_owner_cannot_be_demoted() {
        alice.client().patch().uri("/v1/admin/workspaces/{c}/members/{u}", workspace, alice.user().userId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "ADMIN"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.error").isEqualTo("last_owner")
                .jsonPath("$.message").value(s -> org.assertj.core.api.Assertions.assertThat((String) s)
                        .contains("at least one owner"));
    }

    @Test
    void sole_owner_cannot_be_removed() {
        alice.client().delete().uri("/v1/admin/workspaces/{c}/members/{u}", workspace, alice.user().userId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.error").isEqualTo("last_owner");
    }

    @Test
    void second_owner_can_be_promoted_then_first_owner_can_be_demoted() {
        // Promote Bob to OWNER alongside Alice.
        String bobEmail = "bob-" + UUID.randomUUID() + "@test.io";
        ProvisionedClient bob = users.registerVerifyAndLogin(base, bobEmail, "Bb2!Pass-very-strong", "Bob");

        // Invite + accept Bob as MEMBER first.
        alice.client().post().uri("/v1/admin/workspaces/{c}/invitations", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", bobEmail, "role", "MEMBER"))
                .exchange()
                .expectStatus().isCreated();
        String token = users.grabRawInvitationToken(bobEmail);
        bob.client().get().uri(uri -> uri.path("/v1/auth/invitations/accept").queryParam("token", token).build())
                .exchange()
                .expectStatus().isOk();

        // Alice (OWNER) promotes Bob to OWNER.
        alice.client().patch().uri("/v1/admin/workspaces/{c}/members/{u}", workspace, bob.user().userId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "OWNER"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.role").isEqualTo("OWNER");

        // Now there are two OWNERs — Alice can be demoted.
        alice.client().patch().uri("/v1/admin/workspaces/{c}/members/{u}", workspace, alice.user().userId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "ADMIN"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.role").isEqualTo("ADMIN");

        // ...but Bob (now the sole OWNER) cannot now be demoted.
        bob.client().patch().uri("/v1/admin/workspaces/{c}/members/{u}", workspace, bob.user().userId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "ADMIN"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.error").isEqualTo("last_owner");
    }

    @Test
    void sole_owner_can_archive_the_workspace_to_dissolve_it() {
        // Archive is a different escape hatch than removing a member.
        alice.client().method(org.springframework.http.HttpMethod.DELETE)
                .uri("/v1/admin/workspaces/{c}", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("confirmCode", workspace))
                .exchange()
                .expectStatus().isNoContent();
    }
}
