package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

/**
 * State machine of one embedded-signup onboarding attempt. Linear on the
 * happy path; FAILED attempts are reset to RECEIVED on retry (same row, same
 * idempotency key) so retries can never provision duplicate Twilio resources.
 *
 * <p>COMPLETED means the sender was handed to the asynchronous Senders API
 * registration machinery (it is in {@code PENDING_REVIEW}, not necessarily
 * ONLINE yet) — ongoing provisioning status lives on the sender row.
 */
public enum OnboardingAttemptState {
    RECEIVED,
    ACCOUNT_PROVISIONED,
    SENDER_SUBMITTED,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
