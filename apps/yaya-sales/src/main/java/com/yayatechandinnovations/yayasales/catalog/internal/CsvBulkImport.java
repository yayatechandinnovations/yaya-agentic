package com.yayatechandinnovations.yayasales.catalog.internal;

import com.yayatechandinnovations.yayasales.catalog.BulkImport;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.catalog.ProductView;
import com.yayatechandinnovations.yayasales.platform.Ids.CatalogId;
import com.yayatechandinnovations.yayasales.platform.Ids.ProductId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV upsert, one row = one variant, keyed by SKU (module design §2). Rows
 * with the same {@code title} (or explicit {@code product_id}) group into one
 * product. A bad row is reported with its line number and skipped — the batch
 * never aborts.
 *
 * <p>Columns: {@code product_id,title,description,category,status,sku,
 * variant_label,price_minor,currency,weight_grams,barcode}
 */
@Service
public class CsvBulkImport implements BulkImport {

    static final String HEADER = "product_id,title,description,category,status,sku,"
            + "variant_label,price_minor,currency,weight_grams,barcode";

    private final CatalogService catalog;

    public CsvBulkImport(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    @Transactional
    public Result importCsv(CatalogId catalogId, String csv) {
        catalog.catalog(catalogId)
                .orElseThrow(() -> new ApiErrors.NotFoundException("catalog", catalogId.value()));
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("csv body is empty");
        }

        String[] lines = csv.replace("\r\n", "\n").split("\n");
        List<RowError> errors = new ArrayList<>();
        // (product_id or title) -> parsed variant rows, insertion-ordered
        Map<String, List<ParsedRow>> groups = new LinkedHashMap<>();

        for (int i = 1; i < lines.length; i++) {           // line 0 is the header
            String line = lines[i];
            if (line.isBlank()) continue;
            try {
                ParsedRow row = parse(line, i + 1);
                groups.computeIfAbsent(row.groupKey(), k -> new ArrayList<>()).add(row);
            } catch (IllegalArgumentException e) {
                errors.add(new RowError(i + 1, e.getMessage()));
            }
        }

        int created = 0;
        int updated = 0;
        for (List<ParsedRow> group : groups.values()) {
            ParsedRow head = group.get(0);
            try {
                List<ProductDraft.VariantDraft> variants = group.stream().map(ParsedRow::variant).toList();
                ProductDraft draft = new ProductDraft(head.title, head.description, head.category,
                        null, null, null, head.status, null, variants);
                if (head.productId != null) {
                    catalog.updateProduct(new ProductId(head.productId), draft);
                    updated++;
                } else {
                    ProductView existing = findByTitle(catalogId, head.title);
                    if (existing != null) {
                        catalog.updateProduct(existing.id(), draft);
                        updated++;
                    } else {
                        catalog.createProduct(catalogId, draft);
                        created++;
                    }
                }
            } catch (RuntimeException e) {
                errors.add(new RowError(head.line, e.getMessage()));
            }
        }
        return new Result(created, updated, errors);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportCsv(CatalogId catalogId) {
        catalog.catalog(catalogId)
                .orElseThrow(() -> new ApiErrors.NotFoundException("catalog", catalogId.value()));
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (ProductView product : catalog.productsOf(catalogId)) {
            for (ProductView.VariantView variant : product.variants()) {
                out.append(String.join(",",
                        escape(product.id().value()),
                        escape(product.title()),
                        escape(product.description()),
                        escape(product.category()),
                        product.status().name(),
                        escape(variant.sku()),
                        escape(variant.label()),
                        Long.toString(variant.price().amountMinor()),
                        variant.price().currency(),
                        variant.weightGrams() == null ? "" : variant.weightGrams().toString(),
                        escape(variant.barcode())))
                   .append('\n');
            }
        }
        return out.toString();
    }

    private ProductView findByTitle(CatalogId catalogId, String title) {
        return catalog.productsOf(catalogId).stream()
                .filter(p -> p.title().equals(title))
                .findFirst().orElse(null);
    }

    // ── row parsing ──────────────────────────────────────────────────────────

    private record ParsedRow(int line, String productId, String title, String description,
                             String category, ProductStatus status, ProductDraft.VariantDraft variant) {
        String groupKey() {
            return productId != null ? "id:" + productId : "title:" + title;
        }
    }

    private static ParsedRow parse(String line, int lineNo) {
        List<String> cells = splitCsv(line);
        if (cells.size() < 9) {
            throw new IllegalArgumentException("expected at least 9 columns (" + HEADER + ")");
        }
        String productId = blankToNull(cells.get(0));
        String title = blankToNull(cells.get(1));
        String sku = blankToNull(cells.get(5));
        if (sku == null) throw new IllegalArgumentException("sku is required");
        if (productId == null && title == null) {
            throw new IllegalArgumentException("title is required when product_id is absent");
        }

        ProductStatus status;
        try {
            status = cells.get(4).isBlank() ? null : ProductStatus.valueOf(cells.get(4).trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad status: " + cells.get(4));
        }

        long priceMinor;
        try {
            priceMinor = Long.parseLong(cells.get(7).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad price_minor: " + cells.get(7));
        }
        String currency = cells.get(8).trim();
        Money price = new Money(priceMinor, currency);   // validates the currency code

        Integer weight = null;
        if (cells.size() > 9 && !cells.get(9).isBlank()) {
            try {
                weight = Integer.parseInt(cells.get(9).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad weight_grams: " + cells.get(9));
            }
        }
        String barcode = cells.size() > 10 ? blankToNull(cells.get(10)) : null;

        return new ParsedRow(lineNo, productId, title, blankToNull(cells.get(2)), blankToNull(cells.get(3)),
                status, new ProductDraft.VariantDraft(sku, blankToNull(cells.get(6)),
                        price, null, weight, barcode));
    }

    /** Minimal RFC-4180 split: quoted cells may contain commas and doubled quotes. */
    private static List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
