package com.yayatechandinnovations.yayaidentity.roles;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "role_permissions")
public class RolePermissionEntity {

    @Embeddable
    public record Key(@Column(name = "role_id") String roleId,
                      @Column(name = "permission_key") String permissionKey) implements Serializable {
        public Key {
            Objects.requireNonNull(roleId);
            Objects.requireNonNull(permissionKey);
        }
    }

    @EmbeddedId
    private Key id;

    protected RolePermissionEntity() {}

    public RolePermissionEntity(Key id) {
        this.id = id;
    }

    public Key getId() { return id; }
}
