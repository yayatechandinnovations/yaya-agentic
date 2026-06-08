package com.yayatechandinnovations.yayaagentic.operator_auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared "do the CSRF + login handshake and return a client primed with
 * both cookies + the X-XSRF-TOKEN header" helper. Used by tests that
 * exercise the admin surface end-to-end.
 */
public final class TestAuthDance {

    private TestAuthDance() {}

    /** Logs in as the bootstrap operator (admin / admin) and returns a
     *  mutated client that carries XSRF + session for every future call. */
    public static WebTestClient asBootstrap(WebTestClient client) {
        // 1. GET to mint the XSRF-TOKEN cookie. /v1/auth/me 401s for
        //    unauthenticated, but the CSRF filter still sets the cookie.
        var meRes = client.get().uri("/v1/auth/me").exchange().returnResult(byte[].class);
        String xsrf = readCookie(meRes.getResponseHeaders(), "XSRF-TOKEN");
        assertThat(xsrf).as("CsrfWebFilter should mint XSRF-TOKEN on first GET").isNotNull();

        // 2. POST /login with both the cookie and the matching header.
        var loginRes = client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", xsrf)
                .header("X-XSRF-TOKEN", xsrf)
                .bodyValue(Map.of("username", "admin", "password", "admin"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(byte[].class);
        String session = readCookie(loginRes.getResponseHeaders(), "YAYA_SESSION");
        assertThat(session).as("login should set YAYA_SESSION cookie").isNotNull();

        // 3. Prime the client.
        return client.mutate()
                .defaultCookie("XSRF-TOKEN", xsrf)
                .defaultCookie("YAYA_SESSION", session)
                .defaultHeader("X-XSRF-TOKEN", xsrf)
                .build();
    }

    public static String readCookie(HttpHeaders headers, String name) {
        List<String> values = headers.get(HttpHeaders.SET_COOKIE);
        if (values == null) return null;
        String needle = name + "=";
        for (String v : values) {
            if (v.startsWith(needle)) {
                int end = v.indexOf(';');
                return end < 0 ? v.substring(needle.length())
                               : v.substring(needle.length(), end);
            }
        }
        return null;
    }
}
