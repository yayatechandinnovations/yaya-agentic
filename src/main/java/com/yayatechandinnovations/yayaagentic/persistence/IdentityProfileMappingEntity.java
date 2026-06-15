package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row = "for tenant T, principals whose claim {@code claim_name}
 * matches {@code claim_value} get profile {@code profile_id@profile_version}".
 * Read by {@code IdentityProfileResolver} at session start; written by the
 * {@code /v1/admin/tenants/{id}/identity-profile-mappings} CRUD.
 */
@Entity
@Table(name = "identity_profile_mappings")
public class IdentityProfileMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "claim_name", nullable = false, length = 128)
    private String claimName;

    @Column(name = "claim_value", nullable = false, length = 255)
    private String claimValue;

    @Column(name = "profile_id", nullable = false, length = 128)
    private String profileId;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion = 1;

    @Column(nullable = false)
    private int priority = 100;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IdentityProfileMappingEntity() {}

    public IdentityProfileMappingEntity(String tenantId, String claimName, String claimValue,
                                        String profileId, int profileVersion, int priority) {
        this.tenantId = tenantId;
        this.claimName = claimName;
        this.claimValue = claimValue;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.priority = priority;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getClaimName() { return claimName; }
    public String getClaimValue() { return claimValue; }
    public String getProfileId() { return profileId; }
    public int getProfileVersion() { return profileVersion; }
    public int getPriority() { return priority; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setProfileId(String v) { this.profileId = v; }
    public void setProfileVersion(int v) { this.profileVersion = v; }
    public void setPriority(int v) { this.priority = v; }
}
