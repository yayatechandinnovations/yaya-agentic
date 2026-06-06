package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.core.Principal;

import java.util.Optional;

/**
 * Verifies the caller's identity. Implementations contribute to the
 * {@code AuthenticatorChain}; the chain picks the first that recognises the
 * incoming request.
 *
 * <p>Concrete authenticators should implement {@link #tryAuthenticate} — the
 * chain calls this. {@link #authenticate} stays for direct invocation in
 * legacy paths and defaults to a throwing wrapper around
 * {@code tryAuthenticate}.</p>
 */
public interface Authenticator {

    /** Stable identifier used in {@code auth_bindings.authenticator_ref}. */
    String name();

    /**
     * Attempt to authenticate. Return an empty Optional when this
     * authenticator does not recognise the request (e.g. no matching header),
     * letting the chain try the next one. Throw {@link AuthenticationException}
     * when this authenticator DOES recognise the request but the credentials
     * fail validation — the chain stops on that.
     */
    Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException;

    default Principal authenticate(AuthContext ctx) throws AuthenticationException {
        return tryAuthenticate(ctx).orElseThrow(() ->
                new AuthenticationException(name() + ": no credentials presented"));
    }
}
