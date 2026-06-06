package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;

/**
 * Decides ALLOW/DENY for a (principal, requirement, args) triple. Multiple
 * authorizers are composed into a chain that short-circuits on first DENY.
 * The chain is the only enforcement point — tools never check policy directly.
 */
public interface Authorizer {
    AuthzDecision authorize(Principal principal,
                            PermissionRequirement requirement,
                            Object args,
                            AuthzContext ctx);
}
