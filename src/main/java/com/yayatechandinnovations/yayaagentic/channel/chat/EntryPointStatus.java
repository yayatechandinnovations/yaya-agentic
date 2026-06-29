package com.yayatechandinnovations.yayaagentic.channel.chat;

import java.util.Set;

/**
 * Lifecycle of a chat entry point ({@code chat-channel-design.md} §6) — a small
 * state machine with no regulatory review, unlike WhatsApp/SMS senders.
 *
 * <pre>
 *   DRAFT ──publish──▶ ACTIVE ──pause──▶ PAUSED ──resume──▶ ACTIVE
 *     ▲                  │                                   │
 *     └────── edit ──────┘            deactivate ────────────┴──▶ DEACTIVATED
 * </pre>
 *
 * {@code DEACTIVATED} is terminal — the handle is permanently dead (404).
 */
public enum EntryPointStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    DEACTIVATED;

    private static final java.util.Map<EntryPointStatus, Set<EntryPointStatus>> ALLOWED =
            java.util.Map.of(
                    DRAFT,       Set.of(ACTIVE, DEACTIVATED),
                    ACTIVE,      Set.of(PAUSED, DEACTIVATED),
                    PAUSED,      Set.of(ACTIVE, DEACTIVATED),
                    DEACTIVATED, Set.of());

    public boolean canTransitionTo(EntryPointStatus to) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(to);
    }

    /** Whether the public handle serves session starts in this status. */
    public boolean servesSessions() {
        return this == ACTIVE;
    }

    public static EntryPointStatus parse(String raw) {
        return raw == null || raw.isBlank() ? DRAFT : valueOf(raw.trim().toUpperCase());
    }
}
