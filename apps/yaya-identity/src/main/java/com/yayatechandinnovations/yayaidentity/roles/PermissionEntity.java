package com.yayatechandinnovations.yayaidentity.roles;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "permissions")
public class PermissionEntity {

    @Id
    @Column(name = "key")
    private String key;

    protected PermissionEntity() {}

    public PermissionEntity(String key) {
        this.key = key;
    }

    public String getKey() { return key; }
}
