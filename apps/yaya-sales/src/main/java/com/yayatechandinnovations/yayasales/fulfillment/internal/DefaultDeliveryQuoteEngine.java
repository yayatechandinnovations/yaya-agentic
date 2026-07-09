package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryMethodKind;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryOption;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryQuoteEngine;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryQuoteRequest;
import com.yayatechandinnovations.yayasales.fulfillment.RateRule;
import com.yayatechandinnovations.yayasales.fulfillment.ShippingConfig;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.stores.SellingContextService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * §8.4 resolution: destination → matched zones (§8.5, region-restricted when
 * the region names zones) → active non-DIGITAL methods → the cheapest rate per
 * method×serviceLevel. Weight = Σ catalog {@code weightGrams} × qty (a
 * weightless variant weighs zero); value = the priced goods total. Rates
 * authored in another currency are skipped, never coerced (§5.4).
 */
@Service
public class DefaultDeliveryQuoteEngine implements DeliveryQuoteEngine {

    private final ShippingZoneRepository zones;
    private final ShippingRateRepository rates;
    private final DeliveryMethodRepository methods;
    private final DefaultShippingConfig config;
    private final SellingContextService sellingContext;
    private final CatalogService catalog;
    private final ObjectMapper json;

    public DefaultDeliveryQuoteEngine(ShippingZoneRepository zones, ShippingRateRepository rates,
                                      DeliveryMethodRepository methods, DefaultShippingConfig config,
                                      SellingContextService sellingContext, CatalogService catalog,
                                      ObjectMapper json) {
        this.zones = zones;
        this.rates = rates;
        this.methods = methods;
        this.config = config;
        this.sellingContext = sellingContext;
        this.catalog = catalog;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryOption> quote(DeliveryQuoteRequest request) {
        List<String> matchedZones = matchedZoneIds(request);
        if (matchedZones.isEmpty()) {
            return List.of();   // unservable — that is the whole answer
        }

        Map<String, DeliveryMethodEntity> offered = methods.findByStoreId(request.store().value())
                .stream()
                .filter(DeliveryMethodEntity::isActive)
                .filter(m -> !DeliveryMethodKind.DIGITAL.name().equals(m.getKind()))
                .collect(Collectors.toMap(DeliveryMethodEntity::getId, Function.identity()));

        long weightGrams = totalWeight(request.lines());
        Instant now = Instant.now();

        List<DeliveryOption> options = new ArrayList<>();
        for (ShippingRateEntity rate : rates.findByZoneIdIn(matchedZones)) {
            DeliveryMethodEntity method = offered.get(rate.getMethodId());
            if (method == null) {
                continue;
            }
            RateRule rule = RateRules.read(rate.getRuleJson(), json);
            Money price;
            try {
                price = RateRules.apply(rule, weightGrams, request.cartValue())
                        .plus(new Money(rate.getHandlingFeeMinor(), request.cartValue().currency()));
            } catch (Money.CurrencyMismatchException e) {
                continue;   // authored for a different currency — not offered here
            }
            ShippingConfig.TransitEstimate transit = config.readTransit(rate.getTransitJson());
            options.add(new DeliveryOption(
                    new DeliveryMethodId(method.getId()),
                    DeliveryMethodKind.valueOf(method.getKind()),
                    method.getLabel(),
                    price,
                    now.plus(Duration.ofDays(transit == null ? 0 : transit.minDays())),
                    now.plus(Duration.ofDays(transit == null ? 0 : transit.maxDays())),
                    rate.getCarrierId() == null ? null : new CarrierId(rate.getCarrierId()),
                    rate.getServiceLevel()));
        }

        // One option per method×serviceLevel — overlapping zones keep the cheapest.
        return options.stream()
                .collect(Collectors.toMap(
                        o -> o.method().value() + "|" + o.serviceLevel(),
                        Function.identity(),
                        (a, b) -> a.price().amountMinor() <= b.price().amountMinor() ? a : b))
                .values().stream()
                .sorted(Comparator.comparingLong(o -> o.price().amountMinor()))
                .toList();
    }

    private List<String> matchedZoneIds(DeliveryQuoteRequest request) {
        List<ShippingZoneEntity> candidates = zones.findByStoreId(request.store().value());
        if (request.region() != null) {
            List<String> allowed = sellingContext.region(request.region())
                    .map(r -> r.shippingZones()).orElse(List.of());
            if (!allowed.isEmpty()) {
                candidates = candidates.stream().filter(z -> allowed.contains(z.getId())).toList();
            }
        }
        return candidates.stream()
                .filter(z -> ZoneMatching.matches(config.readMatchers(z.getRegionsJson()),
                        request.destination()))
                .map(ShippingZoneEntity::getId)
                .toList();
    }

    private long totalWeight(List<DeliveryQuoteRequest.Line> lines) {
        long total = 0;
        for (DeliveryQuoteRequest.Line line : lines) {
            Integer grams = catalog.productOfVariant(line.sku())
                    .flatMap(p -> p.variants().stream()
                            .filter(v -> v.id().equals(line.sku())).findFirst())
                    .map(v -> v.weightGrams())
                    .orElse(null);
            total += (grams == null ? 0L : grams) * line.qty();
        }
        return total;
    }
}
