package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code chat_entry_points} (V46). Mirrors the migration; domain
 * conversion (to/from {@code channel.chat.ChatEntryPoint}) lives in
 * {@code ChatEntryPointService}, same split as {@link SmsSenderEntity}.
 */
@Entity
@Table(name = "chat_entry_points")
public class ChatEntryPointEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "handle", nullable = false, length = 64)
    private String handle;

    @Column(name = "profile_id", nullable = false, length = 128)
    private String profileId;

    @Column(name = "profile_version")
    private Integer profileVersion;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "logo_url", nullable = false)
    private String logoUrl;

    @Column(name = "accent_color_hex", length = 9)
    private String accentColorHex;

    @Column(name = "welcome_text")
    private String welcomeText;

    @Column(name = "default_locale", nullable = false, length = 16)
    private String defaultLocale = "en-US";

    @Column(name = "auth_mode", nullable = false, length = 24)
    private String authMode = "ANONYMOUS_ONLY";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_config_json", columnDefinition = "jsonb", nullable = false)
    private String authConfigJson = "{\"kind\":\"None\"}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "abuse_policy_json", columnDefinition = "jsonb", nullable = false)
    private String abusePolicyJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "session_policy_json", columnDefinition = "jsonb", nullable = false)
    private String sessionPolicyJson;

    @Column(name = "requires_verified_phone", nullable = false)
    private boolean requiresVerifiedPhone = false;

    // Opt-in listing in the public directory (chat-public-directory-design.md §2).
    // Defaults false: publishing (DRAFT→ACTIVE) does NOT list a chat.
    @Column(name = "discoverable", nullable = false)
    private boolean discoverable = false;

    @Column(name = "tagline", length = 140)
    private String tagline;

    // Resolver-profile-override guardrails (V61). JSON array of profile keys;
    // empty/[] = any published in-tenant profile is allowed.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_override_profiles_json", columnDefinition = "jsonb", nullable = false)
    private String allowedOverrideProfilesJson = "[]";

    @Column(name = "on_override_denied", nullable = false, length = 8)
    private String onOverrideDenied = "IGNORE";

    @Column(name = "status", nullable = false, length = 24)
    private String status = "DRAFT";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ChatEntryPointEntity() {}

    public ChatEntryPointEntity(String id, String tenantId, String handle, String profileId,
                                Integer profileVersion, String displayName, String logoUrl,
                                String sessionPolicyJson, String status) {
        this.id = id;
        this.tenantId = tenantId;
        this.handle = handle;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.displayName = displayName;
        this.logoUrl = logoUrl;
        this.sessionPolicyJson = sessionPolicyJson;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getHandle() { return handle; }
    public String getProfileId() { return profileId; }
    public Integer getProfileVersion() { return profileVersion; }
    public String getDisplayName() { return displayName; }
    public String getLogoUrl() { return logoUrl; }
    public String getAccentColorHex() { return accentColorHex; }
    public String getWelcomeText() { return welcomeText; }
    public String getDefaultLocale() { return defaultLocale; }
    public String getAuthMode() { return authMode; }
    public String getAuthConfigJson() { return authConfigJson; }
    public String getAbusePolicyJson() { return abusePolicyJson; }
    public String getSessionPolicyJson() { return sessionPolicyJson; }
    public boolean isRequiresVerifiedPhone() { return requiresVerifiedPhone; }
    public boolean isDiscoverable() { return discoverable; }
    public String getTagline() { return tagline; }
    public String getAllowedOverrideProfilesJson() { return allowedOverrideProfilesJson; }
    public String getOnOverrideDenied() { return onOverrideDenied; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setHandle(String v) { this.handle = v; }
    public void setProfileId(String v) { this.profileId = v; }
    public void setProfileVersion(Integer v) { this.profileVersion = v; }
    public void setDisplayName(String v) { this.displayName = v; }
    public void setLogoUrl(String v) { this.logoUrl = v; }
    public void setAccentColorHex(String v) { this.accentColorHex = v; }
    public void setWelcomeText(String v) { this.welcomeText = v; }
    public void setDefaultLocale(String v) { this.defaultLocale = v == null || v.isBlank() ? "en-US" : v; }
    public void setAuthMode(String v) { this.authMode = v; }
    public void setAuthConfigJson(String v) { this.authConfigJson = v == null || v.isBlank() ? "{\"kind\":\"None\"}" : v; }
    public void setAbusePolicyJson(String v) { this.abusePolicyJson = v == null || v.isBlank() ? "{}" : v; }
    public void setSessionPolicyJson(String v) { this.sessionPolicyJson = v; }
    public void setRequiresVerifiedPhone(boolean v) { this.requiresVerifiedPhone = v; }
    public void setDiscoverable(boolean v) { this.discoverable = v; }
    public void setTagline(String v) { this.tagline = v == null || v.isBlank() ? null : v.trim(); }
    public void setAllowedOverrideProfilesJson(String v) {
        this.allowedOverrideProfilesJson = v == null || v.isBlank() ? "[]" : v;
    }
    public void setOnOverrideDenied(String v) {
        this.onOverrideDenied = v == null || v.isBlank() ? "IGNORE" : v;
    }
    public void setStatus(String v) { this.status = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
