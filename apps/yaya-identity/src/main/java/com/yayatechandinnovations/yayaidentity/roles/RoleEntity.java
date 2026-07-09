package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.RoleId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    private String id;

    @Column(name = "org_id")
    private String orgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "system", nullable = false)
    private boolean system;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoleEntity() {}

    public RoleEntity(String id, String orgId, String name, boolean system, Instant createdAt) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.system = system;
        this.createdAt = createdAt;
    }

    public Role toDomain() {
        return new Role(new RoleId(id), orgId == null ? null : new OrgId(orgId), name, system);
    }

    public String getId() { return id; }
    public String getName() { return name; }
}
