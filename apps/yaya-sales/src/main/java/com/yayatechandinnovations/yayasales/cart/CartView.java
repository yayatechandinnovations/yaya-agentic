package com.yayatechandinnovations.yayasales.cart;

import com.yayatechandinnovations.yayasales.platform.Ids.CartId;
import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;
import java.util.List;

public record CartView(
        CartId id,
        StoreId store,
        ChannelId channel,
        RegionId region,
        String customerRef,
        String currency,
        List<Line> lines,
        Instant updatedAt) {

    /** {@code unitPrice} is a display snapshot; checkout re-quotes (§8.2). */
    public record Line(VariantId sku, String title, String variantLabel, long qty, Money unitPrice) {}
}
