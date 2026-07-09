package com.yayatechandinnovations.yayaidentity.groups;

import com.yayatechandinnovations.yayaidentity.platform.Ids.GroupId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "groups")
public class GroupEntity {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GroupEntity() {}

    public GroupEntity(String id, String orgId, String name, Instant createdAt) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.createdAt = createdAt;
    }

    public Group toDomain() {
        return new Group(new GroupId(id), new OrgId(orgId), name);
    }

    public String getId() { return id; }
}
