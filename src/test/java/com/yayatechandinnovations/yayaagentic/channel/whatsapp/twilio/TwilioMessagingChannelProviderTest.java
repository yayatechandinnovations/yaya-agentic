package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.OutboundFreeform;
import com.yayatechandinnovations.yayaagentic.channel.SenderRef;
import com.yayatechandinnovations.yayaagentic.channel.SenderRegistrationRequest;
import com.yayatechandinnovations.yayaagentic.channel.SenderRegistrationResult;
import com.yayatechandinnovations.yayaagentic.channel.SenderSyncResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;

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
    void register_sends_the_senders_v2_wire_shape() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"XE1\",\"status\":\"CREATING\"}")));

        provider.registerSender(buildRequest("snd_shape"), creds);

        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders"))
                .withRequestBody(matchingJsonPath("$.sender_id", equalTo("whatsapp:+15551234567")))
                .withRequestBody(matchingJsonPath("$.profile.name", equalTo("Acme Retail")))
                .withRequestBody(matchingJsonPath("$.profile.vertical", equalTo("Shopping and Retail")))
                .withRequestBody(matchingJsonPath("$.profile.about", equalTo("Stuff.")))
                .withRequestBody(matchingJsonPath("$.profile.emails[0].email", equalTo("ops@acme.com")))
                .withRequestBody(matchingJsonPath("$.profile.websites[0].website", equalTo("https://acme.com")))
                .withRequestBody(matchingJsonPath("$.webhook.callback_url",
                        equalTo("https://hooks.example/v1/channels/whatsapp/twilio/webhook/snd_shape")))
                .withRequestBody(notMatching(".*\"configuration\".*")));
    }

    @Test
    void register_sends_the_logo_as_the_profile_photo() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"XE4\",\"status\":\"CREATING\"}")));

        provider.registerSender(buildRequest("snd_logo",
                Map.of("logoUrl", "https://hooks.example/v1/chat/logos/logo_1/logo.png")), creds);

        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders"))
                .withRequestBody(matchingJsonPath("$.profile.logo_url",
                        equalTo("https://hooks.example/v1/chat/logos/logo_1/logo.png"))));
    }

    @Test
    void register_without_a_logo_sends_no_logo_url() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"XE5\",\"status\":\"CREATING\"}")));

        provider.registerSender(buildRequest("snd_nologo", Map.of("logoUrl", "")), creds);

        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders"))
                .withRequestBody(notMatching(".*logo_url.*")));
    }

    @Test
    void register_passes_the_chosen_verification_method() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"XE2\",\"status\":\"CREATING\"}")));

        provider.registerSender(buildRequest("snd_voice", Map.of("verificationMethod", "voice")), creds);

        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders"))
                .withRequestBody(matchingJsonPath("$.configuration.verification_method", equalTo("voice"))));
    }

    @Test
    void register_with_a_waba_sends_no_verification_method() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"XE3\",\"status\":\"CREATING\"}")));

        provider.registerSender(buildRequest("snd_waba",
                Map.of("wabaId", "WABA42", "verificationMethod", "voice")), creds);

        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders"))
                .withRequestBody(matchingJsonPath("$.configuration.waba_id", equalTo("WABA42")))
                .withRequestBody(notMatching(".*verification_method.*")));
    }

    @Test
    void register_adopts_a_sender_already_on_the_account() {
        wireMock.stubFor(get(urlPathEqualTo("/v2/Channels/Senders"))
                .withQueryParam("Channel", equalTo("whatsapp"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "senders": [
                                    {"sid":"XEother","sender_id":"whatsapp:+15550000000","status":"ONLINE"},
                                    {"sid":"XEmine","sender_id":"whatsapp:+15551234567","status":"ONLINE"}
                                  ],
                                  "meta": {"next_page_url": null}
                                }
                                """)));
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders/XEmine"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"XEmine\",\"status\":\"ONLINE\"}")));

        SenderRegistrationResult result = provider.registerSender(buildRequest("snd_adopt"), creds);

        assertThat(result).isInstanceOf(SenderRegistrationResult.Accepted.class);
        SenderRegistrationResult.Accepted acc = (SenderRegistrationResult.Accepted) result;
        assertThat(acc.senderRef().externalId()).isEqualTo("XEmine");
        assertThat(acc.bspStatus()).isEqualTo("ONLINE");
        // Webhooks re-pointed at the platform; the approved profile is left alone.
        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders/XEmine"))
                .withRequestBody(matchingJsonPath("$.webhook.callback_url",
                        equalTo("https://hooks.example/v1/channels/whatsapp/twilio/webhook/snd_adopt")))
                .withRequestBody(notMatching(".*\"profile\".*")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v2/Channels/Senders")));
    }

    @Test
    void vertical_codes_map_to_twilio_labels() {
        assertThat(TwilioMessagingChannelProvider.twilioVertical("AUTO")).isEqualTo("Automotive");
        assertThat(TwilioMessagingChannelProvider.twilioVertical("HEALTH_AND_WELLNESS")).isEqualTo("Medical and Health");
        assertThat(TwilioMessagingChannelProvider.twilioVertical("Restaurant")).isEqualTo("Restaurant");
        assertThat(TwilioMessagingChannelProvider.twilioVertical("SOMETHING_NEW")).isEqualTo("Other");
        assertThat(TwilioMessagingChannelProvider.twilioVertical(null)).isEqualTo("Other");
    }

    @Test
    void verify_posts_the_code_and_returns_the_new_status() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders/XE9"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"XE9\",\"status\":\"VERIFYING\"}")));

        SenderSyncResult result = provider.verifySender(new SenderRef(provider.id(), "XE9"), "123456", creds);

        assertThat(result.bspStatus()).isEqualTo("VERIFYING");
        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders/XE9"))
                .withRequestBody(matchingJsonPath("$.configuration.verification_code", equalTo("123456"))));
    }

    @Test
    void verify_rejected_code_throws_a_rejection() {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders/XE9"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":63100,\"message\":\"Invalid verification code\"}")));
        try {
            provider.verifySender(new SenderRef(provider.id(), "XE9"), "000000", creds);
            assertThat(true).as("expected verify to throw").isFalse();
        } catch (TwilioMessagingChannelProvider.TwilioApiException e) {
            assertThat(e.code()).isEqualTo("twilio_verification_rejected");
            assertThat(e.getMessage()).contains("Invalid verification code");
        }
    }

    @Test
    void outbound_sends_from_the_senders_own_number_even_with_a_messaging_service() {
        wireMock.stubFor(post(urlEqualTo("/2010-04-01/Accounts/AC1234/Messages.json"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"SM1\"}")));

        provider.sendFreeform(new SenderRef(provider.id(), "XE9"),
                new OutboundFreeform("+15557654321", "hi", Optional.empty(),
                        Map.of("fromPhoneE164", "+15551234567")),
                creds);

        wireMock.verify(postRequestedFor(urlEqualTo("/2010-04-01/Accounts/AC1234/Messages.json"))
                .withRequestBody(containing("From=whatsapp%3A%2B15551234567"))
                .withRequestBody(notMatching(".*MessagingServiceSid.*")));
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
                                  "profile": { "name": "Acme Retail" },
                                  "offline_reasons": null
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
                                  "status": "OFFLINE",
                                  "offline_reasons": [
                                    {"code": "63110", "message": "Display name does not match the business.",
                                     "more_info": "https://www.twilio.com/docs/errors/63110"},
                                    {"code": "63111", "message": "Business profile incomplete."}
                                  ]
                                }
                                """)));

        SenderSyncResult result = provider.syncSender(
                new SenderRef(provider.id(), "SR1234"), creds);

        assertThat(result.bspStatus()).isEqualTo("OFFLINE");
        assertThat(result.lastFailureCode()).isEqualTo("63110");
        assertThat(result.lastFailureMessage())
                .contains("does not match the business")
                .contains("Business profile incomplete");
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
        return buildRequest(idem, Map.of());
    }

    private SenderRegistrationRequest buildRequest(String idem, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.HashMap<>(Map.of(
                "legalName", "Acme Retail Inc.",
                "contactEmail", "ops@acme.com",
                "address", "1 Apple Park Way",
                "websiteUrl", "https://acme.com",
                "vertical", "RETAIL",
                "about", "Stuff."));
        payload.putAll(extra);
        return new SenderRegistrationRequest(
                new ChannelKind.WhatsApp(),
                "+15551234567",
                "Acme Retail",
                "https://hooks.example/v1/channels/whatsapp/twilio/webhook/" + idem,
                "https://hooks.example/v1/channels/whatsapp/twilio/status-callback/" + idem,
                payload,
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
