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
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlResolver;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpToolDispatcher;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.SessionTokenRefresher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smart Lottery player-scope upgrade contract — the HTTP-layer half.
 * Signal 1 (self-healing 409 → reason=refresh → single retry) and Signal 2
 * (registration fast-path: proactive refresh gated on the current token's
 * accountState).
 */
class HttpToolDispatcherRefreshTest {

    private static final String REFRESH_BODY =
            "{\"error\":\"token_refresh_required\",\"reason\":\"principal_upgraded\"}";

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

    /* ---- (a) 409 → refresh → retry succeeds ------------------------------ */

    @Test
    void self_heals_on_409_then_retries_once_with_upgraded_token() {
        wireMock.stubFor(get(urlEqualTo("/wallet/payment-plan"))
                .withHeader("Authorization", equalTo("Bearer guest"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("X-Yaya-Token-Refresh", "required")
                        .withHeader("Content-Type", "application/json")
                        .withBody(REFRESH_BODY)));
        wireMock.stubFor(get(urlEqualTo("/wallet/payment-plan"))
                .withHeader("Authorization", equalTo("Bearer upgraded"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"plan\":\"weekly\"}")));

        StubRefresher refresher = new StubRefresher("Bearer upgraded");
        HttpToolDispatcher dispatcher = dispatcher(refresher);
        ToolDescriptor descriptor = ownScopedGet("/wallet/payment-plan");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                guestCtx(), "call-a");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(result.value()).isEqualTo(Map.of("plan", "weekly"));
        assertThat(refresher.refreshCount.get()).isEqualTo(1);
        wireMock.verify(1, getRequestedFor(urlEqualTo("/wallet/payment-plan"))
                .withHeader("Authorization", equalTo("Bearer guest")));
        wireMock.verify(1, getRequestedFor(urlEqualTo("/wallet/payment-plan"))
                .withHeader("Authorization", equalTo("Bearer upgraded")));
    }

    /* ---- (b) 409 → refresh → still 409 → surface error, no loop ---------- */

    @Test
    void surfaces_error_when_retry_still_409_and_never_loops() {
        // Both the stale AND the re-minted token 409 — a pathological upstream.
        // The single retry is the whole guard: we must not refresh again.
        wireMock.stubFor(get(urlEqualTo("/customers/me"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("X-Yaya-Token-Refresh", "required")
                        .withHeader("Content-Type", "application/json")
                        .withBody(REFRESH_BODY)));

        StubRefresher refresher = new StubRefresher("Bearer upgraded");
        HttpToolDispatcher dispatcher = dispatcher(refresher);
        ToolDescriptor descriptor = ownScopedGet("/customers/me");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                guestCtx(), "call-b");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("409");
        // Exactly one refresh (not a refresh→409→refresh cycle).
        assertThat(refresher.refreshCount.get()).isEqualTo(1);
        // Initial + one retry = two requests. No third.
        wireMock.verify(2, getRequestedFor(urlEqualTo("/customers/me")));
    }

    /* ---- (c) plain 403 must not trigger a refresh ----------------------- */

    @Test
    void plain_403_does_not_trigger_refresh() {
        // 403 = genuine authorization denial (caller is not registered). Distinct
        // from the 409 stale-token signal — we must NOT re-mint.
        wireMock.stubFor(get(urlEqualTo("/customers/me"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"forbidden\"}")));

        StubRefresher refresher = new StubRefresher("Bearer upgraded");
        HttpToolDispatcher dispatcher = dispatcher(refresher);
        ToolDescriptor descriptor = ownScopedGet("/customers/me");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                guestCtx(), "call-c");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("403");
        assertThat(refresher.refreshCount.get()).isZero();
        wireMock.verify(1, getRequestedFor(urlEqualTo("/customers/me")));
    }

    /* ---- (d) tokenRefreshRequired=true refreshes only for a guest -------- */

    @Test
    void proactively_refreshes_when_result_flags_upgrade_and_token_is_a_guest() {
        wireMock.stubFor(post(urlEqualTo("/customers/register"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"customerRef\":\"c1\",\"accountState\":\"registered\","
                                + "\"tokenRefreshRequired\":true}")));

        StubRefresher refresher = new StubRefresher("Bearer upgraded");
        HttpToolDispatcher dispatcher = dispatcher(refresher);
        ToolDescriptor descriptor = registerPost();

        // Guest token: accountState claim absent → proactive refresh fires.
        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor,
                Map.of("name", "Ana"), ctx(Map.of("phone", "+15555550123")), "call-d1");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(refresher.refreshCount.get()).isEqualTo(1);
    }

    @Test
    void skips_proactive_refresh_when_current_token_is_already_registered() {
        wireMock.stubFor(post(urlEqualTo("/customers/register"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"customerRef\":\"c1\",\"accountState\":\"registered\","
                                + "\"tokenRefreshRequired\":true}")));

        StubRefresher refresher = new StubRefresher("Bearer upgraded");
        HttpToolDispatcher dispatcher = dispatcher(refresher);
        ToolDescriptor descriptor = registerPost();

        // Already-registered token: the extra round-trip is pointless — skip it.
        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor,
                Map.of("name", "Ana"),
                ctx(Map.of("phone", "+15555550123", "accountState", "registered")), "call-d2");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(refresher.refreshCount.get()).isZero();
    }

    /* ---- refresh is a no-op guard when no refresher is wired ------------- */

    @Test
    void without_a_refresher_a_409_just_surfaces_as_a_normal_error() {
        wireMock.stubFor(get(urlEqualTo("/customers/me"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("X-Yaya-Token-Refresh", "required")
                        .withBody(REFRESH_BODY)));

        HttpToolDispatcher dispatcher = dispatcher(null);
        ToolDescriptor descriptor = ownScopedGet("/customers/me");

        Turn.ToolResult result = dispatcher.dispatch(
                (ToolHandlerRef.Http) descriptor.handler(), descriptor, Map.of(),
                guestCtx(), "call-e");

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("409");
        wireMock.verify(1, getRequestedFor(urlEqualTo("/customers/me")));
    }

    /* ---- helpers -------------------------------------------------------- */

    private static final class StubRefresher implements SessionTokenRefresher {
        final AtomicInteger refreshCount = new AtomicInteger();
        private final String next; // returned by refresh(); null → empty (can't refresh)
        StubRefresher(String next) { this.next = next; }
        @Override public Optional<String> current(ExecutionContext ctx) { return Optional.empty(); }
        @Override public Optional<String> refresh(ExecutionContext ctx) {
            refreshCount.incrementAndGet();
            return Optional.ofNullable(next);
        }
    }

    private HttpToolDispatcher dispatcher(SessionTokenRefresher refresher) {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null, null,
                new YayaAgenticProperties.HttpTools(List.of("localhost"), true, Duration.ofSeconds(3)),
                null, null, null);
        HttpEgressPolicy egress = new HttpEgressPolicy(props);
        BaseUrlResolver resolver = new BaseUrlResolver(null, new ObjectMapper()) {
            @Override
            public URI resolve(Ids.TenantId t, String path, String override) {
                return URI.create("http://localhost:" + wireMock.port() + path);
            }
        };
        return new HttpToolDispatcher(WebClient.builder(), egress, resolver, new ObjectMapper(), props, refresher);
    }

    private static ToolDescriptor ownScopedGet(String urlTemplate) {
        HttpToolSpec spec = new HttpToolSpec(
                HttpToolSpec.HttpMethod.GET, urlTemplate, Map.of(), null,
                new HttpToolSpec.ResponseProjection("$", Map.of()),
                HttpToolSpec.AuthForwarding.PRINCIPAL_TOKEN);
        return descriptor(spec);
    }

    private static ToolDescriptor registerPost() {
        HttpToolSpec spec = new HttpToolSpec(
                HttpToolSpec.HttpMethod.POST, "/customers/register", Map.of(),
                new HttpToolSpec.BodyTemplate("application/json", "{\"name\":\"{name}\"}"),
                new HttpToolSpec.ResponseProjection("$", Map.of()),
                HttpToolSpec.AuthForwarding.PRINCIPAL_TOKEN);
        return descriptor(spec);
    }

    private static ToolDescriptor descriptor(HttpToolSpec spec) {
        return new ToolDescriptor(
                new Ids.ToolId("test"),
                "{\"type\":\"object\"}",
                "{\"type\":\"object\"}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Http(spec),
                new ToolPolicy(Duration.ofSeconds(2), 0, true, false));
    }

    private static ExecutionContext guestCtx() {
        return ctx(Map.of("phone", "+15555550123"));
    }

    private static ExecutionContext ctx(Map<String, Object> claims) {
        Principal p = new Principal("player", new Ids.TenantId("default"), Set.of(), claims, Instant.now());
        return new ExecutionContext(p, new Ids.SessionId("s"), new Ids.TurnId("t"), "trace",
                Map.of("inboundAuthorization", "Bearer guest"));
    }
}
