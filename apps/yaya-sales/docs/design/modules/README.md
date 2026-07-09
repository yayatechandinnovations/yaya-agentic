# yaya-sales — Module Designs

The parent [`yaya-sales-design.md`](../yaya-sales-design.md) is the **system** design: the invariants (§5), the module map (§6), the cross-cutting lifecycles (§7–§8), the data model (§9), the API surface (§10), the yaya-agentic integration seam (§11), and the migration plan (§12). It is the source of truth for anything that spans modules.

This directory holds one **module** design per Spring-Modulith module (`@ApplicationModule`, §6). Each expands the slice of the system doc that its module owns into an implementation-ready spec — its aggregates, state machines, tables, endpoints, SPIs, and invariants — without re-litigating decisions already settled in the parent (§16 Decisions).

**Rule of thumb:** cross-module concerns and shared invariants live in the parent; a module doc details *how this module fulfills its row of §6* and *the contracts it publishes to the other modules*. When a module doc and the parent disagree, the parent wins — open a change against it first.

| # | Module | Design | Parent anchors | Owns (one line) |
|---|---|---|---|---|
| 1 | catalog | [catalog-design.md](catalog/catalog-design.md) | §5.1, §6 r1 | Products, variants/SKUs, categories, media, publish/versioning, CSV |
| 2 | inventory ★ | [inventory-design.md](inventory/inventory-design.md) | §5.1–5.3, §7 | Stock ledger, reservations, availability, valuation, locations |
| 3 | pricing | [pricing-design.md](pricing/pricing-design.md) | §6.2 | Price lists, tax (inclusive/exclusive), promotions → priced quote |
| 4 | cart | [cart-design.md](cart/cart-design.md) | §5.4 | Session carts, lines, cart pricing, soft-hold trigger, merge |
| 5 | orders | [orders-design.md](orders/orders-design.md) | §8.1–8.2 | Order aggregate + FSM, draft orders, checkout saga, order events |
| 6 | payments | [payments-design.md](payments/payments-design.md) | §8.3 | Tender model (PSP/COD/manual/store-credit), ledger, onboarding, webhooks |
| 7 | fulfillment & delivery ★ | [fulfillment-design.md](fulfillment/fulfillment-design.md) | §8.4–8.9 | Delivery methods, zones/rates, carriers, shipments, tracking, courier fleet |
| 8 | returns | [returns-design.md](returns/returns-design.md) | §8.8 | RMA, return shipments, restock, refund/store-credit |
| 9 | purchasing | [purchasing-design.md](purchasing/purchasing-design.md) | §9 | Suppliers, purchase orders, goods receipts → RECEIPT movements |
| 10 | customers | [customers-design.md](customers/customers-design.md) | §5.8-eq, §15 | Customer records + addresses (principal-keyed), order history, PII |
| 11 | stores | [stores-design.md](stores/stores-design.md) | §6.1 | Sales channels, regions/markets, locations, policies, branding |
| 12 | search | [search-design.md](search/search-design.md) | §5.10-eq | Product search + facets (FTS; optional pgvector), channel-scoped |
| 13 | integration | [integration-design.md](integration/integration-design.md) | §10.2, §11 | Agent-facing endpoints, `_uiHint` framing, service/principal auth |
| 14 | reporting | [reporting-design.md](reporting/reporting-design.md) | §12-eq | Sales, valuation/margin, low-stock, COD reconciliation reports |
| 15 | platform | [platform-design.md](platform/platform-design.md) | §5.4–5.7 | IDs, idempotency, outbox, concurrency, money/FX, auth, media |

★ = the modules that carry the "real sales system" weight.

Each module doc follows the same skeleton (Status/Anchors header → Purpose → Scope IN/OUT → Responsibilities & key types → Data model → API → SPIs published/consumed → Invariants → Open questions), matching the house style of the parent and the yaya-agentic design docs. Sections marked **TBD** are the deep-design work still to be done.
