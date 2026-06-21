package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Slug generator for default workspaces: lowercase ASCII alphanumeric +
 * dash, ≤ 32 chars, collision-safe via a 3-char random suffix.
 *
 * <p>Reserved-code list blocks impersonation of well-known paths (e.g.
 * {@code admin}, {@code api}, {@code auth}). The collision-suffix attempt
 * is bounded — if 5 random suffixes can't find an open slot the caller
 * surfaces a generic {@link SlugAllocationException}.</p>
 */
@Component
public class WorkspaceSlugger {

    private static final Set<String> RESERVED = Set.of(
            "admin", "api", "auth", "health", "system", "static", "public",
            "www", "signup", "login", "logout", "verify", "reset",
            "accept-invitation", "me", "workspaces", "platform-settings",
            "audit", "metrics", "status", "version", "root", "support", "billing"
    );

    private static final SecureRandom RAND = new SecureRandom();
    private static final char[] SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int MAX_LEN = 32;
    private static final int SUFFIX_LEN = 3;
    private static final int MAX_COLLISION_ATTEMPTS = 5;

    /** Pure transform — does not consult the DB. */
    public String baseSlug(String displayName) {
        if (displayName == null) return "workspace";
        String normalized = Normalizer.normalize(displayName, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(normalized.length());
        boolean lastDash = true;     // collapses leading dashes
        for (int i = 0; i < normalized.length(); i++) {
            char c = Character.toLowerCase(normalized.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastDash = false;
            } else if (!lastDash) {
                sb.append('-');
                lastDash = true;
            }
            if (sb.length() >= MAX_LEN) break;
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.deleteCharAt(sb.length() - 1);
        }
        String s = sb.toString();
        if (s.isEmpty() || RESERVED.contains(s)) return "workspace";
        return s;
    }

    /**
     * Resolve a slug against a take-or-exists predicate. Returns the first
     * non-colliding candidate; throws {@link SlugAllocationException} if
     * every attempt collides.
     *
     * @param exists returns true iff the candidate is already taken
     */
    public String allocate(String displayName, Predicate<String> exists) {
        String base = baseSlug(displayName);
        if (!exists.test(base)) return base;
        String truncated = base.length() > MAX_LEN - SUFFIX_LEN - 1
                ? base.substring(0, MAX_LEN - SUFFIX_LEN - 1)
                : base;
        for (int i = 0; i < MAX_COLLISION_ATTEMPTS; i++) {
            String candidate = truncated + "-" + randomSuffix();
            if (!exists.test(candidate)) return candidate;
        }
        throw new SlugAllocationException("could not allocate slug for: " + displayName);
    }

    private static String randomSuffix() {
        char[] out = new char[SUFFIX_LEN];
        for (int i = 0; i < SUFFIX_LEN; i++) {
            out[i] = SUFFIX_ALPHABET[RAND.nextInt(SUFFIX_ALPHABET.length)];
        }
        return new String(out);
    }
}
