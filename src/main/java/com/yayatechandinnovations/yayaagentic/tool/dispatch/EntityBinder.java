package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.tool.BindingMode;
import com.yayatechandinnovations.yayaagentic.tool.EntityBinding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies {@link EntityBinding}s declared on a tool to the LLM-proposed
 * args BEFORE the Authorizer chain runs. Pure function (no side effects on
 * memory) so it's unit-testable without a session fixture.
 */
public final class EntityBinder {

    private EntityBinder() {}

    /**
     * Returns a possibly-expanded copy of {@code args} with bindings applied.
     * Also reports which arg names were filled from memory so the engine
     * can put them into the tool-call audit row.
     *
     * <p>For {@link BindingMode#REQUIRED} bindings with no value in memory
     * AND no value in {@code args}, the arg is left absent — the existing
     * elicitation flow detects the missing required field downstream.</p>
     */
    public static Result apply(Map<String, Object> args,
                               List<EntityBinding> bindings,
                               EntityMemory memory,
                               Ids.SessionId session) {
        Map<String, Object> result = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
        List<String> bound = new java.util.ArrayList<>();
        if (bindings == null || bindings.isEmpty() || memory == null || session == null) {
            return new Result(Map.copyOf(result), List.of());
        }
        for (EntityBinding b : bindings) {
            Optional<EntityRef> ref = memory.resolve(session, b.entityName());
            switch (b.mode()) {
                case FILL_IF_MISSING, REQUIRED -> {
                    if (!isPresent(result, b.argName()) && ref.isPresent()) {
                        result.put(b.argName(), ref.get().value());
                        bound.add(b.argName());
                    }
                }
                case OVERRIDE -> {
                    if (ref.isPresent()) {
                        result.put(b.argName(), ref.get().value());
                        bound.add(b.argName());
                    }
                }
            }
        }
        return new Result(Map.copyOf(result), List.copyOf(bound));
    }

    private static boolean isPresent(Map<String, Object> args, String key) {
        if (!args.containsKey(key)) return false;
        Object v = args.get(key);
        if (v == null) return false;
        return !(v instanceof CharSequence cs && cs.toString().isBlank());
    }

    /**
     * @param args   the expanded args map to pass downstream
     * @param bound  arg names that were filled from memory (for audit)
     */
    public record Result(Map<String, Object> args, List<String> bound) {}
}
