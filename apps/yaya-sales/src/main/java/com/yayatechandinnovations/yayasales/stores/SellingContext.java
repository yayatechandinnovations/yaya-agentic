package com.yayatechandinnovations.yayasales.stores;

/**
 * §6.1 — the resolved (channel, region) pair every cart and checkout binds to.
 * Exactly one of each; currency, tax treatment, tenders and zones follow.
 */
public record SellingContext(ChannelView channel, RegionView region) {}
