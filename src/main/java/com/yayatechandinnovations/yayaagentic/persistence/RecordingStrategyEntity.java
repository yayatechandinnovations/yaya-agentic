package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "recording_strategies")
public class RecordingStrategyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "scope_kind", nullable = false, length = 16)
    private String scopeKind;   // 'TENANT' | 'PROFILE'

    @Column(name = "scope_id", nullable = false, length = 128)
    private String scopeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_json", columnDefinition = "jsonb", nullable = false)
    private String strategyJson;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RecordingStrategyEntity() {}

    public RecordingStrategyEntity(String tenantId, String scopeKind, String scopeId,
                                   String strategyJson, int version) {
        this.tenantId = tenantId;
        this.scopeKind = scopeKind;
        this.scopeId = scopeId;
        this.strategyJson = strategyJson;
        this.version = version;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getScopeKind() { return scopeKind; }
    public String getScopeId() { return scopeId; }
    public String getStrategyJson() { return strategyJson; }
    public Integer getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
