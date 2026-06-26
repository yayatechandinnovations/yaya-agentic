package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Provider-pluggable SMS transport — mirror of {@link
 * com.yayatechandinnovations.yayaagentic.email.MailSender} but for the
 * step-up OTP secondary-channel path (W-A7).
 *
 * <p>Implementations distinguish transient failures (throw {@link
 * SmsSendException} with {@code transient=true}) from permanent failures
 * ({@code transient=false}). The caller — currently
 * {@code OtpSmsSecondaryProvider} — fails the OTP cycle when send throws,
 * relying on user retry. v1 has no outbox-style at-least-once delivery; OTP
 * codes are short-lived enough that re-mint-on-retry is the right model.
 *
 * <p>v1 ships only the dev-console sender; a Twilio Programmable SMS impl
 * (NOT the WhatsApp sender — distinct platform-owned Twilio Messaging
 * Service) lands as a follow-up.
 */
public interface SmsSender {

    /** Stable identifier; reported in audit + logs and matched against the
     *  tenant's {@code SmsBackup.providerId}. */
    String name();

    /** Send synchronously. Throws {@link SmsSendException} on failure. */
    void send(SmsMessage msg) throws SmsSendException;
}
