package com.yayatechandinnovations.yayaidentity.roles;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, RolePermissionEntity.Key> {

    List<RolePermissionEntity> findByIdRoleId(String roleId);
}
