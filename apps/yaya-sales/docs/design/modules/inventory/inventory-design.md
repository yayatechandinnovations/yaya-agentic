# yaya-sales · inventory ★ — Module Design

**Status:** Scaffold (to expand) — the core module; highest correctness bar.
**Module:** `com.yayatechandinnovations.yayasales.inventory` (§6 row 2)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §5.1–5.3 (ledger, no-oversell, reserve→capture→deduct), §7 (the full module), §9 (tables), §14 (correctness).
**Consumes:** platform (concurrency, outbox), stores (policies: deduction point, backorder, TTLs).
**Consumed by:** orders (reserve/commit), fulfillment (commit on dispatch), returns (restock), purchasing (receive), catalog/search (availability).

---

## 1. Purpose

Answer authoritatively: *how many can I sell, what happened to a unit, and what is my stock worth?* On-hand is a **projection of an append-only movement ledger**; availability is guarded so nothing oversells; valuation gives COGS/margin.

## 2. Scope

**IN:** `stock_movements` ledger; `stock_summary` projection (on_hand/reserved/avg_cost/version); reservations (`SOFT` add-to-cart + `FIRM` checkout, revalidation); availability + backorder; locations (multi, single default seeded); adjustments/transfers/receipts/restocks; moving-average valuation; reorder points + `stock.low`; expiry sweeper.

**OUT:** *when* to reserve/commit is orders/fulfillment's call (this module executes); pricing of stock (→ pricing); PO lifecycle (→ purchasing, which calls `receive`).

## 3. Responsibilities & key types

See parent §7.1–7.3 for `StockMovement`, `MovementKind`, `StockSummary`, `Reservation` (`ReservationKind SOFT|FIRM`), `ReserveResult`, and the `InventoryLedger` SPI (`reserve/revalidate/commit/release/receive/restock/adjust/transfer`).

## 4. Data model (owned tables)

`stock_movements` (append-only, SoT), `stock_summary` (projection + concurrency guard), `reservations` — parent §9. Lot/serial/expiry columns present, off in v1.

## 5. API surface

Admin (§10.1): `GET /inventory`, `POST /inventory/{sku}/adjust`, `POST /inventory/transfer`, `GET /inventory/{sku}/ledger`, `PUT /inventory/{sku}/reorder`, `GET/POST /locations`. No public/agent write path — stock moves only via orders/fulfillment/returns/purchasing.

## 6. SPIs

- `InventoryLedger` (published; §7.3).
- `Valuation { Money unitCostOnSale(...); void onReceipt(...) }` — moving-average v1, FIFO layers vNext.

## 7. Invariants (must be green — parent §14)

- **No oversell:** N concurrent `reserve` for the last unit → exactly one `Held`.
- **Ledger ≡ summary:** `Σ movements.qtyDelta == stock_summary.on_hand` for every (sku, location).
- **Soft-hold + revalidation:** SOFT holds expire/sweep; checkout revalidation never commits on expired stock.
- Reservation reserved-count never goes negative; commit posts exactly one `SALE`.

## 8. Open questions (module-local)

- **TBD:** summary rebuild/repair job (ledger → summary) cadence + online rebuild.
- **TBD:** multi-location availability aggregation + allocation strategy when a second location is enabled.
- **TBD:** FIFO cost-layer schema behind `Valuation` (vNext) — reserve the table shape now.
