package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.PermissionKey;
import com.yayatechandinnovations.yayaidentity.platform.Ids.RoleId;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Roles and their permissions (design §5.4). System roles ({@code owner},
 * {@code admin}, {@code member}) ship built-in (org-less); orgs compose custom
 * roles from declared permissions.
 */
@Service
public class RoleService {

    private static final List<String> SYSTEM_ROLES = List.of("owner", "admin", "member");

    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final PermissionCatalogService catalog;

    public RoleService(RoleRepository roles, RolePermissionRepository rolePermissions,
                       PermissionCatalogService catalog) {
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.catalog = catalog;
    }

    @PostConstruct
    @Transactional
    public void bootstrapSystemRoles() {
        for (String name : SYSTEM_ROLES) {
            if (roles.findByOrgIdIsNullAndName(name).isEmpty()) {
                roles.save(new RoleEntity(
                        "role_sys_" + name, null, name, true, Instant.now()));
            }
        }
    }

    @Transactional
    public Role createRole(OrgId org, String name, Collection<String> permissionKeys) {
        RoleEntity role = new RoleEntity(
                "role_" + UUID.randomUUID().toString().replace("-", ""),
                org.value(), name, false, Instant.now());
        roles.save(role);
        for (String key : permissionKeys) {
            addPermission(new RoleId(role.getId()), key);
        }
        return role.toDomain();
    }

    @Transactional
    public void addPermission(RoleId role, String permissionKey) {
        new PermissionKey(permissionKey);                       // validate shape
        if (!catalog.exists(permissionKey)) {
            throw new IllegalArgumentException(
                    "unknown permission '" + permissionKey + "' — declare it in the app manifest first");
        }
        RolePermissionEntity.Key key = new RolePermissionEntity.Key(role.value(), permissionKey);
        if (!rolePermissions.existsById(key)) {
            rolePermissions.save(new RolePermissionEntity(key));
        }
    }

    @Transactional(readOnly = true)
    public Set<PermissionKey> permissionsOf(RoleId role) {
        return rolePermissions.findByIdRoleId(role.value()).stream()
                .map(e -> new PermissionKey(e.getId().permissionKey()))
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public Optional<Role> byId(RoleId role) {
        return roles.findById(role.value()).map(RoleEntity::toDomain);
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles(OrgId org) {
        return roles.findByOrgId(org.value()).stream().map(RoleEntity::toDomain).toList();
    }

    @Transactional(readOnly = true)
    public List<Role> systemRoles() {
        return roles.findByOrgIdIsNull().stream().map(RoleEntity::toDomain).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Role> systemRole(String name) {
        return roles.findByOrgIdIsNullAndName(name).map(RoleEntity::toDomain);
    }
}
