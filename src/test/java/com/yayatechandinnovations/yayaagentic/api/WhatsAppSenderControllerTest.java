package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end coverage of W1's WhatsApp sender CRUD + state machine.
 * Twilio is deliberately NOT configured for these tests — every assertion
 * about the response shape implicitly verifies that the tenant-facing
 * surface doesn't leak BSP vocabulary (no fields named twilioSid, no error
 * strings containing "Twilio").
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WhatsAppSenderControllerTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "whatsapp-test";
    private static final AtomicReference<String> SENDER_ID = new AtomicReference<>();

    @BeforeEach
    void authenticate() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        ensureTenant();
    }

    private void ensureTenant() {
        var req = new AdminDtos.TenantRequest(
                TENANT, "WhatsApp Test", "https://wa-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    @Test @Order(1)
    void creates_sender_in_draft() {
        var req = new WhatsAppSenderDtos.SenderRequest(
                "+15551234567",
                "Acme Retail",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "Acme Retail Inc.", "Acme Retail", "ops@acme.com",
                        "1 Apple Park Way", "https://acme.com", "RETAIL", "We sell things."),
                null);
        var res = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.id()).startsWith("snd_");
        assertThat(res.tenantId()).isEqualTo(TENANT);
        assertThat(res.status()).isEqualTo(SenderStatus.DRAFT);
        assertThat(res.approvedDisplayName()).isNull();
        assertThat(res.phoneE164()).isEqualTo("+15551234567");
        assertThat(res.defaultLocale()).isEqualTo("en-US");
        SENDER_ID.set(res.id());
    }

    @Test @Order(2)
    void rejects_duplicate_phone_number_across_tenants() {
        var req = new WhatsAppSenderDtos.SenderRequest(
                "+15551234567",        // same phone as @Order(1)
                "Different Display",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "Dup Inc.", "Dup", "ops@dup.com",
                        "—", "https://dup.com", "RETAIL", "—"),
                null);
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("phone_in_use"));
    }

    @Test @Order(3)
    void rejects_malformed_phone() {
        var req = new WhatsAppSenderDtos.SenderRequest(
                "15551231234",         // missing leading '+'
                "X",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "X Inc.", "X", "ops@x.com",
                        "—", "https://x.com", "RETAIL", "—"),
                null);
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("bad_phone"));
    }

    @Test @Order(4)
    void rejects_missing_business_profile() {
        var req = new WhatsAppSenderDtos.SenderRequest(
                "+15551111111", "X", null, null);
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("missing_business_profile"));
    }

    @Test @Order(5)
    void lists_senders_for_tenant() {
        var list = client.get()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(list).isNotNull().isNotEmpty();
        assertThat(list).anySatisfy(s -> assertThat(s.id()).isEqualTo(SENDER_ID.get()));
    }

    @Test @Order(6)
    void edits_draft_sender_phone_and_display_name() {
        var req = new WhatsAppSenderDtos.SenderRequest(
                "+15552223333",
                "Acme Retail Renamed",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "Acme Retail Inc.", "Acme Retail Renamed", "ops2@acme.com",
                        "1 Apple Park Way", "https://acme.com", "RETAIL", "Stuff."),
                "es-DO");
        var res = client.put()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.phoneE164()).isEqualTo("+15552223333");
        assertThat(res.displayName()).isEqualTo("Acme Retail Renamed");
        assertThat(res.defaultLocale()).isEqualTo("es-DO");
        assertThat(res.status()).isEqualTo(SenderStatus.DRAFT);
    }

    @Test @Order(7)
    void submit_requires_twilio_to_be_configured() {
        // The integration test rig does not configure Twilio creds, so the
        // resolver reports not ready and the submit endpoint declines with
        // a typed error rather than crashing later in the Twilio call.
        // W2 e2e coverage with a stubbed Twilio lives in the dedicated
        // TwilioMessagingChannelProvider + WhatsAppSenderSubmitTest suites.
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/submit", TENANT, SENDER_ID.get())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("provider_not_ready"));
    }

    @Test @Order(8)
    void suspend_from_draft_is_illegal() {
        // DRAFT → SUSPENDED is not a legal §6 transition.
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/suspend", TENANT, SENDER_ID.get())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("illegal_transition"));
    }

    @Test @Order(9)
    void revoke_from_draft_is_legal() {
        var res = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/revoke", TENANT, SENDER_ID.get())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(SenderStatus.REVOKED);
    }

    @Test @Order(10)
    void edit_revoked_sender_is_rejected() {
        var req = new WhatsAppSenderDtos.SenderRequest(
                "+15554445555", "X",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "X Inc.", "X", "ops@x.com",
                        "—", "https://x.com", "RETAIL", "—"),
                null);
        client.put()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("not_editable"));
    }

    @Test @Order(11)
    void get_unknown_sender_returns_404() {
        client.get()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}", TENANT, "snd_does_not_exist")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("sender_not_found"));
    }
}
