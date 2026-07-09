package com.yayatechandinnovations.yayasales.pricing.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
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
import java.util.Map;

/** Price-list + promotion authoring (design §10.1). */
@RestController
@RequestMapping("/v1/sales/admin")
public class PricingAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final PriceListRepository priceLists;
    private final PromotionRepository promotions;
    private final ObjectMapper json;

    public PricingAdminController(PriceListRepository priceLists, PromotionRepository promotions,
                                  ObjectMapper json) {
        this.priceLists = priceLists;
        this.promotions = promotions;
        this.json = json;
    }

    @GetMapping("/price-lists")
    public List<Map<String, Object>> priceLists(@RequestHeader(STORE_HEADER) String storeId) {
        return priceLists.findByStoreId(storeId).stream().map(this::toMap).toList();
    }

    @PostMapping("/price-lists")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPriceList(@RequestHeader(STORE_HEADER) String storeId,
                                               @Valid @RequestBody CreatePriceList body) {
        PriceListEntity list = new PriceListEntity(Ids.newId(), storeId,
                body.regionId(), body.channelId(), body.customerGroup(), write(body.rules()));
        priceLists.save(list);
        return toMap(list);
    }

    @PutMapping("/price-lists/{id}")
    public Map<String, Object> updatePriceList(@PathVariable String id,
                                               @Valid @RequestBody UpdatePriceList body) {
        PriceListEntity list = priceLists.findById(id)
                .orElseThrow(() -> new ApiErrors.NotFoundException("price list", id));
        if (body.rules() != null) list.setRulesJson(write(body.rules()));
        if (body.active() != null) list.setActive(body.active());
        priceLists.save(list);
        return toMap(list);
    }

    @GetMapping("/promotions")
    public List<Map<String, Object>> promotions(@RequestHeader(STORE_HEADER) String storeId) {
        return promotions.findByStoreId(storeId).stream().map(this::toMap).toList();
    }

    @PostMapping("/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPromotion(@RequestHeader(STORE_HEADER) String storeId,
                                               @Valid @RequestBody CreatePromotion body) {
        PromotionEntity promo = new PromotionEntity(Ids.newId(), storeId, body.kind(),
                write(body.value()), body.eligibility() == null ? null : write(body.eligibility()),
                body.code(), body.startsAt(), body.endsAt());
        promotions.save(promo);
        return toMap(promo);
    }

    @PutMapping("/promotions/{id}")
    public Map<String, Object> updatePromotion(@PathVariable String id,
                                               @RequestBody Map<String, Object> body) {
        PromotionEntity promo = promotions.findById(id)
                .orElseThrow(() -> new ApiErrors.NotFoundException("promotion", id));
        if (body.containsKey("active")) promo.setActive(Boolean.TRUE.equals(body.get("active")));
        promotions.save(promo);
        return toMap(promo);
    }

    private Map<String, Object> toMap(PriceListEntity e) {
        return Map.of("id", e.getId(), "regionId", nullable(e.getRegionId()),
                "channelId", nullable(e.getChannelId()),
                "customerGroup", nullable(e.getCustomerGroup()),
                "rules", read(e.getRulesJson()), "active", e.isActive());
    }

    private Map<String, Object> toMap(PromotionEntity e) {
        return Map.of("id", e.getId(), "kind", e.getKind(), "value", read(e.getValueJson()),
                "eligibility", e.getEligibilityJson() == null ? Map.of() : read(e.getEligibilityJson()),
                "code", nullable(e.getCode()), "active", e.isActive());
    }

    private static Object nullable(String value) {
        return value == null ? "" : value;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("unserializable body", e);
        }
    }

    private Object read(String raw) {
        try {
            return json.readValue(raw, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt json column", e);
        }
    }

    public record CreatePriceList(String regionId, String channelId, String customerGroup,
                                  @NotNull List<DefaultPricingEngine.Rule> rules) {}

    public record UpdatePriceList(List<DefaultPricingEngine.Rule> rules, Boolean active) {}

    public record CreatePromotion(@NotBlank String kind,
                                  @NotNull DefaultPricingEngine.PromoValue value,
                                  DefaultPricingEngine.Eligibility eligibility,
                                  String code, Instant startsAt, Instant endsAt) {}
}
