package com.yayatechandinnovations.yayaagentic.auth.policy;

import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Token-scope check. DENY when any required scope is missing from the
 * principal's scope set. Required scopes empty = ALLOW.
 */
@Component
@Order(10)
public class ScopeAuthorizer implements Authorizer {

    @Override
    public AuthzDecision authorize(Principal principal,
                                   PermissionRequirement requirement,
                                   Object args,
                                   AuthzContext ctx) {
        if (requirement == null || requirement.requiredScopes() == null
                || requirement.requiredScopes().isEmpty()) {
            return AuthzDecision.Allow.none();
        }
        Set<String> have = principal == null || principal.scopes() == null
                ? Set.of() : principal.scopes();
        for (String needed : requirement.requiredScopes()) {
            if (!have.contains(needed)) {
                return new AuthzDecision.Deny(
                        "this action needs a permission your session doesn't have",
                        "scope check: principal=" + (principal == null ? null : principal.subject())
                                + " missing scope=" + needed
                                + " have=" + have);
            }
        }
        return AuthzDecision.Allow.none();
    }
}
