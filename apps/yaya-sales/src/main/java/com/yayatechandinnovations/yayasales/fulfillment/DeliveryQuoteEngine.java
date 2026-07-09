package com.yayatechandinnovations.yayasales.fulfillment;

import java.util.List;

/**
 * §8.4/§17 — destination address → zone match (§8.5) → methods offered there →
 * a rate per method. DIGITAL methods never appear (they skip delivery). An
 * unmatched address yields an empty list — that is what "unservable" means.
 */
public interface DeliveryQuoteEngine {

    List<DeliveryOption> quote(DeliveryQuoteRequest request);
}
