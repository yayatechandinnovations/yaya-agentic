package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppEmbeddedSignupDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioSignatureVerifier;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end embedded-signup coverage against a WireMock-stubbed Twilio:
 * subaccount provisioning (create vs reuse), WABA/phone ownership conflicts,
 * idempotent retries, Senders API registration under the subaccount's own
 * credentials, asynchronous status transitions (CREATING → ONLINE), unknown
 * future statuses, failure sanitization, and webhook signature verification
 * with the subaccount auth token.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WhatsAppEmbeddedSignupE2ETest {

    private static WireMockServer wireMock;

    /** base64 of a fixed 32-byte AES key — test-only. */
    private static final String TEST_MASTER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** Unique-per-run digits: the reusable Testcontainers database keeps rows
     *  across runs, and subaccount SIDs / phones / WABAs all carry UNIQUE
     *  constraints — fixed literals would collide on the second run. */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final String RUN =
            String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));

    private static String uniquePhone() {
        return "+1555" + RUN.substring(0, 5) + String.format("%02d", SEQ.incrementAndGet());
    }

    private static String uniqueWaba() {
        return "111" + RUN + String.format("%02d", SEQ.incrementAndGet());
    }

    private static String uniqueSubSid() {
        return "AC" + RUN + "00000000000000000000" + String.format("%05d", SEQ.incrementAndGet());
    }

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
        r.add("yaya.agentic.channels.twilio.api-key-secret", () -> "parent-api-secret");
        r.add("yaya.agentic.channels.twilio.auth-token", () -> "parent-auth-token");
        r.add("yaya.agentic.channels.twilio.webhook-base-url", () -> "https://hooks.example");
        r.add("yaya.agentic.channels.twilio.api-base-url", () -> base);
        r.add("yaya.agentic.channels.twilio.messaging-base-url", () -> base);
        r.add("yaya.agentic.channels.whatsapp.poller.enabled", () -> "false");
        r.add("yaya.agentic.channels.whatsapp.embedded-signup.enabled", () -> "true");
        r.add("yaya.agentic.channels.whatsapp.embedded-signup.meta-app-id", () -> "1234567890");
        r.add("yaya.agentic.channels.whatsapp.embedded-signup.meta-configuration-id", () -> "9876543210");
        r.add("yaya.agentic.channels.whatsapp.embedded-signup.partner-solution-id", () -> "solution-1");
        r.add("yaya.agentic.secrets.encryption-key", () -> TEST_MASTER_KEY);
        r.add("yaya.agentic.llm.provider", () -> "stub");
        r.add("spring.ai.anthropic.api-key", () -> "");
    }

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired WhatsAppSenderRepository senderRepo;

    private static final String TENANT_A = "es-tenant-a";
    private static final String TENANT_B = "es-tenant-b";

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        wireMock.resetAll();
        wireMock.stubFor(get(urlPathMatching("/2010-04-01/Accounts/.*\\.json"))
                .willReturn(aResponse().withStatus(200).withBody("{\"sid\":\"AC1\"}")));
        ensureTenant(TENANT_A);
        ensureTenant(TENANT_B);
    }

    /* ---------------------------- happy path ------------------------------ */

    @Test
    void first_onboarding_provisions_subaccount_and_registers_sender_under_it() {
        String phone = uniquePhone();
        String waba = uniqueWaba();
        String subSid = uniqueSubSid();
        String subToken = "tok-" + subSid;
        stubSubaccountCreate(subSid, subToken);
        stubSenderCreate("XE_" + RUN + "_001", "CREATING");

        var res = startOnboarding(TENANT_A, phone, waba, "555000111", HttpStatus.ACCEPTED);

        assertThat(res.attempt().state()).isEqualTo(OnboardingAttemptState.COMPLETED);
        assertThat(res.attempt().messagingAccountId()).isNotNull();
        assertThat(res.sender()).isNotNull();
        assertThat(res.sender().status().name()).isEqualTo("PENDING_REVIEW");
        assertThat(res.sender().onboarding().twilioSenderSid()).isEqualTo("XE_" + RUN + "_001");
        assertThat(res.sender().wabaId()).isEqualTo(waba);

        // The Senders API call authenticated with the SUBACCOUNT's SID +
        // auth token — not the parent API key.
        String subBasic = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (subSid + ":" + subToken).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMock.verify(postRequestedFor(urlEqualTo("/v2/Channels/Senders"))
                .withHeader("Authorization", equalTo(subBasic))
                .withRequestBody(matchingJsonPath("$.configuration.waba_id", equalTo(waba)))
                .withRequestBody(matchingJsonPath("$.sender_id", equalTo("whatsapp:" + phone))));

        // Subaccount SID / credentials never reach the tenant surface.
        var accounts = listAccounts(TENANT_A);
        assertThat(accounts).anySatisfy(a -> {
            assertThat(a.get("wabaId")).isEqualTo(waba);
            assertThat(a).doesNotContainKey("twilioSubaccountSid");
            assertThat(a.toString()).doesNotContain(subSid).doesNotContain(subToken);
        });
    }

    @Test
    void duplicate_submission_is_idempotent_and_creates_no_second_subaccount() {
        String phone = uniquePhone();
        String waba = uniqueWaba();
        stubSubaccountCreate(uniqueSubSid(), "tok-idem");
        stubSenderCreate("XE_" + RUN + "_002", "CREATING");

        startOnboarding(TENANT_A, phone, waba, null, HttpStatus.ACCEPTED);
        var replay = startOnboarding(TENANT_A, phone, waba, null, HttpStatus.ACCEPTED);

        assertThat(replay.attempt().state()).isEqualTo(OnboardingAttemptState.COMPLETED);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/2010-04-01/Accounts.json")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v2/Channels/Senders")));
    }

    @Test
    void second_number_under_same_waba_reuses_the_subaccount() {
        String waba = uniqueWaba();
        stubSubaccountCreate(uniqueSubSid(), "tok-reuse");
        stubSenderCreate("XE_" + RUN + "_003a", "CREATING");
        startOnboarding(TENANT_A, uniquePhone(), waba, null, HttpStatus.ACCEPTED);

        stubSenderCreate("XE_" + RUN + "_003b", "CREATING");
        var second = startOnboarding(TENANT_A, uniquePhone(), waba, null, HttpStatus.ACCEPTED);

        assertThat(second.attempt().state()).isEqualTo(OnboardingAttemptState.COMPLETED);
        // One subaccount total, two sender registrations.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/2010-04-01/Accounts.json")));
        wireMock.verify(2, postRequestedFor(urlEqualTo("/v2/Channels/Senders")));
        assertThat(listAccounts(TENANT_A).stream()
                .filter(a -> waba.equals(a.get("wabaId")))).hasSize(1);
    }

    /* ---------------------------- conflicts ------------------------------- */

    @Test
    void waba_owned_by_another_tenant_is_rejected() {
        String waba = uniqueWaba();
        stubSubaccountCreate(uniqueSubSid(), "tok-conf1");
        stubSenderCreate("XE_" + RUN + "_004", "CREATING");
        startOnboarding(TENANT_A, uniquePhone(), waba, null, HttpStatus.ACCEPTED);

        client.post().uri("/v1/admin/tenants/{t}/whatsapp/embedded-signup", TENANT_B)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(uniquePhone(), waba, null))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("waba_owned_by_other_tenant"));
    }

    @Test
    void phone_owned_by_another_tenant_is_rejected() {
        String phone = uniquePhone();
        stubSubaccountCreate(uniqueSubSid(), "tok-conf2");
        stubSenderCreate("XE_" + RUN + "_005", "CREATING");
        startOnboarding(TENANT_A, phone, uniqueWaba(), null, HttpStatus.ACCEPTED);

        client.post().uri("/v1/admin/tenants/{t}/whatsapp/embedded-signup", TENANT_B)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(phone, uniqueWaba(), null))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("phone_in_use"));
    }

    /* ---------------------------- failures + retry ------------------------ */

    @Test
    void subaccount_outage_is_retryable_without_duplicates() {
        String phone = uniquePhone();
        String waba = uniqueWaba();
        wireMock.stubFor(post(urlEqualTo("/2010-04-01/Accounts.json"))
                .willReturn(aResponse().withStatus(503).withBody("upstream busy")));

        client.post().uri("/v1/admin/tenants/{t}/whatsapp/embedded-signup", TENANT_A)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(phone, waba, null))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // Retry after the outage clears — same attempt resumes, one
        // subaccount, one sender.
        stubSubaccountCreate(uniqueSubSid(), "tok-retry");
        stubSenderCreate("XE_" + RUN + "_006", "CREATING");
        var retried = startOnboarding(TENANT_A, phone, waba, null, HttpStatus.ACCEPTED);

        assertThat(retried.attempt().state()).isEqualTo(OnboardingAttemptState.COMPLETED);
        assertThat(listAccounts(TENANT_A).stream()
                .filter(a -> waba.equals(a.get("wabaId")))).hasSize(1);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v2/Channels/Senders")));
    }

    @Test
    void sender_rejection_fails_attempt_with_sanitized_message() {
        String phone = uniquePhone();
        String waba = uniqueWaba();
        stubSubaccountCreate(uniqueSubSid(), "tok-rej");
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":21712,"message":"Twilio rejected sender AC0000000000000000000000000000dead for policy reasons."}
                                """)));

        var res = startOnboarding(TENANT_A, phone, waba, null, HttpStatus.ACCEPTED);

        assertThat(res.attempt().state()).isEqualTo(OnboardingAttemptState.FAILED);
        assertThat(res.attempt().failureMessage())
                .doesNotContain("Twilio")
                .doesNotContain("AC0000000000000000000000000000dead")
                .contains("the messaging provider");
        assertThat(res.sender().status().name()).isEqualTo("FAILED");

        // Operator retry after the rejection: same idempotency row resumes,
        // the FAILED sender flips back through DRAFT and resubmits.
        stubSenderCreate("XE_" + RUN + "_007", "CREATING");
        var retried = startOnboarding(TENANT_A, phone, waba, null, HttpStatus.ACCEPTED);
        assertThat(retried.attempt().state()).isEqualTo(OnboardingAttemptState.COMPLETED);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/2010-04-01/Accounts.json")));
    }

    /* ---------------------------- async status ---------------------------- */

    @Test
    void sender_transitions_creating_to_online_via_sync_and_unknown_statuses_hold() {
        String phone = uniquePhone();
        String waba = uniqueWaba();
        String xe = "XE_" + RUN + "_008";
        stubSubaccountCreate(uniqueSubSid(), "tok-async");
        stubSenderCreate(xe, "CREATING");
        var res = startOnboarding(TENANT_A, phone, waba, null, HttpStatus.ACCEPTED);
        String senderId = res.sender().id();

        // Unknown future Twilio status: state held, raw value stored.
        stubSenderFetch(xe, "SOME_FUTURE_STATE");
        var held = sync(TENANT_A, senderId);
        assertThat(held.status().name()).isEqualTo("PENDING_REVIEW");
        assertThat(held.onboarding().status()).isEqualTo("SOME_FUTURE_STATE");

        // Still in flight.
        stubSenderFetch(xe, "TWILIO_REVIEW");
        assertThat(sync(TENANT_A, senderId).status().name()).isEqualTo("PENDING_REVIEW");

        // Online → ACTIVE (message sending allowed only now).
        stubSenderFetch(xe, "ONLINE");
        assertThat(sync(TENANT_A, senderId).status().name()).isEqualTo("ACTIVE");
    }

    /* ---------------------------- webhook trust --------------------------- */

    @Test
    void webhook_verifies_with_subaccount_token_not_platform_token() {
        String phone = uniquePhone();
        String waba = uniqueWaba();
        String subToken = "tok-webhook-" + RUN;
        stubSubaccountCreate(uniqueSubSid(), subToken);
        stubSenderCreate("XE_" + RUN + "_009", "CREATING");
        var res = startOnboarding(TENANT_A, phone, waba, null, HttpStatus.ACCEPTED);
        String senderId = res.sender().id();

        stubSenderFetch("XE_" + RUN + "_009", "ONLINE");
        sync(TENANT_A, senderId);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("MessageSid", "SM_es_" + senderId);
        params.put("From", "whatsapp:+15559876543");
        params.put("To", "whatsapp:" + phone);
        params.put("Body", "hola");
        params.put("NumMedia", "0");
        String url = "https://hooks.example/v1/channels/whatsapp/twilio/webhook/" + senderId;

        // Signed with the platform token → rejected (the subaccount signs
        // this sender's webhooks).
        String platformSig = TwilioSignatureVerifier.computeExpectedSignature(
                url, params, "parent-auth-token");
        postWebhook(senderId, params, platformSig).expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

        // Signed with the subaccount token → accepted.
        String subSig = TwilioSignatureVerifier.computeExpectedSignature(url, params, subToken);
        postWebhook(senderId, params, subSig).expectStatus().isOk();
    }

    /* ---------------------------- config surface -------------------------- */

    @Test
    void config_endpoint_exposes_public_meta_ids_only() {
        client.get().uri("/v1/admin/tenants/{t}/whatsapp/embedded-signup/config", TENANT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppEmbeddedSignupDtos.SignupConfigResponse.class)
                .value(cfg -> {
                    assertThat(cfg.enabled()).isTrue();
                    assertThat(cfg.metaAppId()).isEqualTo("1234567890");
                    assertThat(cfg.metaConfigurationId()).isEqualTo("9876543210");
                    assertThat(cfg.partnerSolutionId()).isEqualTo("solution-1");
                });
    }

    /* ---------------------------- helpers --------------------------------- */

    private void ensureTenant(String tenant) {
        var req = new AdminDtos.TenantRequest(
                tenant, "ES " + tenant, "https://" + tenant + ".example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private void stubSubaccountCreate(String sid, String token) {
        wireMock.stubFor(post(urlEqualTo("/2010-04-01/Accounts.json"))
                .withRequestBody(containing("FriendlyName="))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"" + sid + "\",\"auth_token\":\"" + token
                                + "\",\"status\":\"active\"}")));
    }

    private void stubSenderCreate(String senderSid, String status) {
        wireMock.stubFor(post(urlEqualTo("/v2/Channels/Senders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"" + senderSid + "\",\"status\":\"" + status + "\"}")));
    }

    private void stubSenderFetch(String senderSid, String status) {
        wireMock.stubFor(get(urlEqualTo("/v2/Channels/Senders/" + senderSid))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"" + senderSid + "\",\"status\":\"" + status + "\"}")));
    }

    private WhatsAppEmbeddedSignupDtos.OnboardingRequestBody body(String phone, String waba,
                                                                  String metaPhoneNumberId) {
        return new WhatsAppEmbeddedSignupDtos.OnboardingRequestBody(
                phone, waba, metaPhoneNumberId, "ES Test Co",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "ES Test Inc.", "ES Test Co", "ops@es-test.example",
                        "1 Test Way", "https://es-test.example", "RETAIL", "Stuff."),
                null);
    }

    private WhatsAppEmbeddedSignupDtos.OnboardingResponse startOnboarding(
            String tenant, String phone, String waba, String metaPhoneNumberId,
            HttpStatus expected) {
        var raw = client.post().uri("/v1/admin/tenants/{t}/whatsapp/embedded-signup", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(phone, waba, metaPhoneNumberId))
                .exchange()
                .returnResult(String.class);
        String bodyStr = new String(raw.getResponseBodyContent(), java.nio.charset.StandardCharsets.UTF_8);
        if (!raw.getStatus().equals(expected)) {
            throw new AssertionError("status=" + raw.getStatus() + " body=" + bodyStr);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .readValue(bodyStr, WhatsAppEmbeddedSignupDtos.OnboardingResponse.class);
        } catch (Exception ex) {
            throw new AssertionError("unparseable body: " + bodyStr, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listAccounts(String tenant) {
        return client.get().uri("/v1/admin/tenants/{t}/whatsapp/messaging-accounts", tenant)
                .exchange()
                .expectStatus().isOk()
                .expectBody(List.class)
                .returnResult().getResponseBody();
    }

    private WhatsAppSenderDtos.SenderResponse sync(String tenant, String senderId) {
        return client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{id}/sync", tenant, senderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec postWebhook(String senderId, Map<String, String> params,
                                                   String signature) {
        return client.post()
                .uri("/v1/channels/whatsapp/twilio/webhook/{sid}", senderId)
                .header("X-Twilio-Signature", signature)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(asForm(params))
                .exchange();
    }

    private static MultiValueMap<String, String> asForm(Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        return form;
    }
}
