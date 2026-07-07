package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One row of {@code merchant_psp_accounts} (V72) — a tenant's connected PSP
 * account obtained via in-app onboarding. Keyed {@code (tenant_id, provider)}:
 * an operator connects a provider once for the whole workspace. See
 * {@code merchant-psp-onboarding-design.md} §5.
 */
@Entity
@Table(name = "merchant_psp_accounts")
@IdClass(MerchantPspAccountEntity.PK.class)
public class MerchantPspAccountEntity {

    @Id
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id
    @Column(name = "provider", length = 16)
    private String provider;

    @Column(name = "account_id", length = 255)
    private String accountId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "charges_enabled", nullable = false)
    private boolean chargesEnabled = false;

    @Column(name = "tracking_ref", length = 128)
    private String trackingRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb", nullable = false)
    private String detailJson = "{}";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected MerchantPspAccountEntity() {}

    public MerchantPspAccountEntity(String tenantId, String provider) {
        this.tenantId = tenantId;
        this.provider = provider;
    }

    // updated_at is NOT NULL and Hibernate always sends the column, so the DB
    // DEFAULT never applies — stamp it here (same lesson as CommerceCartEntity).
    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public String getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getAccountId() { return accountId; }
    public String getStatus() { return status; }
    public boolean isChargesEnabled() { return chargesEnabled; }
    public String getTrackingRef() { return trackingRef; }
    public String getDetailJson() { return detailJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setAccountId(String v) { this.accountId = v; }
    public void setStatus(String v) { this.status = v; }
    public void setChargesEnabled(boolean v) { this.chargesEnabled = v; }
    public void setTrackingRef(String v) { this.trackingRef = v; }
    public void setDetailJson(String v) { this.detailJson = v == null || v.isBlank() ? "{}" : v; }

    public static class PK implements Serializable {
        private String tenantId;
        private String provider;

        public PK() {}

        public PK(String tenantId, String provider) {
            this.tenantId = tenantId;
            this.provider = provider;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId) && Objects.equals(provider, pk.provider);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, provider);
        }
    }
}
