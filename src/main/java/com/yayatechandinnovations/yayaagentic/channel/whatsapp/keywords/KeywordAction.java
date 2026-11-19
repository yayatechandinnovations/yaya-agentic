package com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords;

/**
 * A keyword a sender answers deterministically: an inbound whose normalised
 * text starts with {@code prefix} (as a whole word) is forwarded to
 * {@code forwardUrl} and the tenant's reply, if any, goes back to the caller.
 *
 * @param prefix     normalised (trimmed, single-spaced, upper-case, accent-free)
 * @param forwardUrl absolute http(s) URL on the tenant's side
 */
public record KeywordAction(String prefix, String forwardUrl) {
    public KeywordAction {
        if (prefix == null || prefix.isBlank()) throw new IllegalArgumentException("prefix required");
        if (forwardUrl == null || forwardUrl.isBlank()) throw new IllegalArgumentException("forwardUrl required");
        prefix = KeywordTextNormalizer.normalize(prefix);
        forwardUrl = forwardUrl.trim();
    }
}
