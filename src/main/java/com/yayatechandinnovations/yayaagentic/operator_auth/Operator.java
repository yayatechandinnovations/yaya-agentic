package com.yayatechandinnovations.yayaagentic.operator_auth;

import java.time.Instant;
import java.util.Map;

/**
 * A verified console operator. Distinct from {@code core.Principal} — the
 * two planes deliberately do not share an identity type. See
 * {@code docs/design/operator-auth-design.md} §2 and §4.
 *
 * <p>{@code subject} is whatever the authenticating strategy returned (an
 * email, a username, a JSONPath-extracted id). It is the audit identity for
 * this operator; it is NOT a database key. v1 has no operators table.</p>
 */
public record Operator(
        String subject,
        String displayName,
        Source source,
        Map<String, Object> attributes,
        Instant verifiedAt
) {
    public enum Source { BOOTSTRAP, HTTP_DELEGATE }
}
