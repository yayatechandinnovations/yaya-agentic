package com.yayatechandinnovations.yayasales.pricing.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.pricing.CartSnapshot;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
import com.yayatechandinnovations.yayasales.pricing.PricingContext;
import com.yayatechandinnovations.yayasales.pricing.PricingEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * §6.2 — list price → most-specific price-list tier → promotions → tax.
 * Integer minor units end to end; the inclusive path derives the contained tax
 * by exact division so the grand total reconstructs to the minor unit (§14).
 */
@Service
public class DefaultPricingEngine implements PricingEngine {

    private final PriceListRepository priceLists;
    private final PromotionRepository promotions;
    private final ObjectMapper json;

    public DefaultPricingEngine(PriceListRepository priceLists, PromotionRepository promotions,
                                ObjectMapper json) {
        this.priceLists = priceLists;
        this.promotions = promotions;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public PricedQuote quote(CartSnapshot cart, PricingContext ctx) {
        Objects.requireNonNull(ctx.currency(), "pricing context needs a currency");
        String currency = ctx.currency();

        // 1. line prices: most specific active price-list tier, else list price
        List<PriceListEntity> lists = priceLists.findByStoreIdAndActiveTrue(cart.store().value());
        List<PricedQuote.PricedLine> lines = new ArrayList<>();
        long subtotal = 0;
        for (CartSnapshot.Line line : cart.lines()) {
            long unit = resolveUnitPrice(line, lists, ctx);
            long lineTotal = Math.multiplyExact(unit, line.qty());
            subtotal = Math.addExact(subtotal, lineTotal);
            lines.add(new PricedQuote.PricedLine(line.sku(), line.title(), line.qty(),
                    new Money(unit, currency), new Money(lineTotal, currency)));
        }

        // 2. promotions (deterministic; v1 simple stacking capped at the goods total)
        long discount = 0;
        long shipping = ctx.shipping() == null ? 0 : ctx.shipping().amountMinor();
        List<PricedQuote.AppliedPromotion> applied = new ArrayList<>();
        for (PromotionEntity promo : promotions.findByStoreIdAndActiveTrue(cart.store().value())) {
            if (!promo.live(Instant.now())) continue;
            if (promo.getCode() != null && !promo.getCode().equalsIgnoreCase(
                    ctx.couponCode() == null ? "" : ctx.couponCode())) continue;

            Eligibility eligibility = readEligibility(promo.getEligibilityJson());
            long eligibleBase = eligibleBase(lines, eligibility);
            if (eligibility != null && eligibility.minSubtotalMinor() != null
                    && subtotal < eligibility.minSubtotalMinor()) continue;
            if (eligibleBase == 0 && !"FREE_SHIPPING".equals(promo.getKind())) continue;

            PromoValue value = readValue(promo.getValueJson());
            long off = switch (promo.getKind()) {
                case "PERCENT_OFF" -> BigDecimal.valueOf(eligibleBase)
                        .multiply(BigDecimal.valueOf(value.bps() == null ? 0 : value.bps()))
                        .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN)
                        .longValueExact();
                case "FIXED_OFF" -> Math.min(
                        value.amountMinor() == null ? 0 : value.amountMinor(), eligibleBase);
                case "FREE_SHIPPING" -> 0;
                default -> 0;
            };
            if ("FREE_SHIPPING".equals(promo.getKind()) && shipping > 0) {
                applied.add(new PricedQuote.AppliedPromotion(promo.getId(), "Free shipping",
                        new Money(shipping, currency)));
                shipping = 0;
            } else if (off > 0) {
                off = Math.min(off, subtotal - discount);   // never discount below zero
                discount = Math.addExact(discount, off);
                applied.add(new PricedQuote.AppliedPromotion(promo.getId(),
                        promo.getCode() == null ? promo.getKind() : promo.getCode(),
                        new Money(off, currency)));
            }
        }

        // 3. tax — the correctness fork (§6.2)
        long goods = subtotal - discount;
        long tax;
        long grand;
        boolean inclusive = ctx.tax() != null && ctx.tax().inclusive();
        int rateBps = ctx.tax() == null ? 0 : ctx.tax().rateBps();
        if (inclusive) {
            // Displayed prices CONTAIN tax: net = goods / (1 + rate); tax is the
            // remainder, so goods == net + tax exactly — no rounding drift.
            long net = BigDecimal.valueOf(goods)
                    .multiply(BigDecimal.valueOf(10_000))
                    .divide(BigDecimal.valueOf(10_000L + rateBps), 0, RoundingMode.HALF_EVEN)
                    .longValueExact();
            tax = goods - net;
            grand = Math.addExact(goods, shipping);
        } else {
            tax = BigDecimal.valueOf(goods)
                    .multiply(BigDecimal.valueOf(rateBps))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN)
                    .longValueExact();
            grand = Math.addExact(Math.addExact(goods, tax), shipping);
        }

        return new PricedQuote(lines,
                new Money(subtotal, currency),
                new Money(discount, currency),
                new Money(tax, currency),
                inclusive,
                new Money(shipping, currency),
                new Money(grand, currency),
                applied, currency);
    }

    // ── price lists ──────────────────────────────────────────────────────────

    private long resolveUnitPrice(CartSnapshot.Line line, List<PriceListEntity> lists,
                                  PricingContext ctx) {
        return lists.stream()
                .filter(list -> matches(list, ctx))
                .sorted(Comparator.comparingInt(this::specificity).reversed())
                .map(list -> bestTier(list, line))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(line.listPrice().amountMinor());
    }

    private boolean matches(PriceListEntity list, PricingContext ctx) {
        return (list.getRegionId() == null || list.getRegionId().equals(ctx.region().value()))
                && (list.getChannelId() == null || list.getChannelId().equals(ctx.channel().value()))
                && (list.getCustomerGroup() == null
                        || list.getCustomerGroup().equals(ctx.customerGroup()));
    }

    private int specificity(PriceListEntity list) {
        return (list.getRegionId() != null ? 1 : 0)
                + (list.getChannelId() != null ? 1 : 0)
                + (list.getCustomerGroup() != null ? 1 : 0);
    }

    /** Highest applicable tier: the rule with the largest minQty ≤ line qty wins. */
    private Long bestTier(PriceListEntity list, CartSnapshot.Line line) {
        return readRules(list.getRulesJson()).stream()
                .filter(rule -> rule.sku().equals(line.sku().value()))
                .filter(rule -> rule.minQty() == null || rule.minQty() <= line.qty())
                .max(Comparator.comparingLong(rule -> rule.minQty() == null ? 0 : rule.minQty()))
                .map(Rule::priceMinor)
                .orElse(null);
    }

    private long eligibleBase(List<PricedQuote.PricedLine> lines, Eligibility eligibility) {
        if (eligibility == null || eligibility.skus() == null || eligibility.skus().isEmpty()) {
            return lines.stream().mapToLong(l -> l.lineTotal().amountMinor()).sum();
        }
        Set<String> skus = Set.copyOf(eligibility.skus());
        return lines.stream()
                .filter(l -> skus.contains(l.sku().value()))
                .mapToLong(l -> l.lineTotal().amountMinor())
                .sum();
    }

    // ── json shapes ──────────────────────────────────────────────────────────

    record Rule(String sku, Long minQty, long priceMinor) {}
    record PromoValue(Integer bps, Long amountMinor) {}
    record Eligibility(Long minSubtotalMinor, List<String> skus) {}

    private List<Rule> readRules(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<Rule>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt price list rules_json", e);
        }
    }

    private PromoValue readValue(String raw) {
        try {
            return json.readValue(raw, PromoValue.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt promotion value_json", e);
        }
    }

    private Eligibility readEligibility(String raw) {
        if (raw == null) return null;
        try {
            return json.readValue(raw, Eligibility.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt promotion eligibility_json", e);
        }
    }
}
