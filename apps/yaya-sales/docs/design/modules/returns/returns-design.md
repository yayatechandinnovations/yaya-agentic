# yaya-sales · returns — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.returns` (§6 row 8)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §8.8 (returns & reverse logistics), §9 (tables).
**Consumes:** orders (fulfilled lines to return), fulfillment (return shipment/label), inventory (restock), payments (refund → PSP or store credit).
**Consumed by:** reporting, customers (return history).

---

## 1. Purpose

Close the loop after a sale: authorize a return, get the goods back (reverse logistics), restock inventory, and move money out — correctly and auditably.

## 2. Scope

**IN:** RMA lifecycle against fulfilled lines; **return shipment** (carrier return label or drop-off/pickup instructions for in-house); on receipt → `RETURN` movements (restock if resellable, else `SHRINKAGE`/`DAMAGE`); partial `refund` via payments (PSP or **store credit**); order → `PARTIALLY_REFUNDED`/`REFUNDED`.

**OUT:** the refund mechanics (→ payments), the physical shipment (→ fulfillment), the stock movement mechanics (→ inventory) — returns orchestrates these.

## 3. Responsibilities & key types

`Rma`, `ReturnShipment`, `RmaStatus` (parent §8.8). An RMA references order lines, a return shipment, and the resulting refund transaction.

## 4. Data model (owned tables)

`rmas(id, order_id, lines_json, status, reason, return_shipment_id, refund_txn_id, received_at, created_at)` — parent §9.

## 5. API surface (§10.1, admin)

`POST /v1/sales/admin/rmas`, `POST …/rmas/{id}/label` (return label), `POST …/rmas/{id}/receive` (restock + refund). Agent-facing "start a return" is **TBD** (could be a `sales.*` tool for self-service returns).

## 6. SPIs

None published; consumes `InventoryLedger`, `TenderProvider` (refund), fulfillment's return-shipment API.

## 7. Invariants

- Restock only what actually came back and is resellable; damaged/unsellable posts `SHRINKAGE`/`DAMAGE`, not `RETURN`.
- Refund ≤ captured amount for the returned lines; reconstructs from the order's price/FX snapshot.

## 8. Open questions (module-local)

- **TBD:** exchange/swap flows and store-credit issuance policy (parent scope table: vNext).
- **TBD:** self-service returns as an agent tool (shopper starts an RMA in chat).
- **TBD:** restocking fees / return windows per store policy.
