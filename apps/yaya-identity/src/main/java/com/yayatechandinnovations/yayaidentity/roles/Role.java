package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.RoleId;

/** A named bundle of permissions. {@code org == null} marks a built-in system role. */
public record Role(RoleId id, OrgId org, String name, boolean system) {}
