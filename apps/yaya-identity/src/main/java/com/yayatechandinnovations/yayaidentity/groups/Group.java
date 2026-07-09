package com.yayatechandinnovations.yayaidentity.groups;

import com.yayatechandinnovations.yayaidentity.platform.Ids.GroupId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;

public record Group(GroupId id, OrgId org, String name) {}
