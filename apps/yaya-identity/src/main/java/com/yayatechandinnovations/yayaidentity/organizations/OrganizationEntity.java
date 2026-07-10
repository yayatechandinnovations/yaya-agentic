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

    @Column
    private String description;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column
    private String website;

    @Column
    private String country;

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
                parentId == null ? null : new OrgId(parentId), mfaRequired,
                new Organization.Profile(description, contactEmail, contactPhone, website, country),
                createdAt);
    }

    /** The slug is the immutable handle; everything else about the profile can change. */
    public void update(String name, Organization.Profile profile) {
        this.name = name;
        this.description = profile.description();
        this.contactEmail = profile.contactEmail();
        this.contactPhone = profile.contactPhone();
        this.website = profile.website();
        this.country = profile.country();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isMfaRequired() { return mfaRequired; }
    public void setMfaRequired(boolean mfaRequired) { this.mfaRequired = mfaRequired; }
}
