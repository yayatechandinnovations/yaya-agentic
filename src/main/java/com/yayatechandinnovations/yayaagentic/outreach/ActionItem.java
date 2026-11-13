package com.yayatechandinnovations.yayaagentic.outreach;

import java.util.List;
import java.util.Map;

/**
 * An inline action button riding a proactive / outreach message (owner-cockpit
 * v3 §D3). It travels the ingest → {@code chat_inbox} → {@code InboxItem} →
 * drain-JSON path so a proactive message can offer the operator a one-tap reply.
 *
 * <p>{@code label} is the button text and {@code style} ∈ {primary, danger,
 * neutral} — defaulting to {@code neutral} when unset. A button either sends a
 * text turn ({@code send}) or is a BOUND action ({@code tool}, optionally with
 * one {@code ask}ed input): the tap runs that tool as the single consent
 * (whatsapp-ops-integration-design.md §6.4), and only for tools that opt in.
 * {@code actionId} is assigned at delivery; a client that knows it taps by id,
 * an older one still posts {@code send} (or the label) as text.
 */
public record ActionItem(String label, String send, String style,
                         BoundTool tool, Ask ask, String actionId) {

    public ActionItem {
        style = (style == null || style.isBlank()) ? "neutral" : style;
    }

    public ActionItem(String label, String send, String style) {
        this(label, send, style, null, null, null);
    }

    public boolean bound() {
        return tool != null && tool.id() != null && !tool.id().isBlank();
    }

    public ActionItem withActionId(String id) {
        return new ActionItem(label, send, style, tool, ask, id);
    }

    public record BoundTool(String id, Map<String, Object> args) {
        public BoundTool {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    public record Ask(String arg, String prompt, List<String> suggestions) {
        public Ask {
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }
    }
}
