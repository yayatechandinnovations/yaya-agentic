package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.util.List;

/**
 * §8.4 — what the quote engine needs: destination + cart shape. Weight comes
 * from the catalog's variant {@code weightGrams} × qty; {@code cartValue} is
 * the priced goods total (drives ByPrice / FreeOver). {@code region} may be
 * null (options preview before a region is pinned); when present, its zone
 * list — if any — restricts the candidate zones (§6.1).
 */
public record DeliveryQuoteRequest(
        StoreId store,
        RegionId region,
        Address destination,
        List<Line> lines,
        Money cartValue) {

    public record Line(VariantId sku, long qty) {}
}
