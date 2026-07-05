package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.commerce.ManagedProduct;
import com.yayatechandinnovations.yayaagentic.commerce.Product;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders managed products as CSV for the admin "Export CSV" round-trip — the
 * real way a merchant maintains hundreds of products (edit in a spreadsheet,
 * re-import). {@code price_minor} is exported as integer minor units so a
 * re-import never introduces a float (#1). See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A §7.1.
 */
final class ProductCsv {

    private ProductCsv() {}

    private static final String HEADER =
            "product_id,title,short_description,long_description,price_minor,currency,"
                    + "category,availability,inventory,detail_url,image_urls,attributes,status,sort_order";

    static String write(List<ManagedProduct> items) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (ManagedProduct m : items) {
            Product p = m.product();
            row(sb,
                    p.id().value(),
                    p.title(),
                    p.shortDescription(),
                    m.longDescription(),
                    String.valueOf(p.price().amountMinor()),
                    p.price().currency(),
                    p.category(),
                    p.availability().name(),
                    m.inventory() == null ? "" : String.valueOf(m.inventory()),
                    p.detailUrl(),
                    String.join(" | ", p.imageUrls()),
                    attributes(p.attributes()),
                    m.status(),
                    String.valueOf(m.sortOrder()));
        }
        return sb.toString();
    }

    private static String attributes(Map<String, String> attrs) {
        return attrs.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" | "));
    }

    private static void row(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells[i]));
        }
        sb.append('\n');
    }

    private static String escape(String v) {
        if (v == null) return "";
        boolean needsQuote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        String s = v.replace("\"", "\"\"");
        return needsQuote ? "\"" + s + "\"" : s;
    }
}
