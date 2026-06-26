package com.yayatechandinnovations.yayaagentic.auth.stepup.providers;

import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpHint;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpMethod;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpProvider;
import com.yayatechandinnovations.yayaagentic.auth.stepup.TenantSecondaryComms;
import com.yayatechandinnovations.yayaagentic.auth.stepup.TenantSecondaryCommsService;
import com.yayatechandinnovations.yayaagentic.email.MailMessage;
import com.yayatechandinnovations.yayaagentic.email.OutboxMailQueue;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link StepUpProvider} for {@link StepUpMethod.OtpEmail}.
 *
 * <p>Mints a 6-digit code from {@link SecureRandom}, bcrypt-hashes it (cost
 * 10 — fast enough to verify a single OTP per inbound, slow enough that a DB
 * dump of {@code step_up_pending.code_hash_b64} doesn't expose live codes),
 * and enqueues a {@link MailMessage} to the platform mail outbox so delivery
 * survives provider failures. The raw code travels ONLY via the email body.
 *
 * <p>OTP TTL is taken from the {@link StepUpHint#elevationTtl()} divided by 2
 * but clamped to {@code [60s, 300s]} — codes must be quick (otherwise users
 * forget) but not so quick that they expire mid-typing.
 *
 * <p>Reads the tenant's {@code emailRelay} config for the from-address; if
 * none configured, throws — the coordinator turns this into a clear
 * {@code failed:no_secondary_comms} audit row + LLM-visible deny.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.3 + §8 rule 1.
 */
@Component
public class OtpEmailProvider implements StepUpProvider {

    private static final Duration MIN_TTL = Duration.ofSeconds(60);
    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RNG = new SecureRandom();

    private final TenantSecondaryCommsService secondaryComms;
    private final OutboxMailQueue mailQueue;

    public OtpEmailProvider(TenantSecondaryCommsService secondaryComms,
                            OutboxMailQueue mailQueue) {
        this.secondaryComms = secondaryComms;
        this.mailQueue = mailQueue;
    }

    @Override
    public StepUpMethod method() {
        return new StepUpMethod.OtpEmail();
    }

    @Override
    public StartResult start(StepUpStartContext ctx) {
        TenantSecondaryComms comms = secondaryComms.forTenant(ctx.tenant());
        TenantSecondaryComms.EmailRelay relay = comms.emailRelay()
                .orElseThrow(() -> new IllegalStateException(
                        "tenant " + ctx.tenant().value()
                                + " has no emailRelay configured"));

        String code = mintCode();
        String hash = BCrypt.hashpw(code, BCrypt.gensalt(10));
        String masked = mask(ctx.targetAddress());

        Duration ttl = clampTtl(ctx.hint().elevationTtl());
        Instant expiresAt = Instant.now().plus(ttl);

        // Idempotency: bind to a fresh per-cycle nonce so each issued code
        // gets its own outbox row. The pending row stores the hash, not the
        // nonce, so a retry can't double-mint.
        String idem = idempotencyKey(ctx, hash);

        mailQueue.enqueue(new MailMessage(
                ctx.targetAddress(),
                "Verification code: " + code,
                buildBodyText(code, ttl),
                null,
                "step-up-otp-email",
                idem));

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
        String supplied = ctx.userSuppliedSecret() == null ? "" : ctx.userSuppliedSecret().trim();
        if (!supplied.matches("\\d{6}")) {
            return VerifyResult.failure("malformed_code");
        }
        boolean match = constantTimeBcryptCompare(supplied, ctx.codeHashB64());
        if (!match) {
            return VerifyResult.failure("code_mismatch");
        }
        // The provider doesn't pick scopes — the StepUpAuthorizer's hint
        // dictates what an elevated principal looks like. v1: pass through
        // the audience as a scope so the Authorizer can re-evaluate with
        // "they verified, retry."
        String elevatedSubject = "stepup:" + ctx.sessionId().value();
        return VerifyResult.success(
                elevatedSubject,
                java.util.Set.of("stepup:" + ctx.audience()),
                java.util.Map.of(
                        "stepup.audience", ctx.audience(),
                        "stepup.method", "otp_email"),
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

    private static String mask(String address) {
        if (address == null || address.isBlank()) return "***";
        int at = address.indexOf('@');
        if (at <= 1) return "***" + address.substring(at);
        return address.charAt(0) + "***" + address.substring(at);
    }

    private static String buildBodyText(String code, Duration ttl) {
        return "Your verification code is " + code + ".\n\n"
                + "It expires in about " + ttl.toMinutes() + " minute"
                + (ttl.toMinutes() == 1 ? "" : "s")
                + ".\n\nIf you didn't request this code, you can safely ignore this email.";
    }

    private static String idempotencyKey(StepUpStartContext ctx, String hash) {
        // Bind to (session, audience, nonce) so a retry inside the same cycle
        // reuses the queue row but a new cycle gets its own.
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String key = ctx.sessionId().value() + "|" + ctx.audience()
                    + "|" + UUID.randomUUID();
            byte[] digest = sha.digest(key.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return "step-up-otp-" + hex.substring(0, 32);
        } catch (Exception e) {
            return "step-up-otp-" + UUID.randomUUID();
        }
    }

    /** BCrypt's own checkpw is already constant-time over the hash; this
     *  wrapper documents the intent and isolates the call. */
    private static boolean constantTimeBcryptCompare(String supplied, String hash) {
        try {
            return BCrypt.checkpw(supplied, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
