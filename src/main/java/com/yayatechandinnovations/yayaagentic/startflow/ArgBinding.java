package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.tool.BindingMode;

/**
 * Declarative rule for filling ONE tool argument from a {@link ValueSource}.
 * Reuses {@link BindingMode} so authors learn the fill semantics once (shared
 * with tool-declared {@code EntityBinding}s). See
 * {@code docs/design/session-start-flows.md} §5.2.
 *
 * @param argName must exist in the tool's input JSON Schema
 */
public record ArgBinding(String argName, ValueSource source, BindingMode mode) {

    public ArgBinding {
        if (argName == null || argName.isBlank()) {
            throw new IllegalArgumentException("ArgBinding.argName must be non-blank");
        }
        if (source == null) throw new IllegalArgumentException("ArgBinding.source must be set");
        if (mode == null) mode = BindingMode.FILL_IF_MISSING;
    }
}
