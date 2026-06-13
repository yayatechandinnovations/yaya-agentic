package com.yayatechandinnovations.yayaagentic.auth.playground;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Operator-supplied end-user credential for a playground session. See
 * {@code docs/design/playground-actas-auth-design.md} §4.
 *
 * <p>The operator authenticates the admin console via a separate plane
 * ({@link com.yayatechandinnovations.yayaagentic.operator_auth.Operator}).
 * When they start a session against a tenant whose profile calls HTTP tools
 * with {@code AuthForwarding.PRINCIPAL_TOKEN}, the runtime needs an
 * end-user credential — the operator cookie is not one. {@code ActAs}
 * carries that credential in a typed, audit-safe shape that never enters
 * {@code hints} or prompt material.
 *
 * <p>v1 only ships {@link RawToken}. The sealed shape leaves room for
 * {@code SignedIdentity} and {@code ServiceToken} variants without changing
 * the request DTO — each follow-up variant gets a new permitted record and
 * an exhaustive {@code switch} arm in {@link ActAsMaterializer}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActAs.RawToken.class, name = "raw-token")
})
public sealed interface ActAs permits ActAs.RawToken {

    /**
     * The operator pastes a credential they already have — the lowest-trust
     * shape, requires no special permission. Materialized to an
     * {@code Authorization} header on the inbound {@code AuthContext} so
     * whichever runtime {@code Authenticator} matches (typically
     * {@code OidcAuthenticator}) verifies it normally.
     *
     * <p>{@code scheme} is restricted to a small allow-list (see
     * {@link ActAsMaterializer}); arbitrary schemes are rejected to keep
     * the playground from being a fuzzer for the tenant's auth.
     */
    record RawToken(String scheme, String token) implements ActAs {}
}
