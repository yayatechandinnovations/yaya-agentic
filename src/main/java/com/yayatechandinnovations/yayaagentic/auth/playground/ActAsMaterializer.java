package com.yayatechandinnovations.yayaagentic.auth.playground;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an operator-supplied {@link ActAs} spec into a runtime
 * {@link AuthContext} that looks identical to a production request. See
 * {@code docs/design/playground-actas-auth-design.md} §5.
 *
 * <p>When {@code actAs} is present:
 * <ul>
 *   <li>Operator-plane headers (session cookie, CSRF echo, operator-only
 *       headers) are <em>dropped</em> from the returned {@code AuthContext}
 *       so the runtime cannot accidentally promote an operator cookie into a
 *       {@code Principal}. The two-plane rule (operator-auth-design §2) is
 *       enforced here, not by the engine.</li>
 *   <li>The materialized credential is merged in as the headers the runtime
 *       {@code Authenticator} chain already knows how to verify.</li>
 * </ul>
 *
 * <p>When {@code actAs} is absent, the input is returned unchanged.
 */
@Component
public class ActAsMaterializer {

    /**
     * Schemes accepted on {@link ActAs.RawToken}. Restrictive on purpose —
     * see design §11 Q3. Adding a scheme is a one-line config decision, not
     * a let-the-operator-paste-anything posture.
     */
    static final Set<String> ALLOWED_RAW_SCHEMES = Set.of("Bearer", "Basic");

    /**
     * Header names removed from the runtime {@code AuthContext} whenever
     * {@code actAs} is present. These belong to the operator plane and must
     * never be visible to the runtime {@code Authenticator} chain.
     *
     * <p>Match is case-insensitive (HTTP header names are case-insensitive).
     */
    private static final Set<String> OPERATOR_PLANE_HEADERS_LOWER = Set.of(
            "cookie",
            "x-xsrf-token");

    public AuthContext applyIfPresent(AuthContext base, Optional<ActAs> actAs) {
        if (actAs == null || actAs.isEmpty()) return base;
        Map<String, String> headers = stripOperatorHeaders(base.headers());
        switch (actAs.get()) {
            case ActAs.RawToken raw -> applyRawToken(headers, raw);
        }
        return new AuthContext(base.tenant(), Map.copyOf(headers), base.alreadyVerified());
    }

    private void applyRawToken(Map<String, String> headers, ActAs.RawToken raw) {
        if (raw.token() == null || raw.token().isBlank()) {
            throw new InvalidActAsException("actAs.token must not be blank");
        }
        String scheme = normalizeScheme(raw.scheme());
        if (!ALLOWED_RAW_SCHEMES.contains(scheme)) {
            throw new InvalidActAsException(
                    "actAs.scheme '" + scheme + "' is not in the allow-list " + ALLOWED_RAW_SCHEMES);
        }
        headers.put(HttpHeaders.AUTHORIZATION, scheme + " " + raw.token());
    }

    private static String normalizeScheme(String scheme) {
        if (scheme == null || scheme.isBlank()) return "Bearer";
        String trimmed = scheme.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1).toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> stripOperatorHeaders(Map<String, String> in) {
        if (in == null || in.isEmpty()) return new HashMap<>();
        Map<String, String> out = new HashMap<>(in.size());
        for (var e : in.entrySet()) {
            if (e.getKey() == null) continue;
            if (OPERATOR_PLANE_HEADERS_LOWER.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Thrown when an {@link ActAs} spec is shaped wrong (blank token,
     * disallowed scheme). Extends {@link ResponseStatusException} so Spring's
     * default error pipeline returns 422 with the reason string — no extra
     * {@code @ControllerAdvice} required.
     */
    public static final class InvalidActAsException extends ResponseStatusException {
        public InvalidActAsException(String message) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
    }
}
