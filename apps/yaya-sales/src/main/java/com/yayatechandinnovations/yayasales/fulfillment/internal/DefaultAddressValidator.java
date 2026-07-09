package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.AddressValidator;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.SellingContextService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Decision 9, v1: format + required fields + zone match. The selling region's
 * zone list — when authored — narrows which zones count (§6.1); unservable
 * fails Unprocessable before any money moves. External verification services
 * replace this behind the same SPI.
 */
@Service
public class DefaultAddressValidator implements AddressValidator {

    private final ShippingZoneRepository zones;
    private final DefaultShippingConfig config;
    private final SellingContextService sellingContext;

    public DefaultAddressValidator(ShippingZoneRepository zones, DefaultShippingConfig config,
                                   SellingContextService sellingContext) {
        this.zones = zones;
        this.config = config;
        this.sellingContext = sellingContext;
    }

    @Override
    @Transactional(readOnly = true)
    public Address validate(StoreId store, RegionId region, Address address) {
        if (address == null) {
            throw new ApiErrors.UnprocessableException("a ship-to address is required");
        }
        Address normalized = new Address(
                trimToNull(address.line1()), trimToNull(address.line2()),
                trimToNull(address.city()), trimToNull(address.region()),
                trimToNull(address.postalCode()),
                address.country() == null ? null
                        : address.country().trim().toUpperCase(Locale.ROOT));
        if (normalized.line1() == null || normalized.city() == null || normalized.country() == null) {
            throw new ApiErrors.UnprocessableException(
                    "ship-to address needs at least line1, city and country");
        }

        List<ShippingZoneEntity> candidates = candidateZones(store, region);
        boolean servable = candidates.stream()
                .anyMatch(z -> ZoneMatching.matches(config.readMatchers(z.getRegionsJson()), normalized));
        if (!servable) {
            throw new ApiErrors.UnprocessableException(
                    "we do not deliver to this address (no shipping zone covers it)");
        }
        return normalized;
    }

    private List<ShippingZoneEntity> candidateZones(StoreId store, RegionId region) {
        List<ShippingZoneEntity> all = zones.findByStoreId(store.value());
        if (region == null) {
            return all;
        }
        List<String> allowed = sellingContext.region(region)
                .map(r -> r.shippingZones()).orElse(List.of());
        return allowed.isEmpty() ? all
                : all.stream().filter(z -> allowed.contains(z.getId())).toList();
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
