package com.yayatechandinnovations.yayaagentic.operator_auth;

import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
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
 * Direct coverage of {@code CsrfWebFilter}.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class CsrfFilterTest {

    @Autowired WebTestClient client;

    @Test
    void post_without_csrf_token_is_403() {
        client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("FORBIDDEN");
    }

    @Test
    void post_with_mismatched_csrf_token_is_403() {
        client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", "cookie-value")
                .header("X-XSRF-TOKEN", "different-header-value")
                .bodyValue(Map.of("username", "admin", "password", "admin"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void get_mints_xsrf_token_cookie_even_on_401() {
        // /v1/auth/me 401s for unauthenticated, but the CSRF filter still
        // sets the cookie so the SPA can do the synchronizer-token dance
        // on the very first request.
        var result = client.get().uri("/v1/auth/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .returnResult(byte[].class);
        String token = TestAuthDance.readCookie(result.getResponseHeaders(), "XSRF-TOKEN");
        assertThat(token).isNotBlank();
    }

    @Test
    void post_with_matching_csrf_token_passes_to_handler() {
        var meRes = client.get().uri("/v1/auth/me").exchange().returnResult(byte[].class);
        String xsrf = TestAuthDance.readCookie(meRes.getResponseHeaders(), "XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        // Wrong credentials, but at least we get past CSRF — proving the
        // filter is the only thing that would have blocked us first.
        client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", xsrf)
                .header("X-XSRF-TOKEN", xsrf)
                .bodyValue(Map.of("username", "nobody", "password", "wrong"))
                .exchange()
                .expectStatus().isUnauthorized();   // not 403 — auth filter denial, CSRF passed
    }
}
