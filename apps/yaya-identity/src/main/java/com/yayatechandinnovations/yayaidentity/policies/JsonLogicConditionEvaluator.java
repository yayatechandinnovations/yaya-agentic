package com.yayatechandinnovations.yayaidentity.policies;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A small, sandboxed JSON-logic evaluator (design §7). Supports a fixed operator
 * set — {@code var, ==, !=, >, >=, <, <=, and, or, !, in} — over the
 * subject/resource/env attribute bags. Unknown operators are rejected; there is
 * no I/O and no arbitrary code, so a condition can never do more than compute a
 * boolean from the provided data.
 */
@Component
public class JsonLogicConditionEvaluator implements ConditionEvaluator {

    private final ObjectMapper mapper;

    public JsonLogicConditionEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean matches(String conditionJson, Map<String, Object> data) {
        if (conditionJson == null || conditionJson.isBlank()) {
            return true;   // unconditional
        }
        try {
            Object root = mapper.readValue(conditionJson, Object.class);
            return truthy(eval(root, data));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid policy condition: " + e.getMessage(), e);
        }
    }

    private Object eval(Object node, Map<String, Object> data) {
        if (node instanceof Map<?, ?> m) {
            if (m.size() != 1) {
                throw new IllegalArgumentException("a condition node must have exactly one operator");
            }
            Map.Entry<?, ?> e = m.entrySet().iterator().next();
            return apply((String) e.getKey(), e.getValue(), data);
        }
        return node;   // scalar or literal array
    }

    private Object apply(String op, Object arg, Map<String, Object> data) {
        return switch (op) {
            case "var" -> resolveVar(arg, data);
            case "==" -> { List<Object> a = args(arg, data); yield eq(a.get(0), a.get(1)); }
            case "!=" -> { List<Object> a = args(arg, data); yield !eq(a.get(0), a.get(1)); }
            case ">" -> compare(arg, data) > 0;
            case ">=" -> compare(arg, data) >= 0;
            case "<" -> compare(arg, data) < 0;
            case "<=" -> compare(arg, data) <= 0;
            case "and" -> args(arg, data).stream().allMatch(JsonLogicConditionEvaluator::truthy);
            case "or" -> args(arg, data).stream().anyMatch(JsonLogicConditionEvaluator::truthy);
            case "!" -> !truthy(eval(single(arg), data));
            case "in" -> { List<Object> a = args(arg, data); yield inOp(a.get(0), a.get(1)); }
            default -> throw new IllegalArgumentException("unsupported operator: " + op);
        };
    }

    private Object resolveVar(Object arg, Map<String, Object> data) {
        String path;
        Object dflt = null;
        if (arg instanceof List<?> l) {
            path = String.valueOf(l.get(0));
            if (l.size() > 1) dflt = l.get(1);
        } else {
            path = String.valueOf(arg);
        }
        Object current = data;
        for (String seg : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) return dflt;
            current = m.get(seg);
        }
        return current == null ? dflt : current;
    }

    private List<Object> args(Object arg, Map<String, Object> data) {
        List<Object> out = new ArrayList<>();
        if (arg instanceof List<?> l) {
            for (Object o : l) out.add(eval(o, data));
        } else {
            out.add(eval(arg, data));
        }
        return out;
    }

    private Object single(Object arg) {
        return arg instanceof List<?> l && !l.isEmpty() ? l.get(0) : arg;
    }

    private int compare(Object arg, Map<String, Object> data) {
        List<Object> a = args(arg, data);
        return Double.compare(toDouble(a.get(0)), toDouble(a.get(1)));
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("expected a number, got: " + o);
    }

    private static boolean eq(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return Objects.equals(a, b);
    }

    private static boolean inOp(Object needle, Object haystack) {
        return haystack instanceof List<?> l && l.stream().anyMatch(o -> eq(o, needle));
    }

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof String s) return !s.isEmpty();
        if (o instanceof List<?> l) return !l.isEmpty();
        return true;
    }
}
