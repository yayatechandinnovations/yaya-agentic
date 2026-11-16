package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end W2 coverage: registers a sender, submits it through the live
 * service against a WireMock-stubbed Twilio, then forces a sync that flips
 * the row to ACTIVE. Verifies the two-transaction split (submit doesn't
 * hold the row lock across the Twilio call) and the status-event-driven
 * cache invalidation seam.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WhatsAppSenderSubmitTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startStub() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopStub() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void twilioProps(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + wireMock.port();
        r.add("yaya.agentic.channels.twilio.account-sid", () -> "AC1111111111111111111111111111aaaa");
        r.add("yaya.agentic.channels.twilio.api-key-sid", () -> "SK2222222222222222222222222222bbbb");
        r.add("yaya.agentic.channels.twilio.api-key-secret", () -> "supersecret");
        r.add("yaya.agentic.channels.twilio.webhook-base-url", () -> "https://hooks.example");
        r.add("yaya.agentic.channels.twilio.api-base-url", () -> base);
        r.add("yaya.agentic.channels.twilio.messaging-base-url", () -> base);
        r.add("yaya.agentic.channels.whatsapp.poller.enabled", () -> "false");
        r.add("yaya.agentic.llm.provider", () -> "stub");
        r.add("spring.ai.anthropic.api-key", () -> "");
    }

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "wa-submit-test";

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        wireMock.resetAll();
        // Allow the boot-time TwilioStartupProbe to land on /Accounts and ack.
        wireMock.stubFor(get(urlPathMatching("/2010-04-01/Accounts/.*\\.json"))
                .willReturn(aResponse().withStatus(200).withBody("{\"sid\":\"AC1\"}")));
        ensureTenant();
    }

    private void ensureTenant() {
        var req = new AdminDtos.TenantRequest(
                TENANT, "Submit Test", "https://wa-submit-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private WhatsAppSenderDtos.SenderResponse createDraft(String phone) {
        return createDraft(phone, "https://example.com");
    }

    private WhatsAppSenderDtos.SenderRequest draftRequest(String phone, String website) {
        return new WhatsAppSenderDtos.SenderRequest(
                phone,
                "Submit Test Co",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "Submit Test Inc.", "Submit Test Co", "ops@example.com",
                        "1 Apple Park Way", website, "RETAIL", "Stuff."),
                null);
    }

    private WhatsAppSenderDtos.SenderResponse createDraft(String phone, String website) {
        return client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draftRequest(phone, website))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
    }

    @Test
    void submit_accepted_transitions_to_pending_review() {
        var sender = createDraft("+15553000001");

        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .withHeader("Idempotency-Key", equalTo(sender.id()))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sid":"SR_acc_001","status":"PENDING"}
                                """)));

        var submitted = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/submit", TENANT, sender.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();

        assertThat(submitted).isNotNull();
        assertThat(submitted.status().name()).isEqualTo("PENDING_REVIEW");
        assertThat(submitted.onboarding().twilioSenderSid()).isEqualTo("SR_acc_001");
    }

    @Test
    void submit_rejected_transitions_to_failed_with_message() {
        var sender = createDraft("+15553000002");

        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":21408,"message":"Display name violates Meta naming policy."}
                                """)));

        var rejected = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/submit", TENANT, sender.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();

        assertThat(rejected).isNotNull();
        assertThat(rejected.status().name()).isEqualTo("FAILED");
        assertThat(rejected.onboarding().failureMessage())
                .contains("violates Meta naming policy");
    }

    @Test
    void submit_transient_leaves_in_submitting_for_poller() {
        var sender = createDraft("+15553000003");

        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(503).withBody("upstream busy")));

        var pending = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/submit", TENANT, sender.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();

        assertThat(pending).isNotNull();
        // Stays in SUBMITTING — the poller will retry the registration later.
        assertThat(pending.status().name()).isEqualTo("SUBMITTING");
    }

    @Test
    void force_sync_picks_up_activation() {
        var sender = createDraft("+15553000004");

        // Step 1: submit → accepted → PENDING_REVIEW
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sid":"SR_sync_004","status":"PENDING"}
                                """)));
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/submit", TENANT, sender.id())
                .exchange()
                .expectStatus().isOk();

        // Step 2: poll observes Meta-approval; sync should flip to ACTIVE.
        wireMock.stubFor(get(urlEqualTo("/v2/Channels/Senders/SR_sync_004"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "sid":"SR_sync_004",
                                  "status":"ONLINE",
                                  "profile":{"name":"Submit Test Co (Approved)"}
                                }
                                """)));

        var synced = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/sync", TENANT, sender.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();

        assertThat(synced).isNotNull();
        assertThat(synced.status().name()).isEqualTo("ACTIVE");
        assertThat(synced.approvedDisplayName()).isEqualTo("Submit Test Co (Approved)");
    }

    @Test
    void verification_code_completes_registration() {
        var sender = createDraft("+15553000006");
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(json(201, "{\"sid\":\"XE_ver_006\",\"status\":\"CREATING\"}")));
        submit(sender.id());

        wireMock.stubFor(get(urlEqualTo("/v2/Channels/Senders/XE_ver_006"))
                .willReturn(json(200, "{\"sid\":\"XE_ver_006\",\"status\":\"PENDING_VERIFICATION\"}")));
        var waiting = sync(sender.id());
        assertThat(waiting.status().name()).isEqualTo("PENDING_REVIEW");
        assertThat(waiting.onboarding().status()).isEqualTo("PENDING_VERIFICATION");

        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders/XE_ver_006"))
                .willReturn(json(200, "{\"sid\":\"XE_ver_006\",\"status\":\"VERIFYING\"}")));
        var verifying = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/verify", TENANT, sender.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.VerifyRequest("123 456"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(verifying).isNotNull();
        assertThat(verifying.onboarding().status()).isEqualTo("VERIFYING");
        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders/XE_ver_006"))
                .withRequestBody(matchingJsonPath("$.configuration.verification_code", equalTo("123456"))));

        wireMock.stubFor(get(urlEqualTo("/v2/Channels/Senders/XE_ver_006"))
                .willReturn(json(200, "{\"sid\":\"XE_ver_006\",\"status\":\"ONLINE\"}")));
        assertThat(sync(sender.id()).status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void an_offline_report_mid_registration_keeps_the_sender_in_review_and_takes_the_code() {
        var sender = createDraft("+15553000013");
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(json(201, "{\"sid\":\"XE_off_013\",\"status\":\"CREATING\"}")));
        submit(sender.id());

        wireMock.stubFor(get(urlEqualTo("/v2/Channels/Senders/XE_off_013"))
                .willReturn(json(200, """
                        {"sid":"XE_off_013","status":"OFFLINE",
                         "offline_reasons":[{"code":"410","message":"Something went wrong. Please create a support ticket"}]}
                        """)));
        var offline = sync(sender.id());
        assertThat(offline.status().name()).isEqualTo("PENDING_REVIEW");
        assertThat(offline.onboarding().status()).isEqualTo("OFFLINE");
        assertThat(offline.onboarding().failureMessage()).contains("Something went wrong");

        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders/XE_off_013"))
                .willReturn(json(200, "{\"sid\":\"XE_off_013\",\"status\":\"VERIFYING\"}")));
        var verifying = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/verify", TENANT, sender.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.VerifyRequest("654321"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(verifying).isNotNull();
        assertThat(verifying.status().name()).isEqualTo("PENDING_REVIEW");
        assertThat(verifying.onboarding().status()).isEqualTo("VERIFYING");
    }

    @Test
    void verify_rejects_malformed_codes_and_senders_not_waiting_for_one() {
        var sender = createDraft("+15553000007");
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/verify", TENANT, sender.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.VerifyRequest("abc"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("bad_verification_code"));
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/verify", TENANT, sender.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.VerifyRequest("123456"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("not_awaiting_verification"));
    }

    @Test
    void a_pending_sender_can_be_cancelled_and_its_number_registered_again() {
        var sender = createDraft("+15553000008");
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(json(201, "{\"sid\":\"XE_cancel_008\",\"status\":\"CREATING\"}")));
        submit(sender.id());

        var revoked = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/revoke", TENANT, sender.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(revoked).isNotNull();
        assertThat(revoked.status().name()).isEqualTo("REVOKED");

        var again = createDraft("+15553000008");
        assertThat(again.id()).isNotEqualTo(sender.id());
        assertThat(again.status().name()).isEqualTo("DRAFT");
    }

    @Test
    void refresh_re_drives_a_submission_that_never_reached_whatsapp() {
        var sender = createDraft("+15553000009");
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(503).withBody("upstream busy")));
        assertThat(submit(sender.id()).status().name()).isEqualTo("SUBMITTING");

        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(json(201, "{\"sid\":\"XE_retry_009\",\"status\":\"CREATING\"}")));
        var retried = sync(sender.id());

        assertThat(retried.status().name()).isEqualTo("PENDING_REVIEW");
        assertThat(retried.onboarding().twilioSenderSid()).isEqualTo("XE_retry_009");
    }

    @Test
    void submit_forwards_the_chosen_verification_method() {
        var sender = createDraft("+15553000010");
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(json(201, "{\"sid\":\"XE_voice_010\",\"status\":\"CREATING\"}")));

        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/submit", TENANT, sender.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.SubmitRequest("voice"))
                .exchange()
                .expectStatus().isOk();

        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders"))
                .withRequestBody(matchingJsonPath("$.configuration.verification_method", equalTo("voice"))));
    }

    @Test
    void a_bare_website_domain_is_sent_to_twilio_with_https() {
        var sender = createDraft("+15553000011", "test.corillo.do");
        assertThat(sender.businessProfile().websiteUrl()).isEqualTo("https://test.corillo.do");
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(json(201, "{\"sid\":\"XE_site_011\",\"status\":\"CREATING\"}")));

        submit(sender.id());

        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders"))
                .withRequestBody(matchingJsonPath("$.profile.websites[0].website",
                        equalTo("https://test.corillo.do"))));
    }

    @Test
    void a_website_that_is_not_a_web_address_is_rejected_before_twilio() {
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draftRequest("+15553000012", "not a website"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private WhatsAppSenderDtos.SenderResponse submit(String senderId) {
        return client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/submit", TENANT, senderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
    }

    private WhatsAppSenderDtos.SenderResponse sync(String senderId) {
        return client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/sync", TENANT, senderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    @Test
    void force_sync_on_unsubmitted_sender_is_rejected() {
        var sender = createDraft("+15553000005");
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/sync", TENANT, sender.id())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("not_submitted"));
    }
}
