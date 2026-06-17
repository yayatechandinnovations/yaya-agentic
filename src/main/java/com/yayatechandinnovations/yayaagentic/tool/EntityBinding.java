package com.yayatechandinnovations.yayaagentic.tool;

/**
 * Operator-declared instruction to fill a tool's input arg from
 * {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory}
 * before authorization + dispatch.
 * See {@code docs/design/entity-memory-design.md} §5 and §6.
 *
 * @param argName     the field in the tool's input schema
 * @param entityName  the {@code EntityMemory} key to read from
 * @param mode        how aggressively to fill (see {@link BindingMode})
 */
public record EntityBinding(String argName, String entityName, BindingMode mode) {

    public EntityBinding {
        if (argName == null || argName.isBlank()) {
            throw new IllegalArgumentException("EntityBinding.argName must be non-blank");
        }
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("EntityBinding.entityName must be non-blank");
        }
        if (mode == null) mode = BindingMode.FILL_IF_MISSING;
    }

    public static EntityBinding fillIfMissing(String argName, String entityName) {
        return new EntityBinding(argName, entityName, BindingMode.FILL_IF_MISSING);
    }

    public static EntityBinding override(String argName, String entityName) {
        return new EntityBinding(argName, entityName, BindingMode.OVERRIDE);
    }
}
