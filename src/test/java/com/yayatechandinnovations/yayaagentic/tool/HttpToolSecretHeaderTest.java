package com.yayatechandinnovations.yayaagentic.tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlResolver;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code {secret.value}} ({@code docs/guides/admin-panel-sessions.md} §15):
 * set by the platform for one request, rendered in a header only, never
 * supplied by an argument, and absent from results, diagnostics and logs.
 */
class HttpToolSecretHeaderTest {

    private static final String CODE = "481516";
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"proposalId\":{\"type\":\"string\"}}}";
    private static final Map<String, String> HEADERS = Map.of(
            "X-Agent-Secret", "{secret.value}", "X-Agent-Channel", "{session.channel}");

    private WireMockServer wireMock;
    private ListAppender<ILoggingEvent> logs;
    private Logger dispatcherLog;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        dispatcherLog = (Logger) LoggerFactory.getLogger(HttpToolDispatcher.class);
        logs = new ListAppender<>();
        logs.start();
        dispatcherLog.addAppender(logs);
    }

    @AfterEach
    void stop() {
        dispatcherLog.detachAppender(logs);
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void the_secret_is_sent_in_the_header_once_and_the_next_call_has_no_such_header() {
        ToolDescriptor tool = tool(HEADERS, "{\"proposalId\":\"{proposalId}\"}");

        Turn.ToolResult first = dispatch(tool, Map.of("proposalId", "p-1"),
                ctx(Map.of("channel", "app", HttpToolDispatcher.SECRET_ATTRIBUTE, CODE)));
        Turn.ToolResult second = dispatch(tool, Map.of("proposalId", "p-1"), ctx(Map.of("channel", "app")));

        List<LoggedRequest> sent = wireMock.findAll(anyRequestedFor(anyUrl()));
        assertThat(sent).hasSize(2);
        LoggedRequest withSecret = sent.stream().filter(r -> r.containsHeader("X-Agent-Secret")).findFirst()
                .orElseThrow();
        assertThat(withSecret.getHeader("X-Agent-Secret")).isEqualTo(CODE);
        assertThat(withSecret.getBodyAsString()).isEqualTo("{\"proposalId\":\"p-1\"}");
        // Without a secret the header is not sent at all, and nothing is logged about it.
        assertThat(sent).filteredOn(r -> !r.containsHeader("X-Agent-Secret")).hasSize(1)
                .allMatch(r -> "app".equals(r.getHeader("X-Agent-Channel")));
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("secret"));

        for (Turn.ToolResult result : List.of(first, second)) {
            assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
            assertThat(String.valueOf(result)).doesNotContain(CODE);
        }
    }

    @Test
    void an_argument_named_secret_never_stands_in_for_the_platforms_value() {
        ToolDescriptor tool = tool(HEADERS, "{\"proposalId\":\"{proposalId}\"}");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("proposalId", "p-1");
        args.put("secret", Map.of("value", "000000"));

        dispatch(tool, args, ctx(Map.of()));

        LoggedRequest sent = wireMock.findAll(anyRequestedFor(anyUrl())).get(0);
        assertThat(sent.containsHeader("X-Agent-Secret")).isFalse();
    }

    @Test
    void the_secret_is_not_available_to_an_address_or_a_body() {
        ToolDescriptor tool = new ToolDescriptor(new Ids.ToolId("secret_tool"), SCHEMA, "{\"type\":\"object\"}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Http(new HttpToolSpec(HttpToolSpec.HttpMethod.POST, "/api/x?code={secret.value}",
                        Map.of(), new HttpToolSpec.BodyTemplate("application/json", "{\"code\":\"{secret.value}\"}"),
                        new HttpToolSpec.ResponseProjection("$", Map.of()), HttpToolSpec.AuthForwarding.NONE)),
                ToolPolicy.defaults());

        dispatch(tool, Map.of(), ctx(Map.of(HttpToolDispatcher.SECRET_ATTRIBUTE, CODE)));

        LoggedRequest sent = wireMock.findAll(anyRequestedFor(anyUrl())).get(0);
        assertThat(sent.getUrl()).doesNotContain(CODE);
        assertThat(sent.getBodyAsString()).isEqualTo("{\"code\":\"\"}");
    }

    @Test
    void a_failed_call_does_not_name_the_secret() {
        wireMock.resetAll();
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500).withBody("boom")));
        ToolDescriptor tool = tool(HEADERS, "{}");

        Turn.ToolResult result = dispatch(tool, Map.of(), ctx(Map.of(HttpToolDispatcher.SECRET_ATTRIBUTE, CODE)));

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(String.valueOf(result)).doesNotContain(CODE);
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(CODE));
    }

    @Test
    void r22_a_request_that_carries_the_secret_is_sent_once_whatever_the_tools_retry_setting() {
        wireMock.resetAll();
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(503).withBody("busy")));
        ToolDescriptor base = tool(HEADERS, "{}");
        ToolDescriptor retrying = new ToolDescriptor(base.id(), SCHEMA, "{\"type\":\"object\"}",
                PermissionRequirement.none(), base.handler(),
                new ToolPolicy(Duration.ofSeconds(3), 2, false, false, false, null, false, false, false, true));

        // Without a secret the tool's own setting holds: one attempt and two retries.
        dispatch(retrying, Map.of(), ctx(Map.of()));
        assertThat(wireMock.findAll(anyRequestedFor(anyUrl()))).hasSize(3);

        // With the code attached the request goes out once: a repeat would spend
        // an attempt of the code, or apply a change twice.
        wireMock.resetRequests();
        Turn.ToolResult result = dispatch(retrying, Map.of(),
                ctx(Map.of(HttpToolDispatcher.SECRET_ATTRIBUTE, CODE)));
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(wireMock.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
        assertThat(String.valueOf(result)).doesNotContain(CODE);
    }

    private Turn.ToolResult dispatch(ToolDescriptor tool, Map<String, Object> args, ExecutionContext ctx) {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null, null,
                new YayaAgenticProperties.HttpTools(List.of("localhost"), true, Duration.ofSeconds(3)),
                null, null, null);
        BaseUrlResolver resolver = new BaseUrlResolver(null, new ObjectMapper()) {
            @Override
            public URI resolve(Ids.TenantId t, String path, String override) {
                return URI.create("http://localhost:" + wireMock.port() + path);
            }
        };
        HttpToolDispatcher dispatcher = new HttpToolDispatcher(
                WebClient.builder(), new HttpEgressPolicy(props), resolver, new ObjectMapper(), props);
        return dispatcher.dispatch((ToolHandlerRef.Http) tool.handler(), tool, args, ctx, "call-1");
    }

    private static ToolDescriptor tool(Map<String, String> headers, String body) {
        HttpToolSpec spec = new HttpToolSpec(HttpToolSpec.HttpMethod.POST, "/api/x", headers,
                new HttpToolSpec.BodyTemplate("application/json", body),
                new HttpToolSpec.ResponseProjection("$", Map.of()), HttpToolSpec.AuthForwarding.NONE);
        return new ToolDescriptor(new Ids.ToolId("secret_tool"), SCHEMA, "{\"type\":\"object\"}",
                PermissionRequirement.none(), new ToolHandlerRef.Http(spec), ToolPolicy.defaults());
    }

    private static ExecutionContext ctx(Map<String, Object> attributes) {
        Principal p = new Principal("alice", new Ids.TenantId("default"), Set.of(),
                Map.of("org", "org-1"), Instant.now());
        return new ExecutionContext(p, new Ids.SessionId("s"), new Ids.TurnId("t"), "trace", attributes);
    }
}
