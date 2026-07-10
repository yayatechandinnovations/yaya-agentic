package com.yayatechandinnovations.yayaidentity.signup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code yaya.identity.signup.*}. Disabled by default: an IdP with an open
 * registration endpoint is attack surface a single-merchant install never asked
 * for. {@code otpEchoEnabled} returns the raw code in API responses — dev/test
 * ONLY, and meaningless with Twilio (which never hands us the code).
 */
@ConfigurationProperties("yaya.identity.signup")
public record SignupProperties(
        Boolean enabled,
        Long pendingTtlSeconds,
        Long resendCooldownSeconds,
        Boolean otpEchoEnabled,
        Integer maxSendsPerPhonePerHour,
        Integer maxSendsPerIpPerHour,
        Twilio twilio) {

    public SignupProperties {
        enabled = enabled != null && enabled;
        pendingTtlSeconds = pendingTtlSeconds == null ? 1800L : pendingTtlSeconds;
        resendCooldownSeconds = resendCooldownSeconds == null ? 60L : resendCooldownSeconds;
        otpEchoEnabled = otpEchoEnabled != null && otpEchoEnabled;
        maxSendsPerPhonePerHour = maxSendsPerPhonePerHour == null ? 5 : maxSendsPerPhonePerHour;
        maxSendsPerIpPerHour = maxSendsPerIpPerHour == null ? 20 : maxSendsPerIpPerHour;
        twilio = twilio == null ? new Twilio(null, null, null, null) : twilio;
    }

    public record Twilio(String accountSid, String authToken, String verifyServiceSid, String baseUrl) {

        public Twilio {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://verify.twilio.com" : baseUrl;
        }

        public boolean configured() {
            return notBlank(accountSid) && notBlank(authToken) && notBlank(verifyServiceSid);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
