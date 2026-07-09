package com.yayatechandinnovations.yayaidentity.roles;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<PermissionEntity, String> {
}
