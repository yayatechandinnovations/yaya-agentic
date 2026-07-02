package com.yayatechandinnovations.yayaagentic.commerce;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductProjectorTest {

    private final ProductProjector projector = new ProductProjector();

    @Test
    void mapsMerchantJsonOntoNormalizedProducts() {
        String body = """
            { "data": [
              { "sku": "p_44", "name": "Castor Oil Soap", "blurb": "gentle",
                "cents": 1299, "cat": "soap", "imgs": ["https://img/a", "https://img/b"],
                "props": { "ingredient": "castor oil", "form": "soap" }, "stock": "IN_STOCK" }
            ] }
            """;
        ProductProjection projection = new ProductProjection("$.data", Map.of(
                "id", "$.sku",
                "title", "$.name",
                "shortDescription", "$.blurb",
                "price.amountMinor", "$.cents",
                "category", "$.cat",
                "imageUrls", "$.imgs",
                "attributes", "$.props",
                "availability", "$.stock"));

        List<Product> products = projector.project(body, projection, "USD");

        assertEquals(1, products.size());
        Product p = products.get(0);
        assertEquals("p_44", p.id().value());
        assertEquals("Castor Oil Soap", p.title());
        assertEquals(new Money(1299, "USD"), p.price());
        assertEquals(List.of("https://img/a", "https://img/b"), p.imageUrls());
        assertEquals("castor oil", p.attributes().get("ingredient"));
        assertEquals(Availability.IN_STOCK, p.availability());
    }

    @Test
    void defaultsCurrencyFromCatalogWhenProjectionHasNoCurrencyPath() {
        String body = "[{ \"id\": \"x\", \"price\": 500 }]";
        ProductProjection projection = new ProductProjection("$",
                Map.of("id", "$.id", "price.amountMinor", "$.price"));

        assertEquals("DOP", projector.project(body, projection, "DOP").get(0).price().currency());
    }

    @Test
    void rejectsNonIntegerPrice_soAFloatCannotSmuggleIn() {
        String body = "[{ \"id\": \"x\", \"price\": 12.99 }]";
        ProductProjection projection = new ProductProjection("$",
                Map.of("id", "$.id", "price.amountMinor", "$.price"));

        assertThrows(IllegalArgumentException.class, () -> projector.project(body, projection, "USD"));
    }

    @Test
    void toleratesMissingOptionalFields() {
        String body = "[{ \"id\": \"x\", \"price\": 100 }]";
        ProductProjection projection = new ProductProjection("$",
                Map.of("id", "$.id", "price.amountMinor", "$.price", "category", "$.missing"));

        Product p = projector.project(body, projection, "USD").get(0);
        assertTrue(p.imageUrls().isEmpty());
        assertTrue(p.attributes().isEmpty());
        assertEquals(Availability.IN_STOCK, p.availability()); // default when absent/unparseable
    }
}
