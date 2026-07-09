package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Per-org MFA policy (design §11): when an org requires MFA, a user holding a
 * privileged role ({@code owner}/{@code admin}) there must have TOTP enrolled
 * before acting. This resolver is the check the login flow and the admin API
 * consult.
 */
@Service
public class MfaPolicyService {

    private static final Set<String> PRIVILEGED_ROLES = Set.of("owner", "admin");

    private final OrganizationService organizations;
    private final RoleAssignmentService assignments;
    private final CredentialService credentials;

    public MfaPolicyService(OrganizationService organizations, RoleAssignmentService assignments,
                            CredentialService credentials) {
        this.organizations = organizations;
        this.assignments = assignments;
        this.credentials = credentials;
    }

    /** True if the org requires MFA and the user holds a privileged role in it. */
    public boolean requiresMfa(UserId user, OrgId org) {
        if (!organizations.isMfaRequired(org)) {
            return false;
        }
        return assignments.tokenRoles(user, org).stream()
                .map(m -> m.get("role"))
                .anyMatch(PRIVILEGED_ROLES::contains);
    }

    /** True when the user must enrol MFA but hasn't — the login flow blocks until resolved. */
    public boolean mfaEnrollmentRequired(UserId user, OrgId org) {
        return requiresMfa(user, org) && !credentials.hasTotp(user);
    }
}
