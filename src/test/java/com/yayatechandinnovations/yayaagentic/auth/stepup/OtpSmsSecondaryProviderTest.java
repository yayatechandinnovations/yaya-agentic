package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.stepup.providers.OtpSmsSecondaryProvider;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsMessage;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsTransport;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSendException;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpSmsSecondaryProviderTest {

    private final StubSecondary secondary = new StubSecondary();
    private final StubSmsTransport smsTransport = new StubSmsTransport();
    private final OtpSmsSecondaryProvider provider =
            new OtpSmsSecondaryProvider(secondary, smsTransport);

    private final Ids.TenantId tenant = new Ids.TenantId("acme");
    private final Ids.SessionId session = new Ids.SessionId("sess-1");
    private final StepUpHint hint = new StepUpHint(
            new StepUpMethod.OtpSmsSecondary(),
            "tool:transfer_funds",
            Duration.ofMinutes(10),
            Map.of());

    @Test
    void method_is_otp_sms_secondary() {
        assertThat(provider.method()).isInstanceOf(StepUpMethod.OtpSmsSecondary.class);
    }

    @Test
    void start_throws_when_tenant_has_no_sms_backup_configured() {
        secondary.next = TenantSecondaryComms.empty();
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "+15551234567", Map.of());
        assertThatThrownBy(() -> provider.start(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smsBackup");
    }

    @Test
    void start_throws_when_principal_lacks_secondary_phone() {
        secondary.next = new TenantSecondaryComms(
                Optional.empty(),
                Optional.of(new TenantSecondaryComms.SmsBackup(
                        "+15550100123", "dev-console")));
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "", Map.of());
        assertThatThrownBy(() -> provider.start(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phone_secondary");
    }

    @Test
    void start_mints_code_sends_sms_and_returns_masked_phone() {
        secondary.next = new TenantSecondaryComms(
                Optional.empty(),
                Optional.of(new TenantSecondaryComms.SmsBackup(
                        "+15550100123", "dev-console")));
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "+15551234567", Map.of());

        StepUpProvider.StartResult result = provider.start(ctx);

        assertThat(result.codeHashB64()).startsWith("$2a$");
        assertThat(result.maskedTargetAddress()).isEqualTo("+1***4567");
        assertThat(result.userPromptHint()).contains("6-digit code");
        assertThat(result.userPromptHint()).contains("+1***4567");
        assertThat(smsTransport.sent).hasSize(1);
        SmsMessage msg = smsTransport.sent.get(0);
        assertThat(msg.toPhone()).isEqualTo("+15551234567");
        assertThat(msg.fromPhone()).isEqualTo("+15550100123");
        assertThat(msg.bodyText()).containsPattern("\\d{6}");
        assertThat(msg.idempotencyKey()).startsWith("step-up-otp-sms-");
    }

    @Test
    void start_propagates_sms_send_failure_as_illegal_state() {
        secondary.next = new TenantSecondaryComms(
                Optional.empty(),
                Optional.of(new TenantSecondaryComms.SmsBackup(
                        "+15550100123", "dev-console")));
        smsTransport.willFail = true;
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "+15551234567", Map.of());
        assertThatThrownBy(() -> provider.start(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMS delivery failed");
    }

    @Test
    void verify_rejects_non_six_digit_input() {
        String hash = BCrypt.hashpw("123456", BCrypt.gensalt(4));
        StepUpProvider.VerifyResult res = provider.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:x", hint, hash, "", "abc"));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("malformed_code");
    }

    @Test
    void verify_accepts_correct_code_and_returns_audience_scope() {
        String correct = "123456";
        String hash = BCrypt.hashpw(correct, BCrypt.gensalt(4));
        StepUpProvider.VerifyResult res = provider.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:transfer_funds", hint,
                        hash, "", correct));
        assertThat(res.success()).isTrue();
        assertThat(res.addedScopes()).contains("stepup:tool:transfer_funds");
        assertThat(res.addedClaims()).containsEntry("stepup.method", "otp_sms_secondary");
        assertThat(res.auditReason()).isEqualTo("code_verified");
    }

    @Test
    void verify_rejects_wrong_code() {
        String hash = BCrypt.hashpw("123456", BCrypt.gensalt(4));
        StepUpProvider.VerifyResult res = provider.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:x", hint, hash, "", "654321"));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("code_mismatch");
    }

    /** Hand-rolled stubs — Mockito's inline mock-maker can't subclass
     *  concrete classes on JDK 25 reliably. */
    private static class StubSecondary extends TenantSecondaryCommsService {
        TenantSecondaryComms next = TenantSecondaryComms.empty();
        StubSecondary() { super((TenantRepository) null, new ObjectMapper()); }
        @Override public TenantSecondaryComms forTenant(Ids.TenantId tenant) { return next; }
    }

    private static class StubSmsTransport implements SmsTransport {
        final List<SmsMessage> sent = new ArrayList<>();
        boolean willFail = false;
        @Override public String name() { return "stub"; }
        @Override public void send(SmsMessage msg) throws SmsSendException {
            if (willFail) throw new SmsSendException("test failure", true);
            sent.add(msg);
        }
    }
}
