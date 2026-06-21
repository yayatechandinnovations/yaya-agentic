package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

/**
 * Token presented for verify-email or password-reset confirm is missing,
 * expired, already consumed, or malformed. The controller advice maps this
 * to 410 Gone with a generic body — never reveal which branch matched.
 */
public class AccountTokenException extends RuntimeException {
    public AccountTokenException(String reason) {
        super(reason);
    }
}
