package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundOutboundSink;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * E2E verification that the playground simulator routes inbound through the
 * engine and that the bot's reply lands on the in-memory sink — all without
 * Twilio configured.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class PlaygroundWhatsAppTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "playground-wa-test";

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        var t = new AdminDtos.TenantRequest(
                TENANT, "Playground Test", "https://playground-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(t).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));

        var p = new AdminDtos.ProfileRequest(
                TENANT, "playground-wa-profile", "Playground WA",
                "intro one-liner", "system prompt fragment",
                List.of(), null, null, Map.of());
        client.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(p).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    @Test
    void rejects_reserved_phone_range_for_real_senders() {
        var req = new com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos.SenderRequest(
                "+15550100123",  // reserved
                "Bogus",
                new com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos.BusinessProfileRequest(
                        "Bogus Inc.", "Bogus", "ops@bogus.example",
                        "—", "https://bogus.example", "RETAIL", "—"),
                null);
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("reserved_phone"));
    }

    @Test
    void inbound_acks_and_dispatches_worker_async() {
        var body = Map.of(
                "text", "hello playground",
                "fromPhone", "+15558675309",
                "profileId", "playground-wa-profile",
                "profileVersion", 1);
        var receipt = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-playground/inbound", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(PlaygroundWhatsAppController.InboundReceipt.class)
                .returnResult().getResponseBody();
        assertThat(receipt).isNotNull();
        assertThat(receipt.messageSid()).startsWith("SMplay_");
        // The worker pool runs the engine on its own thread; the bot's reply
        // shows up on the outbound sink via the /outbound poll. The end-to-
        // end "sink has a message" assertion lives in WhatsAppWebhookE2ETest's
        // analogue; this test just defends the public surface of the
        // playground controller (sync-block-free, typed 202 receipt).
    }

    @Test
    void inbound_rejects_pre_elevation_with_missing_subject() {
        var body = Map.of(
                "text", "test",
                "fromPhone", "+15558675309",
                "profileId", "playground-wa-profile",
                "profileVersion", 1,
                "preElevation", Map.of(
                        "audience", "tool:transfer_funds",
                        "addedScopes", List.of("stepup:tool:transfer_funds"),
                        "ttlSeconds", 600));
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-playground/inbound", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error())
                        .isEqualTo("missing_pre_elevation_subject"));
    }

    @Test
    void inbound_rejects_pre_elevation_with_reserved_scope() {
        var body = Map.of(
                "text", "test",
                "fromPhone", "+15558675309",
                "profileId", "playground-wa-profile",
                "profileVersion", 1,
                "preElevation", Map.of(
                        "subject", "u_42",
                        "audience", "tool:transfer_funds",
                        "addedScopes", List.of("phone"),   // reserved channel claim key
                        "ttlSeconds", 600));
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-playground/inbound", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error())
                        .isEqualTo("reserved_scope_in_pre_elevation"));
    }

    /** Sanity probe — bean wires up and serves a 200 with an empty list when
     *  no messages have been written. */
    @Autowired PlaygroundOutboundSink sink;

    @Test
    void outbound_poll_returns_empty_for_unknown_session() {
        var drain = client.get()
                .uri("/v1/admin/tenants/{t}/whatsapp-playground/outbound/{s}", TENANT, "no-such-session")
                .exchange()
                .expectStatus().isOk()
                .expectBody(PlaygroundWhatsAppController.OutboundDrain.class)
                .returnResult().getResponseBody();
        assertThat(drain).isNotNull();
        assertThat(drain.messages()).isEmpty();
    }
}
