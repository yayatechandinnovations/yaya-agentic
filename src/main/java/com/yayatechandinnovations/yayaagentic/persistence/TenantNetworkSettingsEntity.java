package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row = the runtime-mutable network-feature settings for a tenant
 * (yaya-agentic-network-chat-runtime-design.md; enablement UX). Read by
 * {@code TenantNetworkSettingsService} (cached, evicted by
 * {@code TenantNetworkSettingsChangedEvent}); written by the
 * {@code /v1/admin/tenants/{id}/network-settings} CRUD.
 */
@Entity
@Table(name = "tenant_network_settings")
public class TenantNetworkSettingsEntity {

    @Id
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "play_enabled", nullable = false)
    private boolean playEnabled;

    @Column(name = "claim_enabled", nullable = false)
    private boolean claimEnabled;

    @Column(name = "hub_base_url", length = 1024)
    private String hubBaseUrl;

    @Column(name = "hub_credential_ref", length = 256)
    private String hubCredentialRef;

    @Column(name = "entry_point_id", length = 64)
    private String entryPointId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected TenantNetworkSettingsEntity() {}

    public TenantNetworkSettingsEntity(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantId() { return tenantId; }
    public boolean isEnabled() { return enabled; }
    public boolean isPlayEnabled() { return playEnabled; }
    public boolean isClaimEnabled() { return claimEnabled; }
    public String getHubBaseUrl() { return hubBaseUrl; }
    public String getHubCredentialRef() { return hubCredentialRef; }
    public String getEntryPointId() { return entryPointId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) { this.enabled = v; }
    public void setPlayEnabled(boolean v) { this.playEnabled = v; }
    public void setClaimEnabled(boolean v) { this.claimEnabled = v; }
    public void setHubBaseUrl(String v) { this.hubBaseUrl = v; }
    public void setHubCredentialRef(String v) { this.hubCredentialRef = v; }
    public void setEntryPointId(String v) { this.entryPointId = v; }
}
