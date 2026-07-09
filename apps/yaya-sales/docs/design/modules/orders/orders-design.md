# yaya-sales · orders — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.orders` (§6 row 5)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §8.1 (order FSM + draft orders), §8.2 (checkout saga), §9 (tables), §14 (correctness).
**Consumes:** inventory (reserve/revalidate/commit), pricing (quote), payments (tenders), fulfillment (delivery quote + slot), stores (region/channel/policies), platform (idempotency, outbox).
**Consumed by:** integration (checkout + draft-order tools), returns (RMA against fulfilled lines), reporting.

---

## 1. Purpose

Own the order aggregate and its lifecycle — the orchestrator that turns a validated cart into a paid, fulfillable order via the **checkout saga**, and the home of **draft orders** (agent-built "tap to pay").

## 2. Scope

**IN:** `Order` aggregate (lines, priced snapshot, payment sub-state, fulfillment sub-state, append-only `order_events`); the FSM (parent §8.1); `DRAFT_ORDER`; the **checkout saga with compensations** (§8.2: address-validate → revalidate holds → quote → slot → create order → begin tender → settle → commit SALE → clear cart); idempotency on every mutation; optimistic concurrency (`version`).

**OUT:** stock mechanics (→ inventory), money capture (→ payments), physical delivery (→ fulfillment) — orders *drives* these via their SPIs but doesn't implement them.

## 3. Responsibilities & key types

`Order`, `OrderLine`, `OrderStatus`, `OrderEvent` (parent §8.1). Payment sub-state + fulfillment sub-state aggregate from the payments/fulfillment modules. Transitions are event-driven (webhooks/tenders/shipments), never raw API mutations.

## 4. Data model (owned tables)

`orders` (+ `channel_id`, `region_id`, `source CHECKOUT|DRAFT`, `version`), `order_events` (append-only) — parent §9. Tenders live in payments' `order_tenders`.

## 5. API surface

- Agent (§10.2): `POST /checkout`, `POST /draft-orders` + `…/{id}/send`, `GET /orders/{id}`.
- Admin (§10.1): `GET /orders[/{id}]`, `POST /draft-orders`, `…/{id}/cancel`.

## 6. SPIs

None published; orchestrates `InventoryLedger`, `PricingEngine`, `TenderProvider`, `DeliveryQuoteEngine`, `DeliverySlots`, `AddressValidator`.

## 7. Invariants (parent §14)

- **Idempotent checkout:** replaying `checkout` with the same key → one order, one reservation set, one tender.
- **Saga compensation:** any step failing releases holds/slot, cancels the draft, voids the auth — no orphan reservation or phantom order.
- A draft reserves stock only when sent for payment, not while assembled (parent §8.1).

## 8. Open questions (module-local)

- **TBD:** order-editing after placement (add/remove line pre-fulfillment) — vNext but reserve the event types.
- **TBD:** split-tender orchestration order (which tender settles first; partial-settle states).
- **TBD:** B2B quote extension of draft orders (net terms/approvals) — vNext.
