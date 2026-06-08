package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * The tenant aggregate per docs/design/tenant-registry-design.md §3.
 * Host base URL + allowlists + lifecycle status all live on this row so
 * outbound + inbound trust can never drift from each other.
 *
 * <p>JSON columns are stored as raw strings; typed array views live on
 * the controller layer (avoids dragging Jackson into the entity).</p>
 */
@Entity
@Table(name = "tenants")
public class TenantEntity {

    public enum Status { ACTIVE, SUSPENDED, ARCHIVED }

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false, length = 16)
    private String status = Status.ACTIVE.name();

    @Column(name = "host_base_url")
    private String hostBaseUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "host_base_url_allowlist", columnDefinition = "jsonb", nullable = false)
    private String hostBaseUrlAllowlistJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inbound_origin_allowlist", columnDefinition = "jsonb", nullable = false)
    private String inboundOriginAllowlistJson = "[]";

    @Column(name = "require_https", nullable = false)
    private boolean requireHttps = true;

    @Column(name = "default_authenticator_binding_id", length = 128)
    private String defaultAuthenticatorBindingId;

    @Column(name = "default_recording_strategy_id")
    private Long defaultRecordingStrategyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings_json", columnDefinition = "jsonb", nullable = false)
    private String settingsJson = "{}";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    protected TenantEntity() {}

    public TenantEntity(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public Status statusEnum() { return Status.valueOf(status); }
    public String getHostBaseUrl() { return hostBaseUrl; }
    public String getHostBaseUrlAllowlistJson() { return hostBaseUrlAllowlistJson; }
    public String getInboundOriginAllowlistJson() { return inboundOriginAllowlistJson; }
    public boolean isRequireHttps() { return requireHttps; }
    public String getDefaultAuthenticatorBindingId() { return defaultAuthenticatorBindingId; }
    public Long getDefaultRecordingStrategyId() { return defaultRecordingStrategyId; }
    public String getSettingsJson() { return settingsJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getArchivedAt() { return archivedAt; }

    public void setDisplayName(String v) { this.displayName = v; }
    public void setStatus(String v) { this.status = v; }
    public void setStatus(Status v) { this.status = v.name(); }
    public void setHostBaseUrl(String v) { this.hostBaseUrl = v; }
    public void setHostBaseUrlAllowlistJson(String v) { this.hostBaseUrlAllowlistJson = v; }
    public void setInboundOriginAllowlistJson(String v) { this.inboundOriginAllowlistJson = v; }
    public void setRequireHttps(boolean v) { this.requireHttps = v; }
    public void setDefaultAuthenticatorBindingId(String v) { this.defaultAuthenticatorBindingId = v; }
    public void setDefaultRecordingStrategyId(Long v) { this.defaultRecordingStrategyId = v; }
    public void setSettingsJson(String v) { this.settingsJson = v; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public void setArchivedAt(OffsetDateTime v) { this.archivedAt = v; }
}
