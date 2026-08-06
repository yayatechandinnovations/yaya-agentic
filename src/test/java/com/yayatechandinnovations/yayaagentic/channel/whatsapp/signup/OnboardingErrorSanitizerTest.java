package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingErrorSanitizerTest {

    // Assembled at runtime so the source holds no literal that secret scanners flag as a live Twilio SID.
    private static final String ACCOUNT_SID = "AC" + "0123456789abcdef".repeat(2);
    private static final String KEY_SID = "SK" + "0123456789abcdef".repeat(2);

    @Test
    void strips_twilio_brand_and_resource_sids() {
        String raw = "Twilio could not register sender under " + ACCOUNT_SID + " "
                + "(key " + KEY_SID + ")";
        String sanitized = OnboardingErrorSanitizer.sanitize(raw);
        assertThat(sanitized)
                .doesNotContain("Twilio")
                .doesNotContain(ACCOUNT_SID)
                .doesNotContain(KEY_SID)
                .contains("the messaging provider")
                .contains("[redacted]");
    }

    @Test
    void truncates_long_messages() {
        String sanitized = OnboardingErrorSanitizer.sanitize("x".repeat(1000));
        assertThat(sanitized).hasSizeLessThanOrEqualTo(301);
    }

    @Test
    void null_or_blank_becomes_generic_message() {
        assertThat(OnboardingErrorSanitizer.sanitize(null)).contains("Please retry");
        assertThat(OnboardingErrorSanitizer.sanitize("  ")).contains("Please retry");
    }

    @Test
    void case_insensitive_brand_scrub() {
        assertThat(OnboardingErrorSanitizer.sanitize("TWILIO said no; twilio again"))
                .doesNotContainIgnoringCase("twilio");
    }
}
