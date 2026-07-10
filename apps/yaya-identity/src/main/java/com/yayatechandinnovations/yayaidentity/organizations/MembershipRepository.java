package com.yayatechandinnovations.yayaidentity.organizations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MembershipRepository extends JpaRepository<MembershipEntity, MembershipEntity.Key> {

    List<MembershipEntity> findByIdOrgId(String orgId);

    List<MembershipEntity> findByIdUserId(String userId);

    long countByIdOrgId(String orgId);
}
