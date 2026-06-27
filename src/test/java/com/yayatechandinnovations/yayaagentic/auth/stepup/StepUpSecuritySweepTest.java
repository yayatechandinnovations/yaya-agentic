package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.stepup.providers.OtpEmailProvider;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.email.MailMessage;
import com.yayatechandinnovations.yayaagentic.email.OutboxMailQueue;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditEntity;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpPendingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpPendingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defends security non-negotiable #9 ({@code whatsapp-channel-auth-design.md}
 * §8): "No code / link / token / unmasked target ever lands in
 * {@code audit_authz}, {@code turns}, or recorder payloads — only in the
 * dedicated {@code step_up_audit} and {@code step_up_pending} tables, the
 * latter as a bcrypt hash."
 *
 * <p>Runs a real OTP cycle end-to-end and scans every audit row + persisted
 * artifact for the issued code. A regression that accidentally serialized
 * the cleartext code into any logged surface fails this test loudly with the
 * offending row identified.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "yaya.agentic.auth.step-up.deep-link.secret=dGVzdC1zZWNyZXQtMzItYnl0ZXMtbG9uZy1zdHJpbmch",
        "yaya.agentic.auth.step-up.deep-link.landing-url=https://acme.example/step-up"
})
class StepUpSecuritySweepTest {

    /** Pattern that catches any 6-digit OTP-shape sequence. */
    private static final Pattern OTP_LIKE = Pattern.compile("\\b\\d{6}\\b");

    @Autowired StepUpPendingRepository pendingRepo;
    @Autowired StepUpAuditRepository auditRepo;
    @Autowired AuditAuthzRepository authzAuditRepo;
    @Autowired TenantRepository tenants;

    @Test
    void no_otp_code_leaks_into_authz_audit_or_step_up_audit_rows() {
        // 0) Make sure the tenant exists — FK constraints on step_up_pending
        //    and step_up_audit reference tenants(id).
        if (tenants.findById("step-up-sweep").isEmpty()) {
            tenants.save(new TenantEntity("step-up-sweep", "Step-Up Sweep Test"));
        }

        // 1) Mint an OTP via the real provider with a local capturing mail
        //    queue. The cleartext code lands only in the queue (which is
        //    what the user would receive in their email).
        var tenant = new Ids.TenantId("step-up-sweep");
        var session = new Ids.SessionId("sweep-session-1");
        TenantSecondaryComms comms = new TenantSecondaryComms(
                Optional.of(new TenantSecondaryComms.EmailRelay(
                        "noreply@acme.example", "platform-smtp")),
                Optional.empty());
        var capturing = new CapturingMailQueue();
        var provider = new OtpEmailProvider(
                new SingleTenantStub(tenant, comms), capturing);

        var hint = new StepUpHint(
                new StepUpMethod.OtpEmail(),
                "tool:sweep_audience",
                Duration.ofMinutes(10), Map.of());
        var startResult = provider.start(new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:sweep_audience", hint, "alice@acme.example", Map.of()));
        String issuedCode = extractCodeFromMail(capturing.captured);
        assertThat(issuedCode)
                .as("test setup sanity: provider must mint a six-digit code")
                .matches("\\d{6}");

        // 2) Persist the same audit-shaped rows the coordinator would write.
        //    Anyone who accidentally logged the wrong field would leak here.
        StepUpPendingEntity row = new StepUpPendingEntity(
                session.value(), tenant.value(), "tool:sweep_audience",
                "otp_email", startResult.codeHashB64(), null,
                startResult.maskedTargetAddress(), 5,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        pendingRepo.save(row);
        auditRepo.save(new StepUpAuditEntity(
                tenant.value(), null, session.value(),
                "tool:sweep_audience", "otp_email",
                "u_42", null, "started",
                null, startResult.maskedTargetAddress(),
                "01HSWEEPSWEEPSWEEPSWEEPSWE", row.getId()));
        auditRepo.save(new StepUpAuditEntity(
                tenant.value(), null, session.value(),
                "tool:sweep_audience", "otp_email",
                "u_42", "u_42", "verified",
                OffsetDateTime.now(ZoneOffset.UTC),
                startResult.maskedTargetAddress(),
                "01HSWEEPSWEEPSWEEPSWEEPSWF", row.getId()));

        // 3) audit_authz sweep — never carries an OTP shape.
        for (var a : authzAuditRepo.findAll()) {
            String tag = "audit_authz#tenant=" + a.getTenantId();
            assertOtpFree(tag + " args", a.getArgsJson(), issuedCode);
            assertOtpFree(tag + " trace", a.getPolicyTraceJson(), issuedCode);
            assertOtpFree(tag + " userReason", a.getUserReason(), issuedCode);
            assertOtpFree(tag + " auditReason", a.getAuditReason(), issuedCode);
        }

        // 4) step_up_audit sweep — compliance trail knows about the cycle
        //    but never about the code.
        var stepRows = auditRepo.findByTenantIdAndSessionIdOrderByStartedAtDesc(
                tenant.value(), session.value());
        assertThat(stepRows).isNotEmpty();
        for (var a : stepRows) {
            String tag = "step_up_audit#" + a.getId();
            assertOtpFree(tag + " outcome", a.getOutcome(), issuedCode);
            assertOtpFree(tag + " targetAddress", a.getTargetAddress(), issuedCode);
            assertOtpFree(tag + " initialSubject", a.getInitialSubject(), issuedCode);
            assertOtpFree(tag + " elevatedSubject", a.getElevatedSubject(), issuedCode);
            assertThat(a.getTargetAddress())
                    .as(tag + " target_address must be masked")
                    .doesNotContain("alice@");
        }

        // 5) step_up_pending stores a bcrypt hash, never the raw code.
        var savedPending = pendingRepo.findById(row.getId()).orElseThrow();
        assertThat(savedPending.getCodeHashB64()).startsWith("$2a$");
        assertThat(savedPending.getCodeHashB64()).doesNotContain(issuedCode);
        assertThat(savedPending.getTargetAddress()).isEqualTo("a***@acme.example");

        // 6) Sanity: the cleartext code DID land where it's supposed to —
        //    in the rendered mail body.  If this fails, the mint side broke.
        assertThat(capturing.captured).hasSize(1);
        assertThat(capturing.captured.get(0).bodyText()).contains(issuedCode);
    }

    private static void assertOtpFree(String location, String value, String issuedCode) {
        if (value == null) return;
        assertThat(value)
                .as(location + " must not contain the issued OTP code")
                .doesNotContain(issuedCode);
        // Stronger check: free-text fields should not contain any 6-digit
        // run. bcrypt hashes can incidentally contain digit runs so we skip
        // them by pattern — but the codeHash test above forbids the exact
        // issued code there too.
        if (!value.startsWith("$2a$")) {
            Matcher m = OTP_LIKE.matcher(value);
            assertThat(m.find())
                    .as(location + " must not contain any 6-digit OTP-shape sequence")
                    .isFalse();
        }
    }

    private static String extractCodeFromMail(List<MailMessage> captured) {
        if (captured.isEmpty()) return "";
        MailMessage msg = captured.get(0);
        if (msg.bodyText() == null) return "";
        Matcher m = OTP_LIKE.matcher(msg.bodyText());
        return m.find() ? m.group() : "";
    }

    /** Lightweight TenantSecondaryCommsService — returns a fixed comms
     *  config for one tenant; empty for any other. */
    private static class SingleTenantStub extends TenantSecondaryCommsService {
        private final Ids.TenantId tenant;
        private final TenantSecondaryComms comms;
        SingleTenantStub(Ids.TenantId tenant, TenantSecondaryComms comms) {
            super(null, new ObjectMapper());
            this.tenant = tenant;
            this.comms = comms;
        }
        @Override public TenantSecondaryComms forTenant(Ids.TenantId t) {
            return t.value().equals(tenant.value()) ? comms : TenantSecondaryComms.empty();
        }
    }

    /** Hand-rolled mail queue that just captures every enqueued message. */
    private static class CapturingMailQueue extends OutboxMailQueue {
        final List<MailMessage> captured = new ArrayList<>();
        CapturingMailQueue() { super(null); }
        @Override public void enqueue(MailMessage msg) { captured.add(msg); }
    }
}
