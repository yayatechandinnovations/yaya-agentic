package com.yayatechandinnovations.yayaagentic.channel.system;

import java.util.Set;

/**
 * Tool authority while attacker-reachable system content is pending in a session
 * (§9.1, Rule 2). Sits IN FRONT OF the Authorizer, not instead of it: it caps the
 * blast radius of prompt injection even if the model is fully turned, by masking
 * the profile's toolset down to what a mediation reply legitimately needs.
 *
 * @param allow                     explicit tool-id allowlist (always the bound
 *                                  response tool)
 * @param allowReads                permit read-only (idempotent, non-confirmable) tools
 * @param requireConfirmForMutating force any surviving mutating tool to confirmable
 */
public record CapabilityMask(
        Set<String> allow,
        boolean allowReads,
        boolean requireConfirmForMutating
) {
    /** Working-memory key the reply flow sets and the engine's toolset build reads. */
    public static final String WORKING_MEMORY_KEY = "__sys_capability_mask";

    public CapabilityMask {
        allow = allow == null ? Set.of() : Set.copyOf(allow);
    }

    /** Serializable shape carried through working memory into the masked turn. */
    public java.util.Map<String, Object> toWorkingMemory() {
        return java.util.Map.of(
                "allow", new java.util.ArrayList<>(allow),
                "allowReads", allowReads,
                "requireConfirm", requireConfirmForMutating);
    }

    /** Deny-by-default: only the response tool + read-only tools; mutating denied. */
    public static CapabilityMask responseOnly(String responseToolId) {
        return new CapabilityMask(
                responseToolId == null || responseToolId.isBlank() ? Set.of() : Set.of(responseToolId),
                true, true);
    }

    /** The effective mask for a sender: its configured mask, else deny-by-default. */
    public static CapabilityMask effective(CapabilityMask configured, String responseToolId) {
        CapabilityMask base = configured == null ? responseOnly(responseToolId) : configured;
        // The response tool is always reachable — a reply must be possible.
        if (responseToolId != null && !responseToolId.isBlank() && !base.allow().contains(responseToolId)) {
            var widened = new java.util.HashSet<>(base.allow());
            widened.add(responseToolId);
            return new CapabilityMask(widened, base.allowReads(), base.requireConfirmForMutating());
        }
        return base;
    }
}
