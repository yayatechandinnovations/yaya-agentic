package com.yayatechandinnovations.yayasales.pricing;

import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.pricing.internal.PriceListEntity;
import com.yayatechandinnovations.yayasales.pricing.internal.PriceListRepository;
import com.yayatechandinnovations.yayasales.pricing.internal.PromotionEntity;
import com.yayatechandinnovations.yayasales.pricing.internal.PromotionRepository;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import com.yayatechandinnovations.yayasales.stores.TaxPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6.2/§14 — the money quote: tax-inclusive vs exclusive is a correctness
 * fork; totals must reconstruct to the minor unit with no drift, for any
 * quantities. Price-list tiers and promotions compose deterministically.
 */
@SpringBootTest
@Testcontainers
class PricingEngineIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_sales");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.sales.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("yaya.sales.outbox.poll-enabled", () -> "false");
        r.add("yaya.sales.inventory.sweep-enabled", () -> "false");
    }

    @Autowired PricingEngine engine;
    @Autowired StoreService storeService;
    @Autowired PriceListRepository priceLists;
    @Autowired PromotionRepository promotions;

    StoreId store;

    @BeforeEach
    void newStore() {
        store = storeService.create(new TenantRef("tenant-p"), "Pricing Store", "DOP",
                StorePolicies.defaults()).id();
    }

    private static CartSnapshot.Line line(String sku, long qty, long priceMinor) {
        return new CartSnapshot.Line(new VariantId(sku), qty, Money.of(priceMinor, "DOP"), sku);
    }

    private PricingContext ctx(TaxPolicy tax, String coupon, long shippingMinor) {
        return new PricingContext(new RegionId("r-1"), new ChannelId("c-1"), "DOP",
                tax, null, coupon, Money.of(shippingMinor, "DOP"));
    }

    // ── tax: the correctness fork ────────────────────────────────────────────

    @Test
    void exclusiveTaxIsAddedOnTop() {
        PricedQuote quote = engine.quote(
                new CartSnapshot(store, List.of(line("A", 2, 10_000))),
                ctx(new TaxPolicy(false, 1800, "ITBIS"), null, 500));

        assertThat(quote.subtotal()).isEqualTo(Money.of(20_000, "DOP"));
        assertThat(quote.taxTotal()).isEqualTo(Money.of(3_600, "DOP"));
        assertThat(quote.taxInclusive()).isFalse();
        // grand = goods + tax + shipping
        assertThat(quote.grandTotal()).isEqualTo(Money.of(24_100, "DOP"));
    }

    @Test
    void inclusiveTaxIsContainedInTheDisplayedPrice() {
        PricedQuote quote = engine.quote(
                new CartSnapshot(store, List.of(line("A", 1, 11_800))),
                ctx(new TaxPolicy(true, 1800, "ITBIS"), null, 0));

        assertThat(quote.taxInclusive()).isTrue();
        // 11800 contains 18%: net 10000, tax 1800 — and the total IS the displayed price.
        assertThat(quote.taxTotal()).isEqualTo(Money.of(1_800, "DOP"));
        assertThat(quote.grandTotal()).isEqualTo(Money.of(11_800, "DOP"));
    }

    @Test
    void inclusiveRoundingNeverDriftsAcrossQuantities() {
        // §14 tax-inclusive rounding: for randomized prices/quantities the parts
        // reconstruct the grand total EXACTLY — inclusive: goods + shipping;
        // exclusive: goods + tax + shipping.
        Random rng = new Random(7);
        for (int i = 0; i < 200; i++) {
            List<CartSnapshot.Line> lines = List.of(
                    line("A", rng.nextInt(9) + 1, rng.nextInt(99_999) + 1),
                    line("B", rng.nextInt(9) + 1, rng.nextInt(99_999) + 1));
            boolean inclusive = rng.nextBoolean();
            int rateBps = rng.nextInt(3000);
            long shipping = rng.nextInt(10_000);

            PricedQuote q = engine.quote(new CartSnapshot(store, lines),
                    ctx(new TaxPolicy(inclusive, rateBps, "t"), null, shipping));

            long goods = q.subtotal().amountMinor() - q.discountTotal().amountMinor();
            long expectedGrand = inclusive
                    ? goods + q.shippingTotal().amountMinor()
                    : goods + q.taxTotal().amountMinor() + q.shippingTotal().amountMinor();
            assertThat(q.grandTotal().amountMinor())
                    .as("iteration %d (inclusive=%s rate=%d)", i, inclusive, rateBps)
                    .isEqualTo(expectedGrand);
            assertThat(q.taxTotal().amountMinor()).isGreaterThanOrEqualTo(0);
        }
    }

    // ── price lists ──────────────────────────────────────────────────────────

    @Test
    void priceListTierOverridesTheListPrice() {
        priceLists.save(new PriceListEntity(Ids.newId(), store.value(), null, null, null,
                "[{\"sku\":\"A\",\"minQty\":null,\"priceMinor\":9000},"
                        + "{\"sku\":\"A\",\"minQty\":10,\"priceMinor\":8000}]"));

        PricedQuote small = engine.quote(new CartSnapshot(store, List.of(line("A", 2, 10_000))),
                ctx(TaxPolicy.none(), null, 0));
        PricedQuote bulk = engine.quote(new CartSnapshot(store, List.of(line("A", 10, 10_000))),
                ctx(TaxPolicy.none(), null, 0));

        assertThat(small.lines().get(0).unitPrice()).isEqualTo(Money.of(9_000, "DOP"));
        assertThat(bulk.lines().get(0).unitPrice()).isEqualTo(Money.of(8_000, "DOP"));   // tier kicks in
    }

    // ── promotions ───────────────────────────────────────────────────────────

    @Test
    void couponPercentOffAppliesOnlyWithTheCode() {
        promotions.save(new PromotionEntity(Ids.newId(), store.value(), "PERCENT_OFF",
                "{\"bps\":1000}", null, "SAVE10", null, null));

        PricedQuote without = engine.quote(new CartSnapshot(store, List.of(line("A", 1, 10_000))),
                ctx(TaxPolicy.none(), null, 0));
        PricedQuote with = engine.quote(new CartSnapshot(store, List.of(line("A", 1, 10_000))),
                ctx(TaxPolicy.none(), "save10", 0));

        assertThat(without.discountTotal().isZero()).isTrue();
        assertThat(with.discountTotal()).isEqualTo(Money.of(1_000, "DOP"));
        assertThat(with.grandTotal()).isEqualTo(Money.of(9_000, "DOP"));
        assertThat(with.promotions()).singleElement()
                .satisfies(p -> assertThat(p.label()).isEqualTo("SAVE10"));
    }

    @Test
    void automaticFixedOffRespectsMinSubtotalEligibility() {
        promotions.save(new PromotionEntity(Ids.newId(), store.value(), "FIXED_OFF",
                "{\"amountMinor\":500}", "{\"minSubtotalMinor\":20000}", null, null, null));

        PricedQuote below = engine.quote(new CartSnapshot(store, List.of(line("A", 1, 10_000))),
                ctx(TaxPolicy.none(), null, 0));
        PricedQuote above = engine.quote(new CartSnapshot(store, List.of(line("A", 3, 10_000))),
                ctx(TaxPolicy.none(), null, 0));

        assertThat(below.discountTotal().isZero()).isTrue();
        assertThat(above.discountTotal()).isEqualTo(Money.of(500, "DOP"));
    }

    @Test
    void freeShippingZeroesTheShippingTotal() {
        promotions.save(new PromotionEntity(Ids.newId(), store.value(), "FREE_SHIPPING",
                "{}", null, null, null, null));

        PricedQuote quote = engine.quote(new CartSnapshot(store, List.of(line("A", 1, 10_000))),
                ctx(TaxPolicy.none(), null, 700));

        assertThat(quote.shippingTotal().isZero()).isTrue();
        assertThat(quote.grandTotal()).isEqualTo(Money.of(10_000, "DOP"));
        assertThat(quote.promotions()).singleElement()
                .satisfies(p -> assertThat(p.amountOff()).isEqualTo(Money.of(700, "DOP")));
    }
}
