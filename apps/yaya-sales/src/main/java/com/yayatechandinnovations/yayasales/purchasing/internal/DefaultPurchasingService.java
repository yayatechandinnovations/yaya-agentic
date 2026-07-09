package com.yayatechandinnovations.yayasales.purchasing.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.Ref;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.GoodsReceiptId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.PurchaseOrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.SupplierId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.purchasing.PurchaseOrderView;
import com.yayatechandinnovations.yayasales.purchasing.PurchasingService;
import com.yayatechandinnovations.yayasales.purchasing.SupplierView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §9/§7.5 — the stock-in orchestrator. The PO row is the paperwork; the goods
 * receipt is the fact: each received line posts one RECEIPT movement (unit
 * cost included) through the inventory ledger, which updates the moving
 * average and fires {@code stock.received} in the same transaction.
 */
@Service
class DefaultPurchasingService implements PurchasingService {

    private final SupplierRepository suppliers;
    private final PurchaseOrderRepository purchaseOrders;
    private final GoodsReceiptRepository receipts;
    private final InventoryLedger ledger;
    private final StoreDirectory stores;
    private final ObjectMapper json;

    DefaultPurchasingService(SupplierRepository suppliers,
                             PurchaseOrderRepository purchaseOrders,
                             GoodsReceiptRepository receipts, InventoryLedger ledger,
                             StoreDirectory stores, ObjectMapper json) {
        this.suppliers = suppliers;
        this.purchaseOrders = purchaseOrders;
        this.receipts = receipts;
        this.ledger = ledger;
        this.stores = stores;
        this.json = json;
    }

    // ── suppliers ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SupplierView createSupplier(StoreId store, String name, Map<String, Object> contact) {
        if (name == null || name.isBlank()) {
            throw new ApiErrors.UnprocessableException("a supplier needs a name");
        }
        SupplierEntity supplier = new SupplierEntity(Ids.newId(), store.value(), name,
                writeContact(contact));
        suppliers.save(supplier);
        return toView(supplier);
    }

    @Override
    @Transactional
    public SupplierView updateSupplier(SupplierId id, String name, Map<String, Object> contact) {
        SupplierEntity supplier = requireSupplier(id);
        if (name != null && !name.isBlank()) {
            supplier.setName(name);
        }
        if (contact != null) {
            supplier.setContactJson(writeContact(contact));
        }
        return toView(supplier);
    }

    @Override
    @Transactional
    public void deleteSupplier(SupplierId id) {
        SupplierEntity supplier = requireSupplier(id);
        if (purchaseOrders.existsBySupplierId(id.value())) {
            throw new ApiErrors.ConflictException(
                    "supplier has purchase orders — history stays explainable");
        }
        suppliers.delete(supplier);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierView> suppliersOf(StoreId store) {
        return suppliers.findByStoreIdOrderByNameAsc(store.value())
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierView> supplier(SupplierId id) {
        return suppliers.findById(id.value()).map(this::toView);
    }

    // ── purchase orders ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public PurchaseOrderView create(StoreId store, SupplierId supplierId, LocationId location,
                                    List<NewLine> lines, Instant expectedAt) {
        if (lines == null || lines.isEmpty()) {
            throw new ApiErrors.UnprocessableException("a purchase order needs at least one line");
        }
        SupplierEntity supplier = requireSupplier(supplierId);
        if (!supplier.getStoreId().equals(store.value())) {
            throw new ApiErrors.NotFoundException("supplier", supplierId.value());
        }
        var loc = stores.location(location)
                .orElseThrow(() -> new ApiErrors.NotFoundException("location", location.value()));
        if (!loc.store().equals(store)) {
            throw new ApiErrors.NotFoundException("location", location.value());
        }
        List<StoredLine> stored = lines.stream().map(line -> {
            if (line.qty() < 1) {
                throw new ApiErrors.UnprocessableException("PO line qty must be >= 1");
            }
            if (line.unitCostMinor() < 0) {
                throw new ApiErrors.UnprocessableException("PO unit cost must be >= 0");
            }
            return new StoredLine(line.sku().value(), line.qty(), line.unitCostMinor());
        }).toList();

        PurchaseOrderEntity po = new PurchaseOrderEntity(Ids.newId(), store.value(),
                supplierId.value(), location.value(), write(stored), expectedAt);
        purchaseOrders.save(po);
        return toView(po);
    }

    @Override
    @Transactional
    public PurchaseOrderView place(PurchaseOrderId id, String actor) {
        PurchaseOrderEntity po = locked(id);
        po.transition(PurchaseOrderView.Status.PLACED);
        return toView(po);
    }

    @Override
    @Transactional
    public PurchaseOrderView cancel(PurchaseOrderId id, String actor) {
        PurchaseOrderEntity po = locked(id);
        po.transition(PurchaseOrderView.Status.CANCELLED);
        return toView(po);
    }

    @Override
    @Transactional
    public PurchaseOrderView receive(PurchaseOrderId id, List<ReceiveLine> lines, String actor) {
        PurchaseOrderEntity po = locked(id);
        if (po.status() != PurchaseOrderView.Status.PLACED
                && po.status() != PurchaseOrderView.Status.PARTIALLY_RECEIVED) {
            throw new ApiErrors.ConflictException("purchase order " + id.value() + " is "
                    + po.status() + " — only PLACED/PARTIALLY_RECEIVED receives");
        }
        if (lines == null || lines.isEmpty()) {
            throw new ApiErrors.UnprocessableException("a receipt needs at least one line");
        }

        Map<String, StoredLine> ordered = new HashMap<>();
        for (StoredLine line : readLines(po)) {
            ordered.put(line.sku(), line);
        }
        Map<String, Long> received = receivedSoFar(po);
        String currency = stores.store(new StoreId(po.getStoreId()))
                .map(StoreView::defaultCurrency)
                .orElseThrow(() -> new IllegalStateException("purchase order store vanished"));

        LocationId location = new LocationId(po.getLocationId());
        List<StoredLine> receiptLines = lines.stream().map(line -> {
            if (line.qty() < 1) {
                throw new ApiErrors.UnprocessableException("receipt qty must be >= 1");
            }
            StoredLine agreed = ordered.get(line.sku().value());
            if (agreed == null) {
                throw new ApiErrors.UnprocessableException(
                        "sku " + line.sku().value() + " is not on purchase order " + id.value());
            }
            long already = received.getOrDefault(line.sku().value(), 0L);
            if (already + line.qty() > agreed.qty()) {
                throw new ApiErrors.UnprocessableException("sku " + line.sku().value()
                        + ": receiving " + line.qty() + " would exceed the ordered "
                        + agreed.qty() + " (already received " + already + ")");
            }
            long unitCost = line.unitCostMinor() != null ? line.unitCostMinor()
                    : agreed.unitCostMinor();
            return new StoredLine(line.sku().value(), line.qty(), unitCost);
        }).toList();

        // The fact, per line: a costed RECEIPT movement — moving average +
        // stock.received ride the ledger call (§7.5, §13).
        for (StoredLine line : receiptLines) {
            ledger.receive(new VariantId(line.sku()), location, line.qty(),
                    new Money(line.unitCostMinor(), currency),
                    new Ref(Ref.RefType.PURCHASE_ORDER, po.getId()));
            received.merge(line.sku(), line.qty(), Long::sum);
        }
        receipts.save(new GoodsReceiptEntity(Ids.newId(), po.getId(), po.getStoreId(),
                write(receiptLines)));

        boolean complete = ordered.values().stream()
                .allMatch(l -> received.getOrDefault(l.sku(), 0L) >= l.qty());
        PurchaseOrderView.Status target = complete
                ? PurchaseOrderView.Status.RECEIVED : PurchaseOrderView.Status.PARTIALLY_RECEIVED;
        if (po.status() != target) {
            po.transition(target);
        }
        return toView(po);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PurchaseOrderView> purchaseOrder(PurchaseOrderId id) {
        return purchaseOrders.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseOrderView> purchaseOrdersOf(StoreId store,
                                                    Optional<PurchaseOrderView.Status> status) {
        List<PurchaseOrderEntity> rows = status
                .map(s -> purchaseOrders.findByStoreIdAndStatusOrderByCreatedAtDesc(
                        store.value(), s.name()))
                .orElseGet(() -> purchaseOrders.findByStoreIdOrderByCreatedAtDesc(store.value()));
        return rows.stream().map(this::toView).toList();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Map<String, Long> receivedSoFar(PurchaseOrderEntity po) {
        Map<String, Long> received = new HashMap<>();
        for (GoodsReceiptEntity receipt : receipts
                .findByPurchaseOrderIdOrderByReceivedAtAsc(po.getId())) {
            for (StoredLine line : readLines(receipt.getLinesJson())) {
                received.merge(line.sku(), line.qty(), Long::sum);
            }
        }
        return received;
    }

    private SupplierEntity requireSupplier(SupplierId id) {
        return suppliers.findById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("supplier", id.value()));
    }

    private PurchaseOrderEntity locked(PurchaseOrderId id) {
        return purchaseOrders.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("purchase order", id.value()));
    }

    private SupplierView toView(SupplierEntity supplier) {
        return new SupplierView(new SupplierId(supplier.getId()),
                new StoreId(supplier.getStoreId()), supplier.getName(),
                readContact(supplier.getContactJson()), supplier.getCreatedAt());
    }

    private PurchaseOrderView toView(PurchaseOrderEntity po) {
        Map<String, Long> received = receivedSoFar(po);
        List<PurchaseOrderView.Line> lines = readLines(po).stream()
                .map(l -> new PurchaseOrderView.Line(new VariantId(l.sku()), l.qty(),
                        l.unitCostMinor(), received.getOrDefault(l.sku(), 0L)))
                .toList();
        List<PurchaseOrderView.Receipt> receiptViews = receipts
                .findByPurchaseOrderIdOrderByReceivedAtAsc(po.getId()).stream()
                .map(r -> new PurchaseOrderView.Receipt(new GoodsReceiptId(r.getId()),
                        readLines(r.getLinesJson()).stream()
                                .map(l -> new PurchaseOrderView.ReceiptLine(
                                        new VariantId(l.sku()), l.qty(), l.unitCostMinor()))
                                .toList(),
                        r.getReceivedAt()))
                .toList();
        return new PurchaseOrderView(new PurchaseOrderId(po.getId()),
                new StoreId(po.getStoreId()), new SupplierId(po.getSupplierId()),
                new LocationId(po.getLocationId()), po.status(), lines, receiptViews,
                po.getExpectedAt(), po.getCreatedAt());
    }

    private List<StoredLine> readLines(PurchaseOrderEntity po) {
        return readLines(po.getLinesJson());
    }

    private List<StoredLine> readLines(String linesJson) {
        try {
            return json.readValue(linesJson, new TypeReference<List<StoredLine>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt purchasing lines_json", e);
        }
    }

    private String write(List<StoredLine> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable purchasing lines", e);
        }
    }

    private String writeContact(Map<String, Object> contact) {
        if (contact == null) {
            return null;
        }
        try {
            return json.writeValueAsString(contact);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable supplier contact", e);
        }
    }

    private Map<String, Object> readContact(String contactJson) {
        if (contactJson == null) {
            return Map.of();
        }
        try {
            return json.readValue(contactJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt supplier contact_json", e);
        }
    }

    record StoredLine(String sku, long qty, long unitCostMinor) {}
}
