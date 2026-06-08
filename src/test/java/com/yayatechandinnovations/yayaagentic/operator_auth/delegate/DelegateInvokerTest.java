package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the delegate's request shaping + criteria evaluation
 * + identity extraction. WireMock stands in for the host endpoint.
 *
 * <p>Each scenario maps to a worked example or footgun called out in
 * {@code docs/design/operator-auth-design.md} §5 / §13.</p>
 */
class DelegateInvokerTest {

    private WireMockServer wireMock;
    private DelegateInvoker invoker;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        // allow-private-networks = true so loopback (WireMock) isn't
        // blocked by the SSRF guard. Prod deployments leave it off.
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null,
                new YayaAgenticProperties.HttpTools(List.of(), true, Duration.ofSeconds(10)),
                null);
        invoker = new DelegateInvoker(WebClient.builder(), new ObjectMapper(), new HttpEgressPolicy(props));
    }

    @AfterEach
    void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void defaults_just_work_url_and_secret_only() {
        // Operator configures NOTHING but URL+secret. Endpoint accepts
        // {username, password} JSON and returns 200. Subject = typed username.
        wireMock.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        HttpDelegateConfig cfg = defaultsAt("/login");
        DelegateInvoker.ProbeResult r = invoker.invoke(cfg, "alice", "s3cret".toCharArray(), "attempt-1");

        assertThat(r.allowed()).isTrue();
        assertThat(r.evaluation().identity().subject()).isEqualTo("alice");
        wireMock.verify(postRequestedFor(urlEqualTo("/login"))
                .withHeader("X-Yaya-Source", equalTo("yaya-agentic"))
                .withHeader("X-Yaya-Source-Secret", equalTo("shh"))
                .withHeader("X-Yaya-Attempt-Id", equalTo("attempt-1"))
                .withRequestBody(equalToJson("{\"username\":\"alice\",\"password\":\"s3cret\"}")));
    }

    @Test
    void custom_body_template_with_email_field() {
        // Customer's endpoint accepts {email, password} unchanged.
        wireMock.stubFor(post(urlEqualTo("/login"))
                .withRequestBody(equalToJson("{\"email\":\"alice@acme\",\"password\":\"s3cret\"}"))
                .willReturn(aResponse().withStatus(200).withBody("{\"user\":{\"email\":\"alice@acme\"}}")));

        HttpDelegateConfig cfg = baseCfg("/login").withRequest(new RequestShape(
                "POST", Map.of(),
                new RequestShape.Body(RequestShape.BodyFormat.JSON,
                        "{\"email\":\"{{username}}\",\"password\":\"{{password}}\"}")));

        DelegateInvoker.ProbeResult r = invoker.invoke(
                cfg, "alice@acme", "s3cret".toCharArray(), "a");
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void success_by_body_when_endpoint_always_returns_200() {
        // Legacy endpoint always 200; signals success in the body.
        wireMock.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));
        wireMock.stubFor(post(urlEqualTo("/login-bad"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"err\"}")));

        SuccessCriteria suc = new SuccessCriteria(
                List.of(200), null,
                List.of(new SuccessCriteria.JsonPathEquals("$.status", "ok")));

        DelegateInvoker.ProbeResult good = invoker.invoke(
                baseCfg("/login").withSuccess(suc), "u", "p".toCharArray(), "a");
        assertThat(good.allowed()).isTrue();

        DelegateInvoker.ProbeResult bad = invoker.invoke(
                baseCfg("/login-bad").withSuccess(suc), "u", "p".toCharArray(), "a");
        assertThat(bad.allowed()).isFalse();
        assertThat(bad.auditReason()).isEqualTo("success_criteria_unmet");
    }

    @Test
    void failure_reason_path_lands_in_audit() {
        wireMock.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"err\",\"error\":{\"code\":\"locked\"}}")));

        SuccessCriteria suc = new SuccessCriteria(List.of(200), null,
                List.of(new SuccessCriteria.JsonPathEquals("$.status", "ok")));
        FailureMapping fail = new FailureMapping("$.error.code");

        DelegateInvoker.ProbeResult r = invoker.invoke(
                baseCfg("/login").withSuccess(suc).withFailure(fail),
                "u", "p".toCharArray(), "a");
        assertThat(r.allowed()).isFalse();
        assertThat(r.auditReason()).isEqualTo("locked");
    }

    @Test
    void identity_extraction_uses_subject_path_when_present() {
        wireMock.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"user\":{\"email\":\"real@acme\",\"name\":\"Real Roe\"}}")));

        IdentityMapping id = new IdentityMapping("$.user.email", "$.user.name", null);
        DelegateInvoker.ProbeResult r = invoker.invoke(
                baseCfg("/login").withIdentity(id), "typed-username", "p".toCharArray(), "a");

        assertThat(r.allowed()).isTrue();
        assertThat(r.evaluation().identity().subject()).isEqualTo("real@acme");
        assertThat(r.evaluation().identity().displayName()).isEqualTo("Real Roe");
    }

    @Test
    void identity_extraction_fail_denies_with_dedicated_reason() {
        // Endpoint 200s but the configured subjectPath doesn't resolve —
        // we must NOT mint a phantom operator. Design §5.3 / §13.
        wireMock.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        IdentityMapping id = new IdentityMapping("$.user.email", null, null);
        DelegateInvoker.ProbeResult r = invoker.invoke(
                baseCfg("/login").withIdentity(id), "typed", "p".toCharArray(), "a");

        assertThat(r.allowed()).isFalse();
        assertThat(r.auditReason()).isEqualTo("identity_extraction_failed");
    }

    @Test
    void reserved_headers_always_present_and_overrides_dropped() {
        wireMock.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        // Operator tries to spoof X-Yaya-Source. We must drop it AND log
        // the attempt — checked here by verifying the real value goes out.
        HttpDelegateConfig cfg = baseCfg("/login").withRequest(new RequestShape(
                "POST",
                Map.of(
                        "X-Yaya-Source", "spoofed",
                        "X-Custom", "kept"),
                new RequestShape.Body(RequestShape.BodyFormat.JSON,
                        "{\"username\":\"{{username}}\",\"password\":\"{{password}}\"}")));

        invoker.invoke(cfg, "u", "p".toCharArray(), "attempt-X");

        wireMock.verify(postRequestedFor(urlEqualTo("/login"))
                .withHeader("X-Yaya-Source", equalTo("yaya-agentic"))
                .withHeader("X-Custom", equalTo("kept"))
                .withHeader("X-Yaya-Attempt-Id", equalTo("attempt-X")));
    }

    @Test
    void password_is_redacted_in_echo_body() {
        wireMock.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        DelegateInvoker.ProbeResult r = invoker.invoke(
                defaultsAt("/login"), "alice", "super-secret".toCharArray(), "a");

        assertThat(r.request().body()).doesNotContain("super-secret");
        assertThat(r.request().body()).contains("***");
    }

    @Test
    void form_format_sends_application_x_www_form_urlencoded() {
        wireMock.stubFor(post(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        HttpDelegateConfig cfg = baseCfg("/login").withRequest(new RequestShape(
                "POST", Map.of(),
                new RequestShape.Body(RequestShape.BodyFormat.FORM, null)));

        invoker.invoke(cfg, "alice", "p w".toCharArray(), "a");

        wireMock.verify(postRequestedFor(urlEqualTo("/login"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("username=alice&password=p+w")));
    }

    @Test
    void basic_auth_format_sets_authorization_header_and_no_body() {
        wireMock.stubFor(get(urlEqualTo("/whoami"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        HttpDelegateConfig cfg = baseCfg("/whoami").withRequest(new RequestShape(
                "GET", Map.of(),
                new RequestShape.Body(RequestShape.BodyFormat.BASIC_AUTH, null)));

        invoker.invoke(cfg, "alice", "s3cret".toCharArray(), "a");

        wireMock.verify(getRequestedFor(urlEqualTo("/whoami"))
                .withHeader("Authorization", matching("Basic .+")));
    }

    @Test
    void transport_failure_falls_through_via_delegate_unreachable() {
        // Point at a closed port — WireMock isn't bound there.
        HttpDelegateConfig cfg = HttpDelegateConfig.disabled();
        HttpDelegateConfig live = new HttpDelegateConfig(
                true,
                "http://localhost:1",   // nothing listens
                "shh",
                Duration.ofMillis(500),
                false,
                RequestShape.defaults(),
                SuccessCriteria.defaults(),
                IdentityMapping.defaults(),
                FailureMapping.defaults());

        DelegateInvoker.ProbeResult r = invoker.invoke(live, "u", "p".toCharArray(), "a");
        assertThat(r.allowed()).isFalse();
        assertThat(r.auditReason()).startsWith("delegate_unreachable");
    }

    // ---- helpers -----------------------------------------------------

    private HttpDelegateConfig defaultsAt(String path) {
        return baseCfg(path);
    }

    private HttpDelegateConfig baseCfg(String path) {
        return new HttpDelegateConfig(
                true,
                "http://localhost:" + wireMock.port() + path,
                "shh",
                Duration.ofSeconds(2),
                false,                  // requireHttps off — we're hitting WireMock over http
                RequestShape.defaults(),
                SuccessCriteria.defaults(),
                IdentityMapping.defaults(),
                FailureMapping.defaults());
    }
}
