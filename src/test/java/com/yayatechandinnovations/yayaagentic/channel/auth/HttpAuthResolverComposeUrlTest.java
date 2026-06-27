package com.yayatechandinnovations.yayaagentic.channel.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAuthResolverComposeUrlTest {

    @Test
    void joins_base_and_path() {
        assertThat(HttpAuthResolver.composeUrl(
                "https://acme.example.com", "/yaya/resolve-identity"))
                .hasToString("https://acme.example.com/yaya/resolve-identity");
    }

    @Test
    void strips_trailing_slash_from_base() {
        assertThat(HttpAuthResolver.composeUrl(
                "https://acme.example.com/", "/yaya/resolve-identity"))
                .hasToString("https://acme.example.com/yaya/resolve-identity");
    }

    @Test
    void requires_leading_slash_on_path() {
        assertThatThrownBy(() -> HttpAuthResolver.composeUrl(
                "https://acme.example.com", "yaya/resolve-identity"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("httpPath must start with '/'");
    }

    @Test
    void requires_base() {
        assertThatThrownBy(() -> HttpAuthResolver.composeUrl(null, "/yaya"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hostBaseUrl required");
    }
}
