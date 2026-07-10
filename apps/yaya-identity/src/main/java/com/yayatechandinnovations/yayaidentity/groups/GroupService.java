package com.yayatechandinnovations.yayaidentity.groups;

import com.yayatechandinnovations.yayaidentity.platform.Ids.GroupId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GroupService {

    private final GroupRepository groups;
    private final GroupMemberRepository members;

    public GroupService(GroupRepository groups, GroupMemberRepository members) {
        this.groups = groups;
        this.members = members;
    }

    @Transactional
    public Group create(OrgId org, String name) {
        GroupEntity e = new GroupEntity(
                "grp_" + UUID.randomUUID().toString().replace("-", ""),
                org.value(), name, Instant.now());
        groups.save(e);
        return e.toDomain();
    }

    @Transactional(readOnly = true)
    public List<Group> listGroups(OrgId org) {
        return groups.findByOrgId(org.value()).stream().map(GroupEntity::toDomain).toList();
    }

    @Transactional
    public void addMember(GroupId group, UserId user) {
        members.save(new GroupMemberEntity(new GroupMemberEntity.Key(group.value(), user.value())));
    }

    @Transactional
    public void removeMember(GroupId group, UserId user) {
        members.deleteById(new GroupMemberEntity.Key(group.value(), user.value()));
    }

    /** The users in a group — the console's group-membership view. */
    @Transactional(readOnly = true)
    public List<UserId> membersOf(GroupId group) {
        return members.findByIdGroupId(group.value()).stream()
                .map(m -> new UserId(m.getId().userId()))
                .toList();
    }

    /** The groups a user belongs to — the input to scoped-permission resolution via groups. */
    @Transactional(readOnly = true)
    public List<GroupId> groupIdsOf(UserId user) {
        return members.findByIdUserId(user.value()).stream()
                .map(m -> new GroupId(m.getId().groupId()))
                .toList();
    }
}
