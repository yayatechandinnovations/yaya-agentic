package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;

import java.time.Instant;

/** A user's tie to an org. Roles are assigned within an org/resource scope from I2. */
public record Membership(UserId user, OrgId org, Status status, Instant joinedAt) {

    public enum Status { ACTIVE, SUSPENDED }
}
