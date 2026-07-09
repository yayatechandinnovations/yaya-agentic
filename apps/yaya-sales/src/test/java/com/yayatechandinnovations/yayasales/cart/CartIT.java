package com.yayatechandinnovations.yayasales.cart;

import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.catalog.ProductView;
import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.platform.Ids.CartId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
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

/**
 * §5.3 — the cart really holds what it says: add takes a SOFT hold, remove and
 * clear give it back, quantities grow by delta, and the totals come from the
 * pricing engine in the cart's region.
 */
@SpringBootTest
@Testcontainers
class CartIT {

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

    @Autowired CartService carts;
    @Autowired CatalogService catalog;
    @Autowired InventoryLedger ledger;
    @Autowired InventoryAdmin inventory;
    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;

    StoreId store;
    LocationId location;
    VariantId sku;

    @BeforeEach
    void storeWithStock() {
        store = storeService.create(new TenantRef("tenant-c"), "Cart Store", "DOP",
                StorePolicies.defaults()).id();
        location = storeDirectory.locationsOf(store).get(0).id();

        ProductView product = catalog.createProduct(
                catalog.createCatalog(store, "Main").id(),
                new ProductDraft("Soap", null, "soap", null, null, null, ProductStatus.ACTIVE, null,
                        List.of(new ProductDraft.VariantDraft("SOAP-200", "200g",
                                Money.of(11_800, "DOP"), null, null, null))));
        sku = product.variants().get(0).id();
        ledger.receive(sku, location, 10, Money.of(5_000, "DOP"), null);
    }

    private long reserved() {
        return inventory.level(sku, location).orElseThrow().reserved();
    }

    @Test
    void addTakesASoftHoldAndGrowsByDelta() {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), null);

        CartResult first = carts.add(cart.id(), sku, 2);
        CartResult second = carts.add(cart.id(), sku, 1);

        assertThat(first).isInstanceOf(CartResult.Ok.class);
        assertThat(((CartResult.Ok) second).cart().lines())
                .singleElement().satisfies(l -> assertThat(l.qty()).isEqualTo(3));
        assertThat(reserved()).isEqualTo(3);
    }

    @Test
    void insufficientIsAnAnswerNotAnError() {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), null);

        CartResult result = carts.add(cart.id(), sku, 25);

        assertThat(result).isInstanceOfSatisfying(CartResult.Insufficient.class, r -> {
            assertThat(r.available()).isEqualTo(10);
            assertThat(r.cart().lines()).isEmpty();
        });
        assertThat(reserved()).isZero();
    }

    @Test
    void setQtyShrinksAndGrowsHoldsAccordingly() {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), null);
        carts.add(cart.id(), sku, 5);

        carts.setQty(cart.id(), sku, 2);
        assertThat(reserved()).isEqualTo(2);

        carts.setQty(cart.id(), sku, 7);
        assertThat(reserved()).isEqualTo(7);

        CartResult over = carts.setQty(cart.id(), sku, 20);
        assertThat(over).isInstanceOf(CartResult.Insufficient.class);
        assertThat(reserved()).isEqualTo(7);   // failed grow leaves the holds untouched
    }

    @Test
    void removeAndClearReleaseHolds() {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), null);
        carts.add(cart.id(), sku, 4);
        assertThat(reserved()).isEqualTo(4);

        carts.remove(cart.id(), sku);
        assertThat(reserved()).isZero();

        carts.add(cart.id(), sku, 2);
        carts.clear(cart.id());
        assertThat(reserved()).isZero();
        assertThat(carts.cart(cart.id()).orElseThrow().lines()).isEmpty();
    }

    @Test
    void quoteComesFromPricingInTheCartsRegion() {
        // The seeded default region has no tax; totals are straight sums.
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), null);
        carts.add(cart.id(), sku, 2);

        PricedQuote quote = carts.quote(cart.id(), null);

        assertThat(quote.subtotal()).isEqualTo(Money.of(23_600, "DOP"));
        assertThat(quote.grandTotal()).isEqualTo(Money.of(23_600, "DOP"));
        assertThat(quote.currency()).isEqualTo("DOP");
    }

    @Test
    void mergeSumsQuantitiesAndEmptiesTheSource() {
        CartView anon = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), null);
        CartView auth = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), "shopper-1");
        carts.add(anon.id(), sku, 2);
        carts.add(auth.id(), sku, 1);

        CartView merged = carts.merge(anon.id(), auth.id());

        assertThat(merged.lines()).singleElement()
                .satisfies(l -> assertThat(l.qty()).isEqualTo(3));
        assertThat(carts.cart(anon.id()).orElseThrow().lines()).isEmpty();
        assertThat(reserved()).isEqualTo(3);
    }

    @Test
    void openBindsTheSeededSellingContext() {
        CartView cart = carts.open(store, ChannelView.Kind.WEB_CHAT, Optional.empty(), null);
        assertThat(cart.currency()).isEqualTo("DOP");
        assertThat(cart.channel()).isNotNull();
        assertThat(cart.region()).isNotNull();
    }
}
