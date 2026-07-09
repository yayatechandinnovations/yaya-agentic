package com.yayatechandinnovations.yayasales.reporting.internal;

import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.StockLevel;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reports admin (design §10.1): sales, inventory valuation, margin (§7.5 —
 * the merchant sees profit, not just gross), low stock, and COD cash
 * reconciliation. Read-only by construction; a slow report can never block a
 * sale. Operator-authenticated, store-scoped.
 */
@RestController
@RequestMapping("/v1/sales/admin/reports")
public class ReportsController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final ReportQueries queries;
    private final InventoryAdmin inventory;
    private final StoreDirectory stores;

    public ReportsController(ReportQueries queries, InventoryAdmin inventory,
                             StoreDirectory stores) {
        this.queries = queries;
        this.inventory = inventory;
        this.stores = stores;
    }

    @GetMapping("/sales")
    public Map<String, Object> sales(@RequestHeader(STORE_HEADER) String storeId,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                     Instant from,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                     Instant to) {
        StoreView store = store(storeId);
        Instant start = window(from);
        Instant end = windowEnd(to);
        ReportQueries.Sales sales = queries.sales(store.id(), start, end);

        Map<String, Object> out = header(store, start, end);
        out.put("ordersCount", sales.ordersCount());
        out.put("grossMinor", sales.grossMinor());
        out.put("refundsMinor", sales.refundsMinor());
        out.put("netMinor", sales.grossMinor() - sales.refundsMinor());
        return out;
    }

    @GetMapping("/inventory-valuation")
    public Map<String, Object> inventoryValuation(@RequestHeader(STORE_HEADER) String storeId) {
        StoreView store = store(storeId);
        List<ReportQueries.ValuationLine> lines = queries.valuation(store.id());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", store.defaultCurrency());
        out.put("totalMinor", lines.stream()
                .mapToLong(ReportQueries.ValuationLine::valueMinor).sum());
        out.put("lines", lines.stream().map(l -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("sku", l.sku());
            line.put("location", l.location());
            line.put("onHand", l.onHand());
            line.put("avgCostMinor", l.avgCostMinor());
            line.put("valueMinor", l.valueMinor());
            return line;
        }).toList());
        return out;
    }

    @GetMapping("/margin")
    public Map<String, Object> margin(@RequestHeader(STORE_HEADER) String storeId,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                      Instant from,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                      Instant to) {
        StoreView store = store(storeId);
        Instant start = window(from);
        Instant end = windowEnd(to);
        long revenue = queries.sales(store.id(), start, end).grossMinor();
        long cogs = queries.cogs(store.id(), start, end);

        Map<String, Object> out = header(store, start, end);
        out.put("revenueMinor", revenue);
        out.put("cogsMinor", cogs);
        out.put("marginMinor", revenue - cogs);
        return out;
    }

    @GetMapping("/low-stock")
    public Map<String, Object> lowStock(@RequestHeader(STORE_HEADER) String storeId) {
        StoreView store = store(storeId);
        List<StockLevel> levels = inventory.levels(store.id(), Optional.empty(), true);
        return Map.of("levels", levels);
    }

    @GetMapping("/cod-reconciliation")
    public Map<String, Object> codReconciliation(@RequestHeader(STORE_HEADER) String storeId,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                 Instant from,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                 Instant to) {
        StoreView store = store(storeId);
        Instant start = window(from);
        Instant end = windowEnd(to);

        Map<String, Object> out = header(store, start, end);
        out.put("couriers", queries.courierCod(store.id(), start, end).stream().map(c -> {
            Map<String, Object> courier = new LinkedHashMap<>();
            courier.put("agentId", c.agentId());
            courier.put("legalName", c.legalName());
            courier.put("ordersDelivered", c.ordersDelivered());
            courier.put("codSettledMinor", c.codSettledMinor());
            return courier;
        }).toList());
        out.put("mismatches", Map.of(
                "settledWithoutDelivery",
                queries.codSettledWithoutDelivery(store.id(), start, end).stream()
                        .map(ReportsController::mismatch).toList(),
                "deliveredWithoutSettlement",
                queries.deliveredWithoutCodSettlement(store.id(), start, end).stream()
                        .map(ReportsController::mismatch).toList()));
        return out;
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private static Map<String, Object> mismatch(ReportQueries.CodMismatch m) {
        return Map.of("orderId", m.orderId(), "amountMinor", m.amountMinor());
    }

    private static Map<String, Object> header(StoreView store, Instant from, Instant to) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("currency", store.defaultCurrency());
        return out;
    }

    private static Instant window(Instant from) {
        return from == null ? Instant.EPOCH : from;
    }

    private static Instant windowEnd(Instant to) {
        return to == null ? Instant.now() : to;
    }

    private StoreView store(String storeId) {
        return stores.store(new StoreId(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("store", storeId));
    }
}
