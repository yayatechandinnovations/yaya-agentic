package com.yayatechandinnovations.yayaagentic.auth.stepup;

import java.time.Duration;
import java.util.Map;

/**
 * Carried by {@link
 * com.yayatechandinnovations.yayaagentic.auth.AuthzDecision.DenyOfferStepUp}
 * to tell the engine HOW it could elevate the Principal in order to retry the
 * denied action.
 *
 * <p>The hint NEVER reaches the LLM. The engine consumes it to mount a
 * {@code StepUpCoordinator} flow (W-A5+); on success the original tool call
 * is re-evaluated by the Authorizer chain against an elevated Principal.
 *
 * @param method      mechanism the coordinator should use to verify
 * @param audience    free-form binding identifier (e.g. {@code "tool:transfer_funds"})
 *                    the resulting elevation is scoped to. Replay against a
 *                    different audience must be impossible.
 * @param elevationTtl how long the elevated Principal survives after
 *                    successful verification; capped server-side at the
 *                    binding's idle timeout in W-A5+
 * @param params      method-specific parameters; opaque to the engine and
 *                    handed to the provider verbatim (e.g. OTP length, deep-
 *                    link return URL). Never logged at INFO; never recorded.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.1.
 */
public record StepUpHint(
        StepUpMethod         method,
        String               audience,
        Duration             elevationTtl,
        Map<String, Object>  params) {

    public StepUpHint {
        if (method == null) {
            throw new IllegalArgumentException("StepUpHint.method is required");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("StepUpHint.audience is required");
        }
        if (elevationTtl == null || elevationTtl.isNegative() || elevationTtl.isZero()) {
            throw new IllegalArgumentException(
                    "StepUpHint.elevationTtl must be positive");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
