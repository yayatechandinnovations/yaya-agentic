package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import java.time.Instant;

/**
 * Snapshot of the last sync from the BSP for a sender in
 * {@code SUBMITTING} / {@code PENDING_REVIEW}. The poller (§6.4) writes this;
 * the admin UI's live status panel (§15.1) reads it.
 *
 * <p>All fields are nullable so an unsubmitted DRAFT can carry an empty
 * onboarding state — the row's existence simply means "we haven't synced yet."
 *
 * @param twilioSenderSid          BSP sender SID; only populated after a
 *                                 successful submit, may stay populated across
 *                                 status transitions
 * @param lastTwilioStatus         raw status string from Twilio (e.g.
 *                                 {@code ONLINE} / {@code OFFLINE} /
 *                                 {@code PENDING})
 * @param lastTwilioFailureCode    machine-readable failure code when Twilio
 *                                 surfaces one; null on success
 * @param lastTwilioFailureMessage human-readable message; rendered in the
 *                                 admin UI under "WhatsApp review"
 * @param lastPolledAt             when we last reached Twilio for this sender
 * @param pollAttempts             cumulative count; bumped on every sync,
 *                                 reset on edit-to-DRAFT
 */
public record OnboardingState(
        String  twilioSenderSid,
        String  lastTwilioStatus,
        String  lastTwilioFailureCode,
        String  lastTwilioFailureMessage,
        Instant lastPolledAt,
        int     pollAttempts
) {

    /** Zero-state, used when a row is freshly created in DRAFT. */
    public static OnboardingState empty() {
        return new OnboardingState(null, null, null, null, null, 0);
    }
}
