package com.yayatechandinnovations.yayaagentic.tool;

/**
 * A result-aware suggestion chip rule (owner-cockpit design; SL
 * {@code yaya-owner-ai-experience-v2-design.md} §4.1). Declared per TOOL so
 * chips reflect what the dispatch actually returned, replacing the static
 * per-capability {@code followUpHints} that produced context-blind chips.
 *
 * <p>{@code when} is a tiny predicate over the tool RESULT (trusted,
 * schema-validated data — never LLM output): {@code $.path[i].field}
 * navigation, optional trailing {@code .length} on arrays/strings, one of
 * {@code == != > >= < <=}, and a literal (number, {@code true|false}, or a
 * single-quoted string). {@code label}/{@code send} may interpolate
 * {@code {{$.path}}} values from the same result. Engine-evaluated,
 * engine-emitted — the LLM never authors chips (non-negotiable #8).</p>
 */
public record SuggestionRule(String when, String label, String send) {

    public SuggestionRule {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("SuggestionRule.label must be non-blank");
        }
        if (send == null || send.isBlank()) {
            throw new IllegalArgumentException("SuggestionRule.send must be non-blank");
        }
    }
}
