package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.tool.SuggestionRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates per-tool {@link SuggestionRule}s against a tool RESULT (trusted,
 * schema-validated data) and renders the surviving chips. Grammar kept
 * deliberately tiny (owner-cockpit design §4.1):
 *
 * <pre>
 *   when  := path OP literal
 *   path  := $.seg(.seg | [idx])* [.length]
 *   OP    := == | != | > | >= | < | <=
 *   literal := number | true | false | 'single quoted string'
 * </pre>
 *
 * {@code {{$.path}}} in label/send interpolates values from the same result.
 * Everything fails CLOSED: an unparsable rule, a missing path, or a type
 * mismatch simply drops the chip — suggestions must never break a turn.
 */
public class SuggestionRuleEvaluator {

    /** A rendered chip: {@code label} is shown, {@code send} is what tapping it sends. */
    public record Chip(String label, String send) {}

    private static final Pattern WHEN = Pattern.compile(
            "^\\s*(\\$[.\\[\\]\\w'\\-]*?)\\s*(==|!=|>=|<=|>|<)\\s*(.+?)\\s*$");
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{(\\$[.\\[\\]\\w'\\-]*?)}}");
    private static final int MAX_CHIPS = 4;

    public List<Chip> evaluate(List<SuggestionRule> rules, Object resultValue) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<Chip> chips = new ArrayList<>();
        for (SuggestionRule rule : rules) {
            if (chips.size() >= MAX_CHIPS) {
                break;
            }
            try {
                if (rule.when() != null && !rule.when().isBlank()
                        && !holds(rule.when(), resultValue)) {
                    continue;
                }
                String label = render(rule.label(), resultValue);
                String send = render(rule.send(), resultValue);
                if (label == null || send == null) {
                    continue; // an unresolved template drops the chip, fail closed
                }
                chips.add(new Chip(label, send));
            } catch (RuntimeException ignored) {
                // fail closed — a broken rule never breaks the turn
            }
        }
        return chips;
    }

    // ------------------------------------------------------------------
    // Predicate
    // ------------------------------------------------------------------

    private boolean holds(String when, Object root) {
        Matcher m = WHEN.matcher(when);
        if (!m.matches()) {
            return false;
        }
        String path = m.group(1);
        String op = m.group(2);
        String rawLiteral = m.group(3).trim();

        boolean wantsLength = path.endsWith(".length");
        Object value = navigate(root, wantsLength
                ? path.substring(0, path.length() - ".length".length())
                : path);
        if (wantsLength) {
            if (value instanceof List<?> l) value = l.size();
            else if (value instanceof String s) value = s.length();
            else if (value instanceof Map<?, ?> mp) value = mp.size();
            else return false;
        }

        Object literal = parseLiteral(rawLiteral);
        if (value instanceof Number vn && literal instanceof Number ln) {
            int cmp = Double.compare(vn.doubleValue(), ln.doubleValue());
            return switch (op) {
                case "==" -> cmp == 0;
                case "!=" -> cmp != 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                default -> false;
            };
        }
        // Non-numeric comparisons support equality only.
        boolean equal = value == null ? literal == null
                : String.valueOf(value).equals(String.valueOf(literal));
        return switch (op) {
            case "==" -> equal;
            case "!=" -> !equal;
            default -> false;
        };
    }

    private static Object parseLiteral(String raw) {
        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1);
        }
        if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(raw)) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // ------------------------------------------------------------------
    // Templating
    // ------------------------------------------------------------------

    /** Null when any placeholder fails to resolve (fail closed). */
    private String render(String template, Object root) {
        if (template == null) {
            return null;
        }
        Matcher m = TEMPLATE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            boolean wantsLength = path.endsWith(".length");
            Object value = navigate(root, wantsLength
                    ? path.substring(0, path.length() - ".length".length())
                    : path);
            if (wantsLength) {
                if (value instanceof List<?> l) value = l.size();
                else if (value instanceof String s) value = s.length();
                else return null;
            }
            if (value == null) {
                return null;
            }
            String rendered = value instanceof Double d && d == Math.floor(d) && !d.isInfinite()
                    ? String.valueOf(d.longValue())
                    : String.valueOf(value);
            m.appendReplacement(out, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Path navigation over Maps/Lists (the shape tool results carry)
    // ------------------------------------------------------------------

    static Object navigate(Object root, String path) {
        if (path == null || !path.startsWith("$")) {
            return null;
        }
        Object current = root;
        int i = 1; // skip '$'
        while (i < path.length() && current != null) {
            char c = path.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                String key = path.substring(i + 1, end);
                current = current instanceof Map<?, ?> m ? m.get(key) : null;
                i = end;
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    return null;
                }
                String idxRaw = path.substring(i + 1, close).trim();
                if (idxRaw.startsWith("'") && idxRaw.endsWith("'") && idxRaw.length() >= 2) {
                    String key = idxRaw.substring(1, idxRaw.length() - 1);
                    current = current instanceof Map<?, ?> m ? m.get(key) : null;
                } else {
                    try {
                        int idx = Integer.parseInt(idxRaw);
                        current = current instanceof List<?> l && idx >= 0 && idx < l.size()
                                ? l.get(idx) : null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                i = close + 1;
            } else {
                return null;
            }
        }
        return current;
    }
}
