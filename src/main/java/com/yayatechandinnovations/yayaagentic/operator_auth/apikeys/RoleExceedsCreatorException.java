package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;

/**
 * Raised when a creator tries to mint a key at a role above their own
 * (§6.2). 400 with code {@code role_exceeds_creator}.
 */
public class RoleExceedsCreatorException extends RuntimeException {
    public RoleExceedsCreatorException(Role requested, Role creator) {
        super("cannot mint a " + requested + " key as a " + creator);
    }
}
