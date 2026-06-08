package com.yayatechandinnovations.yayaagentic.operator_auth;

import java.util.Optional;

/**
 * Verifies operator credentials. Implementations contribute to
 * {@code OperatorAuthenticatorChain}; the chain consults each in {@code @Order}
 * and returns the first that recognises the request.
 *
 * <p>The {@code Optional}-or-throw contract mirrors {@code Authenticator} in
 * the end-user plane — but the two SPIs never share types or chains.</p>
 */
public interface OperatorAuthenticator {

    /** Stable identifier — recorded in audit, used by the admin-UI dropdown. */
    String name();

    /**
     * Attempt to authenticate.
     * <ul>
     *   <li>{@code Optional.empty()} → "this strategy isn't applicable" (chain continues)</li>
     *   <li>throw → "applicable AND denied" (chain stops, controller returns 401)</li>
     *   <li>present → verified operator</li>
     * </ul>
     */
    Optional<Operator> tryAuthenticate(OperatorCredentials creds) throws OperatorAuthenticationException;
}
