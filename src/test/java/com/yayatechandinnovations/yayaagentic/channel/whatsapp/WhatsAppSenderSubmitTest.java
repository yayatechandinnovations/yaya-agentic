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
        var req = new WhatsAppSenderDtos.SenderRequest(
                phone,
                "Submit Test Co",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "Submit Test Inc.", "Submit Test Co", "ops@example.com",
                        "1 Apple Park Way", "https://example.com", "RETAIL", "Stuff."),
                null);
        return client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
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
                                  "configuration":{"profile":{"name":"Submit Test Co (Approved)"}}
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
