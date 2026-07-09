# yaya-sales · fulfillment & delivery ★ — Module Design

**Status:** Scaffold (to expand) — the second core module.
**Module:** `com.yayatechandinnovations.yayasales.fulfillment` (§6 row 7)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §8.4–8.9 (methods, zones/rates, carriers, shipments, tracking, courier fleet), §9 (tables), §10.4 (courier surface), §11.5 (courier screen in yayaagenticchat), §14 (correctness).
**Consumes:** orders (what to ship), inventory (commit on dispatch for `ON_FULFILLMENT`), pricing (rate → shippingTotal), stores (zones/locations), payments (COD settle on collect), platform (media/proof, outbox).
**Consumed by:** orders (delivery quote + slot at checkout), returns (return shipments), integration (`delivery/options`, `track`).

---

## 1. Purpose

Get the order to the customer and prove it arrived — delivery methods, priced+promised options at checkout, shipments, delivery tracking, and the **in-house courier fleet** (self-registration + KYC + COD collection). Given the DR/LatAm/WhatsApp market, `LOCAL_DELIVERY` by an own courier is the dominant path.

## 2. Scope

**IN:** delivery methods (`SHIPPING|LOCAL_DELIVERY|PICKUP|DIGITAL`); shipping **zones**; **rates** (`Flat|ByWeight|ByPrice|FreeOver|CarrierCalculated`); carriers (`ManualCarrier`, `InHouseCourier` in v1); shipments (pick/pack/dispatch, partial/split); **delivery-status tracking** + carrier webhook/polling; delivery **slots** (hard capacity) + pickup OTP; **proof of delivery**; **courier fleet: self-registration, KYC docs + merchant approval, assignment, COD-collect** (§8.9); the courier surface (§10.4) + its screen in yayaagenticchat (§11.5).

**OUT:** the money settle itself (→ payments; courier triggers it), the RMA lifecycle (→ returns; this module provides the return *shipment*), external commercial-carrier API integration (vNext behind `Carrier`).

## 3. Responsibilities & key types

See parent §8.4–8.9: `DeliveryMethodKind`, `DeliveryQuoteEngine`/`DeliveryOption`, `ShippingZone`, `ShippingRate`/`RateRule`, `Carrier`, `Shipment` (`DRAFT→PICKING→PACKED→DISPATCHED`), `DeliveryStatus`/`TrackingEvent`, `DeliverySlots`, `DeliveryAgent` (KYC: legal name/address, photo ID, vehicle docs; `PENDING→DOCS_SUBMITTED→UNDER_REVIEW→APPROVED/REJECTED/SUSPENDED`), `DeliveryFleet`.

## 4. Data model (owned tables)

`shipping_zones`, `delivery_methods`, `shipping_rates`, `carriers`, `delivery_slots`, `shipments`, `tracking_events`, `delivery_agent_invites`, `delivery_agents`, `delivery_agent_documents`, `delivery_assignments` — parent §9.

## 5. API surface

- Agent (§10.2): `POST /delivery/options`, `GET /orders/{id}/track`.
- Admin (§10.1): shipping config CRUD, `POST /shipments`, `…/{pick|pack|dispatch}`, `PUT …/delivery-status`, fleet invites + KYC review + assign.
- Courier (§10.4): register/verify/documents, assignments, status advance + proof, `collect-cod`.
- Webhooks (§10.3): `POST /v1/sales/webhooks/carrier/{carrier}`.

## 6. SPIs

- `DeliveryQuoteEngine`, `Carrier`, `DeliverySlots`, `DeliveryFleet` (all published; parent §17).

## 7. Invariants (parent §14)

- **No slot oversell** (guarded like inventory reservations).
- **Idempotent carrier tracking** (dedupe on carrier event id).
- **Courier KYC gate:** only `APPROVED` agents (docs reviewed) can be assigned / collect COD; invite-gated, store-scoped.
- Dispatch commits the reservation → `SALE` on `ON_FULFILLMENT` stores.

## 8. Open questions (module-local)

- **TBD:** auto-assignment algorithm (coverage/availability) vs manual.
- **TBD:** which specific external carrier (if any) to wire before vNext (parent §16 Q8).
- **TBD:** proof-of-delivery capture spec (photo size/format, signature, OTP) shared with the yayaagenticchat screen.
- **TBD:** fulfillment-order routing abstraction when multi-location ships split.
