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
 * Verifies the per-username 5/min limit on {@code POST /v1/auth/login}.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class LoginRateLimitTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private String xsrf;

    @BeforeEach
    void prep() {
        rateLimiter.resetAll();
        var meRes = client.get().uri("/v1/auth/me").exchange().returnResult(byte[].class);
        xsrf = TestAuthDance.readCookie(meRes.getResponseHeaders(), "XSRF-TOKEN");
    }

    @Test
    void sixth_failed_attempt_for_same_username_returns_429() {
        // 5 failed attempts — all should 401 (auth denial), not 429.
        for (int i = 0; i < 5; i++) {
            client.post().uri("/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .cookie("XSRF-TOKEN", xsrf)
                    .header("X-XSRF-TOKEN", xsrf)
                    .bodyValue(Map.of("username", "limited-user", "password", "wrong-" + i))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        // 6th attempt for the same username — rate-limit kicks in BEFORE
        // the chain runs, so we see 429 + Retry-After.
        var sixth = client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", xsrf)
                .header("X-XSRF-TOKEN", xsrf)
                .bodyValue(Map.of("username", "limited-user", "password", "wrong-6"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Too many login attempts. Try again later.")
                .returnResult();

        String retryAfter = sixth.getResponseHeaders().getFirst("Retry-After");
        assertThat(retryAfter).as("429 must carry Retry-After").isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isPositive();
    }

    @Test
    void different_usernames_have_independent_buckets() {
        // 5 attempts against user-A
        for (int i = 0; i < 5; i++) {
            client.post().uri("/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .cookie("XSRF-TOKEN", xsrf)
                    .header("X-XSRF-TOKEN", xsrf)
                    .bodyValue(Map.of("username", "user-A", "password", "wrong"))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        // user-B is unaffected — bucket is per-username.
        client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", xsrf)
                .header("X-XSRF-TOKEN", xsrf)
                .bodyValue(Map.of("username", "user-B", "password", "wrong"))
                .exchange()
                .expectStatus().isUnauthorized();   // auth-deny, not rate-limit
    }
}
