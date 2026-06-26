package com.yayatechandinnovations.yayaagentic.auth.stepup;

import java.util.Optional;

/**
 * Where step-up artifacts (OTP codes, deep-link callbacks) are delivered for
 * a given tenant. Stored on {@code tenants.secondary_comms_json} and read by
 * the {@link StepUpProvider} implementations.
 *
 * <p>v1 carries two sub-records — {@code emailRelay} and {@code smsBackup}.
 * Both are optional; a provider whose method depends on a missing sub-record
 * raises a clear startup-time or first-use error rather than silently failing.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §9.5.
 *
 * @param emailRelay outbound email config the OTP-email provider uses; empty
 *                   when the tenant hasn't configured email-OTP
 * @param smsBackup  outbound SMS config the OTP-SMS-secondary provider uses
 *                   (W-A7); empty when not configured
 */
public record TenantSecondaryComms(
        Optional<EmailRelay>  emailRelay,
        Optional<SmsBackup>   smsBackup
) {

    public TenantSecondaryComms {
        emailRelay = emailRelay == null ? Optional.empty() : emailRelay;
        smsBackup  = smsBackup  == null ? Optional.empty() : smsBackup;
    }

    public static TenantSecondaryComms empty() {
        return new TenantSecondaryComms(Optional.empty(), Optional.empty());
    }

    /**
     * @param fromAddress operator-controlled "from" header for OTP emails (e.g.
     *                    {@code noreply@acme.example})
     * @param providerId  bean name of a platform-side mail sender; default is
     *                    the platform's bundled SMTP relay
     */
    public record EmailRelay(String fromAddress, String providerId) {
        public EmailRelay {
            if (fromAddress == null || fromAddress.isBlank()) {
                throw new IllegalArgumentException("emailRelay.fromAddress is required");
            }
            if (providerId == null || providerId.isBlank()) {
                providerId = "platform-smtp";
            }
        }
    }

    /**
     * @param fromNumber  the platform-owned phone number SMS OTPs are sent
     *                    from — MUST be different from any WhatsApp sender on
     *                    the tenant (rule 1 of the security non-negotiables)
     * @param providerId  bean name of the SMS sender; platform default is
     *                    {@code twilio-sms}
     */
    public record SmsBackup(String fromNumber, String providerId) {
        public SmsBackup {
            if (fromNumber == null || fromNumber.isBlank()) {
                throw new IllegalArgumentException("smsBackup.fromNumber is required");
            }
            if (providerId == null || providerId.isBlank()) {
                providerId = "twilio-sms";
            }
        }
    }
}
