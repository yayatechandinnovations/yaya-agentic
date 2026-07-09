package com.yayatechandinnovations.yayasales.stores.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.outbox.Outbox;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.LocationView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class DefaultStoreService implements StoreService, StoreDirectory {

    private final StoreRepository stores;
    private final LocationRepository locations;
    private final DefaultSellingContextService sellingContext;
    private final Outbox outbox;
    private final ObjectMapper json;

    public DefaultStoreService(StoreRepository stores, LocationRepository locations,
                               DefaultSellingContextService sellingContext,
                               Outbox outbox, ObjectMapper json) {
        this.stores = stores;
        this.locations = locations;
        this.sellingContext = sellingContext;
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    @Transactional
    public StoreView create(TenantRef tenant, String displayName, String defaultCurrency,
                            StorePolicies policies) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (defaultCurrency == null || !defaultCurrency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("defaultCurrency must be an ISO-4217 code");
        }
        StorePolicies effective = policies == null ? StorePolicies.defaults() : policies;
        StoreEntity store = new StoreEntity(Ids.newId(), tenant.value(), displayName,
                defaultCurrency, write(effective), Instant.now());
        stores.save(store);

        // v1 seeding (§6.1): one sellable default location + one WEB_CHAT
        // channel + one region from the store currency — a simple store needs
        // no extra setup.
        locations.save(new LocationEntity(Ids.newId(), store.getId(), "Default",
                LocationView.Kind.WAREHOUSE.name(), true, 0));
        sellingContext.seedDefaults(new StoreId(store.getId()), defaultCurrency);

        outbox.append(new StoreId(store.getId()), "store", store.getId(), "store.created",
                "{\"storeId\":\"" + store.getId() + "\",\"tenantId\":\"" + tenant.value() + "\"}");
        return toView(store);
    }

    @Override
    @Transactional
    public StoreView updatePolicies(StoreId id, StorePolicies policies) {
        StoreEntity store = stores.findById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("store", id.value()));
        store.setPoliciesJson(write(policies == null ? StorePolicies.defaults() : policies));
        return toView(store);
    }

    @Override
    @Transactional
    public LocationView addLocation(StoreId store, String label, LocationView.Kind kind, boolean sellable) {
        stores.findById(store.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("store", store.value()));
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        int next = locations.findByStoreIdOrderBySortOrderAsc(store.value()).size();
        LocationEntity loc = new LocationEntity(Ids.newId(), store.value(), label,
                (kind == null ? LocationView.Kind.WAREHOUSE : kind).name(), sellable, next);
        locations.save(loc);
        return toView(loc);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreView> store(StoreId id) {
        return stores.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocationView> location(LocationId id) {
        return locations.findById(id.value()).map(DefaultStoreService::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationView> locationsOf(StoreId store) {
        return locations.findByStoreIdOrderBySortOrderAsc(store.value())
                .stream().map(DefaultStoreService::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreView> storesOf(TenantRef tenant) {
        return stores.findByTenantIdOrderByCreatedAtAsc(tenant.value())
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StorePolicies policiesOf(StoreId store) {
        return stores.findById(store.value())
                .map(e -> read(e.getPoliciesJson()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("store", store.value()));
    }

    private StoreView toView(StoreEntity e) {
        return new StoreView(new StoreId(e.getId()), new TenantRef(e.getTenantId()),
                e.getDisplayName(), e.getDefaultCurrency(), read(e.getPoliciesJson()), e.getCreatedAt());
    }

    private static LocationView toView(LocationEntity e) {
        return new LocationView(new LocationId(e.getId()), new StoreId(e.getStoreId()),
                e.getLabel(), LocationView.Kind.valueOf(e.getKind()), e.isSellable(), e.getSortOrder());
    }

    private String write(StorePolicies policies) {
        try {
            return json.writeValueAsString(policies);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize store policies", e);
        }
    }

    private StorePolicies read(String policiesJson) {
        try {
            return json.readValue(policiesJson, StorePolicies.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt policies_json", e);
        }
    }
}
