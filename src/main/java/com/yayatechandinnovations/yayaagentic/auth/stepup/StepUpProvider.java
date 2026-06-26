package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies that the end user is who they claim to be — by sending an OTP, by
 * minting a deep link, or by any tenant-implemented mechanism.
 *
 * <p>The {@link com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator}
 * is the only caller; it persists the pending row, audits the cycle, and on
 * verify success writes the elevation. Providers are stateless — all the
 * persistent state of an in-flight verification lives in {@code step_up_pending}.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.2 + §6.3.
 */
public interface StepUpProvider {

    /** What this provider handles. The coordinator picks a provider by
     *  matching {@link StepUpMethod} via instanceof / pattern. */
    StepUpMethod method();

    /**
     * Mint the verification artifact and ship it. The provider returns a
     * {@link StartResult} the coordinator persists into {@code step_up_pending}
     * along with the issued {@link Ids.SessionId}.
     *
     * <p>OTP providers stamp a {@code codeHashB64} (bcrypt-of-the-code); the
     * code itself NEVER leaves the {@code start} method — it travels only via
     * the secondary-channel delivery.
     */
    StartResult start(StepUpStartContext ctx);

    /**
     * Verify the artifact supplied by the user (an OTP code, a JWT from a
     * deep-link callback, …). Returns success / failure plus, on success, the
     * elevated {@link Principal} the coordinator merges into the session.
     *
     * <p>The provider does NOT read {@code step_up_pending} itself — the
     * coordinator passes the matched row via the context. Implementations
     * MUST do constant-time comparisons; they MUST NOT mutate the pending row
     * (the coordinator owns the {@code attempts} counter and the
     * {@code completion} transition).
     */
    VerifyResult verify(StepUpVerifyContext ctx);

    // ---- contexts + results -----------------------------------------------

    record StepUpStartContext(
            Ids.TenantId        tenant,
            Ids.SessionId       sessionId,
            String              senderId,            // null for non-channel callers
            String              initialSubject,
            String              audience,
            StepUpHint          hint,
            String              targetAddress,       // unmasked email/phone for delivery
            Map<String, Object> tenantSecondaryComms // verbatim from tenant.secondaryComms
    ) {}

    record StartResult(
            /** Bcrypt of the generated OTP — empty for non-OTP providers. */
            String         codeHashB64,
            /** JWT id for DeepLink — empty for non-link providers. */
            String         deepLinkJti,
            /** Masked address the user-safe prompt can quote (e.g. {@code ***@example.com}). */
            String         maskedTargetAddress,
            /** Operator-facing prompt the bot will paraphrase. */
            String         userPromptHint,
            /** Optional URL to embed in the prompt for DeepLink flows. */
            Optional<URI>  deepLinkUrl,
            /** When the pending row should expire (capped by the coordinator). */
            Instant        expiresAt
    ) {
        public StartResult {
            if (userPromptHint == null || userPromptHint.isBlank()) {
                throw new IllegalArgumentException("userPromptHint is required");
            }
            if (expiresAt == null) {
                throw new IllegalArgumentException("expiresAt is required");
            }
            deepLinkUrl = deepLinkUrl == null ? Optional.empty() : deepLinkUrl;
        }
    }

    record StepUpVerifyContext(
            Ids.TenantId  tenant,
            Ids.SessionId sessionId,
            String        audience,
            StepUpHint    hint,
            /** Persisted artifact for the in-flight pending row. */
            String        codeHashB64,
            String        deepLinkJti,
            /** Raw user input — OTP digits or callback JWT. */
            String        userSuppliedSecret
    ) {}

    record VerifyResult(
            boolean                success,
            /** On success, the tenant-side subject the elevation will carry. */
            String                 elevatedSubject,
            /** Scopes added by this elevation; merged on top of the baseline. */
            Set<String>            addedScopes,
            /** Claims added by this elevation; merged on top of the baseline. */
            Map<String, Object>    addedClaims,
            /** Audit string explaining the verdict; lands in step_up_audit. */
            String                 auditReason
    ) {
        public VerifyResult {
            addedScopes = addedScopes == null ? Set.of() : Set.copyOf(addedScopes);
            addedClaims = addedClaims == null ? Map.of() : Map.copyOf(addedClaims);
            if (auditReason == null || auditReason.isBlank()) {
                throw new IllegalArgumentException("auditReason is required");
            }
        }

        public static VerifyResult success(String elevatedSubject,
                                           Set<String> scopes,
                                           Map<String, Object> claims,
                                           String auditReason) {
            return new VerifyResult(true, elevatedSubject, scopes, claims, auditReason);
        }

        public static VerifyResult failure(String auditReason) {
            return new VerifyResult(false, null, Set.of(), Map.of(), auditReason);
        }
    }
}
