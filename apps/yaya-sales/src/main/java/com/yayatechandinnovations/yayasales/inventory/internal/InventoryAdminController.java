package com.yayatechandinnovations.yayasales.inventory.internal;

import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.MovementView;
import com.yayatechandinnovations.yayasales.inventory.Ref;
import com.yayatechandinnovations.yayasales.inventory.StockLevel;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.MovementId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.stores.StorePolicies.BackorderPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inventory admin (design §10.1). Reads come from the summary projection;
 * writes are ledger movements — there is no "set on-hand to N" endpoint on
 * purpose: corrections are ADJUSTMENT movements with a reason.
 */
@RestController
@RequestMapping("/v1/sales/admin/inventory")
public class InventoryAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final InventoryAdmin admin;
    private final InventoryLedger ledger;

    public InventoryAdminController(InventoryAdmin admin, InventoryLedger ledger) {
        this.admin = admin;
        this.ledger = ledger;
    }

    @GetMapping
    public List<StockLevel> levels(@RequestHeader(STORE_HEADER) String storeId,
                                   @RequestParam(required = false) String location,
                                   @RequestParam(name = "low", defaultValue = "false") boolean lowOnly) {
        return admin.levels(new StoreId(storeId),
                Optional.ofNullable(location).map(LocationId::new), lowOnly);
    }

    @GetMapping("/{sku}/ledger")
    public List<MovementView> ledger(@PathVariable String sku,
                                     @RequestParam(required = false) String location) {
        return admin.ledger(new VariantId(sku), Optional.ofNullable(location).map(LocationId::new));
    }

    @PostMapping("/{sku}/adjust")
    public Map<String, String> adjust(@PathVariable String sku, @Valid @RequestBody Adjust body) {
        MovementId movement = ledger.adjust(new VariantId(sku),
                new LocationId(body.location()), body.delta(), body.reason());
        return Map.of("movementId", movement.value());
    }

    @PostMapping("/{sku}/receive")
    public Map<String, String> receive(@PathVariable String sku, @Valid @RequestBody Receive body) {
        MovementId movement = ledger.receive(new VariantId(sku), new LocationId(body.location()),
                body.qty(), new Money(body.unitCostMinor(), body.currency()),
                body.ref() == null ? null : new Ref(Ref.RefType.PURCHASE_ORDER, body.ref()));
        return Map.of("movementId", movement.value());
    }

    @PostMapping("/transfer")
    public Map<String, Object> transfer(@Valid @RequestBody Transfer body) {
        List<MovementId> ids = ledger.transfer(new VariantId(body.sku()),
                new LocationId(body.from()), new LocationId(body.to()), body.qty());
        return Map.of("movementIds", ids.stream().map(MovementId::value).toList());
    }

    @PutMapping("/{sku}/reorder")
    public Map<String, String> reorder(@PathVariable String sku, @Valid @RequestBody Reorder body) {
        admin.setReorder(new VariantId(sku), new LocationId(body.location()), body.point(), body.qty());
        return Map.of("status", "ok");
    }

    @PutMapping("/{sku}/backorder")
    public Map<String, String> backorder(@PathVariable String sku, @Valid @RequestBody Backorder body) {
        admin.setBackorderPolicy(new VariantId(sku), new LocationId(body.location()), body.policy());
        return Map.of("status", "ok");
    }

    public record Adjust(@NotBlank String location, long delta, @NotBlank String reason) {}

    public record Receive(@NotBlank String location, long qty,
                          long unitCostMinor, @NotBlank String currency, String ref) {}

    public record Transfer(@NotBlank String sku, @NotBlank String from, @NotBlank String to, long qty) {}

    public record Reorder(@NotBlank String location, int point, int qty) {}

    public record Backorder(@NotBlank String location, @NotNull BackorderPolicy policy) {}
}
