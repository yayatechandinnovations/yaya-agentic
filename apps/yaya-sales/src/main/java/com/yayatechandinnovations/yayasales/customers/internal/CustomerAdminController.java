package com.yayatechandinnovations.yayasales.customers.internal;

import com.yayatechandinnovations.yayasales.customers.CustomerService;
import com.yayatechandinnovations.yayasales.customers.CustomerView;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.AddressId;
import com.yayatechandinnovations.yayasales.platform.Ids.CustomerId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Customer directory admin (design §10.1). Store-scoped like every admin surface. */
@RestController
@RequestMapping("/v1/sales/admin/customers")
public class CustomerAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final CustomerService customers;

    public CustomerAdminController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerView create(@RequestHeader(STORE_HEADER) String storeId,
                               @Valid @RequestBody Draft body) {
        return customers.create(new StoreId(storeId), toDraft(body));
    }

    @GetMapping
    public List<CustomerView> search(@RequestHeader(STORE_HEADER) String storeId,
                                     @RequestParam(required = false) String q) {
        return customers.search(new StoreId(storeId), q);
    }

    @GetMapping("/{id}")
    public CustomerView get(@RequestHeader(STORE_HEADER) String storeId, @PathVariable String id) {
        return scoped(storeId, id);
    }

    @PutMapping("/{id}")
    public CustomerView update(@RequestHeader(STORE_HEADER) String storeId,
                               @PathVariable String id, @RequestBody Draft body) {
        return customers.update(scoped(storeId, id).id(), toDraft(body));
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@RequestHeader(STORE_HEADER) String storeId,
                                      @PathVariable String id) {
        customers.delete(scoped(storeId, id).id());
        return Map.of("status", "deleted");
    }

    @PostMapping("/{id}/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerView addAddress(@RequestHeader(STORE_HEADER) String storeId,
                                   @PathVariable String id,
                                   @Valid @RequestBody AddressBody body) {
        return customers.addAddress(scoped(storeId, id).id(), body.kind(), body.address());
    }

    @PutMapping("/{id}/addresses/{addressId}")
    public CustomerView updateAddress(@RequestHeader(STORE_HEADER) String storeId,
                                      @PathVariable String id, @PathVariable String addressId,
                                      @RequestBody AddressBody body) {
        return customers.updateAddress(scoped(storeId, id).id(),
                new AddressId(addressId), body.kind(), body.address());
    }

    @DeleteMapping("/{id}/addresses/{addressId}")
    public CustomerView removeAddress(@RequestHeader(STORE_HEADER) String storeId,
                                      @PathVariable String id, @PathVariable String addressId) {
        return customers.removeAddress(scoped(storeId, id).id(), new AddressId(addressId));
    }

    /** Another store's customer is answered as absent — existence is not leaked. */
    private CustomerView scoped(String storeId, String id) {
        return customers.customer(new CustomerId(id))
                .filter(c -> c.store().value().equals(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("customer", id));
    }

    private static CustomerService.CustomerDraft toDraft(Draft body) {
        return new CustomerService.CustomerDraft(body.customerRef(), body.displayName(),
                body.email(), body.phone(), body.notes());
    }

    public record Draft(String customerRef, @NotBlank String displayName,
                        String email, String phone, String notes) {}

    public record AddressBody(CustomerView.Kind kind, @NotNull Address address) {}
}
