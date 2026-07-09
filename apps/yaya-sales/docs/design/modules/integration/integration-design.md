# yaya-sales · integration — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.integration` (§6 row 13)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §10.2 (agent-facing API), §10.3 (webhooks), §11 (the whole integration seam), §13 (events).
**Consumes:** every domain module (orchestrates their in-process APIs).
**Consumed by:** yaya-agentic (via HTTP tools), merchant webhook subscribers.

---

## 1. Purpose

The seam. Own the **agent-facing REST surface** yaya-agentic's HTTP tools call, the **`_uiHint` framing** of responses, and the **service + principal auth** verification — so the rest of yaya-sales stays domain code and yaya-agentic stays domain-neutral.

## 2. Scope

**IN:** `/v1/sales/agent/*` endpoints (search, product, cart, delivery/options, tenders, checkout, draft-orders, orders, track); `_uiHint` frame construction (`product_catalog`, `product_card`, `cart`, `delivery_options`, `tender_options`, `checkout`, `confirm`, `delivery_tracking`); `SERVICE_TOKEN` + `PRINCIPAL_TOKEN` verification; the optional **tool-descriptor manifest** (`/v1/sales/agent/manifest`); outbound-event → merchant-webhook relay hookup (with platform outbox).

**OUT:** the domain logic behind each endpoint (delegated to catalog/cart/orders/…); the courier surface (§10.4 — a distinct caller class, lives with fulfillment); the yaya-agentic `_uiHint` pass-through itself (that's a ~30-line change on the agent side, parent §11.2).

## 3. Responsibilities & key types

Thin controllers that validate the caller, delegate to a domain module, and attach a `_uiHint` frame. The `_uiHint` payload schemas are owned here (versioned with the storefront renderer).

## 4. Data model

No owned domain tables; may own a per-store webhook-subscription config table (**TBD**).

## 5. API surface

- Agent (§10.2): the full `/v1/sales/agent/*` set.
- Webhooks *out*: merchant-configured subscriptions fed by the outbox relay (§13).
- Manifest (parent §16 Q5-legacy): `/v1/sales/agent/manifest` — pull-based, operator-approved tool descriptors.

## 6. SPIs

None published; consumes the platform auth filter, idempotency, and outbox.

## 7. Invariants (parent §15)

- Agent API rejects any call without a valid `SERVICE_TOKEN`; it never exposes catalog mutation, stock adjustment, or checkout to the open internet.
- `_uiHint` frames originate only from trusted tool responses (never the model) — preserves non-negotiable #8.
- Tokens verified against the same issuer trust yaya-agentic uses; never silently forwarded (#6).

## 8. Open questions (module-local)

- **TBD:** manifest auto-registration UX (operator approves; never auto-trust).
- **TBD:** `_uiHint` payload schema versioning strategy shared with `yayaagenticchat` renderer.
- **TBD:** per-store webhook subscription model + retry/backoff.
