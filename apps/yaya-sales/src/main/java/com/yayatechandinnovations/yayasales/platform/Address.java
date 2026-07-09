package com.yayatechandinnovations.yayasales.platform;

/**
 * A postal address as it crosses module APIs — the checkout ship-to (§8.2
 * step 1), the courier's legal address (§8.9), a location. Lives in platform so
 * orders can carry it without depending on fulfillment; fulfillment's
 * {@code AddressValidator} (decision 9) normalizes and zone-matches it.
 */
public record Address(
        String line1,
        String line2,
        String city,
        String region,
        String postalCode,
        String country) {
}
