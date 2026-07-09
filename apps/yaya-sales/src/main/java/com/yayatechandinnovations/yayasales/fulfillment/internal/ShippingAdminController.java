package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliveryMethodKind;
import com.yayatechandinnovations.yayasales.fulfillment.DeliverySlots;
import com.yayatechandinnovations.yayasales.fulfillment.RateRule;
import com.yayatechandinnovations.yayasales.fulfillment.ShippingConfig;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.ZoneId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Shipping config admin (design §10.1): zones, methods, rates, carriers,
 * slots — all merchant-authored, all scoped to the {@code X-Yaya-Store-Id}
 * store like the sibling admin controllers.
 */
@RestController
@RequestMapping("/v1/sales/admin/shipping")
public class ShippingAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final ShippingConfig config;
    private final StoreDirectory stores;

    public ShippingAdminController(ShippingConfig config, StoreDirectory stores) {
        this.config = config;
        this.stores = stores;
    }

    // ── zones ────────────────────────────────────────────────────────────────

    @PostMapping("/zones")
    @ResponseStatus(HttpStatus.CREATED)
    public ShippingConfig.ZoneView addZone(@RequestHeader(STORE_HEADER) String storeId,
                                           @Valid @RequestBody CreateZone body) {
        return config.addZone(new StoreId(storeId), body.name(), body.regions());
    }

    @GetMapping("/zones")
    public List<ShippingConfig.ZoneView> zones(@RequestHeader(STORE_HEADER) String storeId) {
        return config.zonesOf(new StoreId(storeId));
    }

    // ── methods ──────────────────────────────────────────────────────────────

    @PostMapping("/methods")
    @ResponseStatus(HttpStatus.CREATED)
    public ShippingConfig.MethodView addMethod(@RequestHeader(STORE_HEADER) String storeId,
                                               @Valid @RequestBody CreateMethod body) {
        return config.addMethod(new StoreId(storeId), body.kind(), body.label());
    }

    @PutMapping("/methods/{id}")
    public ShippingConfig.MethodView setActive(@PathVariable String id,
                                               @RequestBody UpdateMethod body) {
        return config.setMethodActive(new DeliveryMethodId(id),
                body.active() == null || body.active());
    }

    @GetMapping("/methods")
    public List<ShippingConfig.MethodView> methods(@RequestHeader(STORE_HEADER) String storeId) {
        return config.methodsOf(new StoreId(storeId));
    }

    // ── carriers ─────────────────────────────────────────────────────────────

    @PostMapping("/carriers")
    @ResponseStatus(HttpStatus.CREATED)
    public ShippingConfig.CarrierView addCarrier(@RequestHeader(STORE_HEADER) String storeId,
                                                 @Valid @RequestBody CreateCarrier body) {
        return config.addCarrier(new StoreId(storeId), body.kind(), body.name(),
                body.webhookSecret());
    }

    @GetMapping("/carriers")
    public List<ShippingConfig.CarrierView> carriers(@RequestHeader(STORE_HEADER) String storeId) {
        return config.carriersOf(new StoreId(storeId));
    }

    // ── rates ────────────────────────────────────────────────────────────────

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    public ShippingConfig.RateView addRate(@RequestHeader(STORE_HEADER) String storeId,
                                           @Valid @RequestBody CreateRate body) {
        String currency = currencyOf(storeId);
        return config.addRate(new StoreId(storeId), new ZoneId(body.zoneId()),
                new DeliveryMethodId(body.methodId()),
                body.carrierId() == null ? null : new CarrierId(body.carrierId()),
                body.serviceLevel(), body.rule().toRule(currency),
                new Money(body.handlingFeeMinor() == null ? 0 : body.handlingFeeMinor(), currency),
                body.transit());
    }

    @GetMapping("/rates")
    public List<ShippingConfig.RateView> rates(@RequestHeader(STORE_HEADER) String storeId) {
        return config.ratesOf(new StoreId(storeId));
    }

    // ── slots ────────────────────────────────────────────────────────────────

    @PostMapping("/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public DeliverySlots.SlotView addSlot(@RequestHeader(STORE_HEADER) String storeId,
                                          @Valid @RequestBody CreateSlot body) {
        return config.addSlot(new StoreId(storeId), new LocationId(body.locationId()),
                new DeliveryMethodId(body.methodId()), body.startsAt(), body.endsAt(),
                body.capacity());
    }

    // ── shapes ───────────────────────────────────────────────────────────────

    private String currencyOf(String storeId) {
        return stores.store(new StoreId(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("store", storeId))
                .defaultCurrency();
    }

    public record CreateZone(@NotBlank String name,
                             @NotNull List<ShippingConfig.RegionMatch> regions) {}

    public record CreateMethod(@NotNull DeliveryMethodKind kind, @NotBlank String label) {}

    public record UpdateMethod(Boolean active) {}

    public record CreateCarrier(@NotBlank String kind, @NotBlank String name,
                                String webhookSecret) {}

    public record CreateRate(@NotBlank String zoneId, @NotBlank String methodId,
                             String carrierId, String serviceLevel, @NotNull RuleBody rule,
                             Long handlingFeeMinor, ShippingConfig.TransitEstimate transit) {}

    public record CreateSlot(@NotBlank String locationId, @NotBlank String methodId,
                             @NotNull Instant startsAt, @NotNull Instant endsAt, int capacity) {}

    /** The recursive rule wire shape; amounts default to the store currency. */
    public record RuleBody(String type, Long amountMinor, List<TierBody> tiers,
                           Long thresholdMinor, RuleBody otherwise, String carrierId) {

        RateRule toRule(String currency) {
            if (type == null) {
                throw new ApiErrors.UnprocessableException("rate rule needs a type");
            }
            return switch (type) {
                case "FLAT" -> new RateRule.Flat(new Money(zeroIfNull(amountMinor), currency));
                case "BY_WEIGHT" -> new RateRule.ByWeight(toTiers(currency));
                case "BY_PRICE" -> new RateRule.ByPrice(toTiers(currency));
                case "FREE_OVER" -> {
                    if (otherwise == null) {
                        throw new ApiErrors.UnprocessableException(
                                "FREE_OVER needs an 'otherwise' rule");
                    }
                    yield new RateRule.FreeOver(new Money(zeroIfNull(thresholdMinor), currency),
                            otherwise.toRule(currency));
                }
                case "CARRIER_CALCULATED" -> new RateRule.CarrierCalculated(
                        new CarrierId(carrierId == null ? "" : carrierId));
                default -> throw new ApiErrors.UnprocessableException(
                        "unknown rate rule type: " + type);
            };
        }

        private List<RateRule.Tier> toTiers(String currency) {
            if (tiers == null) {
                return List.of();
            }
            return tiers.stream()
                    .map(t -> new RateRule.Tier(t.upTo(),
                            new Money(zeroIfNull(t.amountMinor()), currency)))
                    .toList();
        }

        private static long zeroIfNull(Long value) {
            return value == null ? 0 : value;
        }
    }

    public record TierBody(long upTo, Long amountMinor) {}
}
