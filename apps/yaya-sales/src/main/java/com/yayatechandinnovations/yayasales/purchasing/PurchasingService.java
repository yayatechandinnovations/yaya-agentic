package com.yayatechandinnovations.yayasales.purchasing;

import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.PurchaseOrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.SupplierId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §9 / §7.5 — suppliers, purchase orders, goods receipts. {@link #receive}
 * is the point of contact with reality: one RECEIPT movement per line through
 * the inventory ledger, unit cost from the receipt line (or the PO line when
 * omitted), partial receipts allowed, never more than ordered.
 */
public interface PurchasingService {

    SupplierView createSupplier(StoreId store, String name, Map<String, Object> contact);

    SupplierView updateSupplier(SupplierId id, String name, Map<String, Object> contact);

    /** Refused while purchase orders reference the supplier — history stays explainable. */
    void deleteSupplier(SupplierId id);

    List<SupplierView> suppliersOf(StoreId store);

    Optional<SupplierView> supplier(SupplierId id);

    PurchaseOrderView create(StoreId store, SupplierId supplier, LocationId location,
                             List<NewLine> lines, Instant expectedAt);

    PurchaseOrderView place(PurchaseOrderId id, String actor);

    /** DRAFT/PLACED only — goods already received cannot be un-bought. */
    PurchaseOrderView cancel(PurchaseOrderId id, String actor);

    PurchaseOrderView receive(PurchaseOrderId id, List<ReceiveLine> lines, String actor);

    Optional<PurchaseOrderView> purchaseOrder(PurchaseOrderId id);

    List<PurchaseOrderView> purchaseOrdersOf(StoreId store,
                                             Optional<PurchaseOrderView.Status> status);

    record NewLine(VariantId sku, long qty, long unitCostMinor) {}

    /** {@code unitCostMinor} null = the PO line's agreed cost. */
    record ReceiveLine(VariantId sku, long qty, Long unitCostMinor) {}
}
