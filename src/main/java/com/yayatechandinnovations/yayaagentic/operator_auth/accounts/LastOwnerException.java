package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

/**
 * Demote or remove would leave a workspace without an OWNER. Controller
 * advice maps this to 409 Conflict with the {@link #getMessage()} as the
 * user-safe reason — the UI surfaces it verbatim.
 *
 * <p>See PRD US-26 and design §11 ("Last-OWNER protection").</p>
 */
public class LastOwnerException extends RuntimeException {

    public LastOwnerException(String userSafeReason) {
        super(userSafeReason);
    }

    public static LastOwnerException forRemoveSelf() {
        return new LastOwnerException(
                "A workspace must have at least one owner. Promote another member to owner before removing yourself.");
    }

    public static LastOwnerException forDemoteSelf() {
        return new LastOwnerException(
                "A workspace must have at least one owner. Promote another member to owner before changing your role.");
    }
}
