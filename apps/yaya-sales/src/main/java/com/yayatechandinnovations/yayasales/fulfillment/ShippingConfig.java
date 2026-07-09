package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.RateId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.ZoneId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;
import java.util.List;

/**
 * §8.5 authoring — the merchant-written configuration that makes the §8.4
 * quote answerable: zones (country / region / postal-prefix matchers), delivery
 * methods, rates per zone×method, carriers, and slot inventory.
 */
public interface ShippingConfig {

    ZoneView addZone(StoreId store, String name, List<RegionMatch> regions);

    List<ZoneView> zonesOf(StoreId store);

    MethodView addMethod(StoreId store, DeliveryMethodKind kind, String label);

    MethodView setMethodActive(DeliveryMethodId id, boolean active);

    List<MethodView> methodsOf(StoreId store);

    CarrierView addCarrier(StoreId store, String kind, String name, String webhookSecret);

    List<CarrierView> carriersOf(StoreId store);

    /** Rejects {@link RateRule.CarrierCalculated} — declared but vNext (decision 8). */
    RateView addRate(StoreId store, ZoneId zone, DeliveryMethodId method, CarrierId carrier,
                     String serviceLevel, RateRule rule, Money handlingFee, TransitEstimate transit);

    List<RateView> ratesOf(StoreId store);

    DeliverySlots.SlotView addSlot(StoreId store, LocationId location, DeliveryMethodId method,
                                   Instant startsAt, Instant endsAt, int capacity);

    /** Country / region / postal-prefix matcher (§8.5); null fields match anything. */
    record RegionMatch(String country, String region, String postalPrefix) {}

    /** Promised transit in days → the §8.4 window shown to the shopper. */
    record TransitEstimate(int minDays, int maxDays) {}

    record ZoneView(ZoneId id, StoreId store, String name, List<RegionMatch> regions) {}

    record MethodView(DeliveryMethodId id, StoreId store, DeliveryMethodKind kind,
                      String label, boolean active) {}

    record CarrierView(CarrierId id, StoreId store, String kind, String name) {}

    record RateView(RateId id, StoreId store, ZoneId zone, DeliveryMethodId method,
                    CarrierId carrier, String serviceLevel, RateRule rule,
                    Money handlingFee, TransitEstimate transit) {}
}
