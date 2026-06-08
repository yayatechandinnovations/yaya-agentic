package com.yayatechandinnovations.yayaagentic.tenant;

import java.util.regex.Pattern;

/**
 * Minimal glob matcher for tenant allowlist patterns: only {@code *} as a
 * wildcard, anchored at both ends, case-insensitive. Intentionally not a
 * regex engine — design §3.1 deliberately disallows regex to keep operator
 * intent obvious from the row.
 */
public final class GlobMatcher {

    private GlobMatcher() {}

    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) return false;
        String[] parts = pattern.split("\\*", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) regex.append(".*");
            regex.append(Pattern.quote(parts[i]));
        }
        regex.append("$");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE)
                .matcher(value).matches();
    }
}
