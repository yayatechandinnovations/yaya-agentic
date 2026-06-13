package com.yayatechandinnovations.yayaagentic.tool;

import java.util.regex.Pattern;

/**
 * Anthropic's tool-name validation pattern, lifted as a backend invariant.
 *
 * <p>The Anthropic Messages API rejects any tool name not matching
 * {@code ^[a-zA-Z0-9_-]{1,128}$}. Discovering this at LLM-call time fails the
 * entire conversational turn and leaves the operator wondering why their
 * profile broke. Enforcing the same pattern at tool-registration time turns
 * the failure into a 400 the operator sees immediately.
 *
 * <p>The pattern is also the source of truth for the bulk-repair endpoint
 * that fixes legacy tools registered before this validation existed
 * ({@code AdminController#repairToolNames}).
 */
public final class ToolIdPattern {

    public static final String REGEX = "^[a-zA-Z0-9_-]{1,128}$";
    public static final Pattern COMPILED = Pattern.compile(REGEX);

    private static final Pattern ANY_BAD_CHARS = Pattern.compile("[^a-zA-Z0-9_-]+");
    private static final Pattern REPEATED_UNDERSCORE = Pattern.compile("_+");

    private ToolIdPattern() {}

    public static boolean isValid(String id) {
        return id != null && COMPILED.matcher(id).matches();
    }

    /**
     * Produce an Anthropic-compatible tool id by replacing every run of
     * disallowed characters with a single {@code _}, collapsing repeats, and
     * truncating to 128 chars. Returns null if the input is null or sanitises
     * to an empty string (e.g. {@code "..."} → empty).
     */
    public static String sanitize(String id) {
        if (id == null || id.isBlank()) return null;
        String replaced = ANY_BAD_CHARS.matcher(id).replaceAll("_");
        String collapsed = REPEATED_UNDERSCORE.matcher(replaced).replaceAll("_");
        String trimmed = trimEdges(collapsed);
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }

    private static String trimEdges(String s) {
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '_') start++;
        while (end > start && s.charAt(end - 1) == '_') end--;
        return s.substring(start, end);
    }
}
