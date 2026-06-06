package com.yayatechandinnovations.yayaagentic.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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

        HttpToolDispatcher dispatcher = dispatcherWithAllowlist(List.of("localhost"));
        ToolDescriptor descriptor = httpGetDescriptor(
                "http://localhost:" + wireMock.port() + "/v1/accounts/{accountId}",
                "$.balance");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(),
                descriptor,
                Map.of("accountId", "1234"),
                execCtx(),
                "call-1");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(result.value()).isEqualTo(42);
        wireMock.verify(getRequestedFor(urlEqualTo("/v1/accounts/1234")));
    }

    @Test
    void denies_when_egress_allowlist_misses() {
        HttpToolDispatcher dispatcher = dispatcherWithAllowlist(List.of("api.example.com"));
        ToolDescriptor descriptor = httpGetDescriptor(
                "http://localhost:" + wireMock.port() + "/v1/anything", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(), execCtx(), "call-2");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("egress");
        wireMock.verify(0, getRequestedFor(urlMatching(".*")));
    }

    @Test
    void surfaces_http_4xx_as_failed_with_status() {
        wireMock.stubFor(get(urlMatching("/missing.*")).willReturn(aResponse().withStatus(404)));

        HttpToolDispatcher dispatcher = dispatcherWithAllowlist(List.of("localhost"));
        ToolDescriptor descriptor = httpGetDescriptor(
                "http://localhost:" + wireMock.port() + "/missing", "$");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(), execCtx(), "call-3");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("404");
    }

    // ---- helpers ----------------------------------------------------

    private HttpToolDispatcher dispatcherWithAllowlist(List<String> allowlist) {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null,
                new YayaAgenticProperties.HttpTools(allowlist, true, Duration.ofSeconds(3)));
        HttpEgressPolicy egress = new HttpEgressPolicy(props);
        return new HttpToolDispatcher(WebClient.builder(), egress, new ObjectMapper(), props);
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

    private static ExecutionContext execCtx() {
        return new ExecutionContext(null, null, null, "trace", Map.of());
    }
}
