package com.yayatechandinnovations.yayasales.payments.internal.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;

/**
 * One row of {@code merchant_psp_accounts} (V7) — a store's connected PSP
 * account obtained via in-app onboarding. Keyed {@code (store_id, provider)}:
 * an operator connects a provider once per store. Design §9.
 */
@Entity
@Table(name = "merchant_psp_accounts")
@IdClass(MerchantAccountEntity.PK.class)
public class MerchantAccountEntity {

    @Id
    @Column(name = "store_id", length = 64)
    private String storeId;

    @Id
    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "account_id", length = 200)
    private String accountId;

    @Column(name = "status", nullable = false, length = 24)
    private String status = "PENDING";

    @Column(name = "charges_enabled", nullable = false)
    private boolean chargesEnabled = false;

    @Column(name = "tracking_ref", length = 200)
    private String trackingRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json")
    private String detailJson;

    protected MerchantAccountEntity() {}

    public MerchantAccountEntity(String storeId, String provider) {
        this.storeId = storeId;
        this.provider = provider;
    }

    public String getStoreId() { return storeId; }
    public String getProvider() { return provider; }
    public String getAccountId() { return accountId; }
    public String getStatus() { return status; }
    public boolean isChargesEnabled() { return chargesEnabled; }
    public String getTrackingRef() { return trackingRef; }
    public String getDetailJson() { return detailJson; }

    public void setAccountId(String v) { this.accountId = v; }
    public void setStatus(String v) { this.status = v; }
    public void setChargesEnabled(boolean v) { this.chargesEnabled = v; }
    public void setTrackingRef(String v) { this.trackingRef = v; }
    public void setDetailJson(String v) { this.detailJson = v == null || v.isBlank() ? "{}" : v; }

    public static class PK implements Serializable {
        private String storeId;
        private String provider;

        public PK() {}

        public PK(String storeId, String provider) {
            this.storeId = storeId;
            this.provider = provider;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(storeId, pk.storeId) && Objects.equals(provider, pk.provider);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storeId, provider);
        }
    }
}
