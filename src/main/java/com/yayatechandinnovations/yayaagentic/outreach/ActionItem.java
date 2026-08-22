package com.yayatechandinnovations.yayaagentic.outreach;

/**
 * An inline action button riding a proactive / outreach message (owner-cockpit
 * v3 §D3). It travels the ingest → {@code chat_inbox} → {@code InboxItem} →
 * drain-JSON path so a proactive message can offer the operator a one-tap reply.
 *
 * <p>{@code label} is the button text, {@code send} the message the client posts
 * back when tapped, and {@code style} ∈ {primary, danger, neutral} — defaulting
 * to {@code neutral} when unset. The payload is data, never instruction: tapping
 * only composes a normal user turn.
 */
public record ActionItem(String label, String send, String style) {

    public ActionItem {
        style = (style == null || style.isBlank()) ? "neutral" : style;
    }
}
