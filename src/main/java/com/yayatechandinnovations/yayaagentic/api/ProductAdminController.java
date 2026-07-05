package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ProductAdminDtos;
import com.yayatechandinnovations.yayaagentic.commerce.Availability;
import com.yayatechandinnovations.yayaagentic.commerce.ManagedProduct;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalogRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.ProductListQuery;
import com.yayatechandinnovations.yayaagentic.commerce.ProductPage;
import com.yayatechandinnovations.yayaagentic.commerce.ProductRegistry;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin REST for managed products under a {@code Database} catalog — the
 * paged/filtered CRUD table plus bulk upsert and CSV export that make authoring
 * hundreds of products viable. A product edit is a single-row write; it never
 * bumps the catalog config version (that lifecycle split is the point). See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
@RestController
@RequestMapping("/v1/admin/product-catalogs/{catalogId}/products")
public class ProductAdminController {

    private final ProductRegistry products;
    private final ProductCatalogRegistry catalogs;
    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;

    public ProductAdminController(ProductRegistry products, ProductCatalogRegistry catalogs,
                                  TenantGuard tenantGuard, WorkspaceAccessHelper workspaceAccess) {
        this.products = products;
        this.catalogs = catalogs;
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping
    public ProductAdminDtos.ProductsPage list(
            @PathVariable("catalogId") String catalogId,
            @RequestParam(value = "tenant", required = false) String tenant,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "availability", required = false) String availability,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        ProductPage p = products.list(new Ids.TenantId(resolved), catalogId,
                new ProductListQuery(q, category, availability, status, sort, page, size));
        return new ProductAdminDtos.ProductsPage(
                p.items().stream().map(ProductAdminController::toRow).toList(),
                p.page(), p.size(), p.total());
    }

    @GetMapping("/{productId}")
    public ProductAdminDtos.ProductDetail get(
            @PathVariable("catalogId") String catalogId,
            @PathVariable("productId") String productId,
            @RequestParam(value = "tenant", required = false) String tenant,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        return products.find(new Ids.TenantId(resolved), catalogId, new Ids.ProductId(productId))
                .map(ProductAdminController::toDetail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such product: " + productId));
    }

    @PostMapping
    @Transactional
    public ProductAdminDtos.ProductDetail create(
            @PathVariable("catalogId") String catalogId,
            @RequestParam(value = "tenant", required = false) String tenant,
            @RequestBody ProductAdminDtos.SaveProductRequest req,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        ProductCatalog catalog = requireCatalog(resolved, catalogId);
        ManagedProduct saved = products.save(toManaged(resolved, catalogId, catalog.currency(), req, null));
        return toDetail(saved);
    }

    @PutMapping("/{productId}")
    @Transactional
    public ProductAdminDtos.ProductDetail update(
            @PathVariable("catalogId") String catalogId,
            @PathVariable("productId") String productId,
            @RequestParam(value = "tenant", required = false) String tenant,
            @RequestBody ProductAdminDtos.SaveProductRequest req,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        ProductCatalog catalog = requireCatalog(resolved, catalogId);
        ManagedProduct existing = products.find(new Ids.TenantId(resolved), catalogId, new Ids.ProductId(productId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such product: " + productId));
        // The path id wins over any body id, so a PUT can never re-key a row.
        ProductAdminDtos.SaveProductRequest fixed = withProductId(req, productId);
        ManagedProduct saved = products.save(toManaged(resolved, catalogId, catalog.currency(), fixed, existing));
        return toDetail(saved);
    }

    @PostMapping("/{productId}/archive")
    @Transactional
    public void archive(@PathVariable("catalogId") String catalogId,
                        @PathVariable("productId") String productId,
                        @RequestParam(value = "tenant", required = false) String tenant,
                        ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        products.setStatus(new Ids.TenantId(resolved), catalogId, new Ids.ProductId(productId), ManagedProduct.ARCHIVED);
    }

    @PostMapping("/{productId}/restore")
    @Transactional
    public void restore(@PathVariable("catalogId") String catalogId,
                        @PathVariable("productId") String productId,
                        @RequestParam(value = "tenant", required = false) String tenant,
                        ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        products.setStatus(new Ids.TenantId(resolved), catalogId, new Ids.ProductId(productId), ManagedProduct.ACTIVE);
    }

    @PostMapping("/bulk")
    @Transactional
    public ProductAdminDtos.BulkUpsertResponse bulk(
            @PathVariable("catalogId") String catalogId,
            @RequestParam(value = "tenant", required = false) String tenant,
            @RequestBody ProductAdminDtos.BulkUpsertRequest req,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        ProductCatalog catalog = requireCatalog(resolved, catalogId);
        List<ProductAdminDtos.RowError> errors = new ArrayList<>();
        List<ManagedProduct> valid = new ArrayList<>();
        List<ProductAdminDtos.SaveProductRequest> rows = req.products() == null ? List.of() : req.products();
        for (int i = 0; i < rows.size(); i++) {
            ProductAdminDtos.SaveProductRequest r = rows.get(i);
            try {
                valid.add(toManaged(resolved, catalogId, catalog.currency(), r, null));
            } catch (RuntimeException ex) {
                errors.add(new ProductAdminDtos.RowError(i, r == null ? null : r.productId(), ex.getMessage()));
            }
        }
        products.saveAll(valid);
        return new ProductAdminDtos.BulkUpsertResponse(valid.size(), errors);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<String> export(
            @PathVariable("catalogId") String catalogId,
            @RequestParam(value = "tenant", required = false) String tenant,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        requireCatalog(resolved, catalogId);
        // Export the whole catalog (including archived) so a spreadsheet round-trip is lossless.
        ProductPage all = products.list(new Ids.TenantId(resolved), catalogId,
                new ProductListQuery(null, null, null, "ALL", "SORT_ORDER", 0, 200));
        String csv = ProductCsv.write(all.items());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + catalogId + "-products.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    // ---- mapping ----------------------------------------------------------

    private ProductCatalog requireCatalog(String tenant, String catalogId) {
        return catalogs.listFor(new Ids.TenantId(tenant)).stream()
                .filter(c -> c.id().value().equals(catalogId))
                .max((a, b) -> Integer.compare(a.id().version(), b.id().version()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such catalog: " + catalogId));
    }

    private static ManagedProduct toManaged(String tenant, String catalogId, String catalogCurrency,
                                            ProductAdminDtos.SaveProductRequest req, ManagedProduct existing) {
        if (req == null) throw badRequest("product body is required");
        require(req.productId(), "productId");
        require(req.title(), "title");
        if (req.priceMinor() == null || req.priceMinor() < 0) throw badRequest("priceMinor must be >= 0");
        String currency = blankToNull(req.currency()) == null ? catalogCurrency : req.currency();
        if (currency == null || currency.isBlank()) throw badRequest("currency is required");

        Availability availability = deriveAvailability(req.availability(), req.inventory());
        Product product = new Product(
                new Ids.ProductId(req.productId().trim()),
                req.title().trim(),
                blankToNull(req.shortDescription()),
                new Money(req.priceMinor(), currency),
                req.imageUrls() == null ? List.of() : req.imageUrls(),
                blankToNull(req.category()),
                req.attributes() == null ? Map.of() : req.attributes(),
                toVariants(req.variants(), currency),
                availability,
                blankToNull(req.detailUrl()));

        String status = existing != null ? existing.status() : ManagedProduct.ACTIVE;
        int sortOrder = req.sortOrder() != null ? req.sortOrder()
                : (existing != null ? existing.sortOrder() : 0);
        return new ManagedProduct(new Ids.TenantId(tenant), catalogId, product,
                blankToNull(req.longDescription()), req.inventory(), status, sortOrder, null);
    }

    private static List<Product.Variant> toVariants(List<ProductAdminDtos.VariantView> vs, String currency) {
        if (vs == null) return List.of();
        List<Product.Variant> out = new ArrayList<>();
        for (ProductAdminDtos.VariantView v : vs) {
            if (v == null || v.variantId() == null || v.variantId().isBlank()) continue;
            String vc = blankToNull(v.currency()) == null ? currency : v.currency();
            out.add(new Product.Variant(v.variantId(), v.label(),
                    new Money(v.priceMinor(), vc), v.sku(), deriveAvailability(v.availability(), null)));
        }
        return out;
    }

    /** Explicit availability wins; otherwise derive from tracked inventory. */
    private static Availability deriveAvailability(String explicit, Integer inventory) {
        if (explicit != null && !explicit.isBlank()) {
            try { return Availability.valueOf(explicit.toUpperCase()); }
            catch (IllegalArgumentException ignore) { /* fall through to derive/default */ }
        }
        if (inventory != null) {
            if (inventory <= 0) return Availability.OUT;
            if (inventory <= 5) return Availability.LOW;
        }
        return Availability.IN_STOCK;
    }

    private static ProductAdminDtos.SaveProductRequest withProductId(
            ProductAdminDtos.SaveProductRequest r, String productId) {
        return new ProductAdminDtos.SaveProductRequest(
                productId, r.title(), r.shortDescription(), r.longDescription(), r.priceMinor(),
                r.currency(), r.category(), r.attributes(), r.imageUrls(), r.variants(),
                r.availability(), r.inventory(), r.detailUrl(), r.sortOrder());
    }

    private static ProductAdminDtos.ProductRow toRow(ManagedProduct m) {
        Product p = m.product();
        String thumb = p.imageUrls().isEmpty() ? null : p.imageUrls().get(0);
        return new ProductAdminDtos.ProductRow(
                p.id().value(), p.title(), p.price().amountMinor(), p.price().currency(),
                thumb, p.availability().name(), m.inventory(), m.status());
    }

    private static ProductAdminDtos.ProductDetail toDetail(ManagedProduct m) {
        Product p = m.product();
        List<ProductAdminDtos.VariantView> variants = new ArrayList<>();
        for (Product.Variant v : p.variants()) {
            variants.add(new ProductAdminDtos.VariantView(v.variantId(), v.label(),
                    v.price().amountMinor(), v.price().currency(), v.sku(),
                    v.availability() == null ? null : v.availability().name()));
        }
        return new ProductAdminDtos.ProductDetail(
                p.id().value(), p.title(), p.shortDescription(), m.longDescription(),
                p.price().amountMinor(), p.price().currency(), p.category(),
                p.attributes(), p.imageUrls(), variants, p.availability().name(),
                m.inventory(), p.detailUrl(), m.status(), m.sortOrder());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw badRequest(field + " is required");
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
