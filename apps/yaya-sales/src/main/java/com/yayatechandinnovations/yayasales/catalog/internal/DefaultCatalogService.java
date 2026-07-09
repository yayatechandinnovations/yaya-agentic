package com.yayatechandinnovations.yayasales.catalog.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.catalog.CatalogService;
import com.yayatechandinnovations.yayasales.catalog.CatalogView;
import com.yayatechandinnovations.yayasales.catalog.ProductDraft;
import com.yayatechandinnovations.yayasales.catalog.ProductStatus;
import com.yayatechandinnovations.yayasales.catalog.ProductView;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.CatalogId;
import com.yayatechandinnovations.yayasales.platform.Ids.ProductId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class DefaultCatalogService implements CatalogService {

    private final CatalogRepository catalogs;
    private final ProductRepository products;
    private final StoreDirectory stores;
    private final ObjectMapper json;

    public DefaultCatalogService(CatalogRepository catalogs, ProductRepository products,
                                 StoreDirectory stores, ObjectMapper json) {
        this.catalogs = catalogs;
        this.products = products;
        this.stores = stores;
        this.json = json;
    }

    @Override
    @Transactional
    public CatalogView createCatalog(StoreId store, String displayName) {
        stores.store(store).orElseThrow(() -> new ApiErrors.NotFoundException("store", store.value()));
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        CatalogEntity catalog = new CatalogEntity(Ids.newId(), store.value(), displayName, Instant.now());
        catalogs.save(catalog);
        return toView(catalog);
    }

    @Override
    @Transactional
    public CatalogView publish(CatalogId id) {
        CatalogEntity catalog = requireCatalog(id);
        catalog.publish();
        return toView(catalog);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogView> catalogsOf(StoreId store) {
        return catalogs.findByStoreIdOrderByCreatedAtAsc(store.value())
                .stream().map(DefaultCatalogService::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CatalogView> catalog(CatalogId id) {
        return catalogs.findById(id.value()).map(DefaultCatalogService::toView);
    }

    @Override
    @Transactional
    public ProductView createProduct(CatalogId catalogId, ProductDraft draft) {
        CatalogEntity catalog = requireCatalog(catalogId);
        if (draft.title() == null || draft.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (draft.variants() == null || draft.variants().isEmpty()) {
            throw new IllegalArgumentException("a product needs at least one variant/SKU");
        }
        ProductEntity product = new ProductEntity(Ids.newId(), catalog.getStoreId(),
                catalog.getId(), draft.title(), Instant.now());
        apply(product, draft);
        products.save(product);
        return toView(product);
    }

    @Override
    @Transactional
    public ProductView updateProduct(ProductId id, ProductDraft draft) {
        ProductEntity product = requireProduct(id);
        apply(product, draft);
        if (product.getVariants().isEmpty()) {
            throw new IllegalArgumentException("a product needs at least one variant/SKU");
        }
        return toView(product);
    }

    @Override
    @Transactional
    public ProductView archive(ProductId id) {
        ProductEntity product = requireProduct(id);
        product.setStatus(ProductStatus.ARCHIVED.name());
        return toView(product);
    }

    @Override
    @Transactional
    public ProductView restore(ProductId id) {
        ProductEntity product = requireProduct(id);
        if (ProductStatus.ARCHIVED.name().equals(product.getStatus())) {
            product.setStatus(ProductStatus.DRAFT.name());
        }
        return toView(product);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductView> product(ProductId id) {
        return products.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductView> productOfVariant(VariantId variant) {
        return products.findByVariantId(variant.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> productsOf(CatalogId catalog) {
        return products.findByCatalogIdOrderBySortOrderAscTitleAsc(catalog.value())
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> productsOfStore(StoreId store) {
        return products.findByStoreIdOrderBySortOrderAscTitleAsc(store.value())
                .stream().map(this::toView).toList();
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private void apply(ProductEntity product, ProductDraft draft) {
        if (draft.title() != null) product.setTitle(draft.title());
        if (draft.description() != null) product.setDescription(draft.description());
        if (draft.category() != null) product.setCategory(draft.category());
        if (draft.attributes() != null) product.setAttributesJson(write(draft.attributes()));
        if (draft.media() != null) product.setMediaJson(write(draft.media()));
        if (draft.metadata() != null) product.setMetadataJson(write(draft.metadata()));
        if (draft.status() != null) product.setStatus(draft.status().name());
        if (draft.sortOrder() != null) product.setSortOrder(draft.sortOrder());
        if (draft.variants() != null) applyVariants(product, draft.variants());
    }

    /** Upsert by SKU; variants absent from the draft are removed (authoring is declarative). */
    private void applyVariants(ProductEntity product, List<ProductDraft.VariantDraft> drafts) {
        Set<String> seen = new HashSet<>();
        for (ProductDraft.VariantDraft draft : drafts) {
            if (draft.sku() == null || draft.sku().isBlank()) {
                throw new IllegalArgumentException("every variant needs a sku");
            }
            if (draft.price() == null) {
                throw new IllegalArgumentException("variant " + draft.sku() + " needs a list price");
            }
            if (!seen.add(draft.sku())) {
                throw new IllegalArgumentException("duplicate sku in draft: " + draft.sku());
            }
            VariantEntity variant = product.getVariants().stream()
                    .filter(v -> v.getSku().equals(draft.sku()))
                    .findFirst()
                    .orElseGet(() -> {
                        VariantEntity created = new VariantEntity(Ids.newId(), draft.sku(),
                                draft.label(), draft.price().amountMinor(), draft.price().currency());
                        product.getVariants().add(created);
                        return created;
                    });
            variant.setLabel(draft.label());
            variant.setPrice(draft.price().amountMinor(), draft.price().currency());
            if (draft.options() != null) variant.setOptionsJson(write(draft.options()));
            variant.setWeightGrams(draft.weightGrams());
            variant.setBarcode(draft.barcode());
        }
        product.getVariants().removeIf(v -> !seen.contains(v.getSku()));
    }

    private CatalogEntity requireCatalog(CatalogId id) {
        return catalogs.findById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("catalog", id.value()));
    }

    private ProductEntity requireProduct(ProductId id) {
        return products.findById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("product", id.value()));
    }

    private static CatalogView toView(CatalogEntity e) {
        return new CatalogView(new CatalogId(e.getId()), new StoreId(e.getStoreId()),
                e.getVersion(), e.getDisplayName(),
                CatalogView.Status.valueOf(e.getStatus()), e.getCreatedAt());
    }

    ProductView toView(ProductEntity e) {
        List<ProductView.VariantView> variants = e.getVariants().stream()
                .map(v -> new ProductView.VariantView(
                        new VariantId(v.getId()), v.getSku(), v.getLabel(),
                        new Money(v.getPriceMinor(), v.getCurrency()),
                        readMap(v.getOptionsJson()), v.getWeightGrams(), v.getBarcode()))
                .toList();
        return new ProductView(
                new ProductId(e.getId()), new StoreId(e.getStoreId()), new CatalogId(e.getCatalogId()),
                e.getTitle(), e.getDescription(), e.getCategory(),
                readMap(e.getAttributesJson()), readList(e.getMediaJson()),
                readObjectMap(e.getMetadataJson()),
                ProductStatus.valueOf(e.getStatus()), e.getSortOrder(),
                variants, e.getCreatedAt(), e.getUpdatedAt());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("unserializable field: " + e.getMessage(), e);
        }
    }

    private Map<String, String> readMap(String raw) {
        if (raw == null) return Map.of();
        try {
            return json.readValue(raw, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt json column", e);
        }
    }

    private Map<String, Object> readObjectMap(String raw) {
        if (raw == null) return Map.of();
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt json column", e);
        }
    }

    private List<String> readList(String raw) {
        if (raw == null) return List.of();
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt json column", e);
        }
    }
}
