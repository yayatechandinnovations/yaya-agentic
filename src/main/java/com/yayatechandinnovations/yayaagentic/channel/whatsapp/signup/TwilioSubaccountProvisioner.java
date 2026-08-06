package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

/**
 * Creates dedicated Twilio subaccounts under the platform's parent account.
 * Mirrors the {@code SmsNumberProvisioner} SPI shape: a real Twilio-backed
 * bean when the parent account is configured, a dev no-op otherwise, and a
 * sealed result so callers fold outcomes without exception plumbing.
 *
 * <p>The returned auth token is the ONLY time the secret exists in plaintext
 * outside the cipher — the caller must encrypt-and-persist immediately and
 * never log it.
 */
public interface TwilioSubaccountProvisioner {

    boolean isReady();

    SubaccountProvisionResult provision(String friendlyName);

    sealed interface SubaccountProvisionResult {
        /** Subaccount created (or Twilio replayed an idempotent create). */
        record Created(String subaccountSid, String authToken) implements SubaccountProvisionResult {}
        /** Twilio refused; operator attention required (e.g. parent account limits). */
        record Rejected(String code, String message) implements SubaccountProvisionResult {}
        /** Transient — safe to retry without side effects. */
        record TransientFailure(String code, String message) implements SubaccountProvisionResult {}
    }
}
