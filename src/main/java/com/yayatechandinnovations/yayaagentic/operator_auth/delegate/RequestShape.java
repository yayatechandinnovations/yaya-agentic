package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Layer 1 of the delegate config — how yaya-agentic shapes the outbound
 * HTTP request. See {@code docs/design/operator-auth-design.md} §5.1.
 *
 * <p>Three reserved headers ({@code X-Yaya-Source},
 * {@code X-Yaya-Source-Secret}, {@code X-Yaya-Attempt-Id}) are always
 * added by the invoker regardless of what's in {@link #headers}; an
 * operator override on those keys is silently dropped.</p>
 */
public record RequestShape(
        String method,
        Map<String, String> headers,
        Body body
) {
    public static RequestShape defaults() {
        return new RequestShape(
                "POST",
                Map.of(),
                new Body(BodyFormat.JSON,
                        "{\"username\":\"{{username}}\",\"password\":\"{{password}}\"}"));
    }

    /** Defensive copy + null safety on the way in. */
    public RequestShape {
        method = (method == null || method.isBlank()) ? "POST" : method.toUpperCase();
        headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        body = body == null ? defaults().body() : body;
    }

    public enum BodyFormat { JSON, FORM, BASIC_AUTH, NONE }

    public record Body(BodyFormat format, String template) {
        public Body {
            if (format == null) format = BodyFormat.JSON;
        }
    }
}
