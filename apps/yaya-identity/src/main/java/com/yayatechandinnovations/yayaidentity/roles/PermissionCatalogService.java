package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.platform.Ids.PermissionKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * The app-permission manifest (design §5.4, §9.4; US-30). An app declares its
 * permission catalog — keys {@code <app>.<resource>:<action>} — so roles can only
 * be composed from real, namespaced permissions and the admin console can offer
 * them. Declaration is idempotent.
 */
@Service
public class PermissionCatalogService {

    private final PermissionRepository permissions;
    private final AppPermissionRepository appPermissions;

    public PermissionCatalogService(PermissionRepository permissions,
                                    AppPermissionRepository appPermissions) {
        this.permissions = permissions;
        this.appPermissions = appPermissions;
    }

    @Transactional
    public void declare(String app, Collection<String> keys) {
        for (String key : keys) {
            PermissionKey validated = new PermissionKey(key);       // enforces the <app>.<resource>:<action> shape
            if (!validated.value().startsWith(app + ".")) {
                throw new IllegalArgumentException(
                        "permission '" + key + "' is not in the '" + app + "' namespace");
            }
            if (!permissions.existsById(key)) {
                permissions.save(new PermissionEntity(key));
            }
            AppPermissionEntity.Key manifestKey = new AppPermissionEntity.Key(app, key);
            if (!appPermissions.existsById(manifestKey)) {
                appPermissions.save(new AppPermissionEntity(manifestKey));
            }
        }
    }

    @Transactional(readOnly = true)
    public List<String> declaredFor(String app) {
        return appPermissions.findByIdApp(app).stream()
                .map(e -> e.getId().permissionKey())
                .toList();
    }

    /** Every declared permission across apps — powers the console's permission picker. */
    @Transactional(readOnly = true)
    public List<String> listAll() {
        return permissions.findAll().stream().map(PermissionEntity::getKey).sorted().toList();
    }

    @Transactional(readOnly = true)
    public boolean exists(String key) {
        return permissions.existsById(key);
    }
}
