package com.yayatechandinnovations.yayaagentic.commerce.facet;

import com.yayatechandinnovations.yayaagentic.commerce.Availability;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacetComputerTest {

    private final FacetComputer computer = new FacetComputer();

    @Test
    void derivesCategoricalFacetsFromCategoryAndAttributes() {
        List<Product> products = List.of(
                product("p1", "Castor Soap", 899, "soap", Map.of("ingredient", "castor oil")),
                product("p2", "Shea Soap", 1299, "soap", Map.of("ingredient", "shea")),
                product("p3", "Castor Oil", 1599, "oil", Map.of("ingredient", "castor oil")));

        List<Facet> facets = computer.compute(products);

        Facet category = facet(facets, "category");
        assertEquals(List.of(new Facet.Value("soap", 2), new Facet.Value("oil", 1)), category.values());

        Facet ingredient = facet(facets, "ingredient");
        assertEquals("Ingredient", ingredient.label());
        assertEquals(new Facet.Value("castor oil", 2), ingredient.values().get(0));
    }

    @Test
    void skipsDimensionsThatCannotSplitTheSet() {
        // Every product is the same category + ingredient → no useful facet.
        List<Product> products = List.of(
                product("p1", "Soap A", 899, "soap", Map.of("ingredient", "castor oil")),
                product("p2", "Soap B", 999, "soap", Map.of("ingredient", "castor oil")));

        List<Facet> facets = computer.compute(products);
        assertTrue(facets.stream().noneMatch(f -> f.key().equals("category")));
        assertTrue(facets.stream().noneMatch(f -> f.key().equals("ingredient")));
    }

    @Test
    void buildsPriceBandsFromMinorUnits() {
        List<Product> products = List.of(
                product("p1", "Cheap", 500, "soap", Map.of()),      // < $10
                product("p2", "Mid", 1500, "soap", Map.of()),       // $10–$20
                product("p3", "Pricey", 6000, "oil", Map.of()));    // $50+

        Facet price = facet(computer.compute(products), "price");
        assertEquals(3, price.buckets().size());
        assertEquals("< $10", price.buckets().get(0).label());
        assertEquals(1, price.buckets().get(0).count());
        assertNull(price.buckets().get(2).max()); // $50+ open-ended
    }

    @Test
    void emptyInputYieldsNoFacets() {
        assertTrue(computer.compute(List.of()).isEmpty());
    }

    private static Facet facet(List<Facet> facets, String key) {
        Optional<Facet> f = facets.stream().filter(x -> x.key().equals(key)).findFirst();
        assertTrue(f.isPresent(), "expected a '" + key + "' facet");
        return f.get();
    }

    private static Product product(String id, String title, long minor, String category,
                                   Map<String, String> attrs) {
        return new Product(new Ids.ProductId(id), title, "", new Money(minor, "USD"),
                List.of(), category, attrs, List.of(), Availability.IN_STOCK, null);
    }
}
