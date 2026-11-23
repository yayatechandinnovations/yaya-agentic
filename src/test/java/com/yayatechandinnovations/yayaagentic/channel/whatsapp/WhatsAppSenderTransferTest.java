package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioSignatureVerifier;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderTransferRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * whatsapp-sender-transfer-design.md — a platform admin moves a live sender to
 * another workspace: same BSP sender, new row, history left behind, webhooks
 * re-pointed (and forwarded until they are), plus the adopt guard (§10.1).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WhatsAppSenderTransferTest {

    private static final String WEBHOOK_BASE = "https://hooks.example";
    private static final String AUTH_TOKEN = "transfer-test-auth-token";

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
    static void props(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + wireMock.port();
        r.add("yaya.agentic.channels.twilio.account-sid", () -> "AC1111111111111111111111111111aaaa");
        r.add("yaya.agentic.channels.twilio.api-key-sid", () -> "SK2222222222222222222222222222bbbb");
        r.add("yaya.agentic.channels.twilio.api-key-secret", () -> "supersecret");
        r.add("yaya.agentic.channels.twilio.auth-token", () -> AUTH_TOKEN);
        r.add("yaya.agentic.channels.twilio.webhook-base-url", () -> WEBHOOK_BASE);
        r.add("yaya.agentic.channels.twilio.api-base-url", () -> base);
        r.add("yaya.agentic.channels.twilio.messaging-base-url", () -> base);
        r.add("yaya.agentic.channels.whatsapp.poller.enabled", () -> "false");
        r.add("yaya.agentic.user-accounts.enabled", () -> "true");
        r.add("yaya.agentic.llm.provider", () -> "stub");
        r.add("spring.ai.anthropic.api-key", () -> "");
    }

    @Autowired WebTestClient base;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired UserAccountsTestSupport users;
    @Autowired WhatsAppSenderRepository senderRows;
    @Autowired WhatsAppSenderTransferRepository transferRows;
    @Autowired WhatsAppSenderTransferService transfers;
    @Autowired ChannelOptOutRepository optOuts;
    @Autowired ChannelBindingRepository bindingRows;
    @Autowired ChannelSessionService channelSessions;
    @Autowired ChannelSessionRepository channelSessionRows;
    @Autowired OutboundPendingRepository parkedRows;
    @Autowired SystemSenderRepository systemSenders;
    @Autowired InboundMessageRepository inbound;

    private WebTestClient admin;
    private String a;
    private String b;

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        admin = TestAuthDance.asBootstrap(base);
        wireMock.resetAll();
        wireMock.stubFor(get(urlPathMatching("/2010-04-01/Accounts/.*\\.json"))
                .willReturn(aResponse().withStatus(200).withBody("{\"sid\":\"AC1\"}")));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        a = createTenant("xfer-a-" + suffix);
        b = createTenant("xfer-b-" + suffix);
        createProfile(a, "front-desk");
        createProfile(b, "concierge");
    }

    @Test
    void a_dry_run_reports_the_effects_and_changes_nothing() {
        String sid = "SR_dry_" + shortId();
        var sender = liveSender(a, randomPhone(), sid);
        optOuts.save(new ChannelOptOutEntity(sender.id(), "+15550001111", "STOP"));
        bind(a, sender.id(), "front-desk");

        var plan = transfer(a, sender.id(), request(b, "concierge"), true)
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.TransferResponse.class)
                .returnResult().getResponseBody();

        assertThat(plan.dryRun()).isTrue();
        assertThat(plan.destination()).isNull();
        assertThat(plan.effects().optOutsCopied()).isEqualTo(1);
        assertThat(plan.effects().bindingsDeactivated()).isEqualTo(1);
        assertThat(senderRows.findById(sender.id()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v2/Channels/Senders/" + sid)));
    }

    @Test
    void a_transfer_keeps_the_bsp_sender_and_leaves_history_behind() {
        String sid = "SR_move_" + shortId();
        String phone = randomPhone();
        var sender = liveSender(a, phone, sid);
        stubRepoint(sid, 200);

        optOuts.save(new ChannelOptOutEntity(sender.id(), "+15550002222", "STOP"));
        bind(a, sender.id(), "front-desk");
        channelSessions.register(new Ids.TenantId(a), new SenderId(sender.id()), "+15550003333",
                new Ids.SessionId("ses_" + shortId()));
        parkedRows.save(new OutboundPendingEntity(a, sender.id(), "+15550003333", "ses_parked", "later", null));
        SystemSenderEntity pos = new SystemSenderEntity("ss_" + shortId(), a, "ep_none", "POS");
        pos.setWhatsappSenderId(sender.id());
        pos.setUpdatedAt(java.time.OffsetDateTime.now());
        systemSenders.save(pos);
        String historySid = "SM_hist_" + shortId();
        inbound.save(new InboundMessageEntity(historySid, a, sender.id(), "+15550003333", 0, "{}"));

        var moved = transfer(a, sender.id(), request(b, "concierge"), false)
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.TransferResponse.class)
                .returnResult().getResponseBody();

        assertThat(moved.webhookRepoint()).isEqualTo("DONE");
        assertThat(moved.source().status().name()).isEqualTo("REVOKED");
        var dest = moved.destination();
        assertThat(dest.tenantId()).isEqualTo(b);
        assertThat(dest.id()).isNotEqualTo(sender.id());
        assertThat(dest.phoneE164()).isEqualTo(phone);
        assertThat(dest.status().name()).isEqualTo("ACTIVE");
        assertThat(dest.onboarding().twilioSenderSid()).isEqualTo(sid);
        assertThat(dest.approvedDisplayName()).isEqualTo("Transfer Co");

        assertThat(moved.effects().optOutsCopied()).isEqualTo(1);
        assertThat(moved.effects().openSessionsClosed()).isEqualTo(1);
        assertThat(moved.effects().parkedOutboundDropped()).isEqualTo(1);
        assertThat(moved.effects().bindingsDeactivated()).isEqualTo(1);
        assertThat(moved.effects().systemSendersUnlinked()).containsExactly(pos.getId());

        // The BSP sender was re-pointed at the new row, never deleted.
        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders/" + sid))
                .withRequestBody(containing(WEBHOOK_BASE + "/v1/channels/whatsapp/twilio/webhook/" + dest.id())));
        wireMock.verify(0, deleteRequestedFor(urlPathMatching("/v2/Channels/Senders/.*")));

        assertThat(optOuts.findBySenderIdAndEndUserPhone(dest.id(), "+15550002222")).isPresent();
        assertThat(bindingRows.findByTenantIdOrderByPriorityAscIdAsc(a)).allSatisfy(binding ->
                assertThat(binding.getStatus()).isEqualTo(BindingStatus.DEACTIVATED.name()));
        assertThat(bindingRows.findByTenantIdOrderByPriorityAscIdAsc(b))
                .anySatisfy(binding -> {
                    assertThat(binding.getEndpointId()).isEqualTo(dest.id());
                    assertThat(binding.getProfileId()).isEqualTo("concierge");
                    assertThat(binding.getStatus()).isEqualTo(BindingStatus.ACTIVE.name());
                });
        assertThat(channelSessionRows.findByTenantIdAndSenderIdAndStatus(a, sender.id(), "OPEN")).isEmpty();
        assertThat(parkedRows.findByTenantIdAndSenderIdAndReleasedAtIsNull(a, sender.id())).isEmpty();
        assertThat(systemSenders.findById(pos.getId()).orElseThrow().getWhatsappSenderId()).isNull();

        // A's conversation history stays with A's retired row.
        var history = inbound.findById(historySid).orElseThrow();
        assertThat(history.getTenantId()).isEqualTo(a);
        assertThat(history.getSenderId()).isEqualTo(sender.id());

        assertThat(listSenders(b)).extracting(WhatsAppSenderDtos.SenderResponse::id).contains(dest.id());

        // Removing the retired row in A must never delete B's BSP sender.
        admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/revoke", a, sender.id())
                .exchange().expectStatus().isOk();
        wireMock.verify(0, deleteRequestedFor(urlPathMatching("/v2/Channels/Senders/.*")));
    }

    @Test
    void inbound_posted_to_the_old_id_lands_on_the_destination() {
        String sid = "SR_fwd_" + shortId();
        var sender = liveSender(a, randomPhone(), sid);
        // The re-point fails, so Twilio keeps posting to the old URL.
        stubRepoint(sid, 503);

        var moved = transfer(a, sender.id(), request(b, "concierge"), false)
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.TransferResponse.class)
                .returnResult().getResponseBody();
        assertThat(moved.webhookRepoint()).isEqualTo("PENDING");
        assertThat(senderRows.findById(sender.id()).orElseThrow().getTransferredToSenderId())
                .isEqualTo(moved.destination().id());

        String messageSid = "SM_fwd_" + shortId();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MessageSid", messageSid);
        params.put("From", "whatsapp:+15550004444");
        params.put("To", "whatsapp:" + sender.phoneE164());
        params.put("Body", "hello");
        params.put("NumMedia", "0");
        String url = WEBHOOK_BASE + "/v1/channels/whatsapp/twilio/webhook/" + sender.id();
        base.post().uri("/v1/channels/whatsapp/twilio/webhook/{id}", sender.id())
                .header("X-Twilio-Signature", TwilioSignatureVerifier.computeExpectedSignature(url, params, AUTH_TOKEN))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(asForm(params))
                .exchange()
                .expectStatus().isOk();

        var row = inbound.findById(messageSid).orElseThrow();
        assertThat(row.getTenantId()).isEqualTo(b);
        assertThat(row.getSenderId()).isEqualTo(moved.destination().id());
    }

    @Test
    void a_failed_repoint_is_retried_until_it_lands() {
        String sid = "SR_retry_" + shortId();
        var sender = liveSender(a, randomPhone(), sid);
        stubRepoint(sid, 503);

        var moved = transfer(a, sender.id(), request(b, null), false)
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.TransferResponse.class)
                .returnResult().getResponseBody();
        assertThat(moved.webhookRepoint()).isEqualTo("PENDING");
        assertThat(moved.warnings()).anyMatch(w -> w.contains("no route"));
        assertThat(transferMetadata(b, moved.destination().id())).containsEntry("webhooks", "PENDING");

        wireMock.resetAll();
        stubRepoint(sid, 200);
        transfers.retryPendingRepoints();

        var t = transferRows.findById(moved.transferId()).orElseThrow();
        assertThat(t.getRepointStatus()).isEqualTo("DONE");
        assertThat(t.getRepointAttempts()).isEqualTo(2);
        assertThat(transferMetadata(b, moved.destination().id())).containsEntry("webhooks", "DONE");
    }

    @Test
    void opt_outs_stay_behind_when_asked() {
        var sender = liveSender(a, randomPhone(), "SR_keep_" + shortId());
        stubRepoint(senderRows.findById(sender.id()).orElseThrow().getTwilioSenderSid(), 200);
        optOuts.save(new ChannelOptOutEntity(sender.id(), "+15550005555", "STOP"));

        var moved = transfer(a, sender.id(),
                new WhatsAppSenderDtos.TransferRequest(b, null, null, false), false)
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.TransferResponse.class)
                .returnResult().getResponseBody();

        assertThat(moved.effects().optOutsCopied()).isZero();
        assertThat(optOuts.findBySenderId(moved.destination().id())).isEmpty();
    }

    @Test
    void transfers_that_cannot_happen_are_refused() {
        var live = liveSender(a, randomPhone(), "SR_ref_" + shortId());
        var draft = draftSender(a, randomPhone());

        expectError(transfer(a, live.id(), request(a, null), false), 400, "same_workspace");
        expectError(transfer(a, live.id(), request("no-such-ws-" + shortId(), null), false), 400, "unknown_tenant");
        expectError(transfer(a, live.id(), request(b, "no-such-assistant"), false), 400, "unknown_profile");
        expectError(transfer(a, draft.id(), request(b, null), false), 409, "sender_not_transferable");

        String suspended = createTenant("xfer-s-" + shortId());
        admin.post().uri("/v1/admin/tenants/{t}/suspend", suspended).exchange().expectStatus().isOk();
        expectError(transfer(a, live.id(), request(suspended, null), false), 409, "destination_not_active");

        assertThat(senderRows.findById(live.id()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void only_a_platform_admin_may_move_a_number() {
        var owner = users.registerVerifyAndLogin(base,
                "xfer-" + UUID.randomUUID() + "@t.io", "Aa1!Pass-strong-xyz", "Owner");
        String ws = owner.user().defaultWorkspaceCode();
        var created = owner.client().post().uri("/v1/admin/tenants/{t}/whatsapp-senders", ws)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(senderRequest(randomPhone()))
                .exchange().expectStatus().isCreated()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        forceLive(created.id(), "SR_own_" + shortId());

        owner.client().post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/transfer", ws, created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(b, null))
                .exchange()
                .expectStatus().isForbidden();
        assertThat(senderRows.findById(created.id()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void a_number_still_registered_elsewhere_is_not_adopted() {
        String phone = randomPhone();
        String sid = "SR_held_" + shortId();
        var sender = liveSender(a, phone, sid);
        wireMock.stubFor(delete(urlEqualTo("/v2/Channels/Senders/" + sid))
                .willReturn(aResponse().withStatus(500)));

        var revoked = admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/revoke", a, sender.id())
                .exchange().expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(revoked.status().name()).isEqualTo("REVOKED");
        assertThat(revoked.metadata()).containsEntry("bspDeleteFailed", true);

        expectError(admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", b)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(senderRequest(phone))
                .exchange(), 409, "phone_held_elsewhere");

        // Retrying the removal finishes it at the BSP and frees the number.
        wireMock.resetAll();
        wireMock.stubFor(delete(urlEqualTo("/v2/Channels/Senders/" + sid))
                .willReturn(aResponse().withStatus(204)));
        var finished = admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/revoke", a, sender.id())
                .exchange().expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        assertThat(finished.metadata()).doesNotContainKey("bspDeleteFailed");

        admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", b)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(senderRequest(phone))
                .exchange().expectStatus().isCreated();
    }

    /* ---------------------------- helpers --------------------------------- */

    private WebTestClient.ResponseSpec transfer(String tenant, String senderId,
                                                WhatsAppSenderDtos.TransferRequest req, boolean dryRun) {
        return admin.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/transfer?dryRun={d}", tenant, senderId, dryRun)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange();
    }

    private static WhatsAppSenderDtos.TransferRequest request(String dest, String profileId) {
        return new WhatsAppSenderDtos.TransferRequest(dest, profileId, null, null);
    }

    private static void expectError(WebTestClient.ResponseSpec spec, int status, String code) {
        spec.expectStatus().isEqualTo(status)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo(code));
    }

    private String createTenant(String id) {
        admin.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.TenantRequest(id, "Transfer " + id, "https://" + id + ".example",
                        List.of(), List.of(), true, null, null, Map.of()))
                .exchange()
                .expectStatus().isCreated();
        return id;
    }

    private void createProfile(String tenant, String id) {
        admin.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.ProfileRequest(tenant, id, id, "intro", "prompt fragment",
                        List.of(), null, null, Map.of()))
                .exchange()
                .expectStatus().isCreated();
    }

    private void bind(String tenant, String senderId, String profileId) {
        admin.post().uri("/v1/admin/tenants/{t}/channel-bindings", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChannelBindingDtos.BindingRequest(
                        new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", senderId),
                        profileId, 1, new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100,
                        BindingStatus.ACTIVE, null, null, null, null))
                .exchange()
                .expectStatus().isCreated();
    }

    private static WhatsAppSenderDtos.SenderRequest senderRequest(String phone) {
        return new WhatsAppSenderDtos.SenderRequest(phone, "Transfer Co",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "Transfer Inc.", "Transfer Co", "ops@transfer.example",
                        "1 Main St", "https://transfer.example", "RETAIL", "Stuff."),
                null);
    }

    private WhatsAppSenderDtos.SenderResponse draftSender(String tenant, String phone) {
        return admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(senderRequest(phone))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
    }

    /** Registration needs a real WhatsApp code, so tests put the row live directly. */
    private WhatsAppSenderDtos.SenderResponse liveSender(String tenant, String phone, String sid) {
        var draft = draftSender(tenant, phone);
        forceLive(draft.id(), sid);
        return draft;
    }

    private void forceLive(String senderId, String sid) {
        var row = senderRows.findById(senderId).orElseThrow();
        row.setStatus("ACTIVE");
        row.setTwilioSenderSid(sid);
        row.setOnboardingStateJson("{\"twilioSenderSid\":\"" + sid + "\",\"lastTwilioStatus\":\"ONLINE\"}");
        row.setApprovedDisplayName("Transfer Co");
        senderRows.save(row);
    }

    private static void stubRepoint(String sid, int status) {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders/" + sid))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(status == 200 ? "{\"sid\":\"" + sid + "\",\"status\":\"ONLINE\"}" : "{}")));
    }

    private List<WhatsAppSenderDtos.SenderResponse> listSenders(String tenant) {
        return admin.get().uri("/v1/admin/tenants/{t}/whatsapp-senders", tenant)
                .exchange().expectStatus().isOk()
                .expectBodyList(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> transferMetadata(String tenant, String senderId) {
        var sender = admin.get().uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}", tenant, senderId)
                .exchange().expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        return (Map<String, Object>) sender.metadata().get("transfer");
    }

    private static String randomPhone() {
        return "+1557" + String.format("%07d", (long) (Math.random() * 10_000_000L));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static MultiValueMap<String, String> asForm(Map<String, String> map) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        map.forEach(form::add);
        return form;
    }
}
