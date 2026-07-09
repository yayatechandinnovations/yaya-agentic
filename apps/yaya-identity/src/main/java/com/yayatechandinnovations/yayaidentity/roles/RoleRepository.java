package com.yayatechandinnovations.yayaidentity.roles;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<RoleEntity, String> {

    Optional<RoleEntity> findByOrgIdIsNullAndName(String name);

    Optional<RoleEntity> findByOrgIdAndName(String orgId, String name);

    List<RoleEntity> findByOrgId(String orgId);
}
