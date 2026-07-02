package com.yayatechandinnovations.yayaagentic.commerce.tool;

import java.util.Map;

/** Null-safe typed reads from an LLM tool-args map. Schema validation already ran. */
final class Args {

    private Args() {}

    static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static int intOr(Map<String, Object> args, String key, int fallback) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Long longOrNull(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> strMap(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (!(v instanceof Map<?, ?> m)) return Map.of();
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        ((Map<String, Object>) m).forEach((k, val) -> {
            if (val != null) out.put(String.valueOf(k), String.valueOf(val));
        });
        return out;
    }
}
