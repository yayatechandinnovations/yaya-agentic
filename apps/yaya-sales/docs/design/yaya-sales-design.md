# Yaya Sales — Design Document

**Status:** Proposed (v1 target — a real, production-grade sales system, not a demo)
**Owner:** (you)
**Audience:** Engineers building yaya-sales; engineers wiring the yaya-agentic ↔ yaya-sales integration.
**Location:** `apps/yaya-sales/` — a **shippable integration application**, standalone from the yaya-agentic core (see `../../README.md`, CLAUDE.md → *"apps/ — shippable integrations"*).
**Anchors (yaya-agentic):** `docs/design/yaya-agentic-design.md` §5.2 (Profile), §5.3 (Capability & Tool — the `ToolHandlerRef.Http` transport we integrate through), §5.4/§5.5 (AuthN/AuthZ), §6.1/§9 (`UiHint` events), §12 (Trust boundaries). CLAUDE.md non-negotiables **#1** (LLM never trusted), **#2** (no out-of-profile tools), **#5** (tool results are untrusted data), **#6** (explicit `AuthForwarding`), **#8** (UI hints are engine-emitted, never LLM output).
**Supersedes / relocates:** the in-tree commerce subsystem under `com.yayatechandinnovations.yayaagentic.commerce.*` and the three commerce design docs (`ecommerce-chat-design.md`, `commerce-redesign-authoring-rendering-paypal.md`, `merchant-psp-onboarding-design.md`). Those got the *chat storefront* right; this doc re-homes the runtime into a separate application **and levels the domain up from "a catalog with a cart" to a real sales system** — ledgered inventory, an orders/payments/fulfillment lifecycle, returns, purchasing/receiving, and the correctness spine (idempotency, concurrency, money) a store needs to not lose or oversell stock.

---

## 1. Summary

**yaya-sales** is a standalone, multi-tenant **sales system**: catalog, **ledgered inventory** (stock that moves as you receive, sell, return, and adjust it), pricing/promotions, cart, a full **order → payment → fulfillment → return** lifecycle, purchasing/receiving, customers, and reporting with inventory valuation. It owns every product record, every price, every unit of stock, and every charge.

It ships **inside this repo under `apps/`** but is **not part of yaya-agentic**. yaya-agentic is a framework for profile-driven, auth-aware conversational agents; commerce is a *domain*, not a framework primitive. The two meet at exactly one existing seam — **HTTP tools** (`ToolHandlerRef.Http`, design §5.3): a commerce profile lists `sales.*` operations that dispatch to the tenant's yaya-sales host. The agent proposes and the yaya-agentic `Authorizer` disposes; yaya-sales executes, and **owns the money and the stock**.

What makes it *real*, and not the earlier "simple but complete" sketch:

- **Inventory is an append-only ledger, not a counter.** On-hand is the sum of immutable **stock movements** (receipt, sale, return, adjustment, transfer). Selling a unit posts a `SALE` movement; on-hand is a projection. This gives auditability, correct concurrency, valuation (FIFO / moving-average cost → margin), and a reason for every unit that ever moved. You can answer "why is on-hand 7?" down to the movement.
- **Selling can't oversell.** Availability = on-hand − open reservations, guarded by optimistic concurrency on a per-(SKU, location) summary row. Two shoppers cannot both buy the last unit.
- **Reserve → capture → deduct is explicit.** Checkout takes a time-boxed **reservation** (holds availability, not on-hand); on payment capture (or on fulfillment — a store policy), the reservation commits into a `SALE` movement that decrements on-hand. Cancel/expiry releases it. Returns restock via a `RETURN` movement.
- **Orders, payments, and fulfillment are real state machines**, with a payment ledger (authorize/capture/refund), partial fulfillment and partial refunds, and idempotency on every money- or stock-moving call.
- **The stock loop is closed at both ends.** Purchasing/receiving (`RECEIPT` movements) increases stock; sales/shrinkage decreases it. Not just "decrement on sale."

The division of authority is unchanged and now sharper: **yaya-agentic decides *whether* a shopper may act; yaya-sales decides *what the action costs, whether stock exists, and whether money and inventory actually moved*.**

---

## 2. Goals & Non-Goals

### 2.1 Goals (v1)

- **G1 — Ledgered, auditable inventory.** Every stock change is an immutable movement; on-hand and available are projections; nothing silently decrements. Reservations prevent overselling under concurrency.
- **G2 — A complete sell-side lifecycle.** cart → checkout → order → authorize → capture → fulfill → (return/refund), each a real state machine with partial fulfillment and partial refunds.
- **G3 — Correctness spine.** Idempotency keys on all mutations, optimistic concurrency on stock/orders, money in integer minor units with multi-currency + FX snapshots, a transactional outbox so no event is lost.
- **G4 — Closed stock loop.** Purchasing → goods receipt increases stock; sales/returns/adjustments/transfers move it — the full inventory cycle, not just sale-time decrement.
- **G5 — Zero commerce code in the agent core.** yaya-agentic carries no product/cart/order/stock/PSP types. Only (a) HTTP tool descriptors (data, per tenant) and (b) one generic "tool result → UI hint" pass-through (§11.2).
- **G6 — Integrate through the existing tool seam, no new agent SPI.** Reuse `ToolHandlerRef.Http`, `AuthForwarding`, `TurnEvent.UiHint`.
- **G7 — Money & inventory authoritative in yaya-sales, never the model, never the agent.** Every price, tax, fee, stock check, capture, and movement is a yaya-sales server computation.
- **G8 — Own admin console + storefront rendering contract, and deploy independently** (own DB, migrations, container, scaling). Runs without yaya-agentic and vice versa.

### 2.2 Non-Goals (v1)

- **Not a full ERP.** No general ledger/accounting, no manufacturing/BOM, no payroll, no demand forecasting. We stop at inventory *valuation* (COGS/margin), not double-entry financials.
- **No multi-warehouse transfer *optimization*.** We model locations and support transfers, but not routing/allocation optimization across many warehouses.
- **No new *PSPs* beyond the three integrated** (dLocal, Stripe Connect, PayPal). Adding one is an SPI implementation, not a v1 milestone. (v1 *does* add non-PSP **tenders** — COD, manual transfer, store credit — because those are the market's common cases, not a card integration.)
- **No headless-CMS content modeling.** Products have a pragmatic fixed schema, not arbitrary content types.
- **No shared database with yaya-agentic.** Integration is API-only; the two apps never read each other's tables.
- **No paid-service dependency in the critical path.** Search is Postgres FTS by default; pgvector semantic search is optional and self-hosted (standing self-hosted constraint).

### 2.3 v1 vs. vNext (scope honesty)

| Capability | v1 | vNext |
|---|---|---|
| Ledgered inventory, reservations, availability | ✅ | — |
| Locations (multi) modeled; single default seeded | ✅ | routing/optimization |
| Valuation: moving-average cost | ✅ | FIFO cost layers, landed cost |
| Lot / serial / expiry tracking | schema-ready, off | ✅ enable |
| Purchasing → goods receipt | ✅ (basic PO + receive) | supplier catalogs, 3-way match |
| Orders, partial fulfillment, partial refund | ✅ | — |
| Shipping: zones, rates (flat/weight/price/free-over), delivery methods | ✅ | live carrier-calculated rates |
| Carriers: manual + in-house courier; delivery-status tracking + webhooks | ✅ | real carrier label/rate/tracking APIs |
| Delivery slots / pickup (click-and-collect) + proof of delivery | ✅ | route optimization, driver app |
| Split shipments across multiple locations | modeled | ✅ enable |
| Returns / RMA + restock + return shipment | ✅ | exchange/swap flows |
| **Courier fleet: self-registration + KYC (legal identity, photo ID, vehicle docs, merchant review), assignment, COD collect** | ✅ | background-check hook, route optimization, native driver app |
| Multi-store per tenant (`StoreId` ≠ `TenantId`) | ✅ | cross-store shared catalog |
| Address validation at checkout | ✅ | external verification service |
| Promotions / coupons / tiered pricing | ✅ | complex promo stacking rules |
| **Channels (web-chat/WhatsApp/SMS/POS) + Regions/Markets** | ✅ | per-channel theming, geo auto-detect |
| **Tenders: PSP + cash-on-delivery + manual transfer + store credit; split tender** | ✅ | saved cards, 3DS/SCA vault |
| **Tax: inclusive/exclusive + region rate table** | ✅ | pluggable external tax SPI, tax classes per product |
| **Draft orders / agent-built "tap to pay"** | ✅ | full B2B quotes, net terms, approvals |
| B2B pricing (price lists per region/channel/customer-group) | ✅ | credit lines, PO workflow |
| Custom metadata on aggregates (products/orders/customers) | ✅ (`metadata_json`) | typed attribute schemas |
| PII retention / deletion | ✅ | automated retention policies |

---

## 3. Glossary

| Term | Definition |
|---|---|
| **Store** | One storefront — its catalogs, locations, channels, regions, policies, and branding. A store belongs to a yaya-agentic **tenant**, and a tenant may own **several stores** (`StoreId` is distinct from `TenantId`). Every yaya-sales row is scoped by `store_id`; a commerce profile's binding points at one specific store. |
| **Sales channel** | A selling *surface* a store reaches its customers through — `WEB_CHAT`, `WHATSAPP`, `SMS`, `POS`. Controls product publishing/availability and which regions/tenders apply. The same catalog sells across channels with different rules. |
| **Region / Market** | A selling *context* that bundles `(currency, tax policy [inclusive/exclusive + rate], enabled payment tenders, enabled shipping zones, price list)`. A store has one or more regions; a checkout resolves to exactly one. The unit of commerce configuration (Medusa "Region", Shopify "Market"). |
| **Catalog** | A named, publishable collection of products within a store. |
| **Product / Variant (SKU)** | A product ("Castor-oil soap") has one or more **variants** — the atomic sellable **SKU** ("200g"). Price and stock live on the variant. |
| **Location** | A physical or logical place stock lives (warehouse, shop, "default"). Stock is tracked per (SKU, location). |
| **Stock movement** | An immutable ledger row that changes **on-hand** at a location: `RECEIPT (+)`, `SALE (−)`, `RETURN (+)`, `ADJUSTMENT (±)`, `TRANSFER (±)`, `SHRINKAGE (−)`. On-hand is the sum of movements. |
| **Reservation** | A time-boxed logical hold that reduces **available** but not **on-hand**, taken at checkout so stock can't be double-sold. Commits into a `SALE` movement, or is released/expired. |
| **On-hand / Available** | On-hand = Σ movements at a location. Available = on-hand − open reservations. Shoppers see available. |
| **Deduction point** | Store policy for *when* on-hand actually drops: `ON_PAYMENT` (at capture) or `ON_FULFILLMENT` (at ship). Reservation bridges the gap. |
| **Order** | The immutable record of a committed purchase + its lifecycle (payment, fulfillment, returns) + an append-only event log. |
| **Draft order** | A merchant- or **agent**-built order awaiting the customer's payment ("here's your order, tap to pay"). The conversational-selling and B2B-quote entry point. |
| **Tender** | A *means of payment* on an order — a PSP charge (dLocal/Stripe/PayPal), **cash on delivery**, **manual/bank transfer**, **store credit / gift card**. An order may combine tenders (**split tender**). |
| **Cash on delivery (COD)** | A tender collected by the courier at the doorstep. No PSP auth/capture; the order is `PENDING_PAYMENT` until the delivery agent marks it collected, which flips it `PAID`. Critical for the DR/LatAm/WhatsApp market. |
| **Store credit** | Merchant-issued balance (e.g. from a return) usable as a tender. The default refund method for exchanges. |
| **Payment intent / authorization / capture** | For PSP tenders: authorize reserves funds; capture takes them. Supports partial capture and partial refund. Recorded in a payment ledger. |
| **Fulfillment / Shipment** | Pick/pack/dispatch of some or all order lines; may be partial and split across locations. A **shipment** is one physical dispatch (one carrier, one tracking number). |
| **Delivery method** | *How* an order reaches the customer: `SHIPPING` (carrier parcel), `LOCAL_DELIVERY` (own courier, delivery slot), `PICKUP` (click-and-collect at a location), `DIGITAL` (no physical movement). |
| **Shipping zone** | A geographic region (by country / region / postal-prefix) that maps to the delivery methods and **rates** offered there. |
| **Rate** | The price + promised transit time of a delivery method in a zone. Flat, weight-tiered, price-based, free-over-threshold, or carrier-calculated. Feeds the checkout quote's `shippingTotal`. |
| **Carrier** | A shipping provider (e.g. DHL, a national post, or "in-house courier") with service levels, optional credentials, label generation, and tracking. |
| **Delivery tracking** | The status lifecycle of a shipment (`LABEL_CREATED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED / FAILED / RETURNED_TO_SENDER`) plus its event trail, fed by carrier webhooks/polling and surfaced to the shopper. |
| **Delivery slot / window** | A bookable time window for `LOCAL_DELIVERY` or `PICKUP` (e.g. "Sat 2–4pm"), with a capacity. |
| **Proof of delivery** | Evidence a delivery completed — signature, photo, PIN/OTP, or geostamp. |
| **Delivery agent (courier)** | A person/vehicle in a store's **in-house delivery fleet** who carries `LOCAL_DELIVERY` shipments. Couriers **self-register** (§8.9), are approved by the merchant, receive assignments, and post delivery status + proof of delivery + COD collection from a courier-facing surface. |
| **RMA / Return** | Authorized return of shipped goods, with a **return shipment** (reverse logistics); on receipt, restocks inventory (`RETURN` movement) and/or refunds money. |
| **Purchase order / Goods receipt** | Buying stock from a supplier; receiving it posts `RECEIPT` movements (+ unit cost for valuation). |
| **Valuation / COGS** | Inventory value and cost-of-goods-sold, from unit costs captured at receipt (moving-average in v1). Feeds margin reporting. |
| **Outbox event** | A domain event written in the same transaction as the state change, delivered reliably (webhooks, agent notifications). |
| **Idempotency key** | Caller-supplied key that makes a mutating call safe to retry exactly once. |

---

## 4. High-Level Architecture

```
   ┌──────────────────────────────────────────────────────────────────────┐
   │  Chat client (yayaagenticchat) / WhatsApp / SMS / web                 │
   │  renders product cards, cart, checkout from UI-hint frames            │
   └───────────────┬───────────────────────────────────▲──────────────────┘
                   │ chat turn / tap-action (SSE)        │ UiHint events
                   ▼                                     │
   ┌──────────────────────────────────────────────────────────────────────┐
   │  yaya-agentic  (the agent runtime — knows NOTHING about commerce)     │
   │   Profile ─► capabilities backed by ToolHandlerRef.Http (sales.*)     │
   │   LLM proposes ─► schema✓ ─► Authorizer chain ─► ToolExecutor (HTTP,  │
   │   AuthForwarding SERVICE+PRINCIPAL) ─► generic _uiHint pass-through    │
   └─────────────┬──────────────────────────────────────────────────────── ┘
                 │  HTTPS  (path-only URL + tenant host_base_url, §5.1)
                 ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │  yaya-sales  (Spring Boot modular monolith — own DB / migrations)     │
   │                                                                        │
   │  SELL SIDE                         │  STOCK SIDE                       │
   │  ┌─────────┐ ┌─────────┐ ┌───────┐ │ ┌───────────────────────────────┐│
   │  │ catalog │ │ pricing │ │ cart  │ │ │ inventory                      ││
   │  └─────────┘ └─────────┘ └───┬───┘ │ │  stock_movements (ledger, SoT) ││
   │  ┌──────────────────────────┐│     │ │  reservations · stock_summary  ││
   │  │ orders (lifecycle FSM)   │◄┘     │ │  locations · valuation         ││
   │  │  + checkout saga         │──────►│ └───────────▲──────────────▲────┘│
   │  └────┬───────────┬─────────┘       │             │ RECEIPT      │ SALE/│
   │       │           │                 │  ┌──────────┴─────┐  ┌─────┴────┐│ RETURN
   │  ┌────┴────┐ ┌────┴──────┐ ┌───────┐│  │ purchasing /   │  │ returns  ││
   │  │payments │ │fulfillment│ │returns││  │ receiving      │  │ / RMA    ││
   │  │(ledger) │ │/ shipments│ │/ RMA  ││  └────────────────┘  └──────────┘│
   │  └────┬────┘ └───────────┘ └───────┘│                                   │
   │  ┌────┴──────────────────────────────────────────────────────────────┐│
   │  │ platform: idempotency · optimistic concurrency · outbox/events ·  ││
   │  │ money/FX · service+principal auth · media · reporting/valuation   ││
   │  └────┬──────────────────────────────────────────────────┬──────────┘│
   └───────┼──────────────────────────────────────────────────┼───────────┘
           │ PSP: auth/capture/refund, Connect            outbox│ webhooks/events
           ▼                                                    ▼
   ┌──────────────────┐                              ┌────────────────────────┐
   │ dLocal/Stripe/   │                              │ merchant webhooks ·     │
   │ PayPal           │                              │ yaya-sales admin console │
   └──────────────────┘                              └────────────────────────┘
```

**Reading it:** the **stock side** (inventory ledger + purchasing/returns) and the **sell side** (catalog/pricing/cart/orders/payments/fulfillment) meet at the inventory module, which every sale, return, and receipt posts movements to. The **platform** row is the correctness spine shared by all modules. yaya-agentic holds only tool *descriptors* and dispatches HTTP; it imports no commerce type.

---

## 5. Design spine — the invariants that make it "real"

These apply across every module. They are the difference between a demo and a system that won't lose or oversell stock or double-charge a card.

### 5.1 Inventory is event-sourced; on-hand is a projection

The source of truth is the append-only **`stock_movements`** ledger. On-hand for a (SKU, location) is `Σ qty_delta`. A denormalized **`stock_summary(sku, location, on_hand, reserved, avg_cost, version)`** row is kept *in the same transaction* as every movement, both as the fast read and as the concurrency guard (§5.2). The summary is a cache of the ledger; if they ever disagree, the ledger wins and the summary is rebuilt. Nothing anywhere does `UPDATE stock SET qty = qty - n` without also writing a movement that explains it.

### 5.2 No oversell — availability under optimistic concurrency

A reservation is granted only inside a transaction that (1) reads `stock_summary … FOR UPDATE` (or compare-and-set on `version`), (2) checks `on_hand − reserved ≥ qty` (respecting `BackorderPolicy`), (3) writes the reservation and bumps `reserved` + `version`. Concurrent reservers serialize on the row; the loser retries or fails with `Insufficient(available)`. This is the single hottest correctness path and is covered by concurrency tests (§14).

### 5.3 Reserve → capture → deduct (the sale, precisely)

```
add to cart      →  SOFT-RESERVE qty (short TTL, e.g. 10 min)   available ↓   on_hand —
checkout start   →  REVALIDATE holds: fresh ones stand; expired ones RE-RESERVE (fail if gone)
                 →  then firm-RESERVE qty (checkout TTL)         available ↓   on_hand —
payment captured →  reservation COMMIT → SALE movement (−qty)   available —   on_hand ↓
   (or, if store deduction_point = ON_FULFILLMENT: SALE posts at ship, not capture)
cancel / expire  →  reservation RELEASE                         available ↑   on_hand —
return received  →  RETURN movement (+qty, if resellable)       available ↑   on_hand ↑
```

Reservation begins at **add-to-cart** with a short TTL (a *soft* hold — good UX, the item you put in the cart is really held), and the sweeper releases it if the cart is abandoned. At **checkout** the saga (§8.2) **revalidates** every line: holds still within TTL stand as-is; any that lapsed are **re-reserved against live availability** and the checkout fails the line if stock is gone — so a slow shopper never checks out on stock that quietly expired. The **deduction point** is a store policy: `ON_PAYMENT` (physical stock drops at capture — good for made-to-order / fast digital) or `ON_FULFILLMENT` (drops when picked/shipped — classic retail). The reservation holds availability across the whole gap, so nothing else can sell the held units.

### 5.4 Money is exact, multi-currency, and captured at snapshot

Money is `(amountMinor: long, currency)` — never a float. Every order snapshots the price and, for cross-currency, the FX rate used, so a re-quote or refund years later reconstructs the exact figures. Prices are computed **only** by the pricing engine (§6.2); the agent and client display, never compute.

### 5.5 Every mutation is idempotent

Every state-changing agent/API call carries an **idempotency key** (`Idempotency-Key` header, or `clientRequestId` in the body). The platform stores `(store, key) → response` for a retention window; a repeat returns the stored result without re-executing. This makes checkout, capture, and stock moves safe under network retries, at-least-once webhooks, and double-taps.

### 5.6 Reliable events via transactional outbox

Every meaningful state change (`order.paid`, `stock.low`, `shipment.created`, `refund.succeeded`) writes an **outbox** row in the same DB transaction as the change. A relay publishes outbox rows to merchant webhooks and to yaya-agentic (e.g. "order confirmed" system message) at-least-once. No event is lost if the process dies mid-publish; consumers dedupe on event id.

### 5.7 Optimistic concurrency on aggregates

Orders and stock summaries carry a `version` column; writes compare-and-set. Lost updates surface as a ret/conflict, not silent corruption. Long external calls (PSP capture) never hold a DB lock — they're bracketed by state transitions (`PENDING_CAPTURE` → webhook → `PAID`), not by holding a transaction open.

---

## 6. Module Map

Modular monolith (Spring Modulith `@ApplicationModule` boundaries); one deployable, module calls only through published module APIs. Root package `com.yayatechandinnovations.yayasales`.

| # | Module | Owns | Key collaborators |
|---|---|---|---|
| 1 | **catalog** | Products, variants/SKUs, options, categories/collections, media, publish/versioning, CSV | pricing, inventory, search |
| 2 | **inventory** ★ | Stock ledger, locations, reservations, availability, valuation (avg cost), adjustments, transfers, reorder points | orders, purchasing, returns |
| 3 | **pricing** | Price lists (per region/channel/customer-group), currency/FX, **tax-inclusive/exclusive** tax, promotions, coupons, tiered pricing → priced quote | cart, orders |
| 4 | **cart** | Session carts, lines, cart pricing, merge | pricing, inventory (soft-check), catalog |
| 5 | **orders** | Order aggregate + lifecycle FSM, **draft orders**, **checkout saga**, order events, idempotency | inventory, payments, fulfillment |
| 6 | **payments** | **Tender model** (PSP + COD + manual + store-credit, **split tender**), `PaymentProvider` SPI, auth/capture/refund, payment ledger, merchant onboarding (Connect), webhooks | orders, stores |
| 7 | **fulfillment & delivery** ★ | Delivery methods (ship/local/pickup/digital), **shipping zones + rate calc**, carriers, shipments (partial/split), **delivery-status tracking** + carrier webhooks, delivery slots, proof of delivery, **in-house courier fleet + self-registration** | orders, inventory, pricing (rates) |
| 8 | **returns** | RMA lifecycle, reverse-logistics return shipments, refunds, restock movements | orders, payments, inventory, fulfillment |
| 9 | **purchasing** | Suppliers, purchase orders, goods receipts (→ `RECEIPT` movements + unit cost) | inventory |
| 10 | **customers** | Customer accounts + addresses, keyed by principal subject, order history | orders, cart |
| 11 | **stores** | Per-tenant config: **sales channels**, **regions/markets** (currency+tax+tenders+zones+price list), locations, connected accounts, policies (deduction point, backorder), branding | all (config) |
| 12 | **search** | Product search + facets (Postgres FTS; optional pgvector) | catalog, inventory (availability) |
| 13 | **integration** | Agent-facing tool endpoints, UI-hint framing, service/principal auth, tool manifest | all (orchestrates) |
| 14 | **reporting** | Sales, revenue, **inventory valuation / margin**, low-stock, aged-stock reports | orders, inventory (read models) |
| 15 | **platform** | Typed IDs, idempotency store, outbox/relay, concurrency helpers, money/FX, auth filter, secret cipher, media, observability | — |

Sections 7–10 detail the parts that make this a *real* system; §6.1/§6.2 below detail the cross-cutting configuration aggregates the modules share, and the lighter modules follow the same record-and-SPI style as the yaya-agentic house.

> **Per-module designs.** Each module above has its own implementation-ready design under [`modules/`](modules/README.md) — one directory per module. This doc stays the *system* design (invariants, lifecycles, integration seam, migration); a module doc expands its own row into aggregates, tables, endpoints, SPIs, and invariants without re-deciding what's settled here.

### 6.1 Selling context — Channels & Regions

The lesson every modern commerce platform converged on: the unit of commerce configuration is not "the store," it's the **selling context**. A store sells the same catalog across several **sales channels** (web chat, WhatsApp, SMS, POS) and into one or more **regions/markets**, and those contexts legitimately differ in currency, tax treatment, allowed tenders, and shipping. yaya-sales makes both first-class instead of smearing them across `currency`, `tax_rules`, and `price_lists`.

```java
public record SalesChannel(ChannelId id, StoreId store, ChannelKind kind, String label, boolean active) {}
public enum ChannelKind { WEB_CHAT, WHATSAPP, SMS, POS }

public record Region(                        // the Medusa-"Region" / Shopify-"Market" aggregate
    RegionId id, StoreId store, String name,
    String currency,                         // ISO-4217
    TaxPolicy tax,                            // inclusive|exclusive + rate/table (§6.2)
    List<TenderKind> tenders,                 // which means of payment are offered here (§8.3)
    List<ZoneId> shippingZones,               // eligible delivery (§8.5)
    PriceListId priceList) {}                 // which prices apply (§6.2)
```

- A **checkout resolves to exactly one `(channel, region)`** — from the shopper's channel (the yaya-agentic entry point) and ship-to/locale. That resolution decides the currency shown, whether prices are tax-inclusive, which tenders appear (e.g. **COD only on WhatsApp**, card on web), and which shipping zones/rates apply. This is what makes "offer cash-on-delivery over WhatsApp but not on the web" a *configuration*, not a special case.
- **Product availability is per-channel**: a product can be published to WhatsApp but not POS. Search/catalog filter by the calling channel.
- v1 seeds one channel (`WEB_CHAT`) + one region from the store's currency, so a simple store needs no extra setup; the model is there the moment a second surface or currency appears.

### 6.2 Pricing, tax & the money quote

All money is computed here and nowhere else (§5.4). The quote is **tax-inclusive-aware from day one** — non-negotiable for the DR/LatAm/EU markets, where the displayed price *is* the tax-included price (ITBIS/VAT), not a pre-tax number with tax added on top.

```java
public record TaxPolicy(boolean inclusive, TaxBasis basis) {}   // inclusive: price already contains tax
public interface PricingEngine { PricedQuote quote(CartSnapshot cart, PricingContext ctx); }  // ctx: region, channel, customerGroup, coupon
public record PricedQuote(
    List<PricedLine> lines, Money subtotal, Money discountTotal,
    Money taxTotal, boolean taxInclusive,    // when inclusive, taxTotal is the tax *contained in* the total
    Money shippingTotal, Money grandTotal, List<AppliedPromotion> promotions, String currency) {}
```

- **Inclusive vs exclusive** changes rounding, discount math, and the promised number. An inclusive-tax region computes `taxTotal` as the portion *within* the price; an exclusive region adds it on top. Getting this wrong charges the wrong amount — it's a correctness item, not display sugar.
- **Price lists** are scoped by `(region, channel, customer-group)`, so B2B/wholesale and per-market pricing are the same mechanism, not a bolt-on.
- Promotions/coupons/tiered pricing evaluate deterministically server-side; the agent and client display quotes, never compute them.

---

## 7. Inventory (the core)

The module the whole "powerful sales system" ask hinges on. It answers three questions authoritatively: *how many can I sell? what happened to a unit? what is my stock worth?*

### 7.1 The ledger

```java
public record StockMovement(
    MovementId id, StoreId store, VariantId sku, LocationId location,
    long qtyDelta,                 // +receipt/return, −sale/shrinkage, ± adjustment/transfer
    MovementKind kind,
    Money unitCost,                // captured on RECEIPT; drives valuation. null for non-costing kinds
    RefType refType, String refId, // provenance: ORDER/RMA/PO/ADJUSTMENT/TRANSFER + its id
    String reason,                 // human note for adjustments/shrinkage
    Instant occurredAt, Instant recordedAt) {}

public enum MovementKind { RECEIPT, SALE, RETURN, ADJUSTMENT, TRANSFER_IN, TRANSFER_OUT, SHRINKAGE, DAMAGE }
```

Immutable. Corrections are **new** movements (a reversing `ADJUSTMENT`), never edits. `on_hand(sku,loc) = Σ qtyDelta`. The ledger is the audit trail: every unit's arrival and departure is a row with a reason and a reference.

### 7.2 The summary (fast read + concurrency guard)

```java
public record StockSummary(
    VariantId sku, LocationId location,
    long onHand,                   // = Σ movements (maintained transactionally)
    long reserved,                 // = Σ open reservations
    Money avgCost,                 // moving-average unit cost (v1 valuation)
    int reorderPoint, int reorderQty,
    long version) {                // optimistic concurrency
  public long available() { return onHand - reserved; }
}
```

Updated in the same transaction as every movement and reservation. `available()` is what search/catalog show. When `available()` crosses `reorderPoint`, an outbox `stock.low` event fires (feeds reorder + can nudge the merchant via the agent).

### 7.3 Reservations

```java
public record Reservation(
    ReservationId id, StoreId store, VariantId sku, LocationId location,
    long qty, ReservationState state, ReservationKind kind, CartId cart, OrderId order,
    Instant expiresAt, Instant createdAt) {}
public enum ReservationState { HELD, COMMITTED, RELEASED, EXPIRED }
public enum ReservationKind  { SOFT, FIRM }   // SOFT: add-to-cart, short TTL · FIRM: checkout, checkout TTL

public sealed interface ReserveResult permits ReserveResult.Held, ReserveResult.Insufficient {
    record Held(ReservationId id, Instant expiresAt) implements ReserveResult {}
    record Insufficient(long available) implements ReserveResult {}
}
public interface InventoryLedger {
    ReserveResult reserve(VariantId sku, LocationId loc, long qty, ReservationKind kind, Ref ref, Duration ttl);
    ReserveResult revalidate(ReservationId id, Duration firmTtl);  // checkout: keep if live, else re-reserve or fail
    void commit(ReservationId id);   // → SALE movement (at deduction point), reserved−=qty, onHand−=qty
    void release(ReservationId id);  // reserved−=qty
    MovementId receive(VariantId sku, LocationId loc, long qty, Money unitCost, String poRef);  // RECEIPT
    MovementId restock(VariantId sku, LocationId loc, long qty, String rmaRef);                 // RETURN
    MovementId adjust(VariantId sku, LocationId loc, long delta, String reason);                // ADJUSTMENT
    List<MovementId> transfer(VariantId sku, LocationId from, LocationId to, long qty);          // TRANSFER_OUT+IN
}
```

- `reserve` is the guarded path of §5.2. **`SOFT`** holds are taken at add-to-cart with a short TTL (per-store, default ~10 min); **`FIRM`** holds at checkout with the checkout TTL. A background sweeper releases any `HELD` reservation past `expiresAt` (posting `RELEASED`), so an abandoned cart returns its stock automatically.
- `revalidate` is the checkout-time step (§8.2): a soft hold still within TTL is promoted to `FIRM`; a lapsed one is re-reserved against live availability, or the line fails `Insufficient` if the stock is gone. This is what lets add-to-cart hold stock generously without a slow shopper checking out on stock that quietly expired.
- `commit` timing follows the store's **deduction point** (§5.3): at capture (`ON_PAYMENT`) or at ship (`ON_FULFILLMENT`).
- `receive`/`restock`/`adjust`/`transfer` are the non-sale ways stock moves — closing the loop so on-hand reflects reality, not just sales.

### 7.4 Availability, backorder, locations

- `BackorderPolicy ∈ {DENY, ALLOW, PREORDER}` per SKU (or store default). `DENY` fails a reserve past available; `ALLOW`/`PREORDER` permit negative availability and flag the line.
- Stock is per (SKU, **location**). v1 seeds a single `default` location but the model is multi-location throughout, so enabling a second warehouse later is data, not a migration of every query. Availability shown to a shopper is the sum across sellable locations (or a chosen fulfillment location).
- **Lot / serial / expiry** columns exist on movements (nullable, off in v1) so regulated goods can turn them on without reshaping the ledger.

### 7.5 Valuation

Each `RECEIPT` carries `unitCost`. v1 keeps a **moving-average** cost on `stock_summary.avgCost`, updated on receipt: `avg' = (onHand·avg + qty·cost)/(onHand+qty)`. Each `SALE` movement draws COGS at the current `avgCost`, enabling margin = revenue − COGS in reporting. FIFO cost layers are a vNext option behind the same `Valuation` SPI. This is where "real sales system" earns its name: the merchant sees profit, not just gross sales.

---

## 8. Order, Payment, Fulfillment & Delivery lifecycle

This section covers the whole sell-side lifecycle after the cart: how an order is placed (§8.1–8.2), paid (§8.3), and then **physically delivered and tracked** (§8.4–8.8) — the part a real store lives or dies on.

### 8.1 Order state machine

```
   agent/merchant-built ─► DRAFT_ORDER ─┐   (send "tap to pay" link)
                                         ▼
                         ┌───────────► CANCELLED ◄──────────┐
                         │                                   │
 CART ─► DRAFT ─► PENDING_PAYMENT ─► PAID ─► FULFILLING ─► FULFILLED
                         │              │        │             │
                         │              │        └─► PARTIALLY_FULFILLED
                         └─ AUTH_FAILED │                      │
                                        └─► REFUNDED / PARTIALLY_REFUNDED ◄─ returns
```

`Order` is an aggregate: lines, priced snapshot, a **payment sub-state**, a **fulfillment sub-state**, and an append-only `order_events` log. Transitions are driven by payments (webhooks/tenders) and fulfillment/returns — never by a raw API mutation. Each transition appends an event (actor, from→to, at) and may emit an outbox event.

**Draft orders.** A `DRAFT_ORDER` is an order the **agent or merchant builds** and the customer then pays — the conversational-selling primitive. In chat, the assistant can assemble a bundle, apply an approved one-off discount, and hand back a pay link ("here's your order — tap to pay"); the shopper's `PENDING_PAYMENT` transition and the two-phase confirm still gate the money. It's also the B2B **quote** entry point (net terms/approvals, vNext). A draft reserves stock only when it's sent for payment, not while it's being assembled.

### 8.2 The checkout saga

Checkout is a short **saga** with compensations, because it spans stock and an external PSP and must not half-commit:

```
1. validate ship-to address + resolve region (§8.4/§6.1)   ── comp: none (read)
2. REVALIDATE each line's cart hold → FIRM (inventory §7.3) ── comp: release reservations
   (holds within TTL stand; lapsed ones re-reserve or fail Insufficient)
2a. price + delivery quote (pricing + §8.4)                 ── comp: none (read)
2b. BOOK delivery slot, if local/pickup                     ── comp: release slot
3. create Order(PENDING_PAYMENT) (orders)                   ── comp: cancel order
4. begin chosen tender (payments §8.3)                      ── comp: void authorization
   (PSP → authorize/hosted-url · COD → nothing to collect now · MANUAL → await confirm)
5. return HostedUrl / PaymentIntent / COD-placed to client
   … shopper pays  (or courier collects COD at the door, or merchant confirms manual) …
6. tender settles → Order.PAID            (idempotent; PSP webhook verified · COD via courier §10.4)
7. commit reservations → SALE movements (inventory, at deduction point)
8. clear cart; emit order.paid (outbox)
```

Any step failing runs the compensations for completed steps (release the hold, release the slot, cancel the draft, void the auth). Steps 2–4 are guarded by the checkout idempotency key so a retried `checkout` tool call re-attaches to the same order rather than re-reserving twice. The chosen delivery method + slot + promised window + validated address are snapshotted onto the order. **Address validation** (step 1) normalizes and checks the ship-to against the region/zone via a pluggable `AddressValidator` (v1: format + required-fields + zone-match; external verification services later) — an unservable address fails checkout before any money moves.

### 8.3 Payments — tenders, PSPs, and COD

An order is paid by one or more **tenders**. Modeling payment as a tender list (not a single PSP charge) is what lets the DR/LatAm/WhatsApp market work at all — where **cash on delivery** and **manual bank transfer** are the common cases, not the exception — and enables **split tender** (store credit + card) and store-credit refunds.

```java
public enum TenderKind { PSP, COD, MANUAL, STORE_CREDIT }   // which appear is per-region (§6.1)

public interface TenderProvider {
    TenderKind kind();
    TenderOutcome begin(TenderRequest r);      // PSP: hosted URL/intent · COD: nothing to collect now
    void settle(TenderSettlement s);           // PSP: capture webhook · COD: courier marks collected · MANUAL: merchant confirms
    RefundOutcome refund(RefundRequest r);     // PSP: refund · STORE_CREDIT: reissue · COD/MANUAL: out-of-band + ledger note
}
```

- **PSP tenders** (`DLocal | Stripe | PayPal`) keep the authorize/capture/refund flow — a **payment ledger** (`payment_transactions`) records every txn with PSP ref, so an order's financials are auditable and reconcilable against payouts. Partial capture (ship part now) and partial refund (return one line) are first-class. Merchant onboarding (Connect) unchanged (`MerchantOnboarding` SPI, browser-origin return URLs, commit `b56f487`). Webhooks are signature-verified, idempotent, and the only thing that flips a PSP order `→ PAID`.
- **`COD`** takes no money at checkout: the order sits `PENDING_PAYMENT` and the **delivery agent marks it collected at the doorstep** (§8.9), which settles the tender and flips `→ PAID` (and, on `ON_FULFILLMENT` stores, is when the `SALE` movement commits). COD couples payment to the *delivery* event — the courier surface is the capture path. A per-region COD cap and cash-reconciliation report (§14) guard against abuse and shrinkage.
- **`MANUAL`** (bank/mobile transfer): the shopper pays out-of-band and uploads/quotes a reference; the merchant confirms in the console → settles → `PAID`.
- **`STORE_CREDIT`** is both a tender and the default **refund** target for returns/exchanges — a per-customer balance ledger.

The legacy `PaymentProvider` (authorize/capture/refund) is retained as the PSP-specific SPI *behind* the `PSP` `TenderProvider`:

```java
public interface PaymentProvider {                   // the PSP tender's engine
    String name();                                   // dlocal | stripe | paypal
    AuthorizeOutcome authorize(AuthorizeRequest r);  // hosted URL or intent; reserves funds
    CaptureOutcome capture(CaptureRequest r);        // full or partial
    RefundOutcome refund(RefundRequest r);           // full or partial
}
public sealed interface AuthorizeOutcome
    permits AuthorizeOutcome.HostedUrl, AuthorizeOutcome.PaymentIntent, AuthorizeOutcome.Captured {}
```

### 8.4 Delivery methods & the shipping quote (at checkout)

Before an order exists, the shopper must choose *how it arrives*, and that choice has a price and a promise. yaya-sales resolves eligible **delivery methods** for the destination and quotes each:

```java
public enum DeliveryMethodKind { SHIPPING, LOCAL_DELIVERY, PICKUP, DIGITAL }

public interface DeliveryQuoteEngine {
    // eligible options for a destination + cart (weight/dims/value/location), each with price + promised window
    List<DeliveryOption> quote(DeliveryQuoteRequest req);
}
public record DeliveryOption(
    DeliveryMethodId method, DeliveryMethodKind kind, String label,   // "Standard courier", "Pickup — Downtown"
    Money price, Instant promisedFrom, Instant promisedTo,            // the delivery window shown to the shopper
    CarrierId carrier, String serviceLevel) {}                        // null for pickup/digital
```

Resolution: the destination address → a **shipping zone** (§8.5) → the methods offered there → a **rate** per method (§8.5). Digital lines skip delivery entirely; a mixed cart can split (ship the mug, email the gift card). The chosen option's `price` is added to the pricing quote's `shippingTotal` (§6.2, computed by the server, never the model), and its promised window is snapshotted on the order so "arrives by Fri" is a commitment, not a guess.

### 8.5 Shipping zones, rates & carriers

The configuration that makes §8.4 answerable — all merchant-authored in the admin console.

```java
public record ShippingZone(ZoneId id, StoreId store, String name, List<RegionMatch> regions) {} // country/region/postal-prefix
public record ShippingRate(
    RateId id, ZoneId zone, DeliveryMethodKind kind, CarrierId carrier, String serviceLevel,
    RateRule rule, Money handlingFee, TransitEstimate transit) {}      // promised transit → the window in §8.4

public sealed interface RateRule
    permits RateRule.Flat, RateRule.ByWeight, RateRule.ByPrice, RateRule.FreeOver, RateRule.CarrierCalculated {
    record Flat(Money amount) implements RateRule {}
    record ByWeight(List<Tier> tiers) implements RateRule {}           // 0–1kg, 1–5kg, …
    record ByPrice(List<Tier> tiers) implements RateRule {}            // cart-value tiers
    record FreeOver(Money threshold, RateRule otherwise) implements RateRule {}
    record CarrierCalculated(CarrierId carrier) implements RateRule {} // live carrier API (vNext)
}
```

- **Carriers** live behind a `Carrier` SPI: `rate()`, `createLabel()`, `track()`. v1 ships a **`ManualCarrier`** (merchant enters a tracking number/URL by hand) and an **`InHouseCourier`** (own delivery, no external API) — no paid carrier dependency in the critical path, consistent with the self-hosted constraint. Real carrier integrations (label + live rate + tracking webhooks) slot in later behind the same SPI.
- `FreeOver` is how "free shipping over $50" is expressed; it's a rate rule, not a promo hack, so it composes with promotions cleanly.

### 8.6 Fulfillment — allocation, shipments, partial & split

An order is fulfilled by one or more **shipments**; the order's `fulfillment_state` aggregates them.

```
UNFULFILLED ─► PARTIALLY_FULFILLED ─► FULFILLED
      │                                    │
      └──────────► (per shipment) ─────────┘   each: DRAFT → PICKING → PACKED → DISPATCHED
```

- **Allocation:** each order line is allocated to the **location** that will fulfill it (v1: the single default; multi-location picks by availability). Split fulfillment lets different lines ship from different locations.
- **Stock coupling:** if the store's deduction point is `ON_FULFILLMENT` (§5.3), **dispatching** a shipment commits the reservation → posts the `SALE` movement for those lines (on `ON_PAYMENT` stores the movement already happened at capture; dispatch just draws down the reservation bookkeeping). Either way, on-hand and the ledger stay truthful.
- **Pick/pack:** a shipment moves `DRAFT → PICKING → PACKED → DISPATCHED`; packing captures parcel weight/dims (for the carrier and for accurate future rate quotes). Partial: ship 2 of 3 lines now, the rest later → `PARTIALLY_FULFILLED`.

### 8.7 Delivery tracking (the "where's my order?" spine)

Once dispatched, a shipment has a **delivery status** independent of the order, driven by the carrier and surfaced to the shopper — including through the chat.

```java
public enum DeliveryStatus { LABEL_CREATED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, FAILED, RETURNED_TO_SENDER }
public record TrackingEvent(TrackingEventId id, ShipmentId shipment, DeliveryStatus status,
                            String description, String location, Instant occurredAt) {}
```

- **Ingestion:** carrier tracking updates arrive via a signed **carrier webhook** (`/v1/sales/webhooks/carrier/{carrier}`) or a polling fallback for carriers without webhooks; each update appends an immutable `TrackingEvent` and advances `DeliveryStatus`. Idempotent on the carrier's event id.
- **Local delivery / pickup** don't have a carrier — the merchant (or a courier app) advances status manually (`OUT_FOR_DELIVERY`, `DELIVERED`) and captures **proof of delivery** (signature/photo/OTP). Pickup adds a `READY_FOR_PICKUP → PICKED_UP` path with an OTP the shopper shows at the counter.
- **Delivery slots:** `LOCAL_DELIVERY`/`PICKUP` book a capacity-limited **slot** at checkout; the slot is the promised window (§8.4).
- **Shopper-facing:** every status change emits an outbox event (`shipment.out_for_delivery`, `shipment.delivered`, `delivery.failed`). yaya-agentic turns those into **system messages** into the shopper's chat ("📦 Out for delivery, arriving 2–4pm — track here"), and the shopper can ask "where's my order?" any time → the `sales.track_delivery` tool (§10.2, §11.3). This is the payoff of a conversational storefront: post-purchase support is *in the same chat*.

### 8.8 Returns & reverse logistics

- Authorize an RMA against fulfilled lines → issue a **return shipment** (a return label from the carrier, or drop-off/pickup instructions for in-house) → on receipt, post `RETURN` movements (restock if resellable, else `SHRINKAGE`/`DAMAGE`) and a partial `refund` through the payment ledger → order moves to `PARTIALLY_REFUNDED`/`REFUNDED`. This closes the loop so a return actually puts stock back *and* money out, correctly and auditably.

### 8.9 In-house courier fleet & self-registration

`LOCAL_DELIVERY` (the dominant delivery mode for WhatsApp/LatAm commerce) is carried by a store's own **delivery agents** — and they **self-register** rather than being hand-entered by the merchant. This mirrors the merchant PSP onboarding pattern (`merchant-psp-onboarding-design.md`): a self-serve flow, invite/approval-gated so it can't be abused.

A courier handles money (COD) and goods, so v1 registration is **KYC-grade**: it collects the courier's real identity and vehicle papers, and the merchant reviews those documents before approval.

```java
public record DeliveryAgent(
    AgentId id, StoreId store,
    String legalName, Address legalAddress, String phone,       // identity
    IdDocument idDoc,                                           // gov photo ID: type + number + scan(s)
    Vehicle vehicle,                                            // kind, make/model, plate, + docs (registration, insurance)
    List<AgentDocument> documents,                              // uploaded scans (ID front/back, license, insurance, …)
    AgentStatus status,                                        // registration lifecycle ↓
    List<LocationId> servesFrom, GeoArea coverage,
    Instant registeredAt, Instant approvedAt, String reviewedBy) {}

public enum AgentStatus { PENDING, DOCS_SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, SUSPENDED }
public record AgentDocument(DocKind kind, String blobRef, DocStatus status) {}   // stored encrypted (§13, PII)
public enum DocKind { PHOTO_ID_FRONT, PHOTO_ID_BACK, DRIVER_LICENSE, VEHICLE_REGISTRATION, INSURANCE, OTHER }
```

**Self-registration flow (the endpoint you asked for):**

```
1. Merchant creates an invite (a signed, expiring registration link/QR) for the store's fleet.
2. Courier opens it (a screen in yayaagenticchat, §10.4/§11.5) and self-registers:
   POST /v1/sales/delivery/agents/register
   { inviteToken, legalName, legalAddress, phone, vehicle }        → agent row PENDING, OTP to phone
3. Courier verifies phone (OTP) and uploads documents (photo ID, license, vehicle registration,
   insurance) → POST …/agents/{id}/documents                       → DOCS_SUBMITTED → UNDER_REVIEW
4. Merchant reviews the identity + vehicle docs in the console and approves → APPROVED (credentials
   minted) or REJECTED (with reason). Only APPROVED agents can be assigned deliveries.
5. Courier works the courier surface (§10.4): sees assigned deliveries, marks
   OUT_FOR_DELIVERY / DELIVERED with proof-of-delivery, and collects COD.
```

- **Trust:** registration is **gated by a signed store invite token** — not open enrollment — so only couriers the merchant invited can join. Phone is OTP-verified (reuses the chat account OTP machinery); identity and vehicle documents are **merchant-reviewed before approval**. A `PENDING`/`DOCS_SUBMITTED`/`UNDER_REVIEW` agent can do nothing operational. A courier is scoped to exactly one store. Documents are PII — stored encrypted with a retention/deletion path (§15) and never returned to shoppers or other couriers.
- **Assignment:** a dispatched `LOCAL_DELIVERY` shipment is assigned to an agent (auto by coverage/availability, or manually in the console) → `delivery_assignments` row + a push to the courier surface. Reassignment and unassignment are events.
- **Courier surface = the delivery status + COD capture path.** The courier posts `OUT_FOR_DELIVERY` and `DELIVERED` (with proof of delivery) — these are the §8.7 manual status advances for the no-carrier case — and, for a COD order, marks **cash collected**, which settles the COD tender (§8.3) and flips the order `→ PAID`. So the fleet is both the delivery mechanism *and* the COD money path.
- **Auth posture:** delivery agents are a **fourth caller class** alongside operator / service / shopper (§10.4, §15) — a scoped agent token, never operator or service credentials. The register endpoint is the only public one; everything else requires an approved-agent token.

---

## 9. Data Model

yaya-sales owns its DB and migration history (`V1__…`). Highlights (JSON where the shape is nested and read-together; columns where we filter/aggregate):

```sql
-- stores & selling context
stores(id, tenant_id, display_name, default_currency,
       policies_json /*deduction_point,backorder,soft_ttl,checkout_ttl*/,
       branding_json, created_at)                                       -- a tenant may own many stores (id ≠ tenant)
locations(id, store_id, label, kind /*WAREHOUSE|SHOP|VIRTUAL*/, address_json, sellable, sort_order)
sales_channels(id, store_id, kind /*WEB_CHAT|WHATSAPP|SMS|POS*/, label, active)
regions(id, store_id, name, currency, tax_json /*inclusive?,basis,rate/table*/, tenders_json /*allowed TenderKinds*/,
        zone_ids_json, price_list_id)                                   -- the (currency,tax,tenders,zones,prices) bundle
channel_product_availability(channel_id, product_id, published)         -- per-channel publishing

-- catalog
catalogs(id, store_id, version, display_name, status, created_at, deprecated_at,
         UNIQUE(store_id, id, version))
products(id, store_id, catalog_id, title, description, category, attributes_json, media_json,
         status, sort_order, search_tsv, metadata_json, created_at, updated_at)   -- metadata_json: merchant custom fields (convention on products/orders/customers)
variants(id, product_id, sku, label, options_json, weight_grams, barcode,
         UNIQUE(product_id, sku))

-- INVENTORY (the core)
stock_movements(id, store_id, sku_id, location_id, qty_delta, kind, unit_cost_minor, currency,
                ref_type, ref_id, reason, lot, serial, expires_on, occurred_at, recorded_at)      -- append-only
stock_summary(sku_id, location_id, store_id, on_hand, reserved, avg_cost_minor, currency,
              reorder_point, reorder_qty, version, updated_at, PRIMARY KEY(sku_id, location_id))    -- projection
reservations(id, store_id, sku_id, location_id, qty, state, order_id, expires_at, created_at)

-- pricing (scoped by region × channel × customer-group)
price_lists(id, store_id, region_id, channel_id, customer_group, rules_json, active)
promotions(id, store_id, kind, value_json, eligibility_json, code, starts_at, ends_at, active)
fx_rates(base, quote, rate, as_of)                                    -- snapshotted onto orders
                                                                      -- tax lives on regions.tax_json (inclusive-aware)

-- cart & orders
carts(id, store_id, channel_id, region_id, customer_ref, currency, lines_json, updated_at)
orders(id, store_id, channel_id, region_id, customer_ref, currency, source /*CHECKOUT|DRAFT*/,
       lines_json, pricing_json /*incl tax_inclusive*/, fx_json,
       status, payment_state, fulfillment_state, version, created_at)
order_events(id, order_id, type, actor, from_state, to_state, data_json, at)     -- append-only

-- tenders & money (an order is paid by one or more tenders — PSP | COD | MANUAL | STORE_CREDIT)
order_tenders(id, order_id, kind, amount_minor, currency, status /*PENDING|SETTLED|REFUNDED*/,
              detail_json /*psp_ref | cod agent+collected_at | manual ref | credit txn*/, created_at)
payment_transactions(id, order_id, tender_id, provider, kind /*AUTH|CAPTURE|REFUND|VOID*/, amount_minor,
                     currency, psp_ref, status, created_at)                       -- PSP payment ledger
store_credit_ledger(id, store_id, customer_ref, delta_minor, currency, reason, ref_id, created_at) -- balance = Σ delta

-- shipping config (merchant-authored)
shipping_zones(id, store_id, name, regions_json /*country/region/postal-prefix matchers*/)
delivery_methods(id, store_id, kind /*SHIPPING|LOCAL_DELIVERY|PICKUP|DIGITAL*/, label, active)
shipping_rates(id, store_id, zone_id, method_id, carrier_id, service_level,
               rule_json /*Flat|ByWeight|ByPrice|FreeOver|CarrierCalculated*/, handling_fee_minor,
               transit_json /*promised min/max days*/)
carriers(id, store_id, kind /*MANUAL|IN_HOUSE|<integration>*/, name, credentials_ref, config_json)
delivery_slots(id, store_id, location_id, method_id, starts_at, ends_at, capacity, booked)

-- fulfillment & delivery
shipments(id, order_id, location_id, method_kind, carrier_id, service_level, lines_json,
          parcel_json /*weight,dims*/, status /*DRAFT..DISPATCHED*/, delivery_status,
          tracking_number, tracking_url, label_ref, slot_id, promised_from, promised_to,
          proof_json /*signature|photo|otp|geo*/, dispatched_at, delivered_at, version)
tracking_events(id, shipment_id, status, description, location, occurred_at, recorded_at)   -- append-only

-- in-house delivery fleet (self-registered, KYC-reviewed couriers)
delivery_agent_invites(token_hash, store_id, created_by, expires_at, used_at)              -- signed invite gate
delivery_agents(id, store_id, legal_name, legal_address_json, phone, phone_verified,
                id_doc_json /*type,number*/, vehicle_json /*kind,make,model,plate*/,
                status /*PENDING|DOCS_SUBMITTED|UNDER_REVIEW|APPROVED|REJECTED|SUSPENDED*/,
                serves_from_json, coverage_json, credential_ref,
                registered_at, approved_at, reviewed_by, reject_reason)
delivery_agent_documents(id, agent_id, kind /*PHOTO_ID_*|DRIVER_LICENSE|VEHICLE_REGISTRATION|INSURANCE*/,
                         blob_ref /*encrypted media*/, status, uploaded_at)                 -- PII, retention/deletion (§15)
delivery_assignments(id, shipment_id, agent_id, status /*ASSIGNED|ACCEPTED|COMPLETED|REASSIGNED*/, assigned_at)

-- returns
rmas(id, order_id, lines_json, status, reason, return_shipment_id, refund_txn_id, received_at, created_at)

-- purchasing
suppliers(id, store_id, name, contact_json)
purchase_orders(id, store_id, supplier_id, location_id, lines_json, status, expected_at, created_at)
goods_receipts(id, purchase_order_id, lines_json /*sku,qty,unit_cost*/, received_at)   -- → RECEIPT movements

-- payments config, customers, platform
merchant_psp_accounts(store_id, provider, account_id, status, charges_enabled, tracking_ref, detail_json,
                      UNIQUE(store_id, provider))
psp_credentials(store_id, provider, ciphertext, key_id)               -- AES-256-GCM envelope
customers(id, store_id, principal_subject, display_name, created_at, UNIQUE(store_id, principal_subject))
addresses(id, customer_id, kind, data_json)
idempotency_keys(store_id, key, request_hash, response_json, created_at, PRIMARY KEY(store_id, key))
outbox(id, store_id, aggregate, event_type, payload_json, status, created_at, published_at)
```

**Notably absent from yaya-agentic after migration:** `products`, `product_catalogs`, `commerce_carts`, `commerce_orders`, `merchant_psp_accounts`; `profiles.commerce_json` shrinks to a store pointer (§12.3).

---

## 10. API Surface

**Four** caller classes, each with its own auth posture: operator (§10.1), platform + shopper (§10.2), public webhooks (§10.3), and **delivery agent** (§10.4). Mutating endpoints accept `Idempotency-Key`.

### 10.1 Admin API — `/v1/sales/admin/*` (operator-authenticated)

Backs the yaya-sales console. Supersets the current commerce admin controllers.

```
# catalog
POST/GET/PUT   /v1/sales/admin/catalogs[/{id}]                       catalogs (versioned)
POST/GET/PUT   …/catalogs/{id}/products[/{pid}]                      products + variants
POST           …/products/{pid}/archive|restore · …/products/bulk   CSV bulk upsert
GET            …/products/export.csv · POST /v1/sales/admin/media    export · image upload

# inventory (the powerful part)
GET   /v1/sales/admin/inventory?location=&low=true                  on-hand/available/reserved per SKU
POST  /v1/sales/admin/inventory/{sku}/adjust   { location, delta, reason }   → ADJUSTMENT movement
POST  /v1/sales/admin/inventory/transfer       { sku, from, to, qty }        → TRANSFER movements
GET   /v1/sales/admin/inventory/{sku}/ledger                        full movement history for a SKU
PUT   /v1/sales/admin/inventory/{sku}/reorder  { point, qty }       reorder policy
GET   /v1/sales/admin/locations · POST …/locations                  locations

# purchasing (stock in)
POST/GET  /v1/sales/admin/purchase-orders[/{id}]                    create/list POs
POST      /v1/sales/admin/purchase-orders/{id}/receive              goods receipt → RECEIPT movements

# selling context (channels, regions, tenders)
GET/POST/PUT  /v1/sales/admin/channels[/{id}]                     sales channels (web-chat/whatsapp/sms/pos)
GET/POST/PUT  /v1/sales/admin/regions[/{id}]                      regions (currency, tax-inclusive?, tenders, zones, price list)
GET/POST/PUT  /v1/sales/admin/price-lists[/{id}]                  prices per region×channel×customer-group

# shipping config
GET/POST/PUT  /v1/sales/admin/shipping/zones[/{id}]                 zones (country/region/postal)
GET/POST/PUT  /v1/sales/admin/shipping/methods[/{id}]              delivery methods (ship/local/pickup/digital)
GET/POST/PUT  /v1/sales/admin/shipping/rates[/{id}]               rates per zone×method (flat/weight/price/free-over)
GET/POST      /v1/sales/admin/shipping/carriers                    carriers (manual/in-house/integration)
GET/POST      /v1/sales/admin/shipping/slots?method=&location=     delivery/pickup slots + capacity

# delivery fleet (couriers self-register at §10.4; merchant invites, reviews docs, approves here)
POST  /v1/sales/admin/delivery/agents/invites                      mint a signed invite link/QR
GET   /v1/sales/admin/delivery/agents?status=                      list couriers (UNDER_REVIEW need a decision)
GET   /v1/sales/admin/delivery/agents/{id}                         identity + vehicle + documents for review
POST  /v1/sales/admin/delivery/agents/{id}/{approve|reject|suspend} { reason? }  KYC decision
POST  /v1/sales/admin/shipments/{id}/assign  { agentId? }          assign a local-delivery shipment (auto if omitted)

# orders / drafts / fulfillment / delivery / returns
GET   /v1/sales/admin/orders[/{id}]                                 list/detail + events + tenders + payment ledger
POST  /v1/sales/admin/draft-orders · PUT …/{id} · POST …/{id}/send  build a draft order → send "tap to pay"
POST  /v1/sales/admin/orders/{id}/tenders  { kind, amount }         add a tender (e.g. MANUAL) · POST …/tenders/{tid}/settle
POST  /v1/sales/admin/orders/{id}/shipments   { lines, location }   create shipment (partial/split ok)
POST  /v1/sales/admin/shipments/{id}/{pick|pack|dispatch}           advance fulfillment; dispatch → label/tracking
PUT   /v1/sales/admin/shipments/{id}/delivery-status  { status, proof }  manual advance (local/pickup)
POST  /v1/sales/admin/orders/{id}/refund      { lines|amount, to:psp|store_credit }   partial/full refund
POST  /v1/sales/admin/orders/{id}/cancel
POST  /v1/sales/admin/rmas · POST …/rmas/{id}/{label|receive}       RMA: authorize · return label · receive (restock)

# payments onboarding & reporting
GET/POST/DELETE  /v1/sales/admin/psp/{provider}[/onboarding-link|/return]     Connect
GET   /v1/sales/admin/reports/{sales|inventory-valuation|low-stock|margin|cod-reconciliation}
```

### 10.2 Agent-facing API — `/v1/sales/agent/*` (service + principal token)

The narrow surface yaya-agentic's HTTP tools call. Every response may carry a `_uiHint` frame (§11.2). `SERVICE_TOKEN` required; carts/orders honor `PRINCIPAL_TOKEN`.

```
POST  /v1/sales/agent/products/search   { query, filters, page }   → results + _uiHint:product_catalog
GET   /v1/sales/agent/products/{id}                                → product (with live availability) + _uiHint:product_card
POST  /v1/sales/agent/cart/{add|remove|set-qty|view|clear}         → cart   + _uiHint:cart
POST  /v1/sales/agent/delivery/options  { cartHandle, address }    → eligible methods+prices+windows + _uiHint:delivery_options
GET   /v1/sales/agent/tenders            { cartHandle }             → tenders allowed for this channel/region (card|cod|…)
POST  /v1/sales/agent/checkout   { cartHandle, deliveryMethod, slot?, tender, returnUrl }  → outcome + _uiHint:checkout (confirmable)
POST  /v1/sales/agent/draft-orders · POST …/{id}/send              agent builds a draft order → "tap to pay" link
GET   /v1/sales/agent/orders/{id}                                  → order status (post-purchase Q&A)
GET   /v1/sales/agent/orders/{id}/track                            → delivery status + timeline + _uiHint:delivery_tracking
```

Availability shown here is the live `available()` (§7.2), so the agent never offers stock that isn't there. `tenders` reflects the resolved region (§6.1) — so **COD appears on WhatsApp, card on web**. `delivery/options` runs the §8.4 quote; `checkout` runs the saga (§8.2) behind the two-phase `confirmable` guard (for `tender:cod` there's no PSP redirect — the order is placed `PENDING_PAYMENT` and collected on delivery); `draft-orders` lets the assistant assemble an order and hand back a pay link (§8.1); `track` powers "where's my order?" (§8.7).

### 10.3 Webhooks — `/v1/sales/webhooks/*` (signature-verified, idempotent, public)

```
POST  /v1/sales/webhooks/{stripe|paypal|dlocal}    payment capture/refund · Connect onboarding
POST  /v1/sales/webhooks/carrier/{carrier}         delivery tracking updates → TrackingEvent (idempotent on carrier event id)
```

### 10.4 Courier surface — `/v1/sales/delivery/*` (delivery-agent token; register is public)

The in-house fleet's surface (§8.9). The **register endpoint is the only public one** and is gated by a signed store invite token; everything else needs an approved-agent token.

```
POST  /v1/sales/delivery/agents/register   { inviteToken, legalName, legalAddress, phone, vehicle }  self-register → PENDING + OTP
POST  /v1/sales/delivery/agents/verify      { phone, otp }                                 verify phone
POST  /v1/sales/delivery/agents/{id}/documents  (multipart: photo ID, license, registration, insurance)  → UNDER_REVIEW
GET   /v1/sales/delivery/me                                                                my status (pending/approved/rejected)
GET   /v1/sales/delivery/assignments                                                       my assigned deliveries (APPROVED only)
POST  /v1/sales/delivery/assignments/{id}/accept
PUT   /v1/sales/delivery/assignments/{id}/status  { status, proof }   OUT_FOR_DELIVERY | DELIVERED (+ proof of delivery)
POST  /v1/sales/delivery/assignments/{id}/collect-cod  { amount }     COD collected → settles the COD tender → order PAID
```

The courier signs in through a **dedicated screen in yayaagenticchat** (§11.5) — the same app the shopper uses, in a courier mode gated by the agent token — so the fleet needs no separate app install.

## 11. Integration with yaya-agentic

Unchanged in spirit from the extraction plan — this is why yaya-sales is an *app*, not a rewrite of the engine.

### 11.1 Tools are HTTP descriptors, not beans

Each shopper operation is a `ToolDescriptor` with `ToolHandlerRef.Http(HttpToolSpec)`. `urlTemplate` is **path-only** (`/v1/sales/agent/cart/add`); the host resolves from the tenant's `host_base_url` (tenant-registry §6.1) → the tenant's yaya-sales deployment. Commerce becomes the canonical example of the framework's "SaaS extension" transport — no bean, no deploy of the engine to add a store.

```jsonc
{ "id": "sales.checkout",
  "inputSchema": { "type":"object", "properties": { "returnUrl": {"type":"string"} } },
  "handler": { "http": {
    "method":"POST", "urlTemplate":"/v1/sales/agent/checkout",
    "headerTemplates": { "Idempotency-Key": "{ctx.turnId}" },
    "body": { "contentType":"application/json",
              "template":"{\"cartHandle\":\"{ctx.cartHandle}\",\"returnUrl\":\"{returnUrl}\"}" },
    "response": { "jsonPath":"$" },
    "authForwarding": "SERVICE_TOKEN" }},               // + PRINCIPAL_TOKEN when authenticated
  "policy": { "confirmable": true } }                    // two-phase money-mover guard (§5.3 engine)
```

### 11.2 One generic engine change: `_uiHint` pass-through

Rich rendering must stay engine-emitted, never LLM prose (#8). yaya-sales returns the UI-hint frame in its tool response; yaya-agentic surfaces it generically:

> **Convention.** If an HTTP tool response contains top-level `"_uiHint": {"kind":…, "payload":…}`, the dispatcher emits `TurnEvent.UiHint(kind, payload)` and strips it from the value shown to the LLM. The hint originates from a **trusted tool response**, so #8 holds — server-emitted, by the commerce service instead of an in-process bean.

~30 lines, no new SPI, domain-neutral (any HTTP tool can render chrome this way). yaya-sales owns the `kind`s (`product_catalog`, `product_card`, `cart`, `delivery_options`, `tender_options`, `checkout`, `confirm`, `delivery_tracking`) and their payload schemas — so a rendered delivery timeline or a "pick a payment method" chip row is a UI hint, not prose the model has to retype.

### 11.3 Auth forwarding, cart handle, notifications

- **`SERVICE_TOKEN`** always (proves the call is the platform); **`PRINCIPAL_TOKEN`** when the shopper is authenticated (attributes cart/order to a verified `subject`). yaya-sales verifies both against the same issuer trust yaya-agentic uses. (#6)
- **`cartHandle`** (opaque `CartId`) lives in the session's working memory, injected via a `{ctx.cartHandle}` binding; the engine never inspects it, cart state lives in yaya-sales.
- **Outbound events** (`order.paid`, `shipment.dispatched`, `shipment.out_for_delivery`, `shipment.delivered`, `delivery.failed`) flow via the outbox to yaya-agentic, which delivers them as **system messages** into the shopper's chat (`system-messaging-design.md`) — e.g. "📦 Out for delivery, arriving 2–4pm." Combined with the `sales.track_delivery` tool, post-purchase delivery support lives in the same conversation the sale happened in — closing the loop after the shopper leaves.
- **The delivery-agent surface (§10.4) is NOT a yaya-agentic caller.** Couriers hold a yaya-sales-issued agent token and talk to yaya-sales directly; they are a fourth caller class (§15), outside the agent's service/principal trust. Only the shopper-facing `sales.*` tools go through yaya-agentic.

### 11.4 The commerce profile, after extraction

A commerce profile is: a system prompt + capabilities backed by `sales.*` HTTP tools + a thin `commerce` binding pointing at the store (§12.3). No catalogs, PSPs, currency, or stock logic in the agent.

### 11.5 Courier surface — a screen in yayaagenticchat

The in-house fleet works from a **courier mode inside `yayaagenticchat`** (the existing end-user app), not a separate install — so onboarding is "open a link, register, upload docs" on the phone the courier already has. The screen calls the courier API (§10.4) directly with the yaya-sales-issued **agent token** (obtained via invite → OTP), *not* through yaya-agentic — couriers are a fourth caller class (§15), outside the shopper/agent trust path. It surfaces: registration + document upload, an assignment list, per-delivery status advance with proof-of-delivery capture (camera/signature/OTP), and the COD-collected action. This is the only yaya-sales UI that lives in `yayaagenticchat` rather than the yaya-sales admin console.

---

## 12. Migration — from in-core commerce to yaya-sales

The current `commerce/*` subsystem (catalog, cart, checkout, PSP, onboarding, `V54–V72`) is correct but fused into the engine. Extraction is milestoned so both apps stay working at each step.

### 12.1 What moves / stays

| Leaving yaya-agentic | Lands in yaya-sales |
|---|---|
| `commerce/` core, `commerce/cart`, `commerce/payment`, `commerce/payment/onboarding`, `commerce/tool` (→ REST + `_uiHint`) | catalog / pricing / cart / orders / payments / inventory / integration |
| commerce persistence entities/repos; `V54–V72` tables | yaya-sales `V1…` (products/variants split out; **new** ledger/reservation/summary/outbox/idempotency tables) |
| commerce admin + webhook controllers | admin / webhooks |
| Flutter admin commerce screens (`yayaagenticweb`) | yaya-sales Flutter admin |

**Stays in yaya-agentic:** the engine, `ToolExecutor`, HTTP dispatch, `Authorizer`, working memory, the `/v1/chat/sessions/{id}/actions` tap channel (generic), and the **chat storefront renderer** (`yayaagenticchat/…/commerce/*` — renders UI-hint frames whose *source* moves but whose wire format doesn't).

### 12.2 Data migration

The current `products`/`product_catalogs`/carts/orders migrate one-shot per tenant. **New in yaya-sales:** the ledger has no source rows — seed an opening-balance `ADJUSTMENT` movement per SKU from today's on-hand so history starts clean and on-hand matches reality on day one.

### 12.3 `Profile.commerce` shrinks to a pointer

```java
public record CommerceBinding(StoreRef store, Ids.ToolId checkoutTool, boolean cartEnabled) {}
// StoreRef = (hostBaseUrlRef, storeId); catalogs/currency/PSP/policies now live on the yaya-sales Store.
```

Additive introduce → dual-read S2–S4 → drop old `CommerceConfig` fields at S4 (open decision, §14 Q1).

### 12.4 Milestones

| # | Milestone | Scope | Exit |
|---|---|---|---|
| **S0** | Scaffold | `apps/yaya-sales` app + `pom.xml` + **platform** (IDs, idempotency, outbox, auth filter, DB in its **own `yaya_sales` schema**). | boots on its own schema (shared Postgres instance ok in v1) with **zero cross-schema FKs/joins** to yaya-agentic — so it lifts to a separate instance without query changes; `./mvnw -pl apps/yaya-sales test` green |
| **S1** | Catalog + inventory ledger + admin | products/variants/locations + **stock_movements/summary/reservations** + adjust/receive + admin authoring/inventory screens | create products, receive stock, see on-hand from the ledger |
| **S2** | Selling context + cart + pricing + agent API + `_uiHint` | **channels/regions (tax-inclusive-aware pricing)**; move cart/pricing; `/v1/sales/agent/*`; engine `_uiHint` pass-through; wire a profile's `sales.*` HTTP tools | chat browses live-availability catalog + edits cart, rendered as UI hints, in the region's currency with correct tax display; **no `commerce/*` in the cart path** |
| **S3** | Orders + checkout saga + tenders (PSP **+ COD** + manual + store-credit) | order FSM, checkout saga with reserve→settle→**commit (SALE movement)**, tender model, payment ledger, onboarding, webhooks | end-to-end checkout (sandbox) settles to a connected account **and a COD order places without a PSP**; **on-hand decrements via a movement**; no oversell under concurrent checkout |
| **S4** | Decommission | delete `commerce/*`; `CommerceConfig`→`CommerceBinding`; drop/migrate `V54–V72`; opening-balance seed | yaya-agentic builds with zero commerce types; existing stores keep working |
| **S5** | Shipping, delivery & the courier fleet | zones + rates + carriers (manual/in-house), delivery methods, `delivery/options`, shipments (pick/pack/dispatch, partial/split), delivery-status tracking + carrier webhook, slots/pickup, **courier self-registration + KYC docs + merchant approval + assignment + COD-collect (§8.9/§10.4)**, **courier screen in `yayaagenticchat` (§11.5)**, `sales.track_delivery` + delivery system-messages | shopper picks a delivery method in chat, gets a promised window, and tracks "where's my order?"; a courier **self-registers + uploads ID/vehicle docs**, the merchant **approves**, and the approved courier delivers a local order and collects COD, flipping it PAID |
| **S6** | Draft orders + returns + purchasing + reporting | agent-built draft orders, RMA + return shipment + restock + store-credit, PO/goods-receipt, valuation/margin/low-stock/aged-stock/**cod-reconciliation** reports | assistant builds a "tap to pay" draft; merchant accepts a return that restocks + issues store credit, receives a PO, and reads a margin report |

Each milestone is independently shippable and leaves both apps working. Market-critical pieces (channels/regions, COD, tax-inclusive) land in S2–S3; "run a business" pieces (valuation, purchasing, reporting) sit in S6 so the first sale doesn't wait on them.

---

## 13. Events, Outbox & Webhooks

Domain events are the nervous system. Written to `outbox` in the same tx as the state change, relayed at-least-once, deduped by consumers on event id.

```
order.created · order.paid · order.cancelled · draft_order.sent · tender.settled · payment.refunded
shipment.dispatched · shipment.out_for_delivery · shipment.delivered · delivery.failed
delivery_agent.registered · delivery.assigned · cod.collected
rma.received · stock.low · stock.received
```

Consumers: (1) **merchant webhooks** (configurable per store); (2) **yaya-agentic** — `order.paid`/`shipment.*`/`delivery.*` → a system message into the shopper's chat ("out for delivery, 2–4pm"); `stock.low` / `delivery_agent.registered` (a courier needs approval) → nudge the merchant's ops profile. This is what lets a *conversation* keep serving a shopper after checkout — delivery updates and "where's my order?" happen in the same chat — and lets a merchant run the store from chat. Carrier tracking updates flow *in* via `/v1/sales/webhooks/carrier/{carrier}` (§10.3), courier updates via the courier surface (§10.4), and both become `shipment.*`/`delivery.*`/`cod.*` events.

---

## 14. Correctness — concurrency, idempotency, money

The tests that must be green for this to be called "real":

- **No oversell:** N concurrent `reserve` calls for the last unit → exactly one `Held`, the rest `Insufficient`. Property test on the §5.2 guarded path.
- **Ledger ≡ summary:** for any SKU/location, `Σ movements.qtyDelta == stock_summary.on_hand`. Invariant test after randomized movement sequences.
- **Idempotent checkout/capture:** replaying a `checkout` or a PSP webhook with the same key/event-id produces one order, one reservation set, one capture — never doubles stock or charges.
- **Saga compensation:** a PSP auth failure after reservation releases the hold and cancels the draft — no orphan reservation, no phantom order.
- **Money exactness:** no float anywhere on the money path; refund of a cross-currency order reconstructs the captured amount from the order's FX snapshot.
- **Reservation expiry:** an abandoned checkout's hold is swept and `available()` recovers.
- **No slot oversell:** concurrent checkouts on the last delivery slot → exactly one booking (same guarded pattern as stock reservations, §5.2).
- **Idempotent carrier tracking:** replaying a carrier webhook with the same event id appends one `TrackingEvent` and advances `DeliveryStatus` once.
- **Split tender balances:** an order's settled tenders sum to exactly its `grandTotal`; no over- or under-collection; a store-credit tender never drives the customer balance negative.
- **COD single-settle:** a courier's `collect-cod` is idempotent — a retried collect settles the COD tender and flips the order `PAID` exactly once (no double SALE movement).
- **Tax-inclusive rounding:** for an inclusive-tax region, `subtotal` and `taxTotal` reconstruct `grandTotal` to the minor unit with no rounding drift across line quantities.
- **Courier trust & KYC gate:** an agent that is not `APPROVED` (still `PENDING`/`DOCS_SUBMITTED`/`UNDER_REVIEW`/`REJECTED`), or belongs to another store, cannot be assigned, read assignments, or collect COD; a `PENDING` agent is only created from a signed, unexpired invite; approval requires the required documents present.
- **Add-to-cart soft-hold + revalidation:** a `SOFT` hold expires and is swept if the cart is abandoned; at checkout a still-live hold firms up while a lapsed one is re-reserved against live stock (or fails), so no checkout commits on expired stock.
- **Address gate:** an unservable/malformed ship-to fails at checkout step 1 before any reservation firms or tender begins.

---

## 15. Safety, Guardrails & Trust Boundaries

The platform non-negotiables (design §12) get a cleaner home, not a weaker one:

- **Money & inventory authoritative in yaya-sales, computed nowhere else.** LLM never sees/sets a price or a stock number; the agent never totals; the client displays server-computed quotes and live availability. (relocated §12 rule)
- **LLM still never trusted (#1/#2).** Tool calls pass schema → Authorizer → HTTP dispatch *before* yaya-sales; out-of-profile `sales.*` tools are never offered.
- **Tokens never silently forwarded (#6).** `SERVICE`/`PRINCIPAL` explicit per spec; yaya-sales independently verifies.
- **Tool results are untrusted data to the model (#5).** Product text (merchant-authored, attacker-reachable) is wrapped as data, never instructions.
- **UI hints stay server-emitted (#8)** via the trusted `_uiHint` frame (§11.2).
- **New boundary — yaya-sales trusts only the platform.** Agent API rejects calls without a valid service token; it never exposes catalog mutation, stock adjustment, or checkout to the open internet. Admin API is operator-authenticated; webhooks are signature-verified and idempotent.
- **Delivery agents are a scoped, least-privilege caller (§10.4).** Self-registration is gated by a signed, expiring store invite — not open enrollment; a `PENDING` agent can do nothing; an approved agent is scoped to one store and can only touch its own assignments. The agent token is issued by yaya-sales and is neither an operator nor a service credential. COD collection is the only money action a courier can take, is capped per region, and is reconciled (`cod-reconciliation` report) against what was handed in.
- **PSP secrets encrypted at rest** (AES-256-GCM envelope), per store, never returned to any client.
- **Customer PII has a retention/deletion path.** Addresses and order history are deletable/redactable on request, mirroring the yaya-agentic recorder's redact/delete (design §5.9) — a compliance requirement, not optional.

---

## 16. Decisions

All 14 open questions are **resolved** (owner sign-off 2026-07). Recorded here as decisions; a couple keep a small follow-up.

1. **Profile-shape change (§12.3) — YES.** Shrink `CommerceConfig` → `CommerceBinding` additively; dual-read through S2–S4; drop old fields at S4.
2. **Deduction point default — `ON_PAYMENT`,** per-store override to `ON_FULFILLMENT`.
3. **Valuation — moving-average in v1;** FIFO layers later behind the `Valuation` SPI.
4. **Cart re-render — cache a read-only snapshot** in the agent's working memory; yaya-sales is the source of truth.
5. **DB isolation — shared Postgres instance in v1 is fine, but yaya-sales gets its own `yaya_sales` schema and takes NO cross-schema FKs/joins to yaya-agentic** — a hard guarantee so it can be lifted to a separate instance quickly, with zero query changes. Enforced from S0 (§12.4).
6. **Reservation — SOFT hold at add-to-cart with a short TTL** (per-store, ~10 min), swept on abandonment; **checkout REVALIDATES** every hold (§5.3/§7.3/§8.2) — fresh ones firm up, lapsed ones re-reserve against live stock or fail the line.
7. **Multi-store per tenant — YES.** `StoreId` is distinct from `TenantId`; a tenant owns 1..N stores, every row is `store_id`-scoped, and a profile's `CommerceBinding` points at one store.
8. **Carrier depth — manual + in-house courier in v1** (no paid dependency; satisfies the self-hosted constraint). *Note: order/delivery **tracking is fully in v1** (§8.7) — it works via courier/merchant status updates + POD, and via a carrier webhook when one exists. What waits for vNext is a **live external commercial-carrier API** (auto label/rate/tracking, e.g. DHL) behind the `Carrier` SPI.* Revisit if a specific carrier is needed sooner.
9. **Address validation — YES, in v1.** A pluggable `AddressValidator` normalizes + validates the ship-to at checkout (format, required fields, zone match); an unservable address fails before any money moves (§8.2). External verification services slot in later behind the same SPI.
10. **Delivery slots — hard capacity holds,** booked in the checkout saga like an inventory reservation (§8.2), with the same no-oversell guarantee (§14).
11. **Region resolution — derive from ship-to,** falling back to the channel's default region; single-region stores never prompt (§6.1).
12. **COD abuse controls — per-region COD value cap + OTP-verified phone in v1;** `cod-reconciliation` report reconciles collected cash. Richer fraud scoring later.
13. **Courier identity — KYC in v1 (§8.9).** Registration collects **legal name, legal address, government photo ID (document upload), and vehicle information + documents** (registration, insurance); the merchant **reviews the documents and approves/rejects** before the courier can be assigned work. Documents are encrypted PII with a retention/deletion path (§15). A pluggable background-check hook is vNext.
14. **Courier surface — a screen inside `yayaagenticchat`** (§11.5), in a courier mode gated by the yaya-sales agent token — no separate app install. It calls the courier API (§10.4) directly, outside the yaya-agentic trust path.

**Remaining follow-ups (do not block S0):** whether a specific external carrier is wired earlier than vNext (Q8); tuning the soft-hold TTL per store (Q6).

---

## 17. Appendix — SPI Recap

All internal to yaya-sales; none extend the yaya-agentic SPI inventory.

```java
interface InventoryLedger { ReserveResult reserve(...); void commit(ReservationId); void release(ReservationId);
                            MovementId receive(...); MovementId restock(...); MovementId adjust(...);
                            List<MovementId> transfer(...); }
interface Valuation       { Money unitCostOnSale(VariantId sku, LocationId loc); void onReceipt(...); }  // avg | fifo
interface PricingEngine   { PricedQuote quote(CartSnapshot cart, PricingContext ctx); }   // region-aware, tax-inclusive
interface TaxProvider     { TaxResult tax(PricedQuote partial, TaxPolicy policy, Region region); } // inclusive|exclusive
interface ProductSearch   { ProductSearchResult search(ProductQuery q); }         // FTS | pgvector; channel-scoped
interface TenderProvider  { TenderKind kind(); TenderOutcome begin(...); void settle(...); RefundOutcome refund(...); } // PSP|COD|MANUAL|STORE_CREDIT
interface PaymentProvider { String name(); AuthorizeOutcome authorize(...); CaptureOutcome capture(...);
                            RefundOutcome refund(...); }                            // the PSP tender's engine
interface MerchantOnboarding { String provider(); OnboardingLink start(...); MerchantAccountView reconcile(String); }
interface DeliveryQuoteEngine { List<DeliveryOption> quote(DeliveryQuoteRequest r); }   // zones × rates × methods
interface Carrier         { String name(); Money rate(RateInput i); Label createLabel(ShipmentInput s);
                            List<TrackingEvent> track(String trackingNumber); }          // ManualCarrier | InHouseCourier | <integration>
interface DeliverySlots   { List<Slot> available(MethodId m, LocationId loc, Range r); void book(SlotId, OrderId); }
interface DeliveryFleet   { DeliveryAgent register(InviteToken t, AgentRegistration r);         // legal name/addr, vehicle
                            void submitDocuments(AgentId, List<AgentDocument> docs);            // photo ID, license, insurance
                            void decide(AgentId, ApprovalDecision d);                           // merchant KYC approve/reject
                            void assign(ShipmentId, AgentId); void collectCod(AssignmentId, Money); }
interface AddressValidator{ ValidatedAddress validate(Address a, Region r); }      // format + required + zone-match; ext. later
interface OutboxRelay     { void publish(); }                                     // at-least-once
interface IdempotencyStore{ Optional<StoredResponse> find(StoreId, String key); void put(...); }
```

The **only** yaya-agentic-side artifacts are configuration (HTTP tool descriptors per store) and one generic engine convenience (`_uiHint` pass-through, §11.2). No agent SPI is added, renamed, or extended — consistent with CLAUDE.md's SPI-stability rule.
