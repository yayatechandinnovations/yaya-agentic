package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryMethodKind;
import com.yayatechandinnovations.yayasales.fulfillment.DeliverySlots;
import com.yayatechandinnovations.yayasales.fulfillment.RateRule;
import com.yayatechandinnovations.yayasales.fulfillment.ShippingConfig;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.RateId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.ZoneId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * §8.5 authoring. Referential checks happen here (a rate needs an existing
 * zone + method of the same store); {@code CarrierCalculated} is rejected —
 * declared for the wire shape, priced by nobody until a live carrier API
 * lands (decision 8).
 */
@Service
public class DefaultShippingConfig implements ShippingConfig {

    private final ShippingZoneRepository zones;
    private final DeliveryMethodRepository methods;
    private final CarrierRepository carriers;
    private final ShippingRateRepository rates;
    private final DeliverySlotRepository slots;
    private final StoreDirectory stores;
    private final ObjectMapper json;

    public DefaultShippingConfig(ShippingZoneRepository zones, DeliveryMethodRepository methods,
                                 CarrierRepository carriers, ShippingRateRepository rates,
                                 DeliverySlotRepository slots, StoreDirectory stores,
                                 ObjectMapper json) {
        this.zones = zones;
        this.methods = methods;
        this.carriers = carriers;
        this.rates = rates;
        this.slots = slots;
        this.stores = stores;
        this.json = json;
    }

    @Override
    @Transactional
    public ZoneView addZone(StoreId store, String name, List<RegionMatch> regions) {
        if (regions == null || regions.isEmpty()) {
            throw new ApiErrors.UnprocessableException("a zone needs at least one region matcher");
        }
        if (regions.stream().anyMatch(r -> r.country() == null || r.country().isBlank())) {
            throw new ApiErrors.UnprocessableException("every zone matcher needs a country");
        }
        ShippingZoneEntity zone = new ShippingZoneEntity(Ids.newId(), store.value(), name,
                write(regions));
        zones.save(zone);
        return toView(zone);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ZoneView> zonesOf(StoreId store) {
        return zones.findByStoreId(store.value()).stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public MethodView addMethod(StoreId store, DeliveryMethodKind kind, String label) {
        DeliveryMethodEntity method = new DeliveryMethodEntity(Ids.newId(), store.value(),
                kind.name(), label);
        methods.save(method);
        return toView(method);
    }

    @Override
    @Transactional
    public MethodView setMethodActive(DeliveryMethodId id, boolean active) {
        DeliveryMethodEntity method = methods.findById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("delivery method", id.value()));
        method.setActive(active);
        return toView(method);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MethodView> methodsOf(StoreId store) {
        return methods.findByStoreId(store.value()).stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public CarrierView addCarrier(StoreId store, String kind, String name, String webhookSecret) {
        String config = webhookSecret == null ? null
                : "{\"webhookSecret\":" + quote(webhookSecret) + "}";
        CarrierEntity carrier = new CarrierEntity(Ids.newId(), store.value(), kind, name, config);
        carriers.save(carrier);
        return new CarrierView(new CarrierId(carrier.getId()), store, kind, name);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CarrierView> carriersOf(StoreId store) {
        return carriers.findByStoreId(store.value()).stream()
                .map(c -> new CarrierView(new CarrierId(c.getId()), new StoreId(c.getStoreId()),
                        c.getKind(), c.getName()))
                .toList();
    }

    @Override
    @Transactional
    public RateView addRate(StoreId store, ZoneId zone, DeliveryMethodId method, CarrierId carrier,
                            String serviceLevel, RateRule rule, Money handlingFee,
                            TransitEstimate transit) {
        rejectCarrierCalculated(rule);
        ShippingZoneEntity zoneRow = zones.findById(zone.value())
                .filter(z -> z.getStoreId().equals(store.value()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("shipping zone", zone.value()));
        methods.findById(method.value())
                .filter(m -> m.getStoreId().equals(store.value()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("delivery method", method.value()));

        ShippingRateEntity rate = new ShippingRateEntity(Ids.newId(), store.value(),
                zoneRow.getId(), method.value(),
                carrier == null ? null : carrier.value(), serviceLevel,
                RateRules.write(rule, json),
                handlingFee == null ? 0 : handlingFee.amountMinor(),
                transit == null ? null : write(transit));
        rates.save(rate);
        return toView(rate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RateView> ratesOf(StoreId store) {
        return rates.findByStoreId(store.value()).stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public DeliverySlots.SlotView addSlot(StoreId store, LocationId location, DeliveryMethodId method,
                                          Instant startsAt, Instant endsAt, int capacity) {
        if (capacity < 1) {
            throw new ApiErrors.UnprocessableException("slot capacity must be at least 1");
        }
        if (!startsAt.isBefore(endsAt)) {
            throw new ApiErrors.UnprocessableException("slot must start before it ends");
        }
        stores.location(location)
                .filter(l -> l.store().equals(store))
                .orElseThrow(() -> new ApiErrors.NotFoundException("location", location.value()));
        methods.findById(method.value())
                .filter(m -> m.getStoreId().equals(store.value()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("delivery method", method.value()));

        DeliverySlotEntity slot = new DeliverySlotEntity(Ids.newId(), store.value(),
                location.value(), method.value(), startsAt, endsAt, capacity);
        slots.save(slot);
        return new DeliverySlots.SlotView(new SlotId(slot.getId()), store, location, method,
                startsAt, endsAt, capacity, 0);
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Also rejects the rule when it hides inside a FreeOver fallback. */
    private static void rejectCarrierCalculated(RateRule rule) {
        switch (rule) {
            case RateRule.CarrierCalculated ignored -> throw new ApiErrors.UnprocessableException(
                    "CarrierCalculated rates are not available yet (live carrier APIs are vNext)");
            case RateRule.FreeOver freeOver -> rejectCarrierCalculated(freeOver.otherwise());
            case RateRule.ByWeight byWeight -> requireTiers(byWeight.tiers());
            case RateRule.ByPrice byPrice -> requireTiers(byPrice.tiers());
            case RateRule.Flat ignored -> { }
        }
    }

    private static void requireTiers(List<RateRule.Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new ApiErrors.UnprocessableException("a tiered rate needs at least one tier");
        }
    }

    private ZoneView toView(ShippingZoneEntity zone) {
        return new ZoneView(new ZoneId(zone.getId()), new StoreId(zone.getStoreId()),
                zone.getName(), readMatchers(zone.getRegionsJson()));
    }

    private MethodView toView(DeliveryMethodEntity method) {
        return new MethodView(new DeliveryMethodId(method.getId()), new StoreId(method.getStoreId()),
                DeliveryMethodKind.valueOf(method.getKind()), method.getLabel(), method.isActive());
    }

    private RateView toView(ShippingRateEntity rate) {
        return new RateView(new RateId(rate.getId()), new StoreId(rate.getStoreId()),
                new ZoneId(rate.getZoneId()), new DeliveryMethodId(rate.getMethodId()),
                rate.getCarrierId() == null ? null : new CarrierId(rate.getCarrierId()),
                rate.getServiceLevel(), RateRules.read(rate.getRuleJson(), json),
                new Money(rate.getHandlingFeeMinor(), storeCurrency(rate.getStoreId())),
                readTransit(rate.getTransitJson()));
    }

    private String storeCurrency(String storeId) {
        return stores.store(new StoreId(storeId))
                .map(s -> s.defaultCurrency())
                .orElse("USD");
    }

    List<RegionMatch> readMatchers(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<RegionMatch>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt zone regions_json", e);
        }
    }

    TransitEstimate readTransit(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return json.readValue(raw, TransitEstimate.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt rate transit_json", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable shipping config", e);
        }
    }

    private String quote(String raw) {
        try {
            return json.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
