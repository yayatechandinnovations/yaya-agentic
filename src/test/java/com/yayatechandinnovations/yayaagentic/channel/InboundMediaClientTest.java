package com.yayatechandinnovations.yayaagentic.channel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Inbound media comes from the BSP's hosts over HTTPS — a forged webhook names no other fetch target. */
class InboundMediaClientTest {

    private WireMockServer cdn;

    @BeforeEach
    void start() {
        cdn = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        cdn.start();
        cdn.stubFor(get(urlEqualTo("/m/1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "image/jpeg").withBody(new byte[]{1, 2, 3})));
    }

    @AfterEach
    void stop() {
        cdn.stop();
    }

    @Test
    void a_host_outside_the_allow_list_is_never_connected_to() {
        InboundMediaClient twilioOnly = InboundMediaClient.direct();
        URI elsewhere = URI.create("http://localhost:" + cdn.port() + "/m/1");

        assertThatThrownBy(() -> twilioOnly.fetchNamed(elsewhere, null, 1024))
                .isInstanceOf(InboundMediaClient.HostNotAllowedException.class);
        assertThatThrownBy(() -> twilioOnly.fetch(elsewhere, null, 1024))
                .isInstanceOf(InboundMediaClient.HostNotAllowedException.class);
        cdn.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void twilio_hosts_pass_over_https_only_and_lookalikes_do_not() {
        InboundMediaClient client = InboundMediaClient.direct();
        assertThat(client.allowed(URI.create("https://api.twilio.com/2010-04-01/Accounts/AC1/Messages/MM1/Media/ME1"))).isTrue();
        assertThat(client.allowed(URI.create("https://media.twiliocdn.com/AC1/abc"))).isTrue();
        assertThat(client.allowed(URI.create("https://twilio.com/x"))).isTrue();
        assertThat(client.allowed(URI.create("http://api.twilio.com/x"))).isFalse();
        assertThat(client.allowed(URI.create("https://api.twilio.com.evil.example/x"))).isFalse();
        assertThat(client.allowed(URI.create("https://eviltwilio.com/x"))).isFalse();
        assertThat(client.allowed(URI.create("https://169.254.169.254/latest/meta-data"))).isFalse();
        assertThat(client.allowed(URI.create("file:///etc/passwd"))).isFalse();
        assertThat(client.allowed(null)).isFalse();
    }

    @Test
    void an_explicitly_allowed_loopback_host_may_use_plain_http() {
        InboundMediaClient withStub = InboundMediaClient.direct("localhost");
        byte[] bytes = withStub.fetch(URI.create("http://localhost:" + cdn.port() + "/m/1"), null, 1024);
        assertThat(bytes).containsExactly(1, 2, 3);
        // the extra host does not loosen the scheme rule for anything else
        assertThat(withStub.allowed(URI.create("http://api.twilio.com/x"))).isFalse();
        assertThat(withStub.allowed(URI.create("http://127.0.0.1/x"))).isFalse();
    }
}
