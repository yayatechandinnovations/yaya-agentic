# yaya-sales · platform — Module Design

**Status:** Scaffold (to expand) — cross-cutting spine; built first (S0).
**Module:** `com.yayatechandinnovations.yayasales.platform` (§6 row 15)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §5.4 (money/FX), §5.5 (idempotency), §5.6 (outbox), §5.7 (concurrency), §12.4 (S0 scaffold + DB isolation), §15 (secrets, PII).
**Consumes:** nothing (no domain deps).
**Consumed by:** every module.

---

## 1. Purpose

The correctness spine and shared infrastructure every domain module leans on — no business logic, just the primitives that make the system safe under retries, concurrency, and money.

## 2. Scope

**IN:** typed IDs (`StoreId`, `ProductId`, `VariantId`, `LocationId`, `CartId`, `OrderId`, `MovementId`, `ReservationId`, `ShipmentId`, `AgentId`, …); `Money` (minor units + currency) + `FxRate`; **idempotency store** (`(store,key)→response`); **transactional outbox** + relay; optimistic-concurrency helpers (`version` compare-and-set); the **auth filter** (service-token verify + forwarded principal-JWT verify + delivery-agent token); **secret cipher** (AES-256-GCM envelope) for PSP/carrier/document secrets; self-hosted **media** store (image/doc, encrypted, capped); observability; the **own-schema DB setup** (`yaya_sales`, no cross-schema FKs — parent §12.4).

**OUT:** all domain concerns.

## 3. Responsibilities & key types

See parent §5.4–5.7 and §17: `IdempotencyStore`, `OutboxRelay`, `Money`, plus the auth filter distinguishing the four caller classes (operator / service / shopper-principal / delivery-agent).

## 4. Data model (owned tables)

`idempotency_keys`, `outbox` — parent §9. Media/secret blobs via the encrypted store. Flyway history starts at `V1__` in the `yaya_sales` schema.

## 5. API surface

No public endpoints; provides filters, stores, and helpers as in-process module APIs.

## 6. SPIs

- `IdempotencyStore`, `OutboxRelay` (published; parent §17). Auth verification + secret cipher are internal services.

## 7. Invariants (parent §5, §14)

- Every mutating call is idempotent; outbox rows are written in the same tx as the state change and delivered at-least-once (consumers dedupe on event id).
- No float on the money path anywhere.
- DB isolation: **zero cross-schema FKs/joins to yaya-agentic** so yaya-sales lifts to a separate instance without query changes.
- Secrets encrypted at rest, never returned to any client.

## 8. Open questions (module-local)

- **TBD:** outbox relay transport (polling table vs LISTEN/NOTIFY vs Debezium) and idempotency-key retention window.
- **TBD:** media backend (MinIO vs local) + document (KYC/PII) encryption + retention policy plumbing (parent §15).
- **TBD:** delivery-agent token issuance/rotation (short-lived vs long-lived) and OTP reuse from chat account machinery.
