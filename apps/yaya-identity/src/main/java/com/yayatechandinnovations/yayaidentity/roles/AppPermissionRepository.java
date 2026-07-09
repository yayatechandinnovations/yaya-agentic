package com.yayatechandinnovations.yayaidentity.roles;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppPermissionRepository extends JpaRepository<AppPermissionEntity, AppPermissionEntity.Key> {

    List<AppPermissionEntity> findByIdApp(String app);
}
