package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateCategory;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateStatus;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** E2E coverage of W6 template CRUD + status transitions. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WhatsAppTemplateControllerTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "template-test";
    private static final AtomicReference<String> SENDER_ID = new AtomicReference<>();
    private static final AtomicLong TEMPLATE_ID = new AtomicLong(-1);

    @BeforeEach
    void auth() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        ensureTenant();
        if (SENDER_ID.get() == null) {
            var req = new WhatsAppSenderDtos.SenderRequest(
                    "+15559001111", "Template Co",
                    new WhatsAppSenderDtos.BusinessProfileRequest(
                            "Template Inc.", "Template Co", "ops@template.example",
                            "Address", "https://template.example", "RETAIL", "Stuff."),
                    null);
            var res = client.post()
                    .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req).exchange()
                    .expectStatus().isCreated()
                    .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                    .returnResult().getResponseBody();
            SENDER_ID.set(res.id());
        }
    }

    private void ensureTenant() {
        var req = new AdminDtos.TenantRequest(
                TENANT, "Template Test", "https://template-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    @Test @Order(1)
    void creates_template_in_draft() {
        var req = new WhatsAppTemplateDtos.TemplateRequest(
                "appt_reminder", "en-US", TemplateCategory.UTILITY,
                "Hi {{1}}, your appointment is at {{2}}.");
        var res = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().isCreated()
                .expectBody(WhatsAppTemplateDtos.TemplateResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(res.placeholderCount()).isEqualTo(2);
        assertThat(res.name()).isEqualTo("appt_reminder");
        TEMPLATE_ID.set(res.id());
    }

    @Test @Order(2)
    void rejects_duplicate_name_locale_on_same_sender() {
        var req = new WhatsAppTemplateDtos.TemplateRequest(
                "appt_reminder", "en-US", TemplateCategory.UTILITY,
                "Different body.");
        client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("template_exists"));
    }

    @Test @Order(3)
    void updates_draft_body_and_category() {
        var req = new WhatsAppTemplateDtos.TemplateUpdate(
                "Hi {{1}}, your appointment is at {{2}}. Reply STOP to opt out.",
                TemplateCategory.UTILITY);
        var res = client.put()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/{tid}",
                        TENANT, SENDER_ID.get(), TEMPLATE_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppTemplateDtos.TemplateResponse.class)
                .returnResult().getResponseBody();
        assertThat(res.body()).contains("Reply STOP");
    }

    @Test @Order(4)
    void submit_flips_to_submitted() {
        var res = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/{tid}/submit",
                        TENANT, SENDER_ID.get(), TEMPLATE_ID.get())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppTemplateDtos.TemplateResponse.class)
                .returnResult().getResponseBody();
        assertThat(res.status()).isEqualTo(TemplateStatus.SUBMITTED);
    }

    @Test @Order(5)
    void update_after_submit_is_rejected() {
        var req = new WhatsAppTemplateDtos.TemplateUpdate(
                "Different body", TemplateCategory.UTILITY);
        client.put()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/{tid}",
                        TENANT, SENDER_ID.get(), TEMPLATE_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("not_editable"));
    }

    @Test @Order(6)
    void delete_draft_template_is_allowed() {
        // Create a fresh DRAFT to delete (the prior one is now SUBMITTED).
        var createReq = new WhatsAppTemplateDtos.TemplateRequest(
                "scratch_template", "en-US", TemplateCategory.MARKETING,
                "Scratch body");
        var created = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createReq).exchange()
                .expectStatus().isCreated()
                .expectBody(WhatsAppTemplateDtos.TemplateResponse.class)
                .returnResult().getResponseBody();
        client.delete()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/{tid}",
                        TENANT, SENDER_ID.get(), created.id())
                .exchange()
                .expectStatus().isNoContent();
    }
}
