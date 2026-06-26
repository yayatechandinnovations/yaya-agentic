package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpHint;

import java.util.Map;

/**
 * Decision result. {@code userSafeReason} is what the agent may paraphrase to
 * the user; {@code auditReason} is what we log. They MUST be different — the
 * former never leaks implementation detail. See design §5.5.
 *
 * <p>{@code DenyOfferStepUp} is a deny that also names a verification flow
 * the engine MAY mount to retry the action (W-A4: type wired; W-A5+: runtime
 * lit up). Every {@code instanceof Deny} check must also treat
 * {@code DenyOfferStepUp} as a denial — use {@link #isDenied()} when the
 * caller just wants "any deny shape."
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.1.
 */
public sealed interface AuthzDecision {

    record Allow(Map<String, Object> obligations) implements AuthzDecision {
        public static Allow none() { return new Allow(Map.of()); }
    }

    record Deny(String userSafeReason, String auditReason) implements AuthzDecision {}

    /**
     * Like {@link Deny}, but the engine MAY mount a step-up flow described by
     * {@code hint} and retry the original action with an elevated Principal.
     * In W-A4 every consumer treats this exactly like {@link Deny}; the actual
     * step-up runtime ({@code StepUpCoordinator}) lands in W-A5.
     */
    record DenyOfferStepUp(
            String     userSafeReason,
            String     auditReason,
            StepUpHint hint
    ) implements AuthzDecision {

        public DenyOfferStepUp {
            if (hint == null) {
                throw new IllegalArgumentException(
                        "DenyOfferStepUp.hint is required — use Deny for plain denials");
            }
        }
    }

    /** {@code true} when this is either {@link Deny} or {@link DenyOfferStepUp}. */
    default boolean isDenied() {
        return this instanceof Deny || this instanceof DenyOfferStepUp;
    }

    /** User-safe reason if this is a denial; throws otherwise. */
    default String userSafeReason() {
        return switch (this) {
            case Deny d            -> d.userSafeReason();
            case DenyOfferStepUp d -> d.userSafeReason();
            case Allow a           -> throw new IllegalStateException(
                    "Allow has no userSafeReason");
        };
    }

    /** Audit reason if this is a denial; throws otherwise. */
    default String auditReason() {
        return switch (this) {
            case Deny d            -> d.auditReason();
            case DenyOfferStepUp d -> d.auditReason();
            case Allow a           -> throw new IllegalStateException(
                    "Allow has no auditReason");
        };
    }
}
