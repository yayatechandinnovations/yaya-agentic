package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yayatechandinnovations.yayasales.fulfillment.RateRule;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The {@code rule_json} codec + the §8.5 pricing math. Kept together so the
 * stored shape and its evaluation can never drift apart.
 */
final class RateRules {

    private RateRules() {}

    // ── evaluation (§8.4: weight from the catalog, value from the quote) ─────

    static Money apply(RateRule rule, long totalWeightGrams, Money cartValue) {
        return switch (rule) {
            case RateRule.Flat flat -> flat.amount();
            case RateRule.ByWeight byWeight -> tiered(byWeight.tiers(), totalWeightGrams);
            case RateRule.ByPrice byPrice -> tiered(byPrice.tiers(), cartValue.amountMinor());
            case RateRule.FreeOver freeOver -> cartValue.amountMinor() >= freeOver.threshold().amountMinor()
                    ? Money.zero(freeOver.threshold().currency())
                    : apply(freeOver.otherwise(), totalWeightGrams, cartValue);
            case RateRule.CarrierCalculated ignored ->
                    throw new IllegalStateException("CarrierCalculated rates are vNext (decision 8)");
        };
    }

    /** First tier whose ceiling covers the measure; beyond the last tier, the last tier. */
    private static Money tiered(List<RateRule.Tier> tiers, long measure) {
        List<RateRule.Tier> sorted = tiers.stream()
                .sorted(Comparator.comparingLong(RateRule.Tier::upTo)).toList();
        return sorted.stream()
                .filter(t -> measure <= t.upTo())
                .findFirst()
                .orElse(sorted.get(sorted.size() - 1))
                .amount();
    }

    // ── codec ─────────────────────────────────────────────────────────────────

    static String write(RateRule rule, ObjectMapper json) {
        ObjectNode node = json.createObjectNode();
        switch (rule) {
            case RateRule.Flat flat -> {
                node.put("type", "FLAT");
                money(node, "amount", flat.amount());
            }
            case RateRule.ByWeight byWeight -> {
                node.put("type", "BY_WEIGHT");
                tiers(node, byWeight.tiers(), json);
            }
            case RateRule.ByPrice byPrice -> {
                node.put("type", "BY_PRICE");
                tiers(node, byPrice.tiers(), json);
            }
            case RateRule.FreeOver freeOver -> {
                node.put("type", "FREE_OVER");
                money(node, "threshold", freeOver.threshold());
                node.set("otherwise", readTree(write(freeOver.otherwise(), json), json));
            }
            case RateRule.CarrierCalculated cc -> {
                node.put("type", "CARRIER_CALCULATED");
                node.put("carrierId", cc.carrier().value());
            }
        }
        return node.toString();
    }

    static RateRule read(String raw, ObjectMapper json) {
        return read(readTree(raw, json));
    }

    private static RateRule read(JsonNode node) {
        String type = node.path("type").asText();
        return switch (type) {
            case "FLAT" -> new RateRule.Flat(money(node, "amount"));
            case "BY_WEIGHT" -> new RateRule.ByWeight(tiers(node));
            case "BY_PRICE" -> new RateRule.ByPrice(tiers(node));
            case "FREE_OVER" -> new RateRule.FreeOver(money(node, "threshold"),
                    read(node.path("otherwise")));
            case "CARRIER_CALCULATED" -> new RateRule.CarrierCalculated(
                    new CarrierId(node.path("carrierId").asText()));
            default -> throw new IllegalStateException("unknown rate rule type: " + type);
        };
    }

    private static void money(ObjectNode node, String field, Money amount) {
        node.put(field + "Minor", amount.amountMinor());
        node.put(field + "Currency", amount.currency());
    }

    private static Money money(JsonNode node, String field) {
        return new Money(node.path(field + "Minor").asLong(),
                node.path(field + "Currency").asText());
    }

    private static void tiers(ObjectNode node, List<RateRule.Tier> tiers, ObjectMapper json) {
        ArrayNode array = json.createArrayNode();
        for (RateRule.Tier tier : tiers) {
            ObjectNode t = json.createObjectNode();
            t.put("upTo", tier.upTo());
            money(t, "amount", tier.amount());
            array.add(t);
        }
        node.set("tiers", array);
    }

    private static List<RateRule.Tier> tiers(JsonNode node) {
        List<RateRule.Tier> tiers = new ArrayList<>();
        for (JsonNode t : node.path("tiers")) {
            tiers.add(new RateRule.Tier(t.path("upTo").asLong(), money(t, "amount")));
        }
        return tiers;
    }

    private static JsonNode readTree(String raw, ObjectMapper json) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt rate rule_json", e);
        }
    }
}
