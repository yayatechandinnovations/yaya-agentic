package com.yayatechandinnovations.yayaagentic.auth.policy;

import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Stub for the OPA sidecar adapter. M1 binding only: always ALLOW. The real
 * HTTP call to OPA + policy trace handling lands in M3 alongside the eval
 * harness (design §16 q5).
 */
@Component
@Order(30)
public class OpaAuthorizer implements Authorizer {

    @Override
    public AuthzDecision authorize(Principal principal,
                                   PermissionRequirement requirement,
                                   Object args,
                                   AuthzContext ctx) {
        return AuthzDecision.Allow.none();
    }
}
