package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionElevationEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionElevationRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditEntity;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpPendingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpPendingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the step-up cycle: mint pending row, ask the provider to ship,
 * intercept the user's response, verify, write elevation, return a retry
 * signal. Engine calls {@link #handleDeny(HandleContext)}; the inbound worker
 * calls {@link #verifyOtp(VerifyContext)} before engine invocation.
 *
 * <p>Cool-off: 3 failures in 10 minutes block any new cycle on the session.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.2 + §6.4.
 */
@Service
public class StepUpCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StepUpCoordinator.class);

    /** §8 rule 8 — cool-off after this many failed cycles. */
    private static final int    COOL_OFF_FAIL_LIMIT = 3;
    private static final Duration COOL_OFF_WINDOW   = Duration.ofMinutes(10);

    private final StepUpPendingRepository pending;
    private final StepUpAuditRepository audit;
    private final ChannelSessionElevationRepository elevations;
    private final ObjectMapper json;
    private final Map<Class<? extends StepUpMethod>, StepUpProvider> providersByMethod;

    public StepUpCoordinator(StepUpPendingRepository pending,
                             StepUpAuditRepository audit,
                             ChannelSessionElevationRepository elevations,
                             ObjectMapper json,
                             List<StepUpProvider> providers) {
        this.pending = pending;
        this.audit = audit;
        this.elevations = elevations;
        this.json = json;
        Map<Class<? extends StepUpMethod>, StepUpProvider> byMethod = new HashMap<>();
        for (StepUpProvider p : providers) {
            byMethod.put(p.method().getClass(), p);
        }
        this.providersByMethod = Map.copyOf(byMethod);
        log.info("StepUpCoordinator initialized with {} provider(s): {}",
                providersByMethod.size(),
                providersByMethod.keySet().stream()
                        .map(Class::getSimpleName).toList());
    }

    /**
     * Engine call site: on {@link AuthzDecision.DenyOfferStepUp}, mount the
     * provider's start flow. Returns the {@link HandleOutcome} the engine
     * uses to emit a single Token message and end the turn — the LLM does
     * NOT get a continuation round; the next inbound is the OTP.
     */
    @Transactional
    public HandleOutcome handleDeny(HandleContext ctx) {
        if (isInCoolOff(ctx.sessionId())) {
            writeAudit(ctx, "failed:cool_off", null, null);
            return HandleOutcome.coolOff(
                    "Too many verification attempts. Please try again later.");
        }
        StepUpProvider provider = providersByMethod.get(ctx.hint().method().getClass());
        if (provider == null) {
            writeAudit(ctx, "failed:no_provider", null, null);
            log.warn("step-up: no provider registered for method {}",
                    ctx.hint().method().getClass().getSimpleName());
            return HandleOutcome.unrecoverable(ctx.decisionUserSafeReason());
        }

        // Close any prior open pending for the same audience — only one
        // active cycle per (session, audience). The partial unique index
        // would otherwise abort the insert.
        pending.findOpenForAudience(ctx.sessionId().value(), ctx.hint().audience())
                .ifPresent(p -> abortPending(p, "superseded"));

        StepUpProvider.StartResult start;
        try {
            start = provider.start(new StepUpProvider.StepUpStartContext(
                    ctx.tenant(),
                    ctx.sessionId(),
                    ctx.senderId(),
                    ctx.initialSubject(),
                    ctx.hint().audience(),
                    ctx.hint(),
                    ctx.targetAddress(),
                    Map.of()));
        } catch (IllegalStateException e) {
            // Missing tenant secondary comms or similar — operator misconfig
            // surfaces as a normal denial but is loud in audit.
            writeAudit(ctx, "failed:start:" + truncate(e.getMessage(), 32), null, null);
            log.warn("step-up: provider start failed for tenant {}: {}",
                    ctx.tenant().value(), e.getMessage());
            return HandleOutcome.unrecoverable(
                    "Verification is temporarily unavailable. Please try again later.");
        }

        StepUpPendingEntity row = new StepUpPendingEntity(
                ctx.sessionId().value(),
                ctx.tenant().value(),
                ctx.hint().audience(),
                methodName(ctx.hint().method()),
                start.codeHashB64(),
                start.deepLinkJti(),
                start.maskedTargetAddress(),
                5,
                OffsetDateTime.ofInstant(start.expiresAt(), ZoneOffset.UTC));
        StepUpPendingEntity saved = pending.save(row);

        Long auditId = writeAudit(ctx, "started", null, saved.getId());

        return HandleOutcome.pending(
                start.userPromptHint(),
                start.maskedTargetAddress(),
                saved.getId(),
                auditId);
    }

    /**
     * Worker call site: on every inbound, if any open OTP-shaped pending row
     * exists for the session, treat the inbound body as the user's OTP code.
     */
    @Transactional
    public VerifyOutcome verifyOtp(VerifyContext ctx) {
        List<StepUpPendingEntity> open = pending.findAllOpenForSession(ctx.sessionId().value());
        for (StepUpPendingEntity row : open) {
            if (isExpired(row)) {
                row.setCompletion("expired");
                row.setCompletedAt(now());
                pending.save(row);
                writeAuditFromPending(ctx.tenant(), ctx.sessionId(), row, "expired",
                        ctx.initialSubject(), null);
                continue;
            }
            StepUpHint reconstructedHint = new StepUpHint(
                    methodFromName(row.getMethod()),
                    row.getAudience(),
                    Duration.ofHours(1),     // overridden by min(elevation.expires_at, binding.idleTimeout)
                    Map.of());
            StepUpProvider provider = providersByMethod.get(reconstructedHint.method().getClass());
            if (provider == null) {
                log.warn("step-up verify: no provider for method {}", row.getMethod());
                continue;
            }

            StepUpProvider.VerifyResult res = provider.verify(
                    new StepUpProvider.StepUpVerifyContext(
                            ctx.tenant(),
                            ctx.sessionId(),
                            row.getAudience(),
                            reconstructedHint,
                            row.getCodeHashB64(),
                            row.getDeepLinkJti(),
                            ctx.userSuppliedSecret()));

            row.setAttempts(row.getAttempts() + 1);
            if (res.success()) {
                row.setCompletion("verified");
                row.setCompletedAt(now());
                row.setElevatedSubject(res.elevatedSubject());
                row.setAddedScopes(res.addedScopes().toArray(new String[0]));
                row.setAddedClaimsJson(writeJson(res.addedClaims()));
                pending.save(row);

                writeElevation(row, ctx, res);
                Long auditId = writeAuditFromPending(ctx.tenant(), ctx.sessionId(),
                        row, "verified", ctx.initialSubject(), res.elevatedSubject());
                return VerifyOutcome.verified(row.getAudience(),
                        res.elevatedSubject(),
                        res.addedScopes(),
                        res.addedClaims(),
                        auditId);
            } else if (row.getAttempts() >= row.getMaxAttempts()) {
                row.setCompletion("exhausted");
                row.setCompletedAt(now());
                pending.save(row);
                writeAuditFromPending(ctx.tenant(), ctx.sessionId(), row,
                        "failed:exhausted", ctx.initialSubject(), null);
                return VerifyOutcome.exhausted(row.getAudience());
            } else {
                pending.save(row);
                writeAuditFromPending(ctx.tenant(), ctx.sessionId(), row,
                        "failed:" + res.auditReason(), ctx.initialSubject(), null);
                int remaining = row.getMaxAttempts() - row.getAttempts();
                return VerifyOutcome.wrongCode(row.getAudience(), remaining);
            }
        }
        return VerifyOutcome.noPending();
    }

    /**
     * Callback endpoint call site: verify a JWT delivered by the tenant's web
     * app after the user completed OIDC there. Found by JTI, not session id —
     * the URL the user clicked carries the JTI that names the pending row.
     *
     * <p>Returns the same {@link VerifyOutcome} shape as the OTP path so the
     * callback controller doesn't need to switch on which verification path
     * succeeded.
     */
    @Transactional
    public VerifyOutcome verifyDeepLinkCallback(String jwtToken) {
        StepUpProvider provider = providersByMethod.get(StepUpMethod.DeepLink.class);
        if (provider == null) {
            return VerifyOutcome.noPending();
        }
        StepUpPendingEntity row = pending.findOpenByDeepLinkJti(extractJti(jwtToken))
                .orElse(null);
        if (row == null) return VerifyOutcome.noPending();
        if (isExpired(row)) {
            row.setCompletion("expired");
            row.setCompletedAt(now());
            pending.save(row);
            writeAuditFromPending(new Ids.TenantId(row.getTenantId()),
                    new Ids.SessionId(row.getSessionId()), row, "expired",
                    "anonymous", null);
            return VerifyOutcome.noPending();
        }

        StepUpHint hint = new StepUpHint(
                new StepUpMethod.DeepLink(),
                row.getAudience(),
                Duration.ofHours(1),
                Map.of());
        StepUpProvider.VerifyResult res = provider.verify(
                new StepUpProvider.StepUpVerifyContext(
                        new Ids.TenantId(row.getTenantId()),
                        new Ids.SessionId(row.getSessionId()),
                        row.getAudience(),
                        hint,
                        null,                          // no OTP hash for DeepLink
                        row.getDeepLinkJti(),
                        jwtToken));

        row.setAttempts(row.getAttempts() + 1);
        if (res.success()) {
            row.setCompletion("verified");
            row.setCompletedAt(now());
            row.setElevatedSubject(res.elevatedSubject());
            row.setAddedScopes(res.addedScopes().toArray(new String[0]));
            row.setAddedClaimsJson(writeJson(res.addedClaims()));
            pending.save(row);
            writeElevation(row,
                    new VerifyContext(
                            new Ids.TenantId(row.getTenantId()),
                            new Ids.SessionId(row.getSessionId()),
                            "anonymous", jwtToken, Duration.ofHours(1)),
                    res);
            Long auditId = writeAuditFromPending(
                    new Ids.TenantId(row.getTenantId()),
                    new Ids.SessionId(row.getSessionId()), row,
                    "verified", "anonymous", res.elevatedSubject());
            return VerifyOutcome.verified(row.getAudience(),
                    res.elevatedSubject(), res.addedScopes(), res.addedClaims(),
                    auditId);
        }
        pending.save(row);
        writeAuditFromPending(
                new Ids.TenantId(row.getTenantId()),
                new Ids.SessionId(row.getSessionId()), row,
                "failed:" + res.auditReason(), "anonymous", null);
        return VerifyOutcome.wrongCode(row.getAudience(),
                Math.max(0, row.getMaxAttempts() - row.getAttempts()));
    }

    /** Best-effort JTI extraction from a signed JWT — used for the pending-row
     *  lookup. The provider re-verifies the full signature; this just gives us
     *  the lookup key without forcing JWT parsing into the coordinator. */
    private static String extractJti(String jwt) {
        if (jwt == null) return "";
        try {
            return com.nimbusds.jwt.SignedJWT.parse(jwt).getJWTClaimsSet().getJWTID();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Playground-only: stamp a {@link ChannelSessionElevationEntity} directly
     * without running an OTP / deep-link cycle. Writes a paired
     * {@code step_up_audit} row so the trail still looks like a normal
     * elevation. Caller (the playground worker path) is responsible for
     * gating on operator role.
     *
     * <p>See {@code whatsapp-channel-auth-design.md} §7.1.
     */
    @Transactional
    public ChannelSessionElevationEntity recordPlaygroundPreElevation(
            Ids.TenantId tenant,
            Ids.SessionId sessionId,
            String elevatedSubject,
            String audience,
            Set<String> addedScopes,
            Map<String, Object> addedClaims,
            Duration ttl) {
        Duration safeTtl = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Duration.ofHours(1) : ttl;
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(safeTtl);

        ChannelSessionElevationEntity row = elevations.findBySessionId(sessionId.value())
                .map(existing -> {
                    Set<String> merged = new java.util.HashSet<>(java.util.Arrays.asList(
                            existing.getAddedScopes() == null
                                    ? new String[0] : existing.getAddedScopes()));
                    merged.addAll(addedScopes == null ? Set.of() : addedScopes);
                    existing.setAddedScopes(merged.toArray(new String[0]));
                    existing.setExpiresAt(expiresAt);
                    return existing;
                })
                .orElseGet(() -> new ChannelSessionElevationEntity(
                        sessionId.value(),
                        tenant.value(),
                        elevatedSubject,
                        addedScopes == null ? new String[0]
                                : addedScopes.toArray(new String[0]),
                        writeJson(addedClaims),
                        audience,
                        "playground_pre_elevation",
                        expiresAt,
                        null));
        elevations.save(row);

        StepUpAuditEntity audit_row = new StepUpAuditEntity(
                tenant.value(), null, sessionId.value(),
                audience, "playground_pre_elevation",
                "playground", elevatedSubject,
                "verified:playground_pre_elevated",
                now(), null, newCorrelationId(), null);
        audit.save(audit_row);

        return row;
    }

    /** Worker call site: is there an active elevation for this session? Used
     *  to merge scopes/claims into the Principal the authenticator produces. */
    public Optional<ChannelSessionElevationEntity> activeElevation(Ids.SessionId sessionId) {
        return elevations.findBySessionId(sessionId.value())
                .filter(e -> e.getExpiresAt().toInstant().isAfter(Instant.now()));
    }

    /** Convenience predicate for the worker — only run verifyOtp when at
     *  least one OTP-shaped pending exists for the session. */
    public boolean hasOpenOtpPending(Ids.SessionId sessionId) {
        return pending.findAllOpenForSession(sessionId.value()).stream()
                .anyMatch(p -> isOtpMethod(p.getMethod()));
    }

    private boolean isInCoolOff(Ids.SessionId sessionId) {
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(COOL_OFF_WINDOW);
        long fails = audit.countFailedSince(sessionId.value(), since);
        return fails >= COOL_OFF_FAIL_LIMIT;
    }

    private void abortPending(StepUpPendingEntity row, String reason) {
        row.setCompletion("aborted");
        row.setCompletedAt(now());
        pending.save(row);
        StepUpAuditEntity rowAudit = new StepUpAuditEntity(
                row.getTenantId(), null, row.getSessionId(),
                row.getAudience(), row.getMethod(),
                "anonymous", null, "aborted:" + reason,
                now(), row.getTargetAddress(),
                newCorrelationId(), row.getId());
        audit.save(rowAudit);
    }

    private void writeElevation(StepUpPendingEntity row,
                                VerifyContext ctx,
                                StepUpProvider.VerifyResult res) {
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plus(ctx.elevationTtl() == null ? Duration.ofHours(1) : ctx.elevationTtl());
        ChannelSessionElevationEntity row2 = elevations
                .findBySessionId(ctx.sessionId().value())
                .map(existing -> {
                    Set<String> merged = new java.util.HashSet<>(java.util.Arrays.asList(
                            existing.getAddedScopes() == null
                                    ? new String[0] : existing.getAddedScopes()));
                    merged.addAll(res.addedScopes());
                    existing.setAddedScopes(merged.toArray(new String[0]));
                    existing.setExpiresAt(expiresAt);
                    return existing;
                })
                .orElseGet(() -> new ChannelSessionElevationEntity(
                        ctx.sessionId().value(),
                        ctx.tenant().value(),
                        res.elevatedSubject(),
                        res.addedScopes().toArray(new String[0]),
                        writeJson(res.addedClaims()),
                        row.getAudience(),
                        row.getMethod(),
                        expiresAt,
                        row.getId()));
        elevations.save(row2);
    }

    private Long writeAudit(HandleContext ctx, String outcome,
                            String elevatedSubject, Long pendingId) {
        StepUpAuditEntity row = new StepUpAuditEntity(
                ctx.tenant().value(),
                ctx.senderId(),
                ctx.sessionId().value(),
                ctx.hint().audience(),
                methodName(ctx.hint().method()),
                ctx.initialSubject(),
                elevatedSubject,
                outcome,
                "started".equals(outcome) ? null : now(),
                ctx.maskedTargetAddress(),
                newCorrelationId(),
                pendingId);
        return audit.save(row).getId();
    }

    private Long writeAuditFromPending(Ids.TenantId tenant, Ids.SessionId sessionId,
                                       StepUpPendingEntity p, String outcome,
                                       String initialSubject, String elevatedSubject) {
        StepUpAuditEntity row = new StepUpAuditEntity(
                tenant.value(), null, sessionId.value(),
                p.getAudience(), p.getMethod(),
                initialSubject, elevatedSubject, outcome,
                now(), p.getTargetAddress(),
                newCorrelationId(), p.getId());
        return audit.save(row).getId();
    }

    private String writeJson(Map<String, Object> claims) {
        try {
            return json.writeValueAsString(claims == null ? Map.of() : claims);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode elevation claims", e);
        }
    }

    private static boolean isExpired(StepUpPendingEntity row) {
        return row.getExpiresAt().toInstant().isBefore(Instant.now());
    }

    private static String methodName(StepUpMethod m) {
        return switch (m) {
            case StepUpMethod.OtpEmail e        -> "otp_email";
            case StepUpMethod.OtpSmsSecondary s -> "otp_sms_secondary";
            case StepUpMethod.DeepLink d        -> "deep_link";
            case StepUpMethod.Custom c          -> "custom:" + c.providerId();
        };
    }

    private static StepUpMethod methodFromName(String name) {
        if ("otp_email".equals(name)) return new StepUpMethod.OtpEmail();
        if ("otp_sms_secondary".equals(name)) return new StepUpMethod.OtpSmsSecondary();
        if ("deep_link".equals(name)) return new StepUpMethod.DeepLink();
        if (name != null && name.startsWith("custom:")) {
            return new StepUpMethod.Custom(name.substring("custom:".length()));
        }
        throw new IllegalStateException("unknown method name on pending row: " + name);
    }

    private static boolean isOtpMethod(String name) {
        return "otp_email".equals(name) || "otp_sms_secondary".equals(name);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String newCorrelationId() {
        // 26-char monotonic-ish id — ULID would be ideal; UUID stripped works
        // for the audit-row uniqueness needs we have today.
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    }

    // ---- in/out shapes ----------------------------------------------------

    public record HandleContext(
            Ids.TenantId  tenant,
            Ids.SessionId sessionId,
            String        senderId,
            String        initialSubject,
            String        targetAddress,
            String        maskedTargetAddress,
            String        decisionUserSafeReason,
            StepUpHint    hint
    ) {}

    public sealed interface HandleOutcome {
        record Pending(String userPromptHint, String maskedTargetAddress,
                       Long pendingId, Long auditId) implements HandleOutcome {}
        record CoolOff(String userSafeReason) implements HandleOutcome {}
        record Unrecoverable(String userSafeReason) implements HandleOutcome {}

        static HandleOutcome pending(String userPromptHint, String masked,
                                     Long pendingId, Long auditId) {
            return new Pending(userPromptHint, masked, pendingId, auditId);
        }
        static HandleOutcome coolOff(String userSafeReason) {
            return new CoolOff(userSafeReason);
        }
        static HandleOutcome unrecoverable(String userSafeReason) {
            return new Unrecoverable(userSafeReason);
        }
    }

    public record VerifyContext(
            Ids.TenantId  tenant,
            Ids.SessionId sessionId,
            String        initialSubject,
            String        userSuppliedSecret,
            Duration      elevationTtl
    ) {}

    public sealed interface VerifyOutcome {
        record NoPending() implements VerifyOutcome {}
        record Verified(String audience, String elevatedSubject,
                        Set<String> scopes, Map<String, Object> claims,
                        Long auditId) implements VerifyOutcome {}
        record WrongCode(String audience, int attemptsRemaining) implements VerifyOutcome {}
        record Exhausted(String audience) implements VerifyOutcome {}

        static VerifyOutcome noPending() { return new NoPending(); }
        static VerifyOutcome verified(String audience, String subject,
                                      Set<String> scopes, Map<String, Object> claims,
                                      Long auditId) {
            return new Verified(audience, subject, scopes, claims, auditId);
        }
        static VerifyOutcome wrongCode(String audience, int remaining) {
            return new WrongCode(audience, remaining);
        }
        static VerifyOutcome exhausted(String audience) {
            return new Exhausted(audience);
        }
    }
}
