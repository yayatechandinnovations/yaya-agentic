package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.orders.CheckoutService;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutCommand;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.ChannelView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §8.4–8.5/§14 — the delivery quote: zone matching (country / postal prefix),
 * Flat / ByWeight / FreeOver math, DIGITAL exclusion, CarrierCalculated
 * rejection, and the address gate: an unservable ship-to fails checkout before
 * any reservation firms and the chosen option's price lands in the order's
 * shipping total.
 */
@SpringBootTest
@Testcontainers
class DeliveryQuoteIT {

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

    @Autowired DeliveryQuoteEngine quoteEngine;
    @Autowired ShippingConfig shipping;
    @Autowired CheckoutService checkout;
    @Autowired OrderService orders;
    @Autowired CartService carts;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin inventory;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;

    static final Address SANTO_DOMINGO = new Address("Calle El Conde 1", null,
            "Santo Domingo", null, "10210", "DO");
    static final Address RURAL_DO = new Address("Camino Real 5", null, "Jarabacoa", null, "51000", "DO");
    static final Address ABROAD = new Address("1 Main St", null, "Springfield", null, "62704", "US");

    StoreId store;
    LocationId location;
    VariantId sku;                       // 500 g, DOP 10 000
    DeliveryMethodId shippingMethod;     // ByWeight, whole country
    DeliveryMethodId localMethod;        // Flat 2 000 + 500 handling, city prefix only
    DeliveryMethodId freeOverMethod;     // FreeOver 25 000 → else Flat 3 000

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef("tenant-quote"), "Quote Store", "DOP",
                StorePolicies.defaults()).id();
        location = storeDirectory.locationsOf(store).get(0).id();
        var product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Candle", null, "decor", null, null, null, ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("CNDL-1", "500g",
                                Money.of(10_000, "DOP"), null, 500, null))));
        sku = product.variants().get(0).id();
        ledger.receive(sku, location, 20, Money.of(4_000, "DOP"), null);

        var countryZone = shipping.addZone(store, "Dominican Republic",
                List.of(new ShippingConfig.RegionMatch("DO", null, null)));
        var cityZone = shipping.addZone(store, "Santo Domingo",
                List.of(new ShippingConfig.RegionMatch("DO", null, "10")));

        shippingMethod = shipping.addMethod(store, DeliveryMethodKind.SHIPPING, "Standard").id();
        localMethod = shipping.addMethod(store, DeliveryMethodKind.LOCAL_DELIVERY, "City courier").id();
        freeOverMethod = shipping.addMethod(store, DeliveryMethodKind.SHIPPING, "Economy").id();
        shipping.addMethod(store, DeliveryMethodKind.DIGITAL, "Email");   // must never appear

        shipping.addRate(store, countryZone.id(), shippingMethod, null, null,
                new RateRule.ByWeight(List.of(
                        new RateRule.Tier(1_000, Money.of(5_000, "DOP")),
                        new RateRule.Tier(5_000, Money.of(8_000, "DOP")))),
                Money.zero("DOP"), new ShippingConfig.TransitEstimate(2, 5));
        shipping.addRate(store, cityZone.id(), localMethod, null, null,
                new RateRule.Flat(Money.of(2_000, "DOP")),
                Money.of(500, "DOP"), new ShippingConfig.TransitEstimate(0, 1));
        shipping.addRate(store, countryZone.id(), freeOverMethod, null, null,
                new RateRule.FreeOver(Money.of(25_000, "DOP"),
                        new RateRule.Flat(Money.of(3_000, "DOP"))),
                Money.zero("DOP"), new ShippingConfig.TransitEstimate(3, 8));
    }

    private List<DeliveryOption> quote(Address destination, long qty, long cartValueMinor) {
        return quoteEngine.quote(new DeliveryQuoteRequest(store, null, destination,
                List.of(new DeliveryQuoteRequest.Line(sku, qty)),
                Money.of(cartValueMinor, "DOP")));
    }

    // ── zone matching (§8.5) ─────────────────────────────────────────────────

    @Test
    void zoneMatchingOffersOnlyWhatCoversTheAddress() {
        assertThat(quote(ABROAD, 1, 10_000)).isEmpty();   // unservable

        List<DeliveryOption> rural = quote(RURAL_DO, 1, 10_000);
        assertThat(rural).extracting(o -> o.method().value())
                .containsExactlyInAnyOrder(shippingMethod.value(), freeOverMethod.value());

        List<DeliveryOption> city = quote(SANTO_DOMINGO, 1, 10_000);
        assertThat(city).extracting(o -> o.method().value())
                .containsExactlyInAnyOrder(shippingMethod.value(), freeOverMethod.value(),
                        localMethod.value());
        // DIGITAL never appears — it skips delivery (§8.4).
        assertThat(city).noneMatch(o -> o.kind() == DeliveryMethodKind.DIGITAL);
    }

    // ── rate math (§8.5) ─────────────────────────────────────────────────────

    @Test
    void flatRateAddsItsHandlingFee() {
        DeliveryOption local = optionFor(quote(SANTO_DOMINGO, 1, 10_000), localMethod);
        assertThat(local.price()).isEqualTo(Money.of(2_500, "DOP"));   // 2 000 + 500
        assertThat(local.kind()).isEqualTo(DeliveryMethodKind.LOCAL_DELIVERY);
        assertThat(local.promisedTo()).isAfterOrEqualTo(local.promisedFrom());
    }

    @Test
    void byWeightPicksTheTierForTheCartsCatalogWeight() {
        // 1 × 500 g → first tier; 4 × 500 g = 2 kg → second; 20 × = 10 kg → beyond last = last.
        assertThat(optionFor(quote(RURAL_DO, 1, 10_000), shippingMethod).price())
                .isEqualTo(Money.of(5_000, "DOP"));
        assertThat(optionFor(quote(RURAL_DO, 4, 40_000), shippingMethod).price())
                .isEqualTo(Money.of(8_000, "DOP"));
        assertThat(optionFor(quote(RURAL_DO, 20, 200_000), shippingMethod).price())
                .isEqualTo(Money.of(8_000, "DOP"));
    }

    @Test
    void freeOverZeroesShippingAboveTheThreshold() {
        assertThat(optionFor(quote(RURAL_DO, 1, 10_000), freeOverMethod).price())
                .isEqualTo(Money.of(3_000, "DOP"));
        assertThat(optionFor(quote(RURAL_DO, 3, 30_000), freeOverMethod).price())
                .isEqualTo(Money.of(0, "DOP"));
    }

    @Test
    void carrierCalculatedRatesAreRejectedAtAuthoring() {
        var zone = shipping.zonesOf(store).get(0);
        assertThatThrownBy(() -> shipping.addRate(store, zone.id(), shippingMethod, null, null,
                new RateRule.CarrierCalculated(new Ids.CarrierId("dhl")),
                Money.zero("DOP"), null))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
        // …even hidden inside a FreeOver fallback.
        assertThatThrownBy(() -> shipping.addRate(store, zone.id(), shippingMethod, null, null,
                new RateRule.FreeOver(Money.of(1, "DOP"),
                        new RateRule.CarrierCalculated(new Ids.CarrierId("dhl"))),
                Money.zero("DOP"), null))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
    }

    // ── the address gate + checkout integration (§8.2, §14) ─────────────────

    @Test
    void unservableAddressFailsCheckoutBeforeAnyReservationFirms() {
        CartView cart = cartWith(2);
        long reservedBefore = reserved();

        assertThatThrownBy(() -> checkout.checkout(new CheckoutCommand(cart.id(), TenderKind.COD,
                false, null, null, "shopper-1", Ids.newId(),
                ABROAD, shippingMethod, null)))
                .isInstanceOf(ApiErrors.UnprocessableException.class);

        assertThat(reserved()).isEqualTo(reservedBefore);   // soft holds untouched, nothing firmed
        assertThat(orders.ordersOf(store, Optional.empty())).isEmpty();   // no phantom order
        assertThat(onHand()).isEqualTo(20);
    }

    @Test
    void malformedAddressFailsTheSameWay() {
        CartView cart = cartWith(1);
        assertThatThrownBy(() -> checkout.checkout(new CheckoutCommand(cart.id(), TenderKind.COD,
                false, null, null, "shopper-1", Ids.newId(),
                new Address(null, null, "Santo Domingo", null, null, "DO"),   // no line1
                localMethod, null)))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
        assertThat(orders.ordersOf(store, Optional.empty())).isEmpty();
    }

    @Test
    void chosenMethodsPriceBecomesTheOrdersShippingTotal() {
        CartView cart = cartWith(1);   // goods 10 000

        CheckoutOutcome outcome = checkout.checkout(new CheckoutCommand(cart.id(), TenderKind.COD,
                false, null, null, "shopper-1", Ids.newId(),
                SANTO_DOMINGO, localMethod, null));

        OrderView order = ((CheckoutOutcome.Placed) outcome).order();
        assertThat(order.totals().shipping()).isEqualTo(Money.of(2_500, "DOP"));
        assertThat(order.totals().grand()).isEqualTo(Money.of(12_500, "DOP"));
        // The §8.2 snapshot: method + promised window + validated ship-to.
        assertThat(order.delivery()).isNotNull();
        assertThat(order.delivery().method()).isEqualTo(localMethod);
        assertThat(order.delivery().kind()).isEqualTo(DeliveryMethodKind.LOCAL_DELIVERY.name());
        assertThat(order.delivery().promisedTo()).isAfterOrEqualTo(order.delivery().promisedFrom());
        assertThat(order.delivery().shipTo().country()).isEqualTo("DO");
    }

    @Test
    void methodNotOfferedForTheAddressFailsCheckout() {
        CartView cart = cartWith(1);
        // The city-only local method, shipped-to rural DO → not offered there.
        assertThatThrownBy(() -> checkout.checkout(new CheckoutCommand(cart.id(), TenderKind.COD,
                false, null, null, "shopper-1", Ids.newId(),
                RURAL_DO, localMethod, null)))
                .isInstanceOf(ApiErrors.UnprocessableException.class);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private DeliveryOption optionFor(List<DeliveryOption> options, DeliveryMethodId method) {
        return options.stream().filter(o -> o.method().equals(method)).findFirst().orElseThrow();
    }

    private CartView cartWith(long qty) {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-1");
        carts.add(cart.id(), sku, qty);
        return cart;
    }

    private long onHand() {
        return inventory.level(sku, location).orElseThrow().onHand();
    }

    private long reserved() {
        return inventory.level(sku, location).orElseThrow().reserved();
    }
}
