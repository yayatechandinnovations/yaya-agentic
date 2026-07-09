package com.yayatechandinnovations.yayaidentity.roles;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "app_permissions")
public class AppPermissionEntity {

    @Embeddable
    public record Key(@Column(name = "app") String app,
                      @Column(name = "permission_key") String permissionKey) implements Serializable {
        public Key {
            Objects.requireNonNull(app);
            Objects.requireNonNull(permissionKey);
        }
    }

    @EmbeddedId
    private Key id;

    protected AppPermissionEntity() {}

    public AppPermissionEntity(Key id) {
        this.id = id;
    }

    public Key getId() { return id; }
}
