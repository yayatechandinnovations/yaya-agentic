package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Outcome of submitting a Brand or Campaign to the carrier registry via
 * {@link A2pRegistrar}. Mirrors {@code channel.SenderRegistrationResult} so the
 * compliance state machine folds verdicts back the same way the WhatsApp sender
 * submit does (see {@code WhatsAppSenderService.finishSubmit}).
 *
 * <ul>
 *   <li>{@link Accepted}        — the registry took the submission; state moves
 *       to the PENDING_* stage. {@code externalId} is the Twilio profile /
 *       campaign SID; {@code messagingServiceSid} is populated for campaign
 *       submits only (null for brands).</li>
 *   <li>{@link Rejected}        — refused at submit (bad EIN, duplicate, …);
 *       state → FAILED with the reason surfaced to the operator.</li>
 *   <li>{@link TransientFailure} — network / 5xx; stay in SUBMITTING so the
 *       poller retries.</li>
 * </ul>
 */
public sealed interface A2pSubmitResult
        permits A2pSubmitResult.Accepted,
                A2pSubmitResult.Rejected,
                A2pSubmitResult.TransientFailure {

    record Accepted(String externalId, String messagingServiceSid, String bspStatus)
            implements A2pSubmitResult {}

    record Rejected(String code, String message) implements A2pSubmitResult {}

    record TransientFailure(String code, String message) implements A2pSubmitResult {}
}
