package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.RoleId;

import java.time.Instant;

/** A role granted to a subject within a scope (design §5.5). */
public record RoleAssignment(String id,
                             OrgId org,
                             AssignmentSubject subject,
                             RoleId role,
                             Scope scope,
                             Instant grantedAt) {}
