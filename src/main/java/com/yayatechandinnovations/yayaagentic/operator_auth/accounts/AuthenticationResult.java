package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;

import java.time.OffsetDateTime;

/**
 * What {@code OperatorAccountService.login} hands back: a verified {@link
 * User} plus an {@link Operator} ready for the existing session-minting path.
 * The {@link #expiresAt} mirrors what {@code OperatorSessionService.create}
 * would produce; the controller still issues the cookie.
 */
public record AuthenticationResult(
        User user,
        Operator operator,
        OffsetDateTime expiresAt
) {
}
