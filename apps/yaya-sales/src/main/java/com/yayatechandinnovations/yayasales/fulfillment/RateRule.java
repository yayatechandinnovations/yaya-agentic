package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.util.List;

/**
 * §8.5 — how a shipping rate prices a cart. {@code FreeOver} is a rate rule,
 * not a promo hack, so "free shipping over $50" composes with promotions
 * cleanly. {@code CarrierCalculated} is declared for the wire shape but
 * REJECTED at authoring until a live carrier API lands (decision 8, vNext).
 */
public sealed interface RateRule
        permits RateRule.Flat, RateRule.ByWeight, RateRule.ByPrice,
                RateRule.FreeOver, RateRule.CarrierCalculated {

    /** @param upTo tier ceiling — grams for {@code ByWeight}, cart-value minor units for {@code ByPrice}. */
    record Tier(long upTo, Money amount) {}

    record Flat(Money amount) implements RateRule {}

    /** Tiers sorted by ceiling; a cart heavier than the last tier pays the last tier. */
    record ByWeight(List<Tier> tiers) implements RateRule {}

    record ByPrice(List<Tier> tiers) implements RateRule {}

    record FreeOver(Money threshold, RateRule otherwise) implements RateRule {}

    record CarrierCalculated(CarrierId carrier) implements RateRule {}
}
