package com.yayatechandinnovations.yayaagentic.commerce.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.Availability;
import com.yayatechandinnovations.yayaagentic.commerce.ManagedProduct;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductListQuery;
import com.yayatechandinnovations.yayaagentic.commerce.ProductPage;
import com.yayatechandinnovations.yayaagentic.commerce.ProductRegistry;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ProductEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JPA-backed {@link ProductRegistry}. Maps a {@link ProductEntity} row ↔ a
 * {@link ManagedProduct} (JSONB columns decoded per read), and builds the
 * admin table's filter/sort as a {@link Specification}. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
@Component
@ConditionalOnMissingBean(value = ProductRegistry.class, ignored = PostgresProductRegistry.class)
public class PostgresProductRegistry implements ProductRegistry {

    private static final TypeReference<Map<String, String>> ATTRS = new TypeReference<>() {};
    private static final TypeReference<List<String>> IMAGES = new TypeReference<>() {};
    private static final TypeReference<List<VariantRow>> VARIANTS = new TypeReference<>() {};

    private final ProductRepository repo;
    private final ObjectMapper json;

    public PostgresProductRegistry(ProductRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductPage list(Ids.TenantId tenant, String catalogId, ProductListQuery q) {
        Page<ProductEntity> page = repo.findAll(
                filter(tenant.value(), catalogId, q),
                PageRequest.of(q.page(), q.size(), sortOf(q.sort())));
        return new ProductPage(page.getContent().stream().map(this::toManaged).toList(),
                q.page(), q.size(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ManagedProduct> find(Ids.TenantId tenant, String catalogId, Ids.ProductId productId) {
        return repo.findById(new ProductEntity.PK(tenant.value(), catalogId, productId.value()))
                .map(this::toManaged);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> loadActive(Ids.TenantId tenant, String catalogId) {
        return repo.findByTenantIdAndCatalogIdAndStatus(
                        tenant.value(), catalogId, ManagedProduct.ACTIVE,
                        Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("productId")))
                .stream().map(this::toProduct).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countActive(Ids.TenantId tenant, String catalogId) {
        return repo.countByTenantIdAndCatalogIdAndStatus(tenant.value(), catalogId, ManagedProduct.ACTIVE);
    }

    @Override
    @Transactional
    public ManagedProduct save(ManagedProduct p) {
        return toManaged(persist(p));
    }

    @Override
    @Transactional
    public void saveAll(List<ManagedProduct> products) {
        for (ManagedProduct p : products) persist(p);
    }

    @Override
    @Transactional
    public void setStatus(Ids.TenantId tenant, String catalogId, Ids.ProductId productId, String status) {
        repo.findById(new ProductEntity.PK(tenant.value(), catalogId, productId.value()))
                .ifPresent(e -> {
                    e.setStatus(status);
                    e.setUpdatedAt(OffsetDateTime.now());
                    repo.save(e);
                });
    }

    // --- mapping -----------------------------------------------------------

    private ProductEntity persist(ManagedProduct p) {
        Product prod = p.product();
        ProductEntity.PK pk = new ProductEntity.PK(p.tenant().value(), p.catalogId(), prod.id().value());
        ProductEntity e = repo.findById(pk).orElseGet(
                () -> new ProductEntity(p.tenant().value(), p.catalogId(), prod.id().value()));
        e.setTitle(prod.title());
        e.setShortDescription(prod.shortDescription());
        e.setLongDescription(p.longDescription());
        e.setPriceMinor(prod.price().amountMinor());
        e.setCurrency(prod.price().currency());
        e.setCategory(blankToNull(prod.category()));
        e.setAttributesJson(write(prod.attributes()));
        e.setVariantsJson(write(toVariantRows(prod.variants())));
        e.setImageUrlsJson(write(prod.imageUrls()));
        e.setAvailability(prod.availability() == null ? "IN_STOCK" : prod.availability().name());
        e.setInventory(p.inventory());
        e.setDetailUrl(blankToNull(prod.detailUrl()));
        e.setStatus(p.status() == null || p.status().isBlank() ? ManagedProduct.ACTIVE : p.status());
        e.setSortOrder(p.sortOrder());
        e.setUpdatedAt(OffsetDateTime.now());
        return repo.save(e);
    }

    private ManagedProduct toManaged(ProductEntity e) {
        return new ManagedProduct(
                new Ids.TenantId(e.getTenantId()), e.getCatalogId(), toProduct(e),
                e.getLongDescription(), e.getInventory(), e.getStatus(), e.getSortOrder(),
                e.getUpdatedAt());
    }

    private Product toProduct(ProductEntity e) {
        return new Product(
                new Ids.ProductId(e.getProductId()),
                e.getTitle(),
                e.getShortDescription(),
                new Money(e.getPriceMinor(), e.getCurrency()),
                read(e.getImageUrlsJson(), IMAGES),
                e.getCategory(),
                read(e.getAttributesJson(), ATTRS),
                toVariants(read(e.getVariantsJson(), VARIANTS), e.getCurrency()),
                availabilityOf(e.getAvailability()),
                e.getDetailUrl());
    }

    private static Availability availabilityOf(String raw) {
        try {
            return raw == null ? Availability.IN_STOCK : Availability.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return Availability.IN_STOCK;
        }
    }

    private static List<Product.Variant> toVariants(List<VariantRow> rows, String currency) {
        List<Product.Variant> out = new ArrayList<>();
        if (rows == null) return out;
        for (VariantRow r : rows) {
            out.add(new Product.Variant(r.variantId(), r.label(),
                    new Money(r.priceMinor(), r.currency() == null ? currency : r.currency()),
                    r.sku(), availabilityOf(r.availability())));
        }
        return out;
    }

    private static List<VariantRow> toVariantRows(List<Product.Variant> variants) {
        List<VariantRow> out = new ArrayList<>();
        for (Product.Variant v : variants) {
            out.add(new VariantRow(v.variantId(), v.label(),
                    v.price() == null ? 0 : v.price().amountMinor(),
                    v.price() == null ? null : v.price().currency(),
                    v.sku(), v.availability() == null ? null : v.availability().name()));
        }
        return out;
    }

    /** JSON shape of a variant in {@code variants_json} — price as integer minor units, never float. */
    private record VariantRow(String variantId, String label, long priceMinor,
                              String currency, String sku, String availability) {}

    private static Specification<ProductEntity> filter(String tenant, String catalogId, ProductListQuery q) {
        return (root, cq, cb) -> {
            List<Predicate> and = new ArrayList<>();
            and.add(cb.equal(root.get("tenantId"), tenant));
            and.add(cb.equal(root.get("catalogId"), catalogId));
            if (!"ALL".equals(q.status())) {
                and.add(cb.equal(root.get("status"), q.status()));
            }
            if (q.category() != null && !q.category().isBlank()) {
                and.add(cb.equal(cb.lower(root.get("category")), q.category().toLowerCase()));
            }
            if (q.availability() != null && !q.availability().isBlank()) {
                and.add(cb.equal(root.get("availability"), q.availability().toUpperCase()));
            }
            if (q.text() != null && !q.text().isBlank()) {
                String like = "%" + q.text().toLowerCase() + "%";
                and.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("productId")), like),
                        cb.like(cb.lower(root.get("shortDescription")), like)));
            }
            return cb.and(and.toArray(new Predicate[0]));
        };
    }

    private static Sort sortOf(String sort) {
        return switch (sort) {
            case "TITLE" -> Sort.by(Sort.Order.asc("title"));
            case "PRICE" -> Sort.by(Sort.Order.asc("priceMinor"));
            case "UPDATED" -> Sort.by(Sort.Order.desc("updatedAt"));
            default -> Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("productId"));
        };
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode JSON for products column", ex);
        }
    }

    private <T> T read(String raw, TypeReference<T> type) {
        if (raw == null || raw.isBlank()) return null;   // callers tolerate null (empty defaults)
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to decode JSON from products column", ex);
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
