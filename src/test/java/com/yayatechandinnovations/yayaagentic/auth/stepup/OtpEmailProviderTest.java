package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.stepup.providers.OtpEmailProvider;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.email.MailMessage;
import com.yayatechandinnovations.yayaagentic.email.OutboxMailQueue;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpEmailProviderTest {

    private final StubSecondaryComms secondary = new StubSecondaryComms();
    private final StubMailQueue mailQueue = new StubMailQueue();
    private final OtpEmailProvider provider = new OtpEmailProvider(secondary, mailQueue);

    private final Ids.TenantId tenant = new Ids.TenantId("acme");
    private final Ids.SessionId session = new Ids.SessionId("sess-1");
    private final StepUpHint hint = new StepUpHint(
            new StepUpMethod.OtpEmail(),
            "tool:transfer_funds",
            Duration.ofMinutes(10),
            Map.of());

    @Test
    void method_is_otp_email() {
        assertThat(provider.method()).isInstanceOf(StepUpMethod.OtpEmail.class);
    }

    @Test
    void start_throws_when_tenant_has_no_email_relay_configured() {
        secondary.next = TenantSecondaryComms.empty();
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "whatsapp:+15551234567",
                "tool:transfer_funds", hint, "user@acme.example", Map.of());
        assertThatThrownBy(() -> provider.start(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("emailRelay");
    }

    @Test
    void start_mints_code_enqueues_email_and_returns_masked_target() {
        secondary.next = new TenantSecondaryComms(
                Optional.of(new TenantSecondaryComms.EmailRelay(
                        "noreply@acme.example", "platform-smtp")),
                Optional.empty());
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "whatsapp:+15551234567",
                "tool:transfer_funds", hint, "alice@acme.example", Map.of());

        StepUpProvider.StartResult result = provider.start(ctx);

        assertThat(result.codeHashB64()).startsWith("$2a$");
        assertThat(result.maskedTargetAddress()).isEqualTo("a***@acme.example");
        assertThat(result.userPromptHint()).contains("6-digit code");
        assertThat(result.userPromptHint()).contains("a***@acme.example");
        assertThat(mailQueue.enqueued).hasSize(1);
        MailMessage msg = mailQueue.enqueued.get(0);
        assertThat(msg.to()).isEqualTo("alice@acme.example");
        assertThat(msg.bodyText()).containsPattern("\\d{6}");
        assertThat(msg.templateId()).isEqualTo("step-up-otp-email");
        assertThat(msg.idempotencyKey()).startsWith("step-up-otp-");
    }

    @Test
    void verify_rejects_non_six_digit_input() {
        StepUpProvider.VerifyResult res = provider.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:x", hint,
                        org.springframework.security.crypto.bcrypt.BCrypt.hashpw("123456",
                                org.springframework.security.crypto.bcrypt.BCrypt.gensalt(4)),
                        "", "abc"));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("malformed_code");
    }

    @Test
    void verify_rejects_correct_length_but_wrong_code() {
        String correct = "123456";
        String hash = org.springframework.security.crypto.bcrypt.BCrypt.hashpw(correct,
                org.springframework.security.crypto.bcrypt.BCrypt.gensalt(4));
        StepUpProvider.VerifyResult res = provider.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:x", hint, hash, "", "654321"));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("code_mismatch");
    }

    @Test
    void verify_accepts_correct_code_and_returns_audience_scope() {
        String correct = "123456";
        String hash = org.springframework.security.crypto.bcrypt.BCrypt.hashpw(correct,
                org.springframework.security.crypto.bcrypt.BCrypt.gensalt(4));
        StepUpProvider.VerifyResult res = provider.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:transfer_funds", hint, hash, "", correct));
        assertThat(res.success()).isTrue();
        assertThat(res.addedScopes()).contains("stepup:tool:transfer_funds");
        assertThat(res.addedClaims()).containsEntry("stepup.method", "otp_email");
        assertThat(res.auditReason()).isEqualTo("code_verified");
    }

    /** Hand-rolled stub — Mockito's inline mock-maker can't subclass concrete
     *  classes on JDK 25 reliably. */
    private static class StubSecondaryComms extends TenantSecondaryCommsService {
        TenantSecondaryComms next = TenantSecondaryComms.empty();
        StubSecondaryComms() { super((TenantRepository) null, new ObjectMapper()); }
        @Override public TenantSecondaryComms forTenant(Ids.TenantId tenant) { return next; }
    }

    private static class StubMailQueue extends OutboxMailQueue {
        final List<MailMessage> enqueued = new ArrayList<>();
        StubMailQueue() { super(null); }
        @Override public void enqueue(MailMessage msg) { enqueued.add(msg); }
    }
}
