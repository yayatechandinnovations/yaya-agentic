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

    // Nullable: the profile's default Session Start Flow id (unpinned = latest).
    @Column(name = "default_start_flow_id", length = 128)
    private String defaultStartFlowId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb", nullable = false)
    private String metadataJson = "{}";

    // Nullable app-specific traits: a map of namespaced kind → opaque payload
    // (e.g. {"sales.store": {...}}). The framework stores/echoes it but never
    // interprets it. Renamed from the retired commerce_json column (V74), which
    // migrated legacy CommerceConfig/CommerceBinding rows into this shape.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extensions_json", columnDefinition = "jsonb")
    private String extensionsJson;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(nullable = false, length = 16)
    private String language = "en";

    // Nullable: the deployment's model / default tool-round ceiling apply.
    @Column(length = 128)
    private String model;

    @Column(name = "max_tool_rounds")
    private Integer maxToolRounds;

    @Column(nullable = false)
    private boolean metered = false;

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
    public String getDefaultStartFlowId() { return defaultStartFlowId; }
    public String getMetadataJson() { return metadataJson; }
    public String getExtensionsJson() { return extensionsJson; }
    public String getStatus() { return status; }
    public String getLanguage() { return language; }
    public String getModel() { return model; }
    public Integer getMaxToolRounds() { return maxToolRounds; }
    public boolean isMetered() { return metered; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDeprecatedAt() { return deprecatedAt; }

    public void setDisplayName(String v) { this.displayName = v; }
    public void setIntro(String v) { this.intro = v; }
    public void setSystemPrompt(String v) { this.systemPrompt = v; }
    public void setCapabilitiesJson(String v) { this.capabilitiesJson = v; }
    public void setAuthBindingId(String v) { this.authBindingId = v; }
    public void setDefaultStartFlowId(String v) { this.defaultStartFlowId = v; }
    public void setMetadataJson(String v) { this.metadataJson = v; }
    public void setExtensionsJson(String v) { this.extensionsJson = v; }
    public void setStatus(String v) { this.status = v; }
    public void setModel(String v) { this.model = (v == null || v.isBlank()) ? null : v.trim(); }
    public void setMaxToolRounds(Integer v) { this.maxToolRounds = v; }
    public void setMetered(boolean v) { this.metered = v; }
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
