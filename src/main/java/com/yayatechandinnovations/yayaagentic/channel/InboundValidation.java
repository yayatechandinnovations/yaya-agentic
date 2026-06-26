package com.yayatechandinnovations.yayaagentic.channel;

/**
 * Result of {@link MessagingChannelProvider#validateInbound}. Signature
 * verification is mandatory and non-bypassable (§16 + §2's second corollary);
 * an {@link Invalid} result causes the controller to drop with a 403 and an
 * audit row — there is NO LLM round-trip on an unverified webhook.
 */
public sealed interface InboundValidation
        permits InboundValidation.Valid,
                InboundValidation.Invalid {

    record Valid() implements InboundValidation {

        public static final Valid INSTANCE = new Valid();
    }

    /**
     * @param code    short stable code for metric labels + audit
     *                ({@code "signature_missing"} / {@code "signature_mismatch"}
     *                / {@code "sender_unknown"})
     * @param message operator-facing detail; NEVER returned to the BSP
     */
    record Invalid(String code, String message) implements InboundValidation {}
}
