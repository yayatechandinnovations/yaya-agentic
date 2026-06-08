package com.yayatechandinnovations.yayaagentic.tool;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEgressPolicyTest {

    @Test
    void denies_when_allowlist_empty() {
        HttpEgressPolicy policy = policyWith(List.of(), false);
        var decision = policy.check(URI.create("https://api.example.com/x"));
        assertThat(decision).isInstanceOf(HttpEgressPolicy.Decision.Deny.class);
        assertThat(((HttpEgressPolicy.Decision.Deny) decision).reason()).contains("allowlist");
    }

    @Test
    void denies_loopback_even_when_allowlisted() {
        HttpEgressPolicy policy = policyWith(List.of("localhost"), false);
        var decision = policy.check(URI.create("http://localhost/anything"));
        assertThat(decision).isInstanceOf(HttpEgressPolicy.Decision.Deny.class);
        assertThat(((HttpEgressPolicy.Decision.Deny) decision).reason()).contains("loopback");
    }

    @Test
    void allows_loopback_when_allow_private_is_true() {
        HttpEgressPolicy policy = policyWith(List.of("localhost"), true);
        var decision = policy.check(URI.create("http://localhost:8080/anything"));
        assertThat(decision).isInstanceOf(HttpEgressPolicy.Decision.Allow.class);
    }

    @Test
    void denies_when_host_not_in_allowlist() {
        HttpEgressPolicy policy = policyWith(List.of("api.example.com"), true);
        var decision = policy.check(URI.create("https://other.example.com/"));
        assertThat(decision).isInstanceOf(HttpEgressPolicy.Decision.Deny.class);
    }

    @Test
    void glob_pattern_matches_anything() {
        // Use localhost + allow-private so the DNS + SSRF legs both pass;
        // the assertion is on the allowlist matcher itself.
        HttpEgressPolicy policy = policyWith(List.of("*"), true);
        var decision = policy.check(URI.create("http://localhost:8080/"));
        assertThat(decision).isInstanceOf(HttpEgressPolicy.Decision.Allow.class);
    }

    private static HttpEgressPolicy policyWith(List<String> allowlist, boolean allowPrivate) {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null,
                new YayaAgenticProperties.HttpTools(allowlist, allowPrivate, Duration.ofSeconds(10)),
                null);
        return new HttpEgressPolicy(props);
    }
}
