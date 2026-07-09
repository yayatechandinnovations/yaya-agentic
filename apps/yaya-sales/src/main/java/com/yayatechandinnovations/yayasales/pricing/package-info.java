/**
 * The money authority (module design {@code modules/pricing/}, parent §5.4,
 * §6.2): list price → price-list overrides → promotions → tax (inclusive OR
 * exclusive — a correctness item, not display) → {@code PricedQuote}. All
 * money is computed here and nowhere else; the agent and client display.
 */
package com.yayatechandinnovations.yayasales.pricing;
