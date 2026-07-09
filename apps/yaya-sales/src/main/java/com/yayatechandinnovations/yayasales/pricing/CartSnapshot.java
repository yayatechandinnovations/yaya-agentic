package com.yayatechandinnovations.yayasales.pricing;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.util.List;

/** What gets priced: lines with their authoritative LIST price (from catalog). */
public record CartSnapshot(StoreId store, List<Line> lines) {

    public record Line(VariantId sku, long qty, Money listPrice, String title) {}
}
