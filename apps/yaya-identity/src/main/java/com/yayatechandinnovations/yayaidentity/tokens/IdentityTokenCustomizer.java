package com.yayatechandinnovations.yayaidentity.tokens;

import com.yayatechandinnovations.yayaidentity.organizations.Membership;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Stamps identity claims into issued access tokens: {@code org} (from membership)
 * and the scoped {@code roles} claim (from role assignments in that org) so a
 * relying party can do coarse PEP checks without a PDP round-trip (FR-3.5).
 * Machine (client-credentials) principals have no membership, so they get neither
 * — keeping service tokens lean.
 */
@Component
public class IdentityTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final OrganizationService organizations;
    private final RoleAssignmentService assignments;

    public IdentityTokenCustomizer(OrganizationService organizations,
                                   RoleAssignmentService assignments) {
        this.organizations = organizations;
        this.assignments = assignments;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        // Audience binding (#6 / §5.6): a token is valid only at the app(s) it's for.
        // Applies to service (client-credentials) and exchanged tokens alike.
        if (context.getRegisteredClient() != null) {
            List<String> audiences = context.getRegisteredClient().getClientSettings()
                    .getSetting(com.yayatechandinnovations.yayaidentity.clients.DbRegisteredClientRepository.AUDIENCES_SETTING);
            if (audiences != null && !audiences.isEmpty()) {
                context.getClaims().audience(audiences);
            }
        }

        UserId user = new UserId(context.getPrincipal().getName());
        List<Membership> memberships = organizations.orgsOf(user);
        if (memberships.isEmpty()) {
            return;
        }
        // v1: a user's primary org. Multi-org selection (an org switcher / an
        // `org` request param) is a later refinement.
        OrgId org = memberships.get(0).org();
        context.getClaims().claim("org", org.value());

        List<Map<String, String>> roles = assignments.tokenRoles(user, org);
        if (!roles.isEmpty()) {
            context.getClaims().claim("roles", roles);
        }
    }
}
