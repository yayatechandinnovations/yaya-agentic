package com.yayatechandinnovations.yayaagentic.tenant;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL grammar checks for the tenant registry (§3.1, §4.2) and for the
 * path-only rule on HTTP tools (§6.1).
 *
 * <p>Kept deliberately independent of {@code BaseUrlResolver} — that
 * resolver runs at dispatch time on already-validated input; this class
 * runs on operator writes and is the canonical place to enforce shape.</p>
 */
public final class BaseUrlValidator {

    private BaseUrlValidator() {}

    public static void validateHostBaseUrl(String url, boolean requireHttps) {
        if (url == null || url.isBlank()) {
            throw AdminApiException.badRequest("bad_host_base_url",
                    "hostBaseUrl is required");
        }
        if (containsControlChars(url)) {
            throw AdminApiException.badRequest("bad_host_base_url",
                    "hostBaseUrl contains control characters");
        }
        if (url.indexOf('?') >= 0 || url.indexOf('#') >= 0) {
            throw AdminApiException.badRequest("bad_host_base_url",
                    "hostBaseUrl must not contain '?' or '#'");
        }
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw AdminApiException.badRequest("bad_host_base_url",
                    "hostBaseUrl is not a valid URI: " + e.getMessage());
        }
        if (parsed.getScheme() == null
                || (!"http".equalsIgnoreCase(parsed.getScheme())
                    && !"https".equalsIgnoreCase(parsed.getScheme()))) {
            throw AdminApiException.badRequest("bad_host_base_url",
                    "hostBaseUrl scheme must be http or https");
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw AdminApiException.badRequest("bad_host_base_url",
                    "hostBaseUrl must declare a host");
        }
        if (requireHttps && !"https".equalsIgnoreCase(parsed.getScheme())) {
            throw AdminApiException.badRequest("bad_host_base_url",
                    "hostBaseUrl must be https when requireHttps=true");
        }
    }

    /**
     * Validates a host_base_url_allowlist entry (§3.1). These are full URL
     * patterns — a {@code scheme://host[:port][/path]} string with optional
     * {@code *} wildcards (per {@link GlobMatcher} semantics). Unlike the
     * concrete {@code host_base_url}, these are not parseable as a URI, so
     * we check structure manually.
     */
    public static void validateUrlPattern(String pattern, boolean requireHttps) {
        if (pattern == null || pattern.isBlank()) {
            throw AdminApiException.badRequest("bad_url_pattern",
                    "URL pattern cannot be blank");
        }
        if (containsControlChars(pattern)) {
            throw AdminApiException.badRequest("bad_url_pattern",
                    "URL pattern contains control characters");
        }
        if (pattern.indexOf('?') >= 0 || pattern.indexOf('#') >= 0) {
            throw AdminApiException.badRequest("bad_url_pattern",
                    "URL pattern must not contain '?' or '#': " + pattern);
        }
        if (!(pattern.startsWith("http://") || pattern.startsWith("https://"))) {
            throw AdminApiException.badRequest("bad_url_pattern",
                    "URL pattern must start with http:// or https://: " + pattern);
        }
        if (requireHttps && !pattern.startsWith("https://")) {
            throw AdminApiException.badRequest("bad_url_pattern",
                    "URL pattern must be https when requireHttps=true: " + pattern);
        }
        String afterScheme = pattern.substring(pattern.indexOf("//") + 2);
        if (afterScheme.isBlank() || afterScheme.startsWith("/")) {
            throw AdminApiException.badRequest("bad_url_pattern",
                    "URL pattern must include a host: " + pattern);
        }
    }

    public static void validateOriginPattern(String pattern, boolean requireHttps) {
        if (pattern == null || pattern.isBlank()) {
            throw AdminApiException.badRequest("bad_origin_pattern",
                    "origin pattern cannot be blank");
        }
        if (containsControlChars(pattern)) {
            throw AdminApiException.badRequest("bad_origin_pattern",
                    "origin pattern contains control characters");
        }
        if (!(pattern.startsWith("http://") || pattern.startsWith("https://"))) {
            throw AdminApiException.badRequest("bad_origin_pattern",
                    "origin pattern must start with http:// or https://");
        }
        if (requireHttps && !pattern.startsWith("https://")) {
            throw AdminApiException.badRequest("bad_origin_pattern",
                    "origin pattern must be https when requireHttps=true: " + pattern);
        }
        String hostPart = pattern.substring(pattern.indexOf("//") + 2);
        if (hostPart.isBlank() || hostPart.indexOf('/') >= 0
                || hostPart.indexOf('?') >= 0 || hostPart.indexOf('#') >= 0) {
            throw AdminApiException.badRequest("bad_origin_pattern",
                    "origin pattern must be scheme://host[:port] only (no path/query/fragment): "
                            + pattern);
        }
    }

    /**
     * Path-only rule per §6.1. Accepts {@code /path...} (post-substitution);
     * rejects absolute URLs, protocol-relative, non-http schemes, and empties.
     */
    public static void validatePathOnlyTemplate(String urlTemplate) {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            throw AdminApiException.badRequest("absolute_url_not_permitted",
                    "httpSpec.urlTemplate is required and must be a path beginning with '/'");
        }
        if (containsControlChars(urlTemplate)) {
            throw AdminApiException.badRequest("absolute_url_not_permitted",
                    "httpSpec.urlTemplate contains control characters");
        }
        if (urlTemplate.startsWith("//")) {
            throw AdminApiException.badRequest("absolute_url_not_permitted",
                    "httpSpec.urlTemplate must not be protocol-relative");
        }
        // Scheme detection: anything before '://' indicates an absolute URL.
        // Note we intentionally check before the '/' rule, so that
        // 'https://api...' fails as absolute_url_not_permitted (not as 'missing /').
        int schemeIdx = urlTemplate.indexOf("://");
        if (schemeIdx > 0) {
            throw AdminApiException.badRequest("absolute_url_not_permitted",
                    "httpSpec.urlTemplate must be a path; absolute URLs are no longer accepted. "
                            + "The host resolves from the tenant's hostBaseUrl. (got: "
                            + urlTemplate + ")");
        }
        if (!urlTemplate.startsWith("/")) {
            throw AdminApiException.badRequest("absolute_url_not_permitted",
                    "httpSpec.urlTemplate must start with '/' (got: " + urlTemplate + ")");
        }
    }

    /**
     * Returns the {@code scheme://host[:port]} component of an absolute URL,
     * lowercased for comparison. Returns null when the input isn't parseable
     * as such — callers treat null as "no default origin available."
     */
    public static String originOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(url);
            if (u.getScheme() == null || u.getHost() == null) return null;
            String origin = u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase();
            if (u.getPort() > 0) origin += ":" + u.getPort();
            return origin;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }
}
