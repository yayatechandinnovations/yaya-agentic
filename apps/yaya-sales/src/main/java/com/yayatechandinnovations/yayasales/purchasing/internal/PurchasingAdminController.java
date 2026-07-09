package com.yayatechandinnovations.yayasales.purchasing.internal;

import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.PurchaseOrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.SupplierId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.purchasing.PurchaseOrderView;
import com.yayatechandinnovations.yayasales.purchasing.PurchasingService;
import com.yayatechandinnovations.yayasales.purchasing.SupplierView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Purchasing admin (design §10.1): suppliers CRUD and the PO lifecycle —
 * create, place, cancel, and receive (the goods receipt that posts costed
 * RECEIPT movements). Operator-authenticated; every aggregate is checked
 * against the acting store.
 */
@RestController
@RequestMapping("/v1/sales/admin")
public class PurchasingAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final PurchasingService purchasing;

    public PurchasingAdminController(PurchasingService purchasing) {
        this.purchasing = purchasing;
    }

    // ── suppliers ────────────────────────────────────────────────────────────

    @PostMapping("/suppliers")
    public SupplierView createSupplier(@RequestHeader(STORE_HEADER) String storeId,
                                       @Valid @RequestBody Supplier body) {
        return purchasing.createSupplier(new StoreId(storeId), body.name(), body.contact());
    }

    @GetMapping("/suppliers")
    public List<SupplierView> suppliers(@RequestHeader(STORE_HEADER) String storeId) {
        return purchasing.suppliersOf(new StoreId(storeId));
    }

    @GetMapping("/suppliers/{id}")
    public SupplierView supplier(@RequestHeader(STORE_HEADER) String storeId,
                                 @PathVariable String id) {
        return scopedSupplier(storeId, id);
    }

    @PutMapping("/suppliers/{id}")
    public SupplierView updateSupplier(@RequestHeader(STORE_HEADER) String storeId,
                                       @PathVariable String id, @RequestBody Supplier body) {
        return purchasing.updateSupplier(scopedSupplier(storeId, id).id(),
                body.name(), body.contact());
    }

    @DeleteMapping("/suppliers/{id}")
    public Map<String, String> deleteSupplier(@RequestHeader(STORE_HEADER) String storeId,
                                              @PathVariable String id) {
        purchasing.deleteSupplier(scopedSupplier(storeId, id).id());
        return Map.of("status", "deleted");
    }

    // ── purchase orders ──────────────────────────────────────────────────────

    @PostMapping("/purchase-orders")
    public PurchaseOrderView create(@RequestHeader(STORE_HEADER) String storeId,
                                    @Valid @RequestBody CreatePo body) {
        return purchasing.create(new StoreId(storeId), new SupplierId(body.supplierId()),
                new LocationId(body.locationId()),
                body.lines().stream()
                        .map(l -> new PurchasingService.NewLine(new VariantId(l.sku()),
                                l.qty(), l.unitCostMinor() == null ? 0 : l.unitCostMinor()))
                        .toList(),
                body.expectedAt());
    }

    @GetMapping("/purchase-orders")
    public List<PurchaseOrderView> list(@RequestHeader(STORE_HEADER) String storeId,
                                        @RequestParam(required = false)
                                        PurchaseOrderView.Status status) {
        return purchasing.purchaseOrdersOf(new StoreId(storeId), Optional.ofNullable(status));
    }

    @GetMapping("/purchase-orders/{id}")
    public PurchaseOrderView detail(@RequestHeader(STORE_HEADER) String storeId,
                                    @PathVariable String id) {
        return scopedPo(storeId, id);
    }

    @PostMapping("/purchase-orders/{id}/place")
    public PurchaseOrderView place(@RequestHeader(STORE_HEADER) String storeId,
                                   @PathVariable String id, Authentication auth) {
        return purchasing.place(scopedPo(storeId, id).id(), actor(auth));
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    public PurchaseOrderView cancel(@RequestHeader(STORE_HEADER) String storeId,
                                    @PathVariable String id, Authentication auth) {
        return purchasing.cancel(scopedPo(storeId, id).id(), actor(auth));
    }

    @PostMapping("/purchase-orders/{id}/receive")
    public PurchaseOrderView receive(@RequestHeader(STORE_HEADER) String storeId,
                                     @PathVariable String id,
                                     @Valid @RequestBody Receive body, Authentication auth) {
        return purchasing.receive(scopedPo(storeId, id).id(),
                body.lines().stream()
                        .map(l -> new PurchasingService.ReceiveLine(new VariantId(l.sku()),
                                l.qty(), l.unitCostMinor()))
                        .toList(),
                actor(auth));
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /** Another store's aggregate is answered as absent — existence is not leaked. */
    private SupplierView scopedSupplier(String storeId, String id) {
        return purchasing.supplier(new SupplierId(id))
                .filter(s -> s.store().value().equals(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("supplier", id));
    }

    private PurchaseOrderView scopedPo(String storeId, String id) {
        return purchasing.purchaseOrder(new PurchaseOrderId(id))
                .filter(p -> p.store().value().equals(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("purchase order", id));
    }

    private static String actor(Authentication auth) {
        return auth == null ? "operator" : "op:" + auth.getName();
    }

    public record Supplier(@NotBlank String name, Map<String, Object> contact) {}

    public record CreatePo(@NotBlank String supplierId, @NotBlank String locationId,
                           @NotEmpty List<Line> lines, Instant expectedAt) {}

    public record Line(String sku, long qty, Long unitCostMinor) {}

    public record Receive(@NotEmpty List<Line> lines) {}
}
