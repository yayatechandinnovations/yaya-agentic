package com.yayatechandinnovations.yayasales.customers.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.customers.CustomerService;
import com.yayatechandinnovations.yayasales.customers.CustomerView;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.AddressId;
import com.yayatechandinnovations.yayasales.platform.Ids.CustomerId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class DefaultCustomerService implements CustomerService {

    private final CustomerRepository customers;
    private final StoreDirectory stores;
    private final ObjectMapper json;

    public DefaultCustomerService(CustomerRepository customers, StoreDirectory stores,
                                  ObjectMapper json) {
        this.customers = customers;
        this.stores = stores;
        this.json = json;
    }

    @Override
    @Transactional
    public CustomerView create(StoreId store, CustomerDraft draft) {
        stores.store(store).orElseThrow(() -> new ApiErrors.NotFoundException("store", store.value()));
        if (draft.displayName() == null || draft.displayName().isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (draft.customerRef() != null && !draft.customerRef().isBlank()
                && customers.findByStoreIdAndCustomerRef(store.value(), draft.customerRef()).isPresent()) {
            throw new ApiErrors.ConflictException(
                    "a customer with ref '" + draft.customerRef() + "' already exists");
        }
        CustomerEntity entity = new CustomerEntity(Ids.newId(), store.value(),
                draft.displayName(), Instant.now());
        apply(entity, draft);
        customers.save(entity);
        return toView(entity);
    }

    @Override
    @Transactional
    public CustomerView update(CustomerId id, CustomerDraft draft) {
        CustomerEntity entity = require(id);
        if (draft.displayName() != null && !draft.displayName().isBlank()) {
            entity.setDisplayName(draft.displayName());
        }
        apply(entity, draft);
        return toView(entity);
    }

    @Override
    @Transactional
    public void delete(CustomerId id) {
        customers.delete(require(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerView> customer(CustomerId id) {
        return customers.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerView> byRef(StoreId store, String customerRef) {
        return customers.findByStoreIdAndCustomerRef(store.value(), customerRef).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerView> search(StoreId store, String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return customers.findByStoreIdOrderByDisplayNameAsc(store.value()).stream()
                .filter(c -> needle.isEmpty() || matches(c, needle))
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public CustomerView addAddress(CustomerId id, CustomerView.Kind kind, Address address) {
        CustomerEntity entity = require(id);
        entity.getAddresses().add(new CustomerAddressEntity(Ids.newId(),
                (kind == null ? CustomerView.Kind.SHIPPING : kind).name(),
                writeAddress(address), Instant.now()));
        return toView(entity);
    }

    @Override
    @Transactional
    public CustomerView updateAddress(CustomerId id, AddressId addressId,
                                      CustomerView.Kind kind, Address address) {
        CustomerEntity entity = require(id);
        CustomerAddressEntity row = entity.getAddresses().stream()
                .filter(a -> a.getId().equals(addressId.value()))
                .findFirst()
                .orElseThrow(() -> new ApiErrors.NotFoundException("address", addressId.value()));
        if (kind != null) row.setKind(kind.name());
        if (address != null) row.setDataJson(writeAddress(address));
        return toView(entity);
    }

    @Override
    @Transactional
    public CustomerView removeAddress(CustomerId id, AddressId addressId) {
        CustomerEntity entity = require(id);
        boolean removed = entity.getAddresses()
                .removeIf(a -> a.getId().equals(addressId.value()));
        if (!removed) {
            throw new ApiErrors.NotFoundException("address", addressId.value());
        }
        return toView(entity);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private static boolean matches(CustomerEntity c, String needle) {
        return contains(c.getDisplayName(), needle) || contains(c.getEmail(), needle)
                || contains(c.getPhone(), needle) || contains(c.getCustomerRef(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void apply(CustomerEntity entity, CustomerDraft draft) {
        if (draft.customerRef() != null) {
            entity.setCustomerRef(draft.customerRef().isBlank() ? null : draft.customerRef());
        }
        if (draft.email() != null) entity.setEmail(blankToNull(draft.email()));
        if (draft.phone() != null) entity.setPhone(blankToNull(draft.phone()));
        if (draft.notes() != null) entity.setNotes(blankToNull(draft.notes()));
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }

    private CustomerEntity require(CustomerId id) {
        return customers.findById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("customer", id.value()));
    }

    private CustomerView toView(CustomerEntity e) {
        List<CustomerView.AddressView> addresses = e.getAddresses().stream()
                .map(a -> new CustomerView.AddressView(new AddressId(a.getId()),
                        CustomerView.Kind.valueOf(a.getKind()), readAddress(a.getDataJson())))
                .toList();
        return new CustomerView(new CustomerId(e.getId()), new StoreId(e.getStoreId()),
                e.getCustomerRef(), e.getDisplayName(), e.getEmail(), e.getPhone(),
                e.getNotes(), addresses, e.getCreatedAt());
    }

    private String writeAddress(Address address) {
        if (address == null) {
            throw new IllegalArgumentException("address is required");
        }
        try {
            return json.writeValueAsString(address);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize address", e);
        }
    }

    private Address readAddress(String raw) {
        try {
            return json.readValue(raw, Address.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt address data_json", e);
        }
    }
}
