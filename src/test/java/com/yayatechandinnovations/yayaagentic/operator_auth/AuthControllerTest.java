package com.yayatechandinnovations.yayaagentic.operator_auth;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end coverage of the operator-auth login flow that AdminControllerTest
 * doesn't exercise: failed login (generic message, no strategy leak),
 * authenticated /me, and logout (session revoked → subsequent /me is 401).
 *
 * <p>Phase 5 — every test seeds a CSRF cookie via TestAuthDance and the
 * rate limiter is reset so the suite doesn't trip 5/min/username.</p>
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class AuthControllerTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    /** Pre-authenticated client carrying the XSRF-TOKEN cookie + header
     *  but no session — used by the deny-path tests so their POSTs aren't
     *  rejected by the CSRF filter before the auth path runs. */
    private WebTestClient csrfClient;

    @BeforeEach
    void prep() {
        rateLimiter.resetAll();
        var meRes = client.get().uri("/v1/auth/me").exchange().returnResult(byte[].class);
        String xsrf = TestAuthDance.readCookie(meRes.getResponseHeaders(), "XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        csrfClient = client.mutate()
                .defaultCookie("XSRF-TOKEN", xsrf)
                .defaultHeader("X-XSRF-TOKEN", xsrf)
                .build();
    }

    @Test
    void rejects_admin_without_session() {
        // GET is not state-changing, so CSRF doesn't apply — and the
        // operator filter denies regardless of XSRF presence.
        client.get().uri("/v1/admin/profiles?tenant=default")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void login_with_wrong_password_returns_generic_401() {
        csrfClient.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                // Generic message — never leaks which strategy denied or why.
                .jsonPath("$.message").isEqualTo("Invalid username or password");
    }

    @Test
    void login_with_unknown_username_returns_generic_401() {
        csrfClient.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "nobody", "password", "whatever"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Invalid username or password");
    }

    @Test
    void successful_login_issues_cookie_and_me_returns_operator() {
        WebTestClient authed = TestAuthDance.asBootstrap(client);

        authed.get().uri("/v1/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.subject").isEqualTo("admin")
                .jsonPath("$.source").isEqualTo("BOOTSTRAP");
    }

    @Test
    void logout_revokes_session_so_next_call_is_401() {
        WebTestClient authed = TestAuthDance.asBootstrap(client);

        authed.post().uri("/v1/auth/logout")
                .exchange()
                .expectStatus().isNoContent();

        authed.get().uri("/v1/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
