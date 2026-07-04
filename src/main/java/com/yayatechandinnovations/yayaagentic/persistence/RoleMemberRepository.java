package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleMemberRepository
        extends JpaRepository<RoleMemberEntity, RoleMemberEntity.PK> {

    List<RoleMemberEntity> findByTenantIdAndRole(String tenantId, String role);
}
