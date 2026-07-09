package com.yayatechandinnovations.yayasales.stores.internal;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.LocationView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
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

/**
 * Store + location admin (design §10.1). Operator-authenticated (SecurityConfig
 * locks /v1/** behind an authenticated caller). Store-scoped endpoints resolve
 * the acting store from the {@code X-Yaya-Store-Id} header.
 */
@RestController
@RequestMapping("/v1/sales/admin")
public class StoreAdminController {

    public static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final StoreService service;
    private final StoreDirectory directory;

    public StoreAdminController(StoreService service, StoreDirectory directory) {
        this.service = service;
        this.directory = directory;
    }

    @PostMapping("/stores")
    @ResponseStatus(HttpStatus.CREATED)
    public StoreView create(@Valid @RequestBody CreateStore body) {
        return service.create(new TenantRef(body.tenantId()), body.displayName(),
                body.defaultCurrency(), body.policies());
    }

    @GetMapping("/stores")
    public List<StoreView> byTenant(@RequestParam String tenantId) {
        return directory.storesOf(new TenantRef(tenantId));
    }

    @GetMapping("/stores/{id}")
    public StoreView get(@PathVariable String id) {
        return directory.store(new StoreId(id))
                .orElseThrow(() -> new ApiErrors.NotFoundException("store", id));
    }

    @PutMapping("/stores/{id}/policies")
    public StoreView updatePolicies(@PathVariable String id, @RequestBody StorePolicies policies) {
        return service.updatePolicies(new StoreId(id), policies);
    }

    @GetMapping("/locations")
    public List<LocationView> locations(@RequestHeader(STORE_HEADER) String storeId) {
        return directory.locationsOf(new StoreId(storeId));
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public LocationView addLocation(@RequestHeader(STORE_HEADER) String storeId,
                                    @Valid @RequestBody CreateLocation body) {
        return service.addLocation(new StoreId(storeId), body.label(),
                body.kind(), body.sellable() == null || body.sellable());
    }

    public record CreateStore(@NotBlank String tenantId,
                              @NotBlank String displayName,
                              @NotBlank String defaultCurrency,
                              StorePolicies policies) {}

    public record CreateLocation(@NotBlank String label,
                                 LocationView.Kind kind,
                                 Boolean sellable) {}
}
