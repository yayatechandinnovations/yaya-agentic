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
        wireMock.stubFor(get(urlMatching("/missing.*")).willReturn(aResponse().withStatus(404)));

        HttpToolDispatcher dispatcher = dispatcher(
                List.of("localhost"),
                fixedResolver("http://localhost:" + wireMock.port()));
        ToolDescriptor descriptor = httpGetDescriptor("/missing", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                execCtx(null), "call-3");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("404");
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
                "default", "default", null, null, null, null,
                new YayaAgenticProperties.HttpTools(allowlist, true, Duration.ofSeconds(3)),
                null);
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
                ToolPolicy.defaults());
    }

    private static ExecutionContext execCtx(String hostBaseUrlOverride) {
        Principal p = new Principal("alice", new Ids.TenantId("default"),
                Set.of(), Map.of(), Instant.now());
        Map<String, Object> attrs = hostBaseUrlOverride == null
                ? Map.of()
                : Map.of("hostBaseUrlOverride", hostBaseUrlOverride);
        return new ExecutionContext(p, new Ids.SessionId("s"), new Ids.TurnId("t"), "trace", attrs);
    }
}
