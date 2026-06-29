package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

/**
 * Platform-singleton SMS one-time-code phone verification for the chat app's
 * first-run onboarding ({@code chat-channel-design.md} §20). Mirrors the
 * {@code SmsTransport} / {@code SmsNumberProvisioner} SPI split: a real Twilio
 * Verify implementation in production, a dev no-op otherwise.
 *
 * <p>Verification is a <em>platform</em> trust assertion (yaya verifies the
 * phone), not a tenant one — so this SPI is account-wide, not tenant-scoped.
 */
public interface PhoneVerifier {

    String name();

    /** Send a one-time code to the phone. Heavily rate-limited upstream (§20.4
     *  toll-fraud) — implementations must not add their own retries. */
    StartResult start(String phoneE164);

    /** Verify a code the user entered. */
    CheckResult check(String phoneE164, String code);

    enum StartStatus { PENDING, FAILED }

    record StartResult(StartStatus status, String detail) {
        public static StartResult pending() { return new StartResult(StartStatus.PENDING, null); }
        public static StartResult failed(String detail) { return new StartResult(StartStatus.FAILED, detail); }
        public boolean ok() { return status == StartStatus.PENDING; }
    }

    record CheckResult(boolean approved, String detail) {
        public static CheckResult pass() { return new CheckResult(true, null); }
        public static CheckResult denied(String detail) { return new CheckResult(false, detail); }
    }
}
