package com.yayatechandinnovations.yayasales.catalog;

import com.yayatechandinnovations.yayasales.catalog.BulkImport;
import com.yayatechandinnovations.yayasales.platform.Ids.CatalogId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.money.Money;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S1 exit: create products, author them, round-trip CSV — plus store seeding. */
@SpringBootTest
@Testcontainers
class CatalogAuthoringIT {

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

    @Autowired StoreService storeService;
    @Autowired StoreDirectory storeDirectory;
    @Autowired CatalogService catalog;
    @Autowired BulkImport bulk;

    StoreId store;
    CatalogId catalogId;

    @BeforeEach
    void newStoreAndCatalog() {
        store = storeService.create(new TenantRef("tenant-t"), "Soap Co", "DOP",
                StorePolicies.defaults()).id();
        catalogId = catalog.createCatalog(store, "Main").id();
    }

    @Test
    void creatingAStoreSeedsADefaultSellableLocation() {
        assertThat(storeDirectory.locationsOf(store))
                .singleElement()
                .satisfies(loc -> {
                    assertThat(loc.sellable()).isTrue();
                    assertThat(loc.label()).isEqualTo("Default");
                });
        assertThat(storeDirectory.policiesOf(store).deductionPoint())
                .isEqualTo(StorePolicies.DeductionPoint.ON_PAYMENT);   // decision 2
    }

    @Test
    void authorsAProductWithVariants() {
        ProductView created = catalog.createProduct(catalogId, new ProductDraft(
                "Castor-oil soap", "Cold pressed", "soap",
                Map.of("scent", "neutral"), List.of("https://cdn/soap.jpg"),
                Map.of("origin", "DR"), ProductStatus.ACTIVE, 1,
                List.of(new ProductDraft.VariantDraft("SOAP-200", "200g",
                                Money.of(35000, "DOP"), Map.of("size", "200g"), 200, null),
                        new ProductDraft.VariantDraft("SOAP-400", "400g",
                                Money.of(60000, "DOP"), Map.of("size", "400g"), 400, null))));

        assertThat(created.variants()).hasSize(2);
        assertThat(created.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(created.attributes()).containsEntry("scent", "neutral");

        // Declarative variant update: absent SKU is removed, present one is updated.
        ProductView updated = catalog.updateProduct(created.id(), new ProductDraft(
                null, null, null, null, null, null, null, null,
                List.of(new ProductDraft.VariantDraft("SOAP-200", "200g eco",
                        Money.of(36000, "DOP"), null, 200, "758-001"))));
        assertThat(updated.variants()).singleElement().satisfies(v -> {
            assertThat(v.label()).isEqualTo("200g eco");
            assertThat(v.price()).isEqualTo(Money.of(36000, "DOP"));
            assertThat(v.barcode()).isEqualTo("758-001");
        });
    }

    @Test
    void duplicateSkuInADraftIsRejected() {
        assertThatThrownBy(() -> catalog.createProduct(catalogId, new ProductDraft(
                "Twin", null, null, null, null, null, null, null,
                List.of(new ProductDraft.VariantDraft("X", null, Money.of(1, "DOP"), null, null, null),
                        new ProductDraft.VariantDraft("X", null, Money.of(2, "DOP"), null, null, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate sku");
    }

    @Test
    void archiveIsReversibleAndNeverDeletes() {
        ProductView product = catalog.createProduct(catalogId, draft("Soap", "SOAP-1"));

        assertThat(catalog.archive(product.id()).status()).isEqualTo(ProductStatus.ARCHIVED);
        assertThat(catalog.product(product.id())).isPresent();
        assertThat(catalog.restore(product.id()).status()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void publishBumpsTheCatalogVersion() {
        assertThat(catalog.publish(catalogId).status()).isEqualTo(CatalogView.Status.PUBLISHED);
        assertThat(catalog.publish(catalogId).version()).isEqualTo(2);
    }

    @Test
    void csvImportsWithRowLevelErrorsAndExportsRoundTrip() {
        String csv = """
                product_id,title,description,category,status,sku,variant_label,price_minor,currency,weight_grams,barcode
                ,Castor soap,Cold pressed,soap,ACTIVE,CS-200,200g,35000,DOP,200,
                ,Castor soap,Cold pressed,soap,ACTIVE,CS-400,400g,60000,DOP,400,
                ,Broken row,,,ACTIVE,,no sku here,100,DOP,,
                ,Shampoo bar,,hair,DRAFT,SH-100,100g,not-a-number,DOP,,
                ,"Conditioner, solid",,hair,ACTIVE,CO-100,100g,42000,DOP,100,ABC-1
                """;

        BulkImport.Result result = bulk.importCsv(catalogId, csv);

        assertThat(result.created()).isEqualTo(2);      // Castor soap (2 variants) + Conditioner
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains("sku is required"));
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains("bad price_minor"));

        List<ProductView> products = catalog.productsOf(catalogId);
        assertThat(products).hasSize(2);
        assertThat(products).anySatisfy(p -> {
            assertThat(p.title()).isEqualTo("Castor soap");
            assertThat(p.variants()).hasSize(2);
        });
        assertThat(products).anySatisfy(p ->
                assertThat(p.title()).isEqualTo("Conditioner, solid"));   // quoted comma survived

        // Re-import of the export is a no-op upsert: nothing new created.
        String exported = bulk.exportCsv(catalogId);
        BulkImport.Result replay = bulk.importCsv(catalogId, exported);
        assertThat(replay.created()).isZero();
        assertThat(replay.errors()).isEmpty();
        assertThat(catalog.productsOf(catalogId)).hasSize(2);
    }

    private static ProductDraft draft(String title, String sku) {
        return new ProductDraft(title, null, null, null, null, null, ProductStatus.ACTIVE, null,
                List.of(new ProductDraft.VariantDraft(sku, null, Money.of(1000, "DOP"), null, null, null)));
    }
}
