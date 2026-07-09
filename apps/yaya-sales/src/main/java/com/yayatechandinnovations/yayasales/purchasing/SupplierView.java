package com.yayatechandinnovations.yayasales.purchasing;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.SupplierId;

import java.time.Instant;
import java.util.Map;

/** §9 — who we buy from. Contact is free-form merchant data, not a schema. */
public record SupplierView(
        SupplierId id,
        StoreId store,
        String name,
        Map<String, Object> contact,
        Instant createdAt) {}
