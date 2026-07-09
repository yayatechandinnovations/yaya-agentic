package com.yayatechandinnovations.yayasales.catalog;

import com.yayatechandinnovations.yayasales.platform.Ids.CatalogId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.time.Instant;

public record CatalogView(
        CatalogId id,
        StoreId store,
        int version,
        String displayName,
        Status status,
        Instant createdAt) {

    public enum Status { DRAFT, PUBLISHED, DEPRECATED }
}
