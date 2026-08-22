package com.yayatechandinnovations.yayaagentic.tool;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;

import java.util.List;

/**
 * Typed, versioned tool record. {@code inputSchema} / {@code outputSchema}
 * are JSON Schema documents (serialized) — kept as raw strings here to keep
 * the SPI free of a schema-library dependency.
 *
 * <p>{@code captures} and {@code bindings} drive
 * {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory}
 * integration (M2.10 — see {@code docs/design/entity-memory-design.md}).
 * Both default to empty so tools that don't use entity memory behave
 * exactly as before.</p>
 */
public record ToolDescriptor(
        Ids.ToolId id,
        String inputSchemaJson,
        String outputSchemaJson,
        PermissionRequirement requires,
        ToolHandlerRef handler,
        ToolPolicy policy,
        List<EntityCapture> captures,
        List<EntityBinding> bindings,
        List<SuggestionRule> suggestions
) {

    public ToolDescriptor {
        captures = captures == null ? List.of() : List.copyOf(captures);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** Compat constructor for tools without suggestion rules. */
    public ToolDescriptor(Ids.ToolId id, String inputSchemaJson, String outputSchemaJson,
                          PermissionRequirement requires, ToolHandlerRef handler, ToolPolicy policy,
                          List<EntityCapture> captures, List<EntityBinding> bindings) {
        this(id, inputSchemaJson, outputSchemaJson, requires, handler, policy,
                captures, bindings, List.of());
    }

    /** Compat constructor for tools that don't declare entity-memory hooks. */
    public ToolDescriptor(Ids.ToolId id, String inputSchemaJson, String outputSchemaJson,
                          PermissionRequirement requires, ToolHandlerRef handler, ToolPolicy policy) {
        this(id, inputSchemaJson, outputSchemaJson, requires, handler, policy,
                List.of(), List.of(), List.of());
    }
}
