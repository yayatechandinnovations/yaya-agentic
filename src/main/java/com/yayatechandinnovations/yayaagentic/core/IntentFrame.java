package com.yayatechandinnovations.yayaagentic.core;

import java.util.List;
import java.util.Map;

/**
 * Structured snapshot of what the user is currently trying to accomplish.
 * Rendered into the prompt so the LLM does not have to re-derive intent
 * every turn, and updated by the {@code IntentTracker}. See design §6.3.
 */
public record IntentFrame(
        String label,
        Map<String, Object> slots,
        List<ParkedIntent> parkedStack
) {
    public record ParkedIntent(String label, Map<String, Object> slots, String reason) {}

    public static IntentFrame empty() {
        return new IntentFrame(null, Map.of(), List.of());
    }
}
