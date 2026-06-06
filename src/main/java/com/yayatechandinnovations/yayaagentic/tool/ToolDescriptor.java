package com.yayatechandinnovations.yayaagentic.tool;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;

/**
 * Typed, versioned tool record. {@code inputSchema} / {@code outputSchema}
 * are JSON Schema documents (serialized) — kept as raw strings here to keep
 * the SPI free of a schema-library dependency.
 */
public record ToolDescriptor(
        Ids.ToolId id,
        String inputSchemaJson,
        String outputSchemaJson,
        PermissionRequirement requires,
        ToolHandlerRef handler,
        ToolPolicy policy
) {}
