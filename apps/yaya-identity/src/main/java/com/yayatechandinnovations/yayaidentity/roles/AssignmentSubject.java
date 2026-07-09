package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.platform.Ids.GroupId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;

/** Who a role is assigned to — a user directly, or a group (applying to all members). */
public record AssignmentSubject(Kind kind, String id) {

    public enum Kind { USER, GROUP }

    public static AssignmentSubject user(UserId user) {
        return new AssignmentSubject(Kind.USER, user.value());
    }

    public static AssignmentSubject group(GroupId group) {
        return new AssignmentSubject(Kind.GROUP, group.value());
    }
}
