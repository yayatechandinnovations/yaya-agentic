package com.yayatechandinnovations.yayaagentic.commerce.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogSyncPolicy;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalogRegistry;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.persistence.ProductCatalogEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ProductCatalogRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * JPA-backed {@link ProductCatalogRegistry}. Mirrors {@code PostgresProfileRegistry}:
 * JSONB columns decoded per read, versioned reads return the requested version.
 * See {@code ecommerce-chat-design.md} §14.
 */
@Component
@ConditionalOnMissingBean(value = ProductCatalogRegistry.class,
        ignored = PostgresProductCatalogRegistry.class)
public class PostgresProductCatalogRegistry implements ProductCatalogRegistry {

    private static final TypeReference<CatalogLocation> LOCATION = new TypeReference<>() {};
    private static final TypeReference<CatalogSyncPolicy> SYNC = new TypeReference<>() {};
    private static final TypeReference<RetrievalPolicy> SEARCH = new TypeReference<>() {};
    private static final TypeReference<PermissionRequirement> ACCESS = new TypeReference<>() {};

    private final ProductCatalogRepository repo;
    private final ObjectMapper json;

    public PostgresProductCatalogRegistry(ProductCatalogRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductCatalog> find(Ids.TenantId tenant, Ids.ProductCatalogId id) {
        return repo.findById(new ProductCatalogEntity.PK(tenant.value(), id.value(), id.version()))
                .map(this::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductCatalog> listFor(Ids.TenantId tenant) {
        return repo.findByTenantId(tenant.value()).stream().map(this::toRecord).toList();
    }

    @Override
    @Transactional
    public void register(ProductCatalog catalog) {
        ProductCatalogEntity.PK pk = new ProductCatalogEntity.PK(
                catalog.tenant().value(), catalog.id().value(), catalog.id().version());
        ProductCatalogEntity entity = repo.findById(pk).orElseGet(() -> new ProductCatalogEntity(
                catalog.tenant().value(), catalog.id().value(), catalog.id().version(),
                catalog.displayName(), catalog.currency()));

        entity.setDisplayName(catalog.displayName());
        entity.setCurrency(catalog.currency());
        entity.setImageUrl(catalog.imageUrl());
        entity.setLocationJson(writeJson(catalog.location()));
        entity.setSyncJson(catalog.sync() == null ? null : writeJson(catalog.sync()));
        entity.setSearchJson(catalog.search() == null ? null : writeJson(catalog.search()));
        entity.setAccessJson(catalog.access() == null ? null : writeJson(catalog.access()));
        repo.save(entity);
    }

    private ProductCatalog toRecord(ProductCatalogEntity e) {
        return new ProductCatalog(
                new Ids.ProductCatalogId(e.getId(), e.getVersion()),
                new Ids.TenantId(e.getTenantId()),
                e.getDisplayName(),
                readJson(e.getLocationJson(), LOCATION),
                e.getSyncJson() == null ? CatalogSyncPolicy.live() : readJson(e.getSyncJson(), SYNC),
                e.getSearchJson() == null ? RetrievalPolicy.defaults() : readJson(e.getSearchJson(), SEARCH),
                e.getAccessJson() == null ? PermissionRequirement.none() : readJson(e.getAccessJson(), ACCESS),
                e.getCurrency(),
                e.getImageUrl());
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode JSON for product_catalogs column", ex);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to decode JSON from product_catalogs column", ex);
        }
    }
}
