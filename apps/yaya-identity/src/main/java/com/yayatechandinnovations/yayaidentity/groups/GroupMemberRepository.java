package com.yayatechandinnovations.yayaidentity.groups;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupMemberRepository extends JpaRepository<GroupMemberEntity, GroupMemberEntity.Key> {

    List<GroupMemberEntity> findByIdUserId(String userId);

    List<GroupMemberEntity> findByIdGroupId(String groupId);
}
