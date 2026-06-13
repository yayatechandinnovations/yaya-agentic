package com.yayatechandinnovations.yayaagentic.auth.playground;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActAsMaterializerTest {

    private final ActAsMaterializer materializer = new ActAsMaterializer();

    @Test
    void empty_actAs_returns_input_unchanged() {
        AuthContext base = ctx(Map.of("X-Test", "1"));

        AuthContext out = materializer.applyIfPresent(base, Optional.empty());

        assertThat(out).isSameAs(base);
    }

    @Test
    void null_actAs_returns_input_unchanged() {
        AuthContext base = ctx(Map.of("X-Test", "1"));

        AuthContext out = materializer.applyIfPresent(base, null);

        assertThat(out).isSameAs(base);
    }

    @Test
    void rawToken_bearer_sets_Authorization_header() {
        AuthContext base = ctx(Map.of());

        AuthContext out = materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken("Bearer", "eyJhbGc.payload.sig")));

        assertThat(out.headers()).containsEntry("Authorization", "Bearer eyJhbGc.payload.sig");
    }

    @Test
    void rawToken_basic_scheme_accepted() {
        AuthContext base = ctx(Map.of());

        AuthContext out = materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken("Basic", "dXNlcjpwYXNz")));

        assertThat(out.headers()).containsEntry("Authorization", "Basic dXNlcjpwYXNz");
    }

    @Test
    void rawToken_null_scheme_defaults_to_Bearer() {
        AuthContext base = ctx(Map.of());

        AuthContext out = materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken(null, "tok")));

        assertThat(out.headers()).containsEntry("Authorization", "Bearer tok");
    }

    @Test
    void rawToken_lowercases_then_title_cases_scheme() {
        AuthContext base = ctx(Map.of());

        AuthContext out = materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken("BEARER", "tok")));

        assertThat(out.headers()).containsEntry("Authorization", "Bearer tok");
    }

    @Test
    void rawToken_strips_operator_cookie_from_runtime_headers() {
        AuthContext base = ctx(Map.of(
                "Cookie", "YAYA_SESSION=op-secret",
                "X-XSRF-TOKEN", "csrf-echo",
                "Origin", "https://admin.example.com"));

        AuthContext out = materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken("Bearer", "tok")));

        assertThat(out.headers())
                .doesNotContainKey("Cookie")
                .doesNotContainKey("X-XSRF-TOKEN")
                .containsEntry("Authorization", "Bearer tok")
                .containsEntry("Origin", "https://admin.example.com");
    }

    @Test
    void rawToken_strips_cookie_header_case_insensitively() {
        AuthContext base = ctx(Map.of(
                "cookie", "YAYA_SESSION=op-secret",
                "x-xsrf-token", "csrf"));

        AuthContext out = materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken("Bearer", "tok")));

        assertThat(out.headers())
                .doesNotContainKey("cookie")
                .doesNotContainKey("x-xsrf-token");
    }

    @Test
    void rawToken_blank_token_returns_422() {
        AuthContext base = ctx(Map.of());

        assertThatThrownBy(() -> materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken("Bearer", ""))))
                .isInstanceOf(ActAsMaterializer.InvalidActAsException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void rawToken_null_token_returns_422() {
        AuthContext base = ctx(Map.of());

        assertThatThrownBy(() -> materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken("Bearer", null))))
                .isInstanceOf(ActAsMaterializer.InvalidActAsException.class);
    }

    @Test
    void rawToken_disallowed_scheme_returns_422() {
        AuthContext base = ctx(Map.of());

        assertThatThrownBy(() -> materializer.applyIfPresent(base,
                Optional.of(new ActAs.RawToken("Negotiate", "krb-blob"))))
                .isInstanceOf(ActAsMaterializer.InvalidActAsException.class)
                .hasMessageContaining("Negotiate");
    }

    private static AuthContext ctx(Map<String, String> headers) {
        return new AuthContext(new Ids.TenantId("default"),
                new HashMap<>(headers), Optional.empty());
    }
}
