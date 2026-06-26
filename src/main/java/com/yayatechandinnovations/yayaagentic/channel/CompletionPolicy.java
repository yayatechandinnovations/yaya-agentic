package com.yayatechandinnovations.yayaagentic.channel;

import java.time.Duration;

/**
 * Whether a channel adapter streams partial tokens to the user (web SSE) or
 * buffers until the engine emits {@code End} and dispatches one finalized
 * message (WhatsApp, SMS). One of the three behaviors that forced channel to
 * become a first-class concept (the engine cannot keep guessing this per call).
 *
 * <p>See {@code whatsapp-channel-design.md} §5 (intro) and §5.1.
 */
public sealed interface CompletionPolicy
        permits CompletionPolicy.Stream,
                CompletionPolicy.Complete {

    /** Token-by-token. Used by the existing SSE path. */
    record Stream() implements CompletionPolicy {}

    /**
     * Buffer the {@code TurnEvent} flux until {@code End}, then dispatch one
     * message. WhatsApp, SMS.
     *
     * @param maxBuffer safety valve — beyond it, flush what we have and continue
     *                  with a follow-up message in the same session
     *                  (see §10.1 buffering rules).
     */
    record Complete(Duration maxBuffer) implements CompletionPolicy {}
}
