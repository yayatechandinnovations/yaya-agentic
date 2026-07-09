package com.yayatechandinnovations.yayaidentity.groups;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupRepository extends JpaRepository<GroupEntity, String> {

    List<GroupEntity> findByOrgId(String orgId);
}
