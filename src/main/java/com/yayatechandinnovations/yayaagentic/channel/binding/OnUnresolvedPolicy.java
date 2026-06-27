package com.yayatechandinnovations.yayaagentic.channel.binding;

/**
 * What the inbound dispatch worker does when the binding's auth resolver
 * returns {@code Optional.empty()} (the tenant doesn't recognise this
 * caller) — or when the binding has no resolver at all and the worker
 * is asked which policy to apply.
 *
 * <p>{@code REJECT} drops the message: no user-facing reply, audit row
 * written. The verified phone we don't know is treated as a closed-class
 * denial, same shape as inbound to a non-existent endpoint.
 *
 * <p>{@code ANONYMOUS} continues with the baseline {@code whatsapp:+E164}
 * Principal — the pre-W-A behavior. Downstream Authorizers will still
 * deny identity-gated tools naturally.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §6.2 and §9.
 */
public enum OnUnresolvedPolicy {
    REJECT,
    ANONYMOUS;

    public static OnUnresolvedPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) return REJECT;
        return OnUnresolvedPolicy.valueOf(raw.trim().toUpperCase());
    }
}
