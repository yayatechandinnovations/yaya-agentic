package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "profiles")
@IdClass(ProfileEntity.PK.class)
public class ProfileEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(length = 128)
    private String id;

    @Id
    private Integer version;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String intro;

    @Column(name = "system_prompt", nullable = false)
    private String systemPrompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities_json", columnDefinition = "jsonb", nullable = false)
    private String capabilitiesJson = "[]";

    @Column(name = "auth_binding_id", length = 128)
    private String authBindingId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb", nullable = false)
    private String metadataJson = "{}";

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(nullable = false, length = 16)
    private String language = "en";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deprecated_at")
    private OffsetDateTime deprecatedAt;

    protected ProfileEntity() {}

    public ProfileEntity(String tenantId, String id, int version, String displayName,
                         String intro, String systemPrompt) {
        this.tenantId = tenantId;
        this.id = id;
        this.version = version;
        this.displayName = displayName;
        this.intro = intro;
        this.systemPrompt = systemPrompt;
    }

    // --- getters / setters ------------------------------------------------

    public String getTenantId() { return tenantId; }
    public String getId() { return id; }
    public Integer getVersion() { return version; }
    public String getDisplayName() { return displayName; }
    public String getIntro() { return intro; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public String getAuthBindingId() { return authBindingId; }
    public String getMetadataJson() { return metadataJson; }
    public String getStatus() { return status; }
    public String getLanguage() { return language; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDeprecatedAt() { return deprecatedAt; }

    public void setDisplayName(String v) { this.displayName = v; }
    public void setIntro(String v) { this.intro = v; }
    public void setSystemPrompt(String v) { this.systemPrompt = v; }
    public void setCapabilitiesJson(String v) { this.capabilitiesJson = v; }
    public void setAuthBindingId(String v) { this.authBindingId = v; }
    public void setMetadataJson(String v) { this.metadataJson = v; }
    public void setStatus(String v) { this.status = v; }
    public void setLanguage(String v) { this.language = (v == null || v.isBlank()) ? "en" : v; }

    // --- composite key ----------------------------------------------------

    public static class PK implements Serializable {
        private String tenantId;
        private String id;
        private Integer version;

        public PK() {}
        public PK(String tenantId, String id, Integer version) {
            this.tenantId = tenantId; this.id = id; this.version = version;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(id, pk.id)
                    && Objects.equals(version, pk.version);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, id, version); }
    }
}
