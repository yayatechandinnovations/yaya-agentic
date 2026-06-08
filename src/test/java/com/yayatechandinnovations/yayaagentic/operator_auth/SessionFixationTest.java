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
 * Logging in must revoke any pre-existing session bound to the inbound
 * cookie — otherwise an attacker who set a cookie value in the browser
 * could "wait" for the victim to authenticate and inherit the session.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class SessionFixationTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    @BeforeEach
    void prep() { rateLimiter.resetAll(); }

    @Test
    void second_login_invalidates_first_session_cookie() {
        // First login → cookieA works.
        var first = TestAuthDance.asBootstrap(client);
        first.get().uri("/v1/auth/me").exchange().expectStatus().isOk();

        // Pull the raw YAYA_SESSION value used by `first`. The simplest
        // path is to re-do the login dance and capture both cookies.
        var meRes = client.get().uri("/v1/auth/me").exchange().returnResult(byte[].class);
        String xsrf = TestAuthDance.readCookie(meRes.getResponseHeaders(), "XSRF-TOKEN");
        var loginA = client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", xsrf)
                .header("X-XSRF-TOKEN", xsrf)
                .bodyValue(Map.of("username", "admin", "password", "admin"))
                .exchange().expectStatus().isOk().returnResult(byte[].class);
        String sessionA = TestAuthDance.readCookie(loginA.getResponseHeaders(), "YAYA_SESSION");
        assertThat(sessionA).isNotNull();

        // Second login while presenting cookieA → fixation guard revokes
        // sessionA's row server-side before issuing sessionB.
        var loginB = client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", xsrf)
                .cookie("YAYA_SESSION", sessionA)   // present the old cookie
                .header("X-XSRF-TOKEN", xsrf)
                .bodyValue(Map.of("username", "admin", "password", "admin"))
                .exchange().expectStatus().isOk().returnResult(byte[].class);
        String sessionB = TestAuthDance.readCookie(loginB.getResponseHeaders(), "YAYA_SESSION");
        assertThat(sessionB).isNotNull().isNotEqualTo(sessionA);

        // Re-presenting sessionA must 401 — its row was revoked.
        client.get().uri("/v1/auth/me")
                .cookie("YAYA_SESSION", sessionA)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
