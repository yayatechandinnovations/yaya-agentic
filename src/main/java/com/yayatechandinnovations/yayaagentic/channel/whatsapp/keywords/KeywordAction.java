package com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords;

/**
 * A keyword a sender answers deterministically: an inbound whose normalised
 * text starts with {@code prefix} (as a whole word) is forwarded to
 * {@code forwardUrl} and the tenant's reply, if any, goes back to the caller.
 *
 * @param prefix     normalised (trimmed, single-spaced, upper-case, accent-free)
 * @param forwardUrl absolute http(s) URL on the tenant's side
 * @param scope      how the prefix is matched on a number several workspaces
 *                   share (whatsapp-shared-sender-design.md §8.2); on a
 *                   workspace's own number both behave the same
 */
public record KeywordAction(String prefix, String forwardUrl, Scope scope) {

    /**
     * {@code ROUTED}: unique on the number; anyone who sends it reaches the
     * workspace that owns it. {@code REPLY}: each workspace may hold it; it
     * answers a message already routed to that workspace.
     */
    public enum Scope { ROUTED, REPLY }

    public KeywordAction {
        if (prefix == null || prefix.isBlank()) throw new IllegalArgumentException("prefix required");
        if (forwardUrl == null || forwardUrl.isBlank()) throw new IllegalArgumentException("forwardUrl required");
        prefix = KeywordTextNormalizer.normalize(prefix);
        forwardUrl = forwardUrl.trim();
        scope = scope == null ? Scope.ROUTED : scope;
    }

    public KeywordAction(String prefix, String forwardUrl) {
        this(prefix, forwardUrl, Scope.ROUTED);
    }
}
