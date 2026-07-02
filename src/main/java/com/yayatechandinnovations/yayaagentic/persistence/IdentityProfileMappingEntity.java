package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row = "for tenant T, principals whose claim {@code claim_name}
 * matches {@code claim_value} get profile {@code profile_id@profile_version}".
 * Read by {@code IdentityProfileResolver} at session start; written by the
 * {@code /v1/admin/tenants/{id}/identity-profile-mappings} CRUD.
 *
 * <p>{@code profile_version} is nullable: null means "unpinned — resolve to
 * the latest published version at session start"; a concrete value pins the
 * mapping to that exact version. See design §5.2.</p>
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

    // Nullable: null = unpinned (latest at session start); a value pins it.
    @Column(name = "profile_version")
    private Integer profileVersion;

    @Column(nullable = false)
    private int priority = 100;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IdentityProfileMappingEntity() {}

    public IdentityProfileMappingEntity(String tenantId, String claimName, String claimValue,
                                        String profileId, Integer profileVersion, int priority) {
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
    public Integer getProfileVersion() { return profileVersion; }
    public int getPriority() { return priority; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setProfileId(String v) { this.profileId = v; }
    public void setProfileVersion(Integer v) { this.profileVersion = v; }
    public void setPriority(int v) { this.priority = v; }
}
