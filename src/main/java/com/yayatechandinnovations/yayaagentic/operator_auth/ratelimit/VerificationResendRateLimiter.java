package com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties.UserAccounts.Verification;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-axis token-bucket guard for {@code POST /v1/auth/verify/resend}:
 * <ul>
 *   <li>per-email — both the configured cooldown (1 request per
 *       {@code yaya.agentic.user-accounts.verification.resend-cooldown})
 *       AND the per-hour cap
 *       ({@code yaya.agentic.user-accounts.verification.resend-per-hour-cap}).
 *       Whichever bandwidth runs out first denies the call.</li>
 *   <li>per-IP — fixed 30 requests / hour, mirroring the per-IP shape of
 *       {@link LoginRateLimiter} but tightened to an hourly window because
 *       the resend flow is much rarer than login.</li>
 * </ul>
 *
 * <p>Storage is in-process — same single-instance caveat as
 * {@link LoginRateLimiter}.</p>
 */
@Component
public class VerificationResendRateLimiter {

    private static final Bandwidth IP_LIMIT = Bandwidth.builder()
            .capacity(30).refillIntervally(30, Duration.ofHours(1)).build();

    private final Bandwidth emailCooldown;
    private final Bandwidth emailHourly;

    private final ConcurrentHashMap<String, Bucket> emailBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    public VerificationResendRateLimiter(YayaAgenticProperties props) {
        Verification v = props.userAccounts() == null || props.userAccounts().verification() == null
                ? Verification.defaults() : props.userAccounts().verification();
        Duration cooldown = v.resendCooldown() == null ? Duration.ofSeconds(60) : v.resendCooldown();
        int perHourCap = v.resendPerHourCap() <= 0 ? 5 : v.resendPerHourCap();
        this.emailCooldown = Bandwidth.builder()
                .capacity(1).refillIntervally(1, cooldown).build();
        this.emailHourly = Bandwidth.builder()
                .capacity(perHourCap).refillIntervally(perHourCap, Duration.ofHours(1)).build();
    }

    public Outcome check(String email, String clientIp) {
        String emailKey = email == null || email.isBlank() ? "_anon_" : email.toLowerCase(Locale.ROOT);
        String ipKey = clientIp == null || clientIp.isBlank() ? "_anon_" : clientIp;

        Bucket emailBucket = emailBuckets.computeIfAbsent(emailKey, k ->
                Bucket.builder().addLimit(emailCooldown).addLimit(emailHourly).build());
        ConsumptionProbe emailProbe = emailBucket.tryConsumeAndReturnRemaining(1);
        if (!emailProbe.isConsumed()) {
            return new Outcome.Denied("email", nanosToSecondsCeil(emailProbe.getNanosToWaitForRefill()));
        }

        Bucket ipBucket = ipBuckets.computeIfAbsent(ipKey, k ->
                Bucket.builder().addLimit(IP_LIMIT).build());
        ConsumptionProbe ipProbe = ipBucket.tryConsumeAndReturnRemaining(1);
        if (!ipProbe.isConsumed()) {
            return new Outcome.Denied("ip", nanosToSecondsCeil(ipProbe.getNanosToWaitForRefill()));
        }

        return new Outcome.Allowed();
    }

    private static long nanosToSecondsCeil(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    /** Test-only escape hatch — same role as {@link LoginRateLimiter#resetAll()}. */
    public void resetAll() {
        emailBuckets.clear();
        ipBuckets.clear();
    }

    public sealed interface Outcome {
        record Allowed() implements Outcome {}
        record Denied(String axis, long retryAfterSeconds) implements Outcome {}
    }
}
