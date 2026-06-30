package com.yayatechandinnovations.yayaagentic.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlResolutionException;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlResolver;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class HttpToolDispatcherTest {

    private WireMockServer wireMock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void dispatches_get_and_projects_response() {
        wireMock.stubFor(get(urlEqualTo("/v1/accounts/1234"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1234\",\"balance\":42}")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor("/v1/accounts/{accountId}", "$.balance");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(),
                descriptor,
                Map.of("accountId", "1234"),
                execCtx(null),
                "call-1");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(result.value()).isEqualTo(42);
        wireMock.verify(getRequestedFor(urlEqualTo("/v1/accounts/1234")));
    }

    @Test
    void denies_when_egress_allowlist_misses() {
        HttpToolDispatcher dispatcher = dispatcher(
                List.of("api.example.com"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor("/v1/anything", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                execCtx(null), "call-2");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("egress");
        wireMock.verify(0, getRequestedFor(urlMatching(".*")));
    }

    @Test
    void surfaces_http_4xx_as_failed_with_status() {
        wireMock.stubFor(get(urlMatching("/missing.*"))
                .willReturn(aResponse().withStatus(404).withBody("no such resource")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor("/missing", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                execCtx(null), "call-3");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error())
                .contains("404")
                .contains("no such resource");
    }

    @Test
    void does_not_retry_4xx_even_when_policy_allows_retries() {
        wireMock.stubFor(get(urlMatching("/missing.*"))
                .willReturn(aResponse().withStatus(404).withBody("nope")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor("/missing", "$",
                new ToolPolicy(Duration.ofSeconds(2), 3, true, false));

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                execCtx(null), "call-4xx-noretry");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error())
                .contains("404")
                .doesNotContain("Retries exhausted");
        // Exactly one request — no retries on a 4xx that can't recover.
        wireMock.verify(1, getRequestedFor(urlMatching("/missing.*")));
    }

    @Test
    void retries_5xx_and_surfaces_underlying_status_when_exhausted() {
        wireMock.stubFor(get(urlMatching("/flaky.*"))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor("/flaky", "$",
                new ToolPolicy(Duration.ofSeconds(2), 2, true, false));

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                execCtx(null), "call-5xx-retry");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        // The error must report the actual upstream status, not the
        // "Retries exhausted" wrapper.
        assertThat(result.error()).contains("503");
        // Initial + 2 retries = 3 requests.
        wireMock.verify(3, getRequestedFor(urlMatching("/flaky.*")));
    }

    @Test
    void resolver_failure_surfaces_as_failed_tool_result() {
        BaseUrlResolver throwing = new BaseUrlResolver(null, new ObjectMapper()) {
            @Override
            public URI resolve(Ids.TenantId t, String path, String override) {
                throw new BaseUrlResolutionException("tenant has no host_base_url configured");
            }
        };
        HttpToolDispatcher dispatcher = dispatcher(List.of("localhost"), throwing);
        ToolDescriptor descriptor = httpGetDescriptor("/v1/anything", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                execCtx(null), "call-4");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("no host_base_url configured");
    }

    @Test
    void renders_principal_claims_into_url_template() {
        wireMock.stubFor(get(urlPathEqualTo("/api/ai/sales/tickets"))
                .withQueryParam("organizationId", equalTo("abc-123"))
                .withQueryParam("lotteryName", equalTo("real"))
                .willReturn(aResponse().withStatus(200).withBody("\"ok\"")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor(
                "/api/ai/sales/tickets?organizationId={principal.claims.org}&lotteryName={lotteryName}",
                "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor,
                Map.of("lotteryName", "real"),
                execCtxWithPrincipal(Map.of("org", "abc-123", "sub", "user-1")),
                "call-principal-claims");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/ai/sales/tickets"))
                .withQueryParam("organizationId", equalTo("abc-123"))
                .withQueryParam("lotteryName", equalTo("real")));
    }

    @Test
    void renders_principal_subject_and_tenant() {
        wireMock.stubFor(get(urlPathEqualTo("/whoami"))
                .withQueryParam("sub", equalTo("alice"))
                .withQueryParam("tenant", equalTo("acme"))
                .willReturn(aResponse().withStatus(200).withBody("\"ok\"")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor(
                "/whoami?sub={principal.subject}&tenant={principal.tenant}", "$");

        Principal p = new Principal("alice", new Ids.TenantId("acme"),
                Set.of(), Map.of(), Instant.now());
        ExecutionContext ctx = new ExecutionContext(
                p, new Ids.SessionId("s"), new Ids.TurnId("t"), "trace", Map.of());

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                ctx, "call-subject-tenant");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/whoami"))
                .withQueryParam("sub", equalTo("alice"))
                .withQueryParam("tenant", equalTo("acme")));
    }

    @Test
    void missing_claim_substitutes_empty_string() {
        wireMock.stubFor(get(urlPathEqualTo("/api/x"))
                .willReturn(aResponse().withStatus(200).withBody("\"ok\"")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor(
                "/api/x?organizationId={principal.claims.missing}", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                execCtxWithPrincipal(Map.of("sub", "user-1")),
                "call-missing-claim");

        // Dispatcher must not fail just because a claim is absent — the
        // downstream service decides how to respond. We assert the request
        // went out with the empty substitution.
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/x"))
                .withQueryParam("organizationId", equalTo("")));
    }

    @Test
    void renders_call_id_into_idempotency_header() {
        wireMock.stubFor(post(urlEqualTo("/api/mutate"))
                .willReturn(aResponse().withStatus(200).withBody("\"ok\"")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        HttpToolSpec spec = new HttpToolSpec(
                HttpToolSpec.HttpMethod.POST,
                "/api/mutate",
                Map.of("Idempotency-Key", "{session.id}-{turn.id}-{call.id}"),
                new HttpToolSpec.BodyTemplate("application/json", "{}"),
                new HttpToolSpec.ResponseProjection("$", Map.of()),
                HttpToolSpec.AuthForwarding.NONE);
        ToolDescriptor descriptor = new ToolDescriptor(
                new Ids.ToolId("mutate"),
                "{\"type\":\"object\"}",
                "{\"type\":\"object\"}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Http(spec),
                ToolPolicy.defaults());

        Principal p = new Principal("alice", new Ids.TenantId("default"),
                Set.of(), Map.of(), Instant.now());
        ExecutionContext ctx = new ExecutionContext(
                p, new Ids.SessionId("sess-9"), new Ids.TurnId("turn-3"),
                "trace", Map.of());

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                ctx, "call-42");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        wireMock.verify(postRequestedFor(urlEqualTo("/api/mutate"))
                .withHeader("Idempotency-Key", equalTo("sess-9-turn-3-call-42")));
    }

    @Test
    void llm_args_and_principal_claims_coexist_in_url_template() {
        wireMock.stubFor(get(urlPathEqualTo("/api/draft"))
                .withQueryParam("organizationId", equalTo("org-9"))
                .withQueryParam("lotteryName", equalTo("daily"))
                .willReturn(aResponse().withStatus(200).withBody("\"ok\"")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor(
                "/api/draft?organizationId={principal.claims.org}&lotteryName={lotteryName}",
                "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor,
                Map.of("lotteryName", "daily"),
                execCtxWithPrincipal(Map.of("org", "org-9")),
                "call-mixed");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/draft"))
                .withQueryParam("organizationId", equalTo("org-9"))
                .withQueryParam("lotteryName", equalTo("daily")));
    }

    @Test
    void percent_encodes_non_ascii_and_space_query_args() {
        // Reproduces the Tomcat "Invalid character found in the request target"
        // rejection: a value like "Palé" (UTF-8 0xC3 0xA9) and one containing a
        // space must be percent-encoded before they hit the wire, or the
        // receiving server rejects the request line per RFC 7230/3986.
        wireMock.stubFor(get(urlPathEqualTo("/api/ai/sales/payouts/resolve"))
                .withQueryParam("gameName", equalTo("Palé"))
                .withQueryParam("betDOP", equalTo("100 pesos"))
                .willReturn(aResponse().withStatus(200).withBody("\"ok\"")));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor(
                "/api/ai/sales/payouts/resolve?gameName={gameName}&betDOP={betDOP}", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor,
                Map.of("gameName", "Palé", "betDOP", "100 pesos"),
                execCtx(null),
                "call-encode");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        // WireMock matches against the decoded query value — proving the bytes
        // on the wire were properly percent-encoded (Palé -> Pal%C3%A9,
        // "100 pesos" -> "100%20pesos").
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/ai/sales/payouts/resolve"))
                .withQueryParam("gameName", equalTo("Palé"))
                .withQueryParam("betDOP", equalTo("100 pesos")));
    }

    @Test
    void forwards_host_base_url_override_to_resolver() {
        AtomicReference<String> seenOverride = new AtomicReference<>();
        BaseUrlResolver capturing = new BaseUrlResolver(null, new ObjectMapper()) {
            @Override
            public URI resolve(Ids.TenantId t, String path, String override) {
                seenOverride.set(override);
                return URI.create("http://localhost:" + wireMock.port() + path);
            }
        };
        wireMock.stubFor(get(urlEqualTo("/ping"))
                .willReturn(aResponse().withStatus(200).withBody("\"ok\"")));

        HttpToolDispatcher dispatcher = dispatcher(List.of("localhost"), capturing);
        ToolDescriptor descriptor = httpGetDescriptor("/ping", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                execCtx("https://staging.acme.com"), "call-5");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(seenOverride.get()).isEqualTo("https://staging.acme.com");
    }

    // ---- helpers ----------------------------------------------------

    private HttpToolDispatcher dispatcher(List<String> allowlist, BaseUrlResolver resolver) {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null, null,
                new YayaAgenticProperties.HttpTools(allowlist, true, Duration.ofSeconds(3)),
                null, null, null);
        HttpEgressPolicy egress = new HttpEgressPolicy(props);
        return new HttpToolDispatcher(WebClient.builder(), egress, resolver, new ObjectMapper(), props);
    }

    /** A resolver that ignores tenant lookup and just joins a fixed base with the rendered path. */
    private static BaseUrlResolver fixedResolver(String base) {
        return new BaseUrlResolver(null, new ObjectMapper()) {
            @Override
            public URI resolve(Ids.TenantId t, String path, String override) {
                String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                return URI.create(b + path);
            }
        };
    }

    private static ToolDescriptor httpGetDescriptor(String urlTemplate, String jsonPath) {
        return httpGetDescriptor(urlTemplate, jsonPath, ToolPolicy.defaults());
    }

    private static ToolDescriptor httpGetDescriptor(String urlTemplate, String jsonPath, ToolPolicy policy) {
        HttpToolSpec spec = new HttpToolSpec(
                HttpToolSpec.HttpMethod.GET,
                urlTemplate,
                Map.of(),
                null,
                new HttpToolSpec.ResponseProjection(jsonPath, Map.of()),
                HttpToolSpec.AuthForwarding.NONE);
        return new ToolDescriptor(
                new Ids.ToolId("test"),
                "{\"type\":\"object\"}",
                "{\"type\":\"object\"}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Http(spec),
                policy);
    }

    private static ExecutionContext execCtx(String hostBaseUrlOverride) {
        Principal p = new Principal("alice", new Ids.TenantId("default"),
                Set.of(), Map.of(), Instant.now());
        Map<String, Object> attrs = hostBaseUrlOverride == null
                ? Map.of()
                : Map.of("hostBaseUrlOverride", hostBaseUrlOverride);
        return new ExecutionContext(p, new Ids.SessionId("s"), new Ids.TurnId("t"), "trace", attrs);
    }

    private static ExecutionContext execCtxWithPrincipal(Map<String, Object> claims) {
        Principal p = new Principal("alice", new Ids.TenantId("default"),
                Set.of(), claims, Instant.now());
        return new ExecutionContext(p, new Ids.SessionId("s"), new Ids.TurnId("t"), "trace", Map.of());
    }
}
