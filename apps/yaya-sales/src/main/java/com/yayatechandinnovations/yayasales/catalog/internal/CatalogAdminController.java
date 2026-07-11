package com.yayatechandinnovations.yayasales.catalog.internal;

import com.yayatechandinnovations.yayasales.catalog.BulkImport;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.CatalogView;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductView;
import com.yayatechandinnovations.yayasales.platform.Ids.CatalogId;
import com.yayatechandinnovations.yayasales.platform.Ids.ProductId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catalog + product authoring admin (design §10.1). */
@RestController
@RequestMapping("/v1/sales/admin")
public class CatalogAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final CatalogService service;
    private final BulkImport bulk;

    public CatalogAdminController(CatalogService service, BulkImport bulk) {
        this.service = service;
        this.bulk = bulk;
    }

    @PostMapping("/catalogs")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogView createCatalog(@RequestHeader(STORE_HEADER) String storeId,
                                     @Valid @RequestBody CreateCatalog body) {
        return service.createCatalog(new StoreId(storeId), body.displayName());
    }

    @GetMapping("/catalogs")
    public List<CatalogView> catalogs(@RequestHeader(STORE_HEADER) String storeId) {
        return service.catalogsOf(new StoreId(storeId));
    }

    @GetMapping("/catalogs/{id}")
    public CatalogView catalog(@PathVariable String id) {
        return service.catalog(new CatalogId(id))
                .orElseThrow(() -> new ApiErrors.NotFoundException("catalog", id));
    }

    @PutMapping("/catalogs/{id}")
    public CatalogView updateCatalog(@PathVariable String id, @RequestBody UpdateCatalog body) {
        return service.updateCatalog(new CatalogId(id), body.displayName(), body.coverUrl());
    }

    @PostMapping("/catalogs/{id}/publish")
    public CatalogView publish(@PathVariable String id) {
        return service.publish(new CatalogId(id));
    }

    @PostMapping("/catalogs/{id}/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView createProduct(@PathVariable String id, @RequestBody ProductDraft draft) {
        return service.createProduct(new CatalogId(id), draft);
    }

    @GetMapping("/catalogs/{id}/products")
    public List<ProductView> products(@PathVariable String id) {
        return service.productsOf(new CatalogId(id));
    }

    @GetMapping("/products/{pid}")
    public ProductView product(@PathVariable String pid) {
        return service.product(new ProductId(pid))
                .orElseThrow(() -> new ApiErrors.NotFoundException("product", pid));
    }

    @PutMapping("/products/{pid}")
    public ProductView updateProduct(@PathVariable String pid, @RequestBody ProductDraft draft) {
        return service.updateProduct(new ProductId(pid), draft);
    }

    @PostMapping("/products/{pid}/archive")
    public ProductView archive(@PathVariable String pid) {
        return service.archive(new ProductId(pid));
    }

    @PostMapping("/products/{pid}/restore")
    public ProductView restore(@PathVariable String pid) {
        return service.restore(new ProductId(pid));
    }

    @PostMapping(value = "/catalogs/{id}/products/bulk", consumes = "text/csv")
    public BulkImport.Result importCsv(@PathVariable String id, @RequestBody String csv) {
        return bulk.importCsv(new CatalogId(id), csv);
    }

    @GetMapping(value = "/catalogs/{id}/products/export.csv")
    public ResponseEntity<String> exportCsv(@PathVariable String id) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bulk.exportCsv(new CatalogId(id)));
    }

    public record CreateCatalog(@NotBlank String displayName) {}

    public record UpdateCatalog(String displayName, String coverUrl) {}
}
