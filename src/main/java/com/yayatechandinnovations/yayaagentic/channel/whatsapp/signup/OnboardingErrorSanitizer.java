package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import java.util.regex.Pattern;

/**
 * Scrubs provider failure text before it is persisted on an onboarding
 * attempt and surfaced to tenant operators. The tenant-facing invariant
 * (design §7: the BSP is invisible) plus the secrets rule (no SIDs, no
 * tokens) both apply — internal diagnostics stay in the server logs, which
 * keep the raw text.
 */
public final class OnboardingErrorSanitizer {

    private static final int MAX_LENGTH = 300;
    /** Twilio resource SIDs: two-letter prefix + 32 hex chars. */
    private static final Pattern SID = Pattern.compile("\\b[A-Z]{2}[0-9a-fA-F]{32}\\b");
    private static final Pattern BRAND = Pattern.compile("(?i)twilio");

    private OnboardingErrorSanitizer() {}

    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "The messaging provider reported an error. Please retry or contact support.";
        }
        String cleaned = SID.matcher(message).replaceAll("[redacted]");
        cleaned = BRAND.matcher(cleaned).replaceAll("the messaging provider");
        cleaned = cleaned.strip();
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH) + "…";
        }
        return cleaned;
    }
}
