package com.yayatechandinnovations.yayaagentic.profile;

/**
 * A capability's entry in the persistent client menu (owner-cockpit design;
 * SL {@code yaya-owner-ai-experience-v2-design.md} §3.1). The engine
 * assembles the profile's menu from these blocks — grouped by
 * {@code section}, ordered by {@code order} — and emits it as the
 * {@code menu} ui_hint / session-start extension. Because it derives from
 * the installed profile it is automatically per-tenant and per-role, and
 * never lists something the profile can't do.
 *
 * @param badgeSource optional key into the engine's badge state (bucket
 *                    names of the owner-briefing {@code attention} map,
 *                    e.g. {@code "pendingUndoRequests"}) — rendered as a
 *                    count chip on the menu item.
 */
public record MenuBlock(
        String section,
        String label,
        String send,
        Integer order,
        String badgeSource
) {
    public MenuBlock {
        if (section == null || section.isBlank()) {
            throw new IllegalArgumentException("MenuBlock.section must be non-blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("MenuBlock.label must be non-blank");
        }
        if (send == null || send.isBlank()) {
            throw new IllegalArgumentException("MenuBlock.send must be non-blank");
        }
        if (order == null) order = 100;
    }
}
