package com.yayatechandinnovations.yayaidentity.api;

import com.yayatechandinnovations.yayaidentity.policies.Decision;

/**
 * Wire form of a PDP decision. Both reasons are returned to the calling PEP (a
 * trusted service): the app shows {@code userSafeReason} to the end user and
 * logs {@code auditReason} — the two are always different (#3).
 */
public record DecisionDto(String decision,
                          String userSafeReason,
                          String auditReason,
                          String obligationsJson) {

    public static DecisionDto of(Decision d) {
        return switch (d) {
            case Decision.Permit p -> new DecisionDto("PERMIT", null, null, p.obligationsJson());
            case Decision.Deny x -> new DecisionDto("DENY", x.userSafeReason(), x.auditReason(), null);
        };
    }
}
