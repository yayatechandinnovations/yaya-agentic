# yaya-sales · reporting — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.reporting` (§6 row 14)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §6 (module map), §7.5 (valuation/margin), §9 (read models), §10.1 (report endpoints).
**Consumes:** orders, inventory, payments (read-only projections).
**Consumed by:** the yaya-sales admin console; optionally a merchant ops profile via the agent.

---

## 1. Purpose

Read-only business insight — sales, revenue, **inventory valuation / margin**, low-stock, aged-stock, and **COD reconciliation** — with no write path and no impact on the transactional modules.

## 2. Scope

**IN:** materialized views / read models over orders + inventory + payments; the report endpoints; low-stock alerts (from `stock.low`); COD cash reconciliation (collected vs handed in).

**OUT:** anything transactional; double-entry accounting/GL (non-goal); forecasting (non-goal).

## 3. Responsibilities & key types

Query/read-model DTOs per report. Margin = revenue − COGS (COGS from the moving-average cost drawn at each `SALE`, parent §7.5).

## 4. Data model

No owned transactional tables; materialized views/projections over `orders`, `order_tenders`, `stock_movements`, `stock_summary`. Refresh cadence **TBD**.

## 5. API surface (§10.1, admin)

`GET /v1/sales/admin/reports/{sales|inventory-valuation|low-stock|margin|cod-reconciliation}` (with date ranges/filters).

## 6. SPIs

None.

## 7. Invariants

- Read-only: reporting never mutates domain state; a slow/failed report never blocks a sale.
- Figures reconcile to the ledgers (stock ledger, payment ledger) they project.

## 8. Open questions (module-local)

- **TBD:** materialized-view refresh strategy (on-demand vs scheduled vs event-driven from the outbox).
- **TBD:** exposing selected reports as an agent tool for a merchant ops profile.
- **TBD:** aged-stock/turnover metric definitions.
