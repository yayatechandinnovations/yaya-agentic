package com.yayatechandinnovations.yayasales.stores;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;

import java.time.Instant;

public record StoreView(
        StoreId id,
        TenantRef tenant,
        String displayName,
        String defaultCurrency,
        StorePolicies policies,
        Instant createdAt) {}
