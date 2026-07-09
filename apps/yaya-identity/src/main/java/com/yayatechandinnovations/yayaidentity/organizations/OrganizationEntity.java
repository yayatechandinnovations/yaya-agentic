package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "organizations")
public class OrganizationEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String status;

    @Column(name = "parent_id")
    private String parentId;

    @Column(name = "mfa_required", nullable = false)
    private boolean mfaRequired;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrganizationEntity() {}

    public OrganizationEntity(String id, String name, String slug, String status,
                              String parentId, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.status = status;
        this.parentId = parentId;
        this.mfaRequired = false;
        this.createdAt = createdAt;
    }

    public Organization toDomain() {
        return new Organization(new OrgId(id), name, slug,
                Organization.Status.valueOf(status),
                parentId == null ? null : new OrgId(parentId), mfaRequired, createdAt);
    }

    public String getId() { return id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isMfaRequired() { return mfaRequired; }
    public void setMfaRequired(boolean mfaRequired) { this.mfaRequired = mfaRequired; }
}
