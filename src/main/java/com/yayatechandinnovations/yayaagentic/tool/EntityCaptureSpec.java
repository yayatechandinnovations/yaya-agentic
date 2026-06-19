package com.yayatechandinnovations.yayaagentic.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityKind;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityScope;

import java.util.Locale;
import java.util.Optional;

/**
 * Wire / config form of {@link EntityCapture}. This is the shape stored as
 * JSON in {@code tools_registry.captures_json} and exchanged on the admin
 * REST surface.
 *
 * <p>Why a separate type: {@link EntityCapture} uses {@code Optional<Ids.ToolId>}
 * for the rollback / describe references, and {@code Ids.ToolId} is a record
 * that Jackson serializes as {@code {"value":"..."}}. The wire form is
 * deliberately flat — enums as strings, tool ids as nullable strings — so the
 * JSON column matches the admin DTO and an operator-readable curl response.
 *
 * <p>Conversion goes both ways via {@link #toRuntime()} and
 * {@link #fromRuntime(EntityCapture)}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntityCaptureSpec(
        String name,
        String jsonPointer,
        String scope,
        String kind,
        String onConflict,
        boolean visibleToLlm,
        String rollbackTool,
        String describeTool
) {

    public EntityCapture toRuntime() {
        return new EntityCapture(
                name,
                jsonPointer,
                scope == null || scope.isBlank()
                        ? EntityScope.SESSION
                        : EntityScope.valueOf(scope.toUpperCase(Locale.ROOT)),
                kind == null || kind.isBlank()
                        ? EntityKind.SINGLETON
                        : EntityKind.valueOf(kind.toUpperCase(Locale.ROOT)),
                onConflict == null || onConflict.isBlank()
                        ? OnConflict.ASK_USER
                        : OnConflict.valueOf(onConflict.toUpperCase(Locale.ROOT)),
                visibleToLlm,
                rollbackTool == null || rollbackTool.isBlank()
                        ? Optional.empty()
                        : Optional.of(new Ids.ToolId(rollbackTool)),
                describeTool == null || describeTool.isBlank()
                        ? Optional.empty()
                        : Optional.of(new Ids.ToolId(describeTool)));
    }

    public static EntityCaptureSpec fromRuntime(EntityCapture c) {
        return new EntityCaptureSpec(
                c.name(),
                c.jsonPointer(),
                c.scope().name(),
                c.kind().name(),
                c.onConflict().name(),
                c.visibleToLlm(),
                c.rollbackTool().map(Ids.ToolId::value).orElse(null),
                c.describeTool().map(Ids.ToolId::value).orElse(null));
    }
}
