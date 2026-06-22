package com.yayatechandinnovations.yayaagentic.user_accounts;

import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.SignupRequest;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.VerificationResendRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.AuditUserEventEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditUserEventRepository;
import com.yayatechandinnovations.yayaagentic.persistence.EmailOutboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.EmailOutboxRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Self-serve verification resend. Covers PRD FR-1.5: a user who signs up
 * but never verifies can request a fresh link, with per-account cooldown
 * and per-hour cap enforced; calls for unknown / already-active emails are
 * silently 202 to keep the surface enumeration-safe.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.user-accounts.enabled=true",
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        // Long cooldown so the rate-limit test never flakes on a slow CI
        // host — we only ever need the second call inside one test to be
        // denied; the per-test resetAll() at @BeforeEach gives the next
        // test a clean bucket.
        "yaya.agentic.user-accounts.verification.resend-cooldown=PT10M",
        "yaya.agentic.user-accounts.verification.resend-per-hour-cap=3"
})
class ResendVerificationIT {

    @Autowired WebTestClient client;
    @Autowired OperatorAccountService accounts;
    @Autowired UserAccountsTestSupport users;
    @Autowired EmailOutboxRepository outbox;
    @Autowired AuditUserEventRepository auditEvents;
    @Autowired VerificationResendRateLimiter rateLimiter;

    @BeforeEach
    void reset() {
        rateLimiter.resetAll();
    }

    // ------------------------------------------------------------------
    // Happy path — PENDING user gets a fresh token + mail + audit row
    // ------------------------------------------------------------------

    @Test
    void resend_for_pending_user_mints_new_token_and_sends_mail() {
        String email = uniqueEmail();
        accounts.register(new SignupRequest(email, "Pending Pete", "Pp1!Pass-very-strong".toCharArray()),
                RequestMeta.empty());
        String original = users.grabRawVerificationToken(email);
        int verifyMailsBefore = countMailsByTemplate(email, "verify-email");

        client.post().uri("/v1/auth/verify/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email))
                .exchange()
                .expectStatus().isAccepted();

        // A second verification mail landed in the outbox.
        assertThat(countMailsByTemplate(email, "verify-email"))
                .as("verify-email mail count")
                .isEqualTo(verifyMailsBefore + 1);

        // The new mail carries a different raw token.
        String latest = users.grabRawVerificationToken(email);
        assertThat(latest).isNotEqualTo(original);

        // And the new token actually activates the account.
        accounts.verifyEmail(latest, RequestMeta.empty());

        // Audit row recorded.
        assertThat(latestAuditKindsFor(email)).contains("VERIFICATION_RESEND");
    }

    // ------------------------------------------------------------------
    // Already-ACTIVE user → silent no-op (no mail, no audit)
    // ------------------------------------------------------------------

    @Test
    void resend_for_active_user_is_silent() {
        String email = uniqueEmail();
        users.registerAndVerify(email, "Aa1!Pass-very-strong", "Active Ann");
        int verifyMailsBefore = countMailsByTemplate(email, "verify-email");
        List<String> auditBefore = latestAuditKindsFor(email);

        client.post().uri("/v1/auth/verify/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email))
                .exchange()
                .expectStatus().isAccepted();

        assertThat(countMailsByTemplate(email, "verify-email"))
                .as("no new verification mail for already-active accounts")
                .isEqualTo(verifyMailsBefore);
        assertThat(latestAuditKindsFor(email))
                .as("no VERIFICATION_RESEND audit for active accounts")
                .isEqualTo(auditBefore);
    }

    // ------------------------------------------------------------------
    // Unknown email → silent no-op
    // ------------------------------------------------------------------

    @Test
    void resend_for_unknown_email_is_silent() {
        String email = uniqueEmail();
        int beforeAll = (int) outbox.count();

        client.post().uri("/v1/auth/verify/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email))
                .exchange()
                .expectStatus().isAccepted();

        // No new outbox row attributable to this email.
        assertThat(outbox.findAll().stream()
                .filter(o -> email.equalsIgnoreCase(o.getToAddress()))
                .count())
                .as("no mail for unknown email")
                .isZero();
        // (Coarse safety net — the rest of the suite is parallel-safe, so
        // we only assert about this email's rows.)
        assertThat((int) outbox.count()).isGreaterThanOrEqualTo(beforeAll);
    }

    // ------------------------------------------------------------------
    // Rate limit — per-hour cap exhausts and returns 429
    // ------------------------------------------------------------------

    @Test
    void resend_within_cooldown_returns_429_with_retry_after() {
        String email = uniqueEmail();
        accounts.register(new SignupRequest(email, "Capped Cal", "Cc1!Pass-very-strong".toCharArray()),
                RequestMeta.empty());

        // First call drains the cooldown-bucket (capacity 1, refills once
        // per PT10M in this test profile). The second call lands inside the
        // cooldown window and must be denied with Retry-After set.
        client.post().uri("/v1/auth/verify/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email))
                .exchange()
                .expectStatus().isAccepted();

        client.post().uri("/v1/auth/verify/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After");
    }

    // ------------------------------------------------------------------
    // Empty body → silent 202 (probes can't drain buckets with empty calls)
    // ------------------------------------------------------------------

    @Test
    void resend_with_blank_email_is_accepted_silently() {
        client.post().uri("/v1/auth/verify/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", ""))
                .exchange()
                .expectStatus().isAccepted();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String uniqueEmail() {
        return "resend-" + UUID.randomUUID() + "@test.io";
    }

    private int countMailsByTemplate(String email, String templateId) {
        return (int) outbox.findAll().stream()
                .filter(o -> templateId.equals(o.getTemplateId()))
                .filter(o -> email.equalsIgnoreCase(o.getToAddress()))
                .count();
    }

    private List<String> latestAuditKindsFor(String email) {
        return accounts.findByEmail(email)
                .map(u -> auditEvents.findAll().stream()
                        .filter(e -> u.id().value().equals(e.getUserId()))
                        .sorted(Comparator.comparing(AuditUserEventEntity::getOccurredAt))
                        .map(AuditUserEventEntity::getKind)
                        .toList())
                .orElse(List.of());
    }
}
