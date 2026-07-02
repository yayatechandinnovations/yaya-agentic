package com.yayatechandinnovations.yayaagentic.channel.binding;

/**
 * What a channel surface does when a resolver-selected profile override fails
 * a guardrail (unpublished, cross-tenant, or not on the surface's allowlist).
 *
 * <p>{@code IGNORE} (default) drops the override and runs the session under the
 * bound profile — a mis-route is annoying but not dangerous. {@code REJECT}
 * fails the inbound closed, for public surfaces where landing in the wrong
 * profile is worse than no answer.
 *
 * <p>See {@code docs/design/resolver-profile-override-design.md} §7.
 */
public enum OnOverrideDenied {
    IGNORE,
    REJECT;

    public static OnOverrideDenied parse(String raw) {
        if (raw == null || raw.isBlank()) return IGNORE;
        return OnOverrideDenied.valueOf(raw.trim().toUpperCase());
    }
}
