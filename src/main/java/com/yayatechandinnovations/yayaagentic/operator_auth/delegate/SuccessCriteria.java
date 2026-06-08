package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

import java.util.List;

/**
 * Layer 2 of the delegate config — predicates over the response. AND
 * semantics: every configured criterion must match for the response to
 * be treated as ALLOW. See {@code docs/design/operator-auth-design.md} §5.2.
 *
 * <p>The set is intentionally bounded — status code, JSONPath existence,
 * JSONPath equality. No general expression language; if a customer's
 * existing endpoint can't be expressed this way, they wrap it.</p>
 */
public record SuccessCriteria(
        List<Integer> statusIn,
        String jsonPathExists,
        List<JsonPathEquals> jsonPathEquals
) {
    public static SuccessCriteria defaults() {
        return new SuccessCriteria(List.of(200, 204), null, List.of());
    }

    public SuccessCriteria {
        statusIn = (statusIn == null || statusIn.isEmpty()) ? List.of(200, 204) : List.copyOf(statusIn);
        jsonPathEquals = jsonPathEquals == null ? List.of() : List.copyOf(jsonPathEquals);
    }

    /** A single {path, value} predicate. {@code value} of {@code null} means "field is JSON null". */
    public record JsonPathEquals(String path, Object value) {}
}
