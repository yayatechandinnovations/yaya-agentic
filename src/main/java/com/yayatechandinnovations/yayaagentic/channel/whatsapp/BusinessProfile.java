package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import java.net.URI;
import java.util.Locale;

/**
 * Meta-required business profile attached to every WhatsApp sender. Twilio's
 * Senders API and Meta's Business Manager both consume these fields — they're
 * what Meta evaluates during the business verification step that gates the
 * {@code PENDING_REVIEW} → {@code ACTIVE} transition.
 *
 * <p>The tenant-facing wizard (§15.1 step 1) collects all of this in step 1.
 * The platform never modifies these on the operator's behalf, except that a
 * bare website domain gets an {@code https://} scheme — Twilio rejects the
 * whole registration (63100 "website must be a valid URL") without one.
 *
 * @param vertical Meta-enumerated vertical code (e.g. {@code RETAIL},
 *                 {@code FINANCIAL_SERVICES}). Validation against the Meta
 *                 catalog happens at write time in the service layer.
 * @param logoUrl  optional profile photo. Usually a platform-hosted logo
 *                 ({@code /v1/chat/logos/…}, possibly root-relative); Twilio
 *                 fetches it, so registration sends it as an absolute URL.
 */
public record BusinessProfile(
        String legalName,
        String displayName,
        String contactEmail,
        String address,
        String websiteUrl,
        String vertical,
        String about,
        String logoUrl
) {

    public BusinessProfile {
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalArgumentException("legalName must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (contactEmail == null || contactEmail.isBlank()) {
            throw new IllegalArgumentException("contactEmail must not be blank");
        }
        if (vertical == null || vertical.isBlank()) {
            throw new IllegalArgumentException("vertical must not be blank");
        }
        // Normalized here rather than at the API edge so rows saved before
        // the fix are corrected when they are loaded and resubmitted.
        websiteUrl = normalizeWebsiteUrl(websiteUrl);
    }

    public static String normalizeWebsiteUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://")
                ? trimmed
                : "https://" + trimmed;
    }

    /** True for a blank logo (it is optional), a root-relative platform path,
     *  or an absolute http(s) URL. */
    public static boolean isValidLogoUrl(String url) {
        if (url == null || url.isBlank()) return true;
        String trimmed = url.trim();
        if (trimmed.startsWith("/")) return !trimmed.startsWith("//");
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return (lower.startsWith("https://") || lower.startsWith("http://")) && isValidWebsiteUrl(trimmed);
    }

    /** True for a blank website (it is optional) or an http(s) URL with a dotted host. */
    public static boolean isValidWebsiteUrl(String url) {
        if (url == null || url.isBlank()) return true;
        try {
            URI uri = new URI(normalizeWebsiteUrl(url));
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && host != null && host.contains(".") && !host.endsWith(".");
        } catch (Exception e) {
            return false;
        }
    }
}
