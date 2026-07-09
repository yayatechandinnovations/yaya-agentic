package com.yayatechandinnovations.yayasales.stores;

/**
 * §6.2 — whether the displayed price already CONTAINS tax (ITBIS/VAT markets)
 * or tax is added on top. Rate in basis points (1800 = 18%) — integer, exact.
 * Getting this wrong charges the wrong amount; it's correctness, not display.
 */
public record TaxPolicy(boolean inclusive, int rateBps, String label) {

    public TaxPolicy {
        if (rateBps < 0 || rateBps > 10000) {
            throw new IllegalArgumentException("rateBps must be 0..10000, got " + rateBps);
        }
    }

    public static TaxPolicy none() {
        return new TaxPolicy(false, 0, "none");
    }
}
