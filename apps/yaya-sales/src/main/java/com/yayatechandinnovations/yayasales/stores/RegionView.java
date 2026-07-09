package com.yayatechandinnovations.yayasales.stores;

import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids.PriceListId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.util.List;

/**
 * §6.1 — the (currency, tax, tenders, zones, price list) bundle a checkout
 * resolves into. The Medusa-"Region" / Shopify-"Market" aggregate.
 */
public record RegionView(
        RegionId id,
        StoreId store,
        String name,
        String currency,
        TaxPolicy tax,
        List<TenderKind> tenders,
        List<String> shippingZones,
        PriceListId priceList) {}
