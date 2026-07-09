package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.ShippingConfig.RegionMatch;
import com.yayatechandinnovations.yayasales.platform.Address;

import java.util.List;
import java.util.Locale;

/** §8.5 — country / region / postal-prefix matching; null matcher fields match anything. */
final class ZoneMatching {

    private ZoneMatching() {}

    static boolean matches(List<RegionMatch> matchers, Address address) {
        return matchers.stream().anyMatch(m -> matches(m, address));
    }

    private static boolean matches(RegionMatch matcher, Address address) {
        if (!norm(matcher.country()).equals(norm(address.country()))) {
            return false;
        }
        if (matcher.region() != null && !norm(matcher.region()).equals(norm(address.region()))) {
            return false;
        }
        return matcher.postalPrefix() == null
                || norm(address.postalCode()).startsWith(norm(matcher.postalPrefix()));
    }

    private static String norm(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
}
