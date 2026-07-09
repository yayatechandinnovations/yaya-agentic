package com.yayatechandinnovations.yayasales.reporting.internal;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The report aggregations, as read-only SQL over the sibling modules' tables.
 * Deliberately JdbcTemplate, not their repositories: reporting may never touch
 * another module's internals (Modulith boundary), and an aggregate belongs in
 * the database anyway. Every query is store-scoped and schema-qualified —
 * the JDBC connection's search_path is not ours to assume.
 */
@Component
class ReportQueries {

    /** payment_state values meaning "money actually settled" — revenue, not intent. */
    private static final String PAID_STATES = "('PAID','PARTIALLY_REFUNDED','REFUNDED')";

    private final JdbcTemplate jdbc;

    ReportQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Sales(long ordersCount, long grossMinor, long refundsMinor) {}

    @Transactional(readOnly = true)
    public Sales sales(StoreId store, Instant from, Instant to) {
        var totals = jdbc.queryForMap("""
                SELECT COUNT(*) AS orders_count,
                       COALESCE(SUM((pricing_json->>'grandMinor')::bigint), 0) AS gross_minor
                FROM yaya_sales.orders
                WHERE store_id = ? AND payment_state IN %s
                  AND created_at >= ? AND created_at < ?
                """.formatted(PAID_STATES),
                store.value(), Timestamp.from(from), Timestamp.from(to));
        Long refunds = jdbc.queryForObject("""
                SELECT COALESCE(SUM(pt.amount_minor), 0)
                FROM yaya_sales.payment_transactions pt
                JOIN yaya_sales.orders o ON o.id = pt.order_id
                WHERE o.store_id = ? AND pt.kind = 'REFUND' AND pt.status = 'OK'
                  AND pt.created_at >= ? AND pt.created_at < ?
                """, Long.class, store.value(), Timestamp.from(from), Timestamp.from(to));
        return new Sales(((Number) totals.get("orders_count")).longValue(),
                ((Number) totals.get("gross_minor")).longValue(),
                refunds == null ? 0 : refunds);
    }

    record ValuationLine(String sku, String location, long onHand, Long avgCostMinor,
                         String currency, long valueMinor) {}

    @Transactional(readOnly = true)
    public List<ValuationLine> valuation(StoreId store) {
        return jdbc.query("""
                SELECT sku_id, location_id, on_hand, avg_cost_minor, currency
                FROM yaya_sales.stock_summary
                WHERE store_id = ? AND on_hand > 0
                ORDER BY sku_id, location_id
                """,
                (rs, i) -> {
                    long onHand = rs.getLong("on_hand");
                    long avgCost = rs.getLong("avg_cost_minor");
                    boolean costed = !rs.wasNull();
                    return new ValuationLine(rs.getString("sku_id"), rs.getString("location_id"),
                            onHand, costed ? avgCost : null, rs.getString("currency"),
                            costed ? onHand * avgCost : 0);
                },
                store.value());
    }

    /** COGS = Σ over SALE movements of −qtyDelta × the avg cost drawn at commit (§7.5). */
    @Transactional(readOnly = true)
    public long cogs(StoreId store, Instant from, Instant to) {
        Long cogs = jdbc.queryForObject("""
                SELECT COALESCE(SUM(-qty_delta * unit_cost_minor), 0)
                FROM yaya_sales.stock_movements
                WHERE store_id = ? AND kind = 'SALE' AND unit_cost_minor IS NOT NULL
                  AND occurred_at >= ? AND occurred_at < ?
                """, Long.class, store.value(), Timestamp.from(from), Timestamp.from(to));
        return cogs == null ? 0 : cogs;
    }

    record CourierCod(String agentId, String legalName, long ordersDelivered,
                      long codSettledMinor) {}

    record CodMismatch(String orderId, long amountMinor) {}

    @Transactional(readOnly = true)
    public List<CourierCod> courierCod(StoreId store, Instant from, Instant to) {
        // Delivered orders per courier in the window; settled COD joined per
        // order (settle may land outside the window — the delivery anchors it).
        return jdbc.query("""
                SELECT da.agent_id, ag.legal_name,
                       COUNT(DISTINCT s.order_id) AS orders_delivered,
                       COALESCE(SUM(t.amount_minor), 0) AS cod_settled_minor
                FROM yaya_sales.delivery_assignments da
                JOIN yaya_sales.shipments s ON s.id = da.shipment_id
                JOIN yaya_sales.delivery_agents ag ON ag.id = da.agent_id
                LEFT JOIN yaya_sales.order_tenders t
                       ON t.order_id = s.order_id AND t.kind = 'COD' AND t.status = 'SETTLED'
                WHERE da.store_id = ? AND da.status <> 'REASSIGNED'
                  AND s.delivery_status = 'DELIVERED'
                  AND s.delivered_at >= ? AND s.delivered_at < ?
                GROUP BY da.agent_id, ag.legal_name
                ORDER BY ag.legal_name
                """,
                (rs, i) -> new CourierCod(rs.getString("agent_id"), rs.getString("legal_name"),
                        rs.getLong("orders_delivered"), rs.getLong("cod_settled_minor")),
                store.value(), Timestamp.from(from), Timestamp.from(to));
    }

    /** Cash marked collected without any DELIVERED shipment — where did it go? */
    @Transactional(readOnly = true)
    public List<CodMismatch> codSettledWithoutDelivery(StoreId store, Instant from, Instant to) {
        return jdbc.query("""
                SELECT t.order_id, t.amount_minor
                FROM yaya_sales.order_tenders t
                JOIN yaya_sales.orders o ON o.id = t.order_id
                WHERE o.store_id = ? AND t.kind = 'COD' AND t.status = 'SETTLED'
                  AND t.settled_at >= ? AND t.settled_at < ?
                  AND NOT EXISTS (SELECT 1 FROM yaya_sales.shipments s
                                  WHERE s.order_id = t.order_id
                                    AND s.delivery_status = 'DELIVERED')
                ORDER BY t.settled_at
                """,
                (rs, i) -> new CodMismatch(rs.getString("order_id"), rs.getLong("amount_minor")),
                store.value(), Timestamp.from(from), Timestamp.from(to));
    }

    /** Goods handed over but the cash never marked collected — shrinkage in motion. */
    @Transactional(readOnly = true)
    public List<CodMismatch> deliveredWithoutCodSettlement(StoreId store, Instant from,
                                                           Instant to) {
        return jdbc.query("""
                SELECT DISTINCT s.order_id, t.amount_minor
                FROM yaya_sales.shipments s
                JOIN yaya_sales.orders o ON o.id = s.order_id
                JOIN yaya_sales.order_tenders t
                     ON t.order_id = s.order_id AND t.kind = 'COD' AND t.status = 'PENDING'
                WHERE o.store_id = ? AND s.delivery_status = 'DELIVERED'
                  AND s.delivered_at >= ? AND s.delivered_at < ?
                ORDER BY s.order_id
                """,
                (rs, i) -> new CodMismatch(rs.getString("order_id"), rs.getLong("amount_minor")),
                store.value(), Timestamp.from(from), Timestamp.from(to));
    }
}
