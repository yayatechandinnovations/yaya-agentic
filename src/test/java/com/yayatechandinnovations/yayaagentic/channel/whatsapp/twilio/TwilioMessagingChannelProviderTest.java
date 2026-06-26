package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.SenderRef;
import com.yayatechandinnovations.yayaagentic.channel.SenderRegistrationRequest;
import com.yayatechandinnovations.yayaagentic.channel.SenderRegistrationResult;
import com.yayatechandinnovations.yayaagentic.channel.SenderSyncResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior tests for the Twilio provider over a WireMock instance. Verifies
 * the result-shape mapping documented in
 * {@code whatsapp-channel-design.md} §6.1 and §6.4 + the §16 non-bypassable
 * Idempotency-Key + auth header rules.
 */
class TwilioMessagingChannelProviderTest {

    private WireMockServer wireMock;
    private TwilioMessagingChannelProvider provider;
    private TwilioCreds creds;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        String base = "http://localhost:" + wireMock.port();
        TwilioProperties props = new TwilioProperties(
                "AC1234", "SK1234", "supersecret",
                "twilio-auth-token-fixture",
                "https://hooks.example", "MG9999",
                base, base);
        provider = new TwilioMessagingChannelProvider(props, WebClient.builder(),
                new ObjectMapper());
        creds = new TwilioCreds("AC1234", "SK1234", "supersecret", "MG9999");
    }

    @AfterEach
    void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void register_accepted_returns_sender_ref() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .withHeader("Authorization", containing("Basic "))
                .withHeader("Idempotency-Key", equalTo("snd_demo_idem"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "sid": "SR1234567890abcdef",
                                  "status": "PENDING",
                                  "sender_id": "whatsapp:+15551234567"
                                }
                                """)));

        SenderRegistrationResult result = provider.registerSender(
                buildRequest("snd_demo_idem"), creds);

        assertThat(result).isInstanceOf(SenderRegistrationResult.Accepted.class);
        SenderRegistrationResult.Accepted acc = (SenderRegistrationResult.Accepted) result;
        assertThat(acc.senderRef().externalId()).isEqualTo("SR1234567890abcdef");
        assertThat(acc.bspStatus()).isEqualTo("PENDING");
    }

    @Test
    void register_rejected_when_twilio_returns_400_with_code() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":21610,"message":"Phone number is not a valid WhatsApp number"}
                                """)));

        SenderRegistrationResult result = provider.registerSender(
                buildRequest("snd_idem"), creds);

        assertThat(result).isInstanceOf(SenderRegistrationResult.Rejected.class);
        SenderRegistrationResult.Rejected rej = (SenderRegistrationResult.Rejected) result;
        assertThat(rej.code()).isEqualTo("twilio_21610");
        assertThat(rej.message()).contains("not a valid WhatsApp number");
    }

    @Test
    void register_transient_when_twilio_returns_500() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(503).withBody("upstream busy")));

        SenderRegistrationResult result = provider.registerSender(
                buildRequest("snd_idem"), creds);

        assertThat(result).isInstanceOf(SenderRegistrationResult.TransientFailure.class);
        assertThat(((SenderRegistrationResult.TransientFailure) result).code())
                .isEqualTo("transient_http_503");
    }

    @Test
    void register_transient_when_twilio_returns_429() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(429).withBody("slow down")));

        SenderRegistrationResult result = provider.registerSender(
                buildRequest("snd_idem"), creds);

        assertThat(result).isInstanceOf(SenderRegistrationResult.TransientFailure.class);
        assertThat(((SenderRegistrationResult.TransientFailure) result).code())
                .isEqualTo("transient_http_429");
    }

    @Test
    void sync_maps_online_status() {
        wireMock.stubFor(get(urlEqualTo("/v2/Channels/Senders/SR1234"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "sid": "SR1234",
                                  "status": "ONLINE",
                                  "configuration": {
                                    "profile": { "name": "Acme Retail" }
                                  }
                                }
                                """)));

        SenderSyncResult result = provider.syncSender(
                new SenderRef(provider.id(), "SR1234"), creds);

        assertThat(result.bspStatus()).isEqualTo("ONLINE");
        assertThat(result.approvedDisplayName()).isEqualTo("Acme Retail");
        assertThat(result.lastFailureCode()).isNull();
    }

    @Test
    void sync_surfaces_twilio_failure_reason_on_pending_review() {
        wireMock.stubFor(get(urlEqualTo("/v2/Channels/Senders/SR1234"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "sid": "SR1234",
                                  "status": "FAILED",
                                  "properties": {
                                    "failure_code": "DISPLAY_NAME_REJECTED",
                                    "failure_reason": "Display name does not match the business."
                                  }
                                }
                                """)));

        SenderSyncResult result = provider.syncSender(
                new SenderRef(provider.id(), "SR1234"), creds);

        assertThat(result.bspStatus()).isEqualTo("FAILED");
        assertThat(result.lastFailureCode()).isEqualTo("DISPLAY_NAME_REJECTED");
        assertThat(result.lastFailureMessage()).contains("does not match the business");
    }

    @Test
    void sync_transient_label_on_5xx() {
        wireMock.stubFor(get(urlEqualTo("/v2/Channels/Senders/SR1234"))
                .willReturn(aResponse().withStatus(502)));
        SenderSyncResult result = provider.syncSender(
                new SenderRef(provider.id(), "SR1234"), creds);
        assertThat(result.bspStatus()).isEqualTo("UNKNOWN");
        assertThat(result.lastFailureCode()).isEqualTo("transient_http_502");
    }

    @Test
    void revoke_treats_404_as_already_gone() {
        wireMock.stubFor(delete(urlEqualTo("/v2/Channels/Senders/SR1234"))
                .willReturn(aResponse().withStatus(404).withBody("Not found")));
        provider.revokeSender(new SenderRef(provider.id(), "SR1234"), creds);
        wireMock.verify(deleteRequestedFor(urlEqualTo("/v2/Channels/Senders/SR1234")));
    }

    @Test
    void revoke_throws_on_other_4xx() {
        wireMock.stubFor(delete(urlEqualTo("/v2/Channels/Senders/SR1234"))
                .willReturn(aResponse().withStatus(401).withBody("Auth failed")));
        try {
            provider.revokeSender(new SenderRef(provider.id(), "SR1234"), creds);
            assertThat(true).as("expected revoke to throw").isFalse();
        } catch (TwilioMessagingChannelProvider.TwilioApiException e) {
            assertThat(e.code()).isEqualTo("twilio_revoke_failed");
        }
    }

    @Test
    void register_supports_whatsapp_only() {
        SenderRegistrationResult result = provider.registerSender(
                new SenderRegistrationRequest(
                        new ChannelKind.Sms(), "+15551234567", "Acme",
                        "https://hooks.example/", "https://hooks.example/cb",
                        Map.of(), "snd_x"),
                creds);
        assertThat(result).isInstanceOf(SenderRegistrationResult.Rejected.class);
        assertThat(((SenderRegistrationResult.Rejected) result).code())
                .isEqualTo("unsupported_channel");
    }

    @Test
    void rejects_creds_for_wrong_provider() {
        try {
            provider.syncSender(new SenderRef(provider.id(), "SR1"), new NonTwilioCreds());
            assertThat(true).as("expected IllegalArgumentException").isFalse();
        } catch (IllegalArgumentException ok) {
            assertThat(ok.getMessage()).contains("creds");
        }
    }

    private SenderRegistrationRequest buildRequest(String idem) {
        return new SenderRegistrationRequest(
                new ChannelKind.WhatsApp(),
                "+15551234567",
                "Acme Retail",
                "https://hooks.example/v1/channels/whatsapp/twilio/webhook/" + idem,
                "https://hooks.example/v1/channels/whatsapp/twilio/status-callback/" + idem,
                Map.of(
                        "legalName", "Acme Retail Inc.",
                        "contactEmail", "ops@acme.com",
                        "address", "1 Apple Park Way",
                        "websiteUrl", "https://acme.com",
                        "vertical", "RETAIL",
                        "about", "Stuff."),
                idem);
    }

    /** A stub creds class with a different provider id to exercise the
     *  defensive check in TwilioMessagingChannelProvider. */
    private record NonTwilioCreds()
            implements com.yayatechandinnovations.yayaagentic.channel.ProviderCreds {
        @Override
        public com.yayatechandinnovations.yayaagentic.channel.ProviderId provider() {
            return new com.yayatechandinnovations.yayaagentic.channel.ProviderId("messagebird");
        }
    }
}
