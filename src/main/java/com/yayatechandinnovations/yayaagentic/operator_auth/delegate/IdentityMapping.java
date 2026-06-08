package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

/**
 * Layer 3 of the delegate config — extracting operator identity from a
 * successful response. See {@code docs/design/operator-auth-design.md} §5.3.
 *
 * <p>{@code subjectPath} unset = trust the typed username; configured
 * but unresolvable on success = DENY with {@code identity_extraction_failed}
 * (prevents phantom-operator minting when an endpoint 200s on empty body).</p>
 */
public record IdentityMapping(
        String subjectPath,
        String displayNamePath,
        String attributesPath
) {
    public static IdentityMapping defaults() {
        return new IdentityMapping(null, null, null);
    }
}
