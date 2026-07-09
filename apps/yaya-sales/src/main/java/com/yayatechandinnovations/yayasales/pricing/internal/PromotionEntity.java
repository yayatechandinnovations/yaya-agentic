package com.yayatechandinnovations.yayasales.pricing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "promotions")
public class PromotionEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String kind;                 // PERCENT_OFF | FIXED_OFF | FREE_SHIPPING

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_json", nullable = false)
    private String valueJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligibility_json")
    private String eligibilityJson;

    private String code;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    protected PromotionEntity() {}

    public PromotionEntity(String id, String storeId, String kind, String valueJson,
                           String eligibilityJson, String code, Instant startsAt, Instant endsAt) {
        this.id = id;
        this.storeId = storeId;
        this.kind = kind;
        this.valueJson = valueJson;
        this.eligibilityJson = eligibilityJson;
        this.code = code;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.active = true;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getKind() { return kind; }
    public String getValueJson() { return valueJson; }
    public String getEligibilityJson() { return eligibilityJson; }
    public String getCode() { return code; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public boolean isActive() { return active; }

    public void setActive(boolean active) { this.active = active; }

    public boolean live(Instant now) {
        return active
                && (startsAt == null || !now.isBefore(startsAt))
                && (endsAt == null || now.isBefore(endsAt));
    }
}
