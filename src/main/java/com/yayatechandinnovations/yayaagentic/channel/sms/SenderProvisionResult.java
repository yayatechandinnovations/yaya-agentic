package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Outcome of provisioning an SMS sender's number at the BSP via
 * {@link SmsNumberProvisioner} — buy/host the number, wire its inbound webhook,
 * and (US) attach it to the campaign's Messaging Service. Mirrors
 * {@code A2pSubmitResult} / {@code channel.SenderRegistrationResult} so the
 * sender state machine folds the verdict back the same way.
 */
public sealed interface SenderProvisionResult
        permits SenderProvisionResult.Accepted,
                SenderProvisionResult.Rejected,
                SenderProvisionResult.TransientFailure {

    record Accepted(String twilioSenderSid, String bspStatus) implements SenderProvisionResult {}

    record Rejected(String code, String message) implements SenderProvisionResult {}

    record TransientFailure(String code, String message) implements SenderProvisionResult {}
}
