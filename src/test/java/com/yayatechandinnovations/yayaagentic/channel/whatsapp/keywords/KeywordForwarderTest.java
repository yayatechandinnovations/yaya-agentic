package com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.auth.service_token.ServiceTokenAuthenticator;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantServiceTokenSecretRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tracker C8 P5: the forward carries the C6 body and a service token the
 * install can verify (subject {@code keyword:<senderId>}, scope
 * {@code keyword:forward}), retries once on 5xx or a connect failure, never
 * after a timeout (the tenant may already be acting on the first copy), and
 * never throws.
 */
class KeywordForwarderTest {

    private static final String TENANT = "fastcash";
    private static final String SECRET = "svc-secret-0123456789abcdef0123456789";

    private WireMockServer install;
    private ServiceTokenAuthenticator tokens;
    private KeywordForwarder forwarder;

    @BeforeEach
    void setUp() {
        install = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        install.start();
        YayaAgenticProperties props = mock(YayaAgenticProperties.class);
        when(props.auth()).thenReturn(new YayaAgenticProperties.Auth(false, null,
                new YayaAgenticProperties.Auth.ServiceToken(Map.of(TENANT, SECRET)), null));
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        tokens = new ServiceTokenAuthenticator(props, mock(TenantServiceTokenSecretRepository.class),
                new JwtSecretCipher(Base64.getEncoder().encodeToString(key)));
        forwarder = new KeywordForwarder(tokens, new ObjectMapper(), java.net.http.HttpClient.newHttpClient(), null);
    }

    @AfterEach
    void tearDown() {
        install.stop();
    }

    private KeywordAction action(String path) {
        return new KeywordAction("TICKET", install.baseUrl() + path);
    }

    private KeywordForwarder.Inbound inbound() {
        return new KeywordForwarder.Inbound(new Ids.TenantId(TENANT), new SenderId("wa_1"), "+18095551234",
                "+18455336210", "ticket 7G4K2", Instant.parse("2026-11-18T15:00:00Z"), "SM1");
    }

    @Test
    void posts_the_c6_body_with_a_verifiable_service_token_and_returns_the_reply() throws Exception {
        install.stubFor(post(urlEqualTo("/integrations/keywords"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.tenantId", equalTo(TENANT)))
                .withRequestBody(matchingJsonPath("$.senderId", equalTo("wa_1")))
                .withRequestBody(matchingJsonPath("$.from", equalTo("+18095551234")))
                .withRequestBody(matchingJsonPath("$.to", equalTo("+18455336210")))
                .withRequestBody(matchingJsonPath("$.text", equalTo("ticket 7G4K2")))
                .withRequestBody(matchingJsonPath("$.receivedAt", equalTo("2026-11-18T15:00:00Z")))
                .withRequestBody(matchingJsonPath("$.messageSid", equalTo("SM1")))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"reply\":\"Listo, tu ticket va en camino.\"}")));

        Optional<String> reply = forwarder.forward(action("/integrations/keywords"), inbound());

        assertThat(reply).contains("Listo, tu ticket va en camino.");
        var requests = install.findAll(postRequestedFor(urlEqualTo("/integrations/keywords")));
        assertThat(requests).hasSize(1);
        String token = requests.get(0).getHeader(KeywordForwarder.TOKEN_HEADER);
        Principal p = tokens.tryAuthenticate(new AuthContext(new Ids.TenantId(TENANT),
                Map.of(ServiceTokenAuthenticator.HEADER, token), Optional.empty())).orElseThrow();
        assertThat(p.subject()).isEqualTo("keyword:wa_1");
        assertThat(p.scopes()).containsExactly("keyword:forward");
        assertThat(p.tenant().value()).isEqualTo(TENANT);
    }

    @Test
    void retries_once_on_5xx_then_gives_up_quietly() {
        install.stubFor(post(urlEqualTo("/flaky")).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        install.stubFor(post(urlEqualTo("/flaky")).inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody("{\"reply\":\"ok\"}")));
        assertThat(forwarder.forward(action("/flaky"), inbound())).contains("ok");
        assertThat(install.findAll(postRequestedFor(urlEqualTo("/flaky")))).hasSize(2);

        install.stubFor(post(urlEqualTo("/down")).willReturn(aResponse().withStatus(500)));
        assertThat(forwarder.forward(action("/down"), inbound())).isEmpty();
        assertThat(install.findAll(postRequestedFor(urlEqualTo("/down")))).hasSize(2);
    }

    @Test
    void a_4xx_or_an_empty_reply_is_no_reply_and_no_retry() {
        install.stubFor(post(urlEqualTo("/reject")).willReturn(aResponse().withStatus(403)));
        assertThat(forwarder.forward(action("/reject"), inbound())).isEmpty();
        assertThat(install.findAll(postRequestedFor(urlEqualTo("/reject")))).hasSize(1);

        install.stubFor(post(urlEqualTo("/silent")).willReturn(aResponse().withStatus(200).withBody("{}")));
        assertThat(forwarder.forward(action("/silent"), inbound())).isEmpty();
        install.stubFor(post(urlEqualTo("/empty")).willReturn(aResponse().withStatus(204)));
        assertThat(forwarder.forward(action("/empty"), inbound())).isEmpty();
    }

    @Test
    void no_tenant_secret_means_no_call() {
        var noSecret = new KeywordForwarder.Inbound(new Ids.TenantId("unknown"), new SenderId("wa_1"), "+1", "+2",
                "TICKET", Instant.now(), "SM2");
        install.stubFor(post(urlEqualTo("/never")).willReturn(aResponse().withStatus(200).withBody("{\"reply\":\"x\"}")));
        assertThat(forwarder.forward(action("/never"), noSecret)).isEmpty();
        assertThat(install.findAll(postRequestedFor(urlEqualTo("/never")))).isEmpty();
    }

    @Test
    void a_timed_out_request_is_not_retried() {
        install.stubFor(post(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200)
                .withFixedDelay(1500).withBody("{\"reply\":\"late\"}")));
        KeywordForwarder impatient = new KeywordForwarder(tokens, new ObjectMapper(),
                java.net.http.HttpClient.newHttpClient(), null, java.time.Duration.ofMillis(300));

        assertThat(impatient.forward(action("/slow"), inbound())).isEmpty();

        assertThat(install.findAll(postRequestedFor(urlEqualTo("/slow")))).hasSize(1);
    }

    @Test
    void transport_failure_is_no_reply() {
        KeywordAction gone = action("/gone");
        install.stop();
        assertThat(forwarder.forward(gone, inbound())).isEmpty();
    }

    @Test
    void payload_shape_is_the_c6_one() {
        assertThat(KeywordForwarder.payload(inbound()).keySet()).containsExactly(
                "tenantId", "senderId", "from", "to", "text", "receivedAt", "messageSid");
        assertThat(List.copyOf(KeywordForwarder.payload(inbound()).values())).contains("ticket 7G4K2");
    }
}
