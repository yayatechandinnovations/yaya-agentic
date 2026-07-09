package com.yayatechandinnovations.yayasales.pricing;

/**
 * §6.2 / §17 — the single place money is computed. Deterministic: the same
 * snapshot + context always produces the same quote (refunds reconstruct
 * figures years later).
 */
public interface PricingEngine {

    PricedQuote quote(CartSnapshot cart, PricingContext ctx);
}
