package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-contract coverage for {@link HttpAuthResolver}. Each scenario maps to
 * a row in {@code docs/design/channel-auth-resolver-design.md} §6 (request /
 * response shapes) or §14 (failure modes).
 *
 * <p>WireMock stands in for the tenant's identity webhook. The egress policy
 * is configured with {@code allowPrivateNetworks=true} so the loopback URL
 * the mock listens on isn't blocked by the SSRF guard — prod deployments
 * leave it off.
 */
class HttpAuthResolverTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-27T14:30:00Z");
    private static final Clock FIXED = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    /** 32 bytes — long enough for the MAC signer's HMAC-SHA-256 minimum. */
    private static final String SIGNING_SECRET = "test-secret-bytes-must-be-32-byt";

    private WireMockServer wireMock;
    private HttpEgressPolicy egressPolicy;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null, null,
                new YayaAgenticProperties.HttpTools(List.of(), true, Duration.ofSeconds(10)),
                null, null, null);
        egressPolicy = new HttpEgressPolicy(props);
    }

    @AfterEach
    void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void posts_body_shape_per_design_6_1() {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"eyJhbGciOi.dummy.payload\"}")));

        HttpAuthResolver r = resolver(HttpAuthResolver.ServiceAuthKind.NONE, null);
        Optional<String> token = r.resolve(new AuthResolver.ResolveRequest(
                new Ids.TenantId("acme"),
                new SenderId("snd_acme_main"),
                "+15551112222",
                "+15551234567",
                new Ids.SessionId("sess_01HVXP6PJYV3FK2A0PCNB3JM4F"),
                "whatsapp",
                AuthResolver.ResolveReason.INITIAL,
                FIXED_NOW));

        assertThat(token).contains("eyJhbGciOi.dummy.payload");
        wireMock.verify(postRequestedFor(urlEqualTo("/yaya/resolve-identity"))
                .withHeader("X-Yaya-Tenant", equalTo("acme"))
                .withHeader("X-Yaya-Request-Id", matching("rq_[a-f0-9]+"))
                .withHeader("X-Yaya-Idempotency-Key", matching("rq_[a-f0-9]+"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.sessionId",
                        equalTo("sess_01HVXP6PJYV3FK2A0PCNB3JM4F")))
                .withRequestBody(matchingJsonPath("$.senderPhone",
                        equalTo("+15551112222")))
                .withRequestBody(matchingJsonPath("$.callerPhone",
                        equalTo("+15551234567")))
                .withRequestBody(matchingJsonPath("$.channel",
                        equalTo("whatsapp")))
                .withRequestBody(matchingJsonPath("$.tenantId",
                        equalTo("acme")))
                .withRequestBody(matchingJsonPath("$.reason",
                        equalTo("initial"))));
    }

    @Test
    void refresh_reason_is_lowercased() {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"x\"}")));

        resolver(HttpAuthResolver.ServiceAuthKind.NONE, null).resolve(
                req(AuthResolver.ResolveReason.REFRESH));

        wireMock.verify(postRequestedFor(urlEqualTo("/yaya/resolve-identity"))
                .withRequestBody(matchingJsonPath("$.reason", equalTo("refresh"))));
    }

    @Test
    void status_404_returns_empty() {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(404)));

        Optional<String> token = resolver(HttpAuthResolver.ServiceAuthKind.NONE, null)
                .resolve(req(AuthResolver.ResolveReason.INITIAL));
        assertThat(token).isEmpty();
    }

    @Test
    void status_200_with_empty_body_is_user_not_found() {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        Optional<String> token = resolver(HttpAuthResolver.ServiceAuthKind.NONE, null)
                .resolve(req(AuthResolver.ResolveReason.INITIAL));
        assertThat(token).isEmpty();
    }

    @Test
    void status_500_throws_transport_error() {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> resolver(HttpAuthResolver.ServiceAuthKind.NONE, null)
                .resolve(req(AuthResolver.ResolveReason.INITIAL)))
                .isInstanceOf(AuthResolverException.class)
                .satisfies(e -> {
                    AuthResolverException are = (AuthResolverException) e;
                    assertThat(are.status()).isEqualTo("transport_error");
                    assertThat(are.httpStatus()).isEqualTo(500);
                });
    }

    @Test
    void status_401_throws_self_auth_failed() {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> resolver(HttpAuthResolver.ServiceAuthKind.BEARER, "static-bearer")
                .resolve(req(AuthResolver.ResolveReason.INITIAL)))
                .isInstanceOf(AuthResolverException.class)
                .satisfies(e -> assertThat(((AuthResolverException) e).status())
                        .isEqualTo("self_auth_failed"));
    }

    @Test
    void bearer_auth_sends_static_token() {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .withHeader("Authorization", equalTo("Bearer sek-static-bearer-xyz"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"x\"}")));

        Optional<String> token = resolver(HttpAuthResolver.ServiceAuthKind.BEARER,
                "sek-static-bearer-xyz").resolve(req(AuthResolver.ResolveReason.INITIAL));
        assertThat(token).contains("x");
    }

    @Test
    void signed_jwt_carries_required_claims() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"x\"}")));

        resolver(HttpAuthResolver.ServiceAuthKind.SIGNED_JWT, SIGNING_SECRET)
                .resolve(req(AuthResolver.ResolveReason.INITIAL));

        // Read the Authorization header from WireMock and parse the JWT.
        var events = wireMock.getAllServeEvents();
        assertThat(events).hasSize(1);
        String auth = events.get(0).getRequest().getHeader("Authorization");
        assertThat(auth).startsWith("Bearer ");
        String compact = auth.substring("Bearer ".length());
        JWSObject jws = JWSObject.parse(compact);
        SignedJWT jwt = SignedJWT.parse(compact);
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("yaya-agentic");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("acme");
        assertThat(jwt.getJWTClaimsSet().getSubject())
                .isEqualTo("auth-resolver:acme-customer-lookup");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).startsWith("rq_");
        // 30s lifetime per design §6.3
        long lifeSecs = (jwt.getJWTClaimsSet().getExpirationTime().getTime()
                - jwt.getJWTClaimsSet().getIssueTime().getTime()) / 1000;
        assertThat(lifeSecs).isEqualTo(30);
        // alg = HS256
        assertThat(jws.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        // No kid configured → header carries none (byte-identical to pre-fix).
        assertThat(jws.getHeader().getKeyID()).isNull();
    }

    @Test
    void signed_jwt_emits_configured_kid_in_header() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"x\"}")));

        resolverWithStore(HttpAuthResolver.ServiceAuthKind.SIGNED_JWT, SIGNING_SECRET,
                "yaya-in-1", null, new ResolverExchangeStore())
                .resolve(req(AuthResolver.ResolveReason.INITIAL));

        String auth = wireMock.getAllServeEvents().get(0).getRequest()
                .getHeader("Authorization");
        SignedJWT jwt = SignedJWT.parse(auth.substring("Bearer ".length()));
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("yaya-in-1");
    }

    @Test
    void signed_jwt_audience_override_replaces_tenant_id() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"x\"}")));

        resolverWithStore(HttpAuthResolver.ServiceAuthKind.SIGNED_JWT, SIGNING_SECRET,
                "yaya-in-1", "yaya", new ResolverExchangeStore())
                .resolve(req(AuthResolver.ResolveReason.INITIAL));

        String auth = wireMock.getAllServeEvents().get(0).getRequest()
                .getHeader("Authorization");
        SignedJWT jwt = SignedJWT.parse(auth.substring("Bearer ".length()));
        // Tenant is "acme" but the verifier expects aud=yaya — override wins.
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("yaya");
    }

    @Test
    void signed_jwt_jti_matches_request_id_header() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"x\"}")));

        resolver(HttpAuthResolver.ServiceAuthKind.SIGNED_JWT, SIGNING_SECRET)
                .resolve(req(AuthResolver.ResolveReason.INITIAL));

        var event = wireMock.getAllServeEvents().get(0);
        String auth = event.getRequest().getHeader("Authorization");
        String requestId = event.getRequest().getHeader("X-Yaya-Request-Id");
        SignedJWT jwt = SignedJWT.parse(auth.substring("Bearer ".length()));
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isEqualTo(requestId);
    }

    @Test
    void captures_exchange_with_credential_and_jwt_redacted() {
        // The playground inspector captures the request/response, but the
        // outbound service credential and the returned JWT must be redacted at
        // the source — the in-memory store never holds a usable secret or token.
        wireMock.stubFor(post(urlEqualTo("/yaya/resolve-identity"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"eyJhbGciOi.realjwt.signature\",\"expiresIn\":3600}")));

        ResolverExchangeStore store = new ResolverExchangeStore();
        HttpAuthResolver r = resolverWithStore(
                HttpAuthResolver.ServiceAuthKind.BEARER, "super-secret-bearer-value", store);
        r.resolve(req(AuthResolver.ResolveReason.INITIAL));

        String callerHash = com.yayatechandinnovations.yayaagentic.channel.auth.cache
                .AuthResolverTokenCache.sha256LowerHex("+15551234567");
        var exchanges = store.forCaller("acme", callerHash);
        assertThat(exchanges).hasSize(1);
        var ex = exchanges.get(0);

        // Outbound credential redacted — the real bearer value never stored.
        assertThat(ex.requestHeaders().get("Authorization"))
                .isEqualTo("<BEARER service credential, redacted>");
        assertThat(ex.requestHeaders().toString()).doesNotContain("super-secret-bearer-value");
        // Response captured, status preserved, JWT value redacted.
        assertThat(ex.responseStatus()).isEqualTo(200);
        assertThat(ex.responseBody()).contains("redacted").contains("expiresIn");
        assertThat(ex.responseBody()).doesNotContain("realjwt.signature");
        // Request body shape is visible for debugging.
        assertThat(ex.requestBody()).containsEntry("callerPhone", "+15551234567");
    }

    // ---- helpers ------------------------------------------------------

    private HttpAuthResolver resolver(HttpAuthResolver.ServiceAuthKind kind, String secret) {
        return resolverWithStore(kind, secret, new ResolverExchangeStore());
    }

    private HttpAuthResolver resolverWithStore(HttpAuthResolver.ServiceAuthKind kind,
                                               String secret, ResolverExchangeStore store) {
        return resolverWithStore(kind, secret, null, null, store);
    }

    private HttpAuthResolver resolverWithStore(HttpAuthResolver.ServiceAuthKind kind,
                                               String secret, String signingKid,
                                               String audienceOverride,
                                               ResolverExchangeStore store) {
        URI url = URI.create(wireMock.baseUrl() + "/yaya/resolve-identity");
        return new HttpAuthResolver(
                "acme-customer-lookup",
                "Resolves a phone to a customer JWT",
                url,
                kind,
                secret,
                signingKid,
                audienceOverride,
                Duration.ofSeconds(3),
                WebClient.builder().build(),
                egressPolicy,
                json,
                FIXED,
                store);
    }

    private AuthResolver.ResolveRequest req(AuthResolver.ResolveReason reason) {
        return new AuthResolver.ResolveRequest(
                new Ids.TenantId("acme"),
                new SenderId("snd_acme_main"),
                "+15551112222",
                "+15551234567",
                new Ids.SessionId("sess_01HVXP6PJYV3FK2A0PCNB3JM4F"),
                "whatsapp",
                reason,
                FIXED_NOW);
    }
}
