# yaya-sales · purchasing — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.purchasing` (§6 row 9)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §9 (tables), §7.5 (valuation via unit cost on receipt).
**Consumes:** inventory (`receive` → `RECEIPT` movements + unit cost), stores (locations).
**Consumed by:** inventory valuation, reporting (low-stock → reorder).

---

## 1. Purpose

The stock-in end of the loop: buy from suppliers and **receive goods**, posting `RECEIPT` movements with unit cost so on-hand reflects reality and valuation (moving-average) has real costs.

## 2. Scope

**IN:** suppliers; purchase orders (create/list, expected date, lines); goods receipts → `inventory.receive(sku, loc, qty, unitCost, poRef)`; basic PO status lifecycle.

**OUT:** supplier catalogs / 3-way match / landed cost (vNext); the stock ledger itself (→ inventory); payment to suppliers (out of scope — not accounts-payable).

## 3. Responsibilities & key types

`Supplier`, `PurchaseOrder`, `GoodsReceipt` (parent §9). A receipt line carries `(sku, qty, unitCost)` and drives a `RECEIPT` movement + moving-average update.

## 4. Data model (owned tables)

`suppliers`, `purchase_orders`, `goods_receipts` — parent §9.

## 5. API surface (§10.1, admin)

`POST/GET /v1/sales/admin/purchase-orders[/{id}]`, `POST …/purchase-orders/{id}/receive`.

## 6. SPIs

None published; consumes `InventoryLedger.receive`.

## 7. Invariants

- A goods receipt posts exactly one `RECEIPT` movement per line with its unit cost (feeds valuation §7.5).
- Partial receipt allowed; PO tracks received vs ordered.

## 8. Open questions (module-local)

- **TBD:** partial/over-receipt handling and PO close policy.
- **TBD:** supplier catalog + reorder suggestion from `stock.low` (parent §7.2).
- **TBD:** landed cost (freight/duty allocation into unit cost) — vNext.
