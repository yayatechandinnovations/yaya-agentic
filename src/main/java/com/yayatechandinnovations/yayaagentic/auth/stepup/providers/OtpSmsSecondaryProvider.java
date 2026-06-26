package com.yayatechandinnovations.yayaagentic.auth.stepup.providers;

import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpHint;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpMethod;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpProvider;
import com.yayatechandinnovations.yayaagentic.auth.stepup.TenantSecondaryComms;
import com.yayatechandinnovations.yayaagentic.auth.stepup.TenantSecondaryCommsService;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsMessage;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSendException;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link StepUpProvider} for {@link StepUpMethod.OtpSmsSecondary}.
 *
 * <p>Twin of {@link OtpEmailProvider}, swapping email delivery for SMS
 * delivery via the tenant's configured {@code SmsBackup.fromNumber}. The
 * destination phone comes from the principal's {@code phone_secondary} claim
 * — NEVER from the WhatsApp sender phone (rule 1: secondary channel ≠
 * channel under elevation). If the principal has no {@code phone_secondary},
 * the provider's start throws and the coordinator surfaces a
 * {@code failed:start:...} audit row.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.3 + §8 rule 1.
 */
@Component
public class OtpSmsSecondaryProvider implements StepUpProvider {

    private static final Logger log = LoggerFactory.getLogger(OtpSmsSecondaryProvider.class);

    private static final Duration MIN_TTL = Duration.ofSeconds(60);
    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RNG = new SecureRandom();

    private final TenantSecondaryCommsService secondaryComms;
    private final SmsSender smsSender;

    public OtpSmsSecondaryProvider(TenantSecondaryCommsService secondaryComms,
                                   SmsSender smsSender) {
        this.secondaryComms = secondaryComms;
        this.smsSender = smsSender;
    }

    @Override
    public StepUpMethod method() {
        return new StepUpMethod.OtpSmsSecondary();
    }

    @Override
    public StartResult start(StepUpStartContext ctx) {
        TenantSecondaryComms comms = secondaryComms.forTenant(ctx.tenant());
        TenantSecondaryComms.SmsBackup backup = comms.smsBackup()
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + ctx.tenant().value()
                                + " has no smsBackup configured"));
        if (ctx.targetAddress() == null || ctx.targetAddress().isBlank()) {
            throw new IllegalStateException(
                    "no secondary phone available — principal needs a "
                            + "phone_secondary claim distinct from the WhatsApp sender");
        }

        String code = mintCode();
        String hash = BCrypt.hashpw(code, BCrypt.gensalt(10));
        String masked = maskE164(ctx.targetAddress());

        Duration ttl = clampTtl(ctx.hint().elevationTtl());
        Instant expiresAt = Instant.now().plus(ttl);

        try {
            smsSender.send(new SmsMessage(
                    ctx.targetAddress(),
                    backup.fromNumber(),
                    "Your verification code is " + code
                            + ". It expires in about " + ttl.toMinutes() + " minute"
                            + (ttl.toMinutes() == 1 ? "" : "s") + ".",
                    idempotencyKey(ctx)));
        } catch (SmsSendException e) {
            log.warn("step-up SMS send failed for tenant {}: {}",
                    ctx.tenant().value(), e.getMessage());
            throw new IllegalStateException("SMS delivery failed: " + e.getMessage(), e);
        }

        return new StartResult(
                hash,
                "",
                masked,
                "I've sent a 6-digit code to " + masked + ". Send it back to verify.",
                Optional.empty(),
                expiresAt);
    }

    @Override
    public VerifyResult verify(StepUpVerifyContext ctx) {
        if (ctx.codeHashB64() == null || ctx.codeHashB64().isBlank()) {
            return VerifyResult.failure("no_code_on_pending_row");
        }
        String supplied = ctx.userSuppliedSecret() == null
                ? "" : ctx.userSuppliedSecret().trim();
        if (!supplied.matches("\\d{6}")) {
            return VerifyResult.failure("malformed_code");
        }
        boolean match;
        try {
            match = BCrypt.checkpw(supplied, ctx.codeHashB64());
        } catch (IllegalArgumentException e) {
            match = false;
        }
        if (!match) return VerifyResult.failure("code_mismatch");

        return VerifyResult.success(
                "stepup:" + ctx.sessionId().value(),
                java.util.Set.of("stepup:" + ctx.audience()),
                Map.of(
                        "stepup.audience", ctx.audience(),
                        "stepup.method", "otp_sms_secondary"),
                "code_verified");
    }

    private static String mintCode() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    private static Duration clampTtl(Duration desired) {
        Duration half = desired == null ? MAX_TTL : desired.dividedBy(2);
        if (half.compareTo(MIN_TTL) < 0) return MIN_TTL;
        if (half.compareTo(MAX_TTL) > 0) return MAX_TTL;
        return half;
    }

    /** Mask an E.164 phone to {@code +1***1234}; preserves country and last 4. */
    private static String maskE164(String phone) {
        if (phone == null || phone.length() < 5) return "***";
        // First 2 chars (e.g. "+1") + "***" + last 4
        return phone.substring(0, 2) + "***" + phone.substring(phone.length() - 4);
    }

    private static String idempotencyKey(StepUpStartContext ctx) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String key = ctx.sessionId().value() + "|" + ctx.audience()
                    + "|" + UUID.randomUUID();
            byte[] digest = sha.digest(key.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return "step-up-otp-sms-" + hex.substring(0, 32);
        } catch (Exception e) {
            return "step-up-otp-sms-" + UUID.randomUUID();
        }
    }
}
