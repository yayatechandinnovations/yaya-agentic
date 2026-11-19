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
 * <p>{@code AUTO_REPLY} drops the message exactly like {@code REJECT} (same
 * audit row, the inbound stays in {@code inbound_messages} so the 24 h
 * window logic keeps working) but first answers the caller with the
 * binding's {@code unresolvedReplyText}, at most once per (sender, phone)
 * per 24 h. For a business number that mostly sends (ticket copies,
 * notices) and whose staff are the only recognised callers: a customer who
 * writes back learns where to call instead of hearing nothing.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §6.2 and §9,
 * and smart-lottery {@code pos-ticket-whatsapp-share-design.md} §8 P4.
 */
public enum OnUnresolvedPolicy {
    REJECT,
    ANONYMOUS,
    AUTO_REPLY;

    public static OnUnresolvedPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) return REJECT;
        return OnUnresolvedPolicy.valueOf(raw.trim().toUpperCase());
    }
}
