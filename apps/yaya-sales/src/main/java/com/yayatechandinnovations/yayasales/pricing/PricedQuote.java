package com.yayatechandinnovations.yayasales.pricing;

import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.util.List;

/**
 * §6.2 — the quote. When {@code taxInclusive}, {@code taxTotal} is the tax
 * CONTAINED IN the total (the displayed price is the tax-included price);
 * exclusive regions add it on top. Either way {@code grandTotal} reconstructs
 * from the parts to the minor unit (§14 tax-inclusive rounding).
 */
public record PricedQuote(
        List<PricedLine> lines,
        Money subtotal,
        Money discountTotal,
        Money taxTotal,
        boolean taxInclusive,
        Money shippingTotal,
        Money grandTotal,
        List<AppliedPromotion> promotions,
        String currency) {

    public record PricedLine(VariantId sku, String title, long qty,
                             Money unitPrice, Money lineTotal) {}

    public record AppliedPromotion(String promotionId, String label, Money amountOff) {}
}
