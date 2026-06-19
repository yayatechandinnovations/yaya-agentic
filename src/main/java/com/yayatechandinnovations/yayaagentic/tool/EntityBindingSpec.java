package com.yayatechandinnovations.yayaagentic.tool;

import java.util.Locale;

/**
 * Wire / config form of {@link EntityBinding}. Stored as JSON in
 * {@code tools_registry.bindings_json} and exchanged on the admin REST
 * surface. {@code mode} is the {@link BindingMode} enum name.
 */
public record EntityBindingSpec(String argName, String entityName, String mode) {

    public EntityBinding toRuntime() {
        return new EntityBinding(
                argName,
                entityName,
                mode == null || mode.isBlank()
                        ? BindingMode.FILL_IF_MISSING
                        : BindingMode.valueOf(mode.toUpperCase(Locale.ROOT)));
    }

    public static EntityBindingSpec fromRuntime(EntityBinding b) {
        return new EntityBindingSpec(b.argName(), b.entityName(), b.mode().name());
    }
}
