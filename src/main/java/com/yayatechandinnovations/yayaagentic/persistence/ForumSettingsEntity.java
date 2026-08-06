package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** Per-entry-point forum switch ({@code chat-experience-design.md} §8.1). */
@Entity
@Table(name = "forum_settings")
public class ForumSettingsEntity {

    @Id
    @Column(name = "entry_point_id", nullable = false, length = 64)
    private String entryPointId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected ForumSettingsEntity() {}

    public ForumSettingsEntity(String entryPointId, String tenantId, boolean enabled) {
        this.entryPointId = entryPointId;
        this.tenantId = tenantId;
        this.enabled = enabled;
    }

    @PreUpdate
    void touch() { this.updatedAt = OffsetDateTime.now(); }

    public String getEntryPointId() { return entryPointId; }
    public String getTenantId() { return tenantId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
