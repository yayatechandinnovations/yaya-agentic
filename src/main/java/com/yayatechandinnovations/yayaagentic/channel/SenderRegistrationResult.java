package com.yayatechandinnovations.yayaagentic.channel;

/**
 * BSP response to {@link MessagingChannelProvider#registerSender}. Outcome
 * shapes the sender's state-machine transition (see §6.1):
 * <ul>
 *   <li>{@link Accepted}     — Twilio took the submission; senderRef carries
 *       the freshly-minted SID; state → {@code PENDING_REVIEW}.</li>
 *   <li>{@link Rejected}     — Twilio refused at submit (bad phone, duplicate,
 *       etc.); state → {@code FAILED} with the failure surfaced to the UI.</li>
 *   <li>{@link TransientFailure} — network / 5xx; the submitter retries with
 *       the same {@code idempotencyKey}; state stays {@code SUBMITTING}.</li>
 * </ul>
 */
public sealed interface SenderRegistrationResult
        permits SenderRegistrationResult.Accepted,
                SenderRegistrationResult.Rejected,
                SenderRegistrationResult.TransientFailure {

    record Accepted(SenderRef senderRef, String bspStatus) implements SenderRegistrationResult {}

    record Rejected(String code, String message) implements SenderRegistrationResult {}

    record TransientFailure(String code, String message) implements SenderRegistrationResult {}
}
