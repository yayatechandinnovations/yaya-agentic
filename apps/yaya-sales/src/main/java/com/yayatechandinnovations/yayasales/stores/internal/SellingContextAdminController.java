package com.yayatechandinnovations.yayasales.stores.internal;

import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.stores.ChannelView;
import com.yayatechandinnovations.yayasales.stores.RegionView;
import com.yayatechandinnovations.yayasales.stores.SellingContextService;
import com.yayatechandinnovations.yayasales.stores.TaxPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
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

/** Channels & regions admin (design §10.1 selling context). */
@RestController
@RequestMapping("/v1/sales/admin")
public class SellingContextAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final SellingContextService service;

    public SellingContextAdminController(SellingContextService service) {
        this.service = service;
    }

    @GetMapping("/channels")
    public List<ChannelView> channels(@RequestHeader(STORE_HEADER) String storeId) {
        return service.channelsOf(new StoreId(storeId));
    }

    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelView addChannel(@RequestHeader(STORE_HEADER) String storeId,
                                  @Valid @RequestBody CreateChannel body) {
        return service.addChannel(new StoreId(storeId), body.kind(), body.label());
    }

    @GetMapping("/regions")
    public List<RegionView> regions(@RequestHeader(STORE_HEADER) String storeId) {
        return service.regionsOf(new StoreId(storeId));
    }

    @PostMapping("/regions")
    @ResponseStatus(HttpStatus.CREATED)
    public RegionView addRegion(@RequestHeader(STORE_HEADER) String storeId,
                                @Valid @RequestBody CreateRegion body) {
        return service.addRegion(new StoreId(storeId), body.name(), body.currency(),
                body.tax(), body.tenders());
    }

    @PutMapping("/regions/{id}")
    public RegionView updateRegion(@PathVariable String id, @RequestBody UpdateRegion body) {
        return service.updateRegion(new RegionId(id), body.name(), body.tax(), body.tenders());
    }

    public record CreateChannel(@NotNull ChannelView.Kind kind, String label) {}

    public record CreateRegion(String name, @NotBlank String currency,
                               TaxPolicy tax, List<TenderKind> tenders) {}

    public record UpdateRegion(String name, TaxPolicy tax, List<TenderKind> tenders) {}
}
