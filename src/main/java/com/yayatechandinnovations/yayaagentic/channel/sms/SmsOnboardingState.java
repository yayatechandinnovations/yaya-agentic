package com.yayatechandinnovations.yayaagentic.channel.sms;

import java.time.Instant;

/**
 * Last carrier/BSP onboarding snapshot for an {@link SmsSender}, persisted as
 * {@code onboarding_state_json}. Mirror of {@code channel.whatsapp.OnboardingState}.
 *
 * @param twilioSenderSid  the provisioned IncomingPhoneNumber/sender SID, once known
 * @param lastStatus       last raw BSP status (e.g. "PENDING", "ONLINE")
 * @param lastFailureCode  populated on rejection/transient failure
 * @param lastFailureMessage human-readable failure detail
 * @param lastPolledAt     when the poller last refreshed this
 * @param pollAttempts     monotonic counter for backoff/observability
 */
public record SmsOnboardingState(
        String  twilioSenderSid,
        String  lastStatus,
        String  lastFailureCode,
        String  lastFailureMessage,
        Instant lastPolledAt,
        int     pollAttempts
) {
    public static SmsOnboardingState empty() {
        return new SmsOnboardingState(null, null, null, null, null, 0);
    }
}
