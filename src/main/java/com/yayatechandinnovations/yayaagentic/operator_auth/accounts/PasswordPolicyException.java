package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

/**
 * Password too short or lacking the required character-class diversity.
 * The controller advice surfaces the {@link #getMessage()} verbatim — the
 * UI's password-strength meter already shows the user what's missing, so
 * the server message can be more direct.
 */
public class PasswordPolicyException extends RuntimeException {
    public PasswordPolicyException(String reason) {
        super(reason);
    }
}
