package com.yayatechandinnovations.yayasales.catalog.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "variants")
public class VariantEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String sku;

    private String label;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_json")
    private String optionsJson;

    @Column(name = "weight_grams")
    private Integer weightGrams;

    private String barcode;

    protected VariantEntity() {}

    public VariantEntity(String id, String sku, String label, long priceMinor, String currency) {
        this.id = id;
        this.sku = sku;
        this.label = label;
        this.priceMinor = priceMinor;
        this.currency = currency;
    }

    public String getId() { return id; }
    public String getSku() { return sku; }
    public String getLabel() { return label; }
    public long getPriceMinor() { return priceMinor; }
    public String getCurrency() { return currency; }
    public String getOptionsJson() { return optionsJson; }
    public Integer getWeightGrams() { return weightGrams; }
    public String getBarcode() { return barcode; }

    public void setLabel(String label) { this.label = label; }
    public void setPrice(long priceMinor, String currency) {
        this.priceMinor = priceMinor;
        this.currency = currency;
    }
    public void setOptionsJson(String json) { this.optionsJson = json; }
    public void setWeightGrams(Integer weightGrams) { this.weightGrams = weightGrams; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
}
