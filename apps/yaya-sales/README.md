# yaya-sales

A standalone, multi-tenant **sales & commerce application** shipped alongside yaya-agentic as an integration (see [`../README.md`](../README.md)) — **not** part of the agent core.

It owns the commerce domain end to end as a **real sales system**: catalog, **ledgered inventory** (stock that moves via immutable receipt/sale/return/adjustment movements — on-hand is a projection, reservations prevent overselling, and valuation feeds margin), **selling context** (sales channels + regions/markets: currency, tax-inclusive/exclusive, allowed tenders, shipping — per selling surface), pricing/promotions, cart, draft orders, a full **order → payment → fulfillment → delivery → return** lifecycle, a **tender model** (PSP + **cash-on-delivery** + manual transfer + store credit, split tender), **shipping & delivery** (delivery methods, shipping zones + rate calculation, carriers, shipments, delivery-status tracking, slots/pickup, proof of delivery, and an **in-house courier fleet with self-registration**), purchasing/receiving, customers, and reporting. It has its own database, migrations, and deployment, plus a correctness spine (idempotency, optimistic concurrency, exact money/FX, transactional outbox).

yaya-agentic reaches it only through HTTP tools (`/v1/sales/agent/*`) with explicit auth forwarding; rich chat rendering flows back as engine-emitted UI-hint frames. The agent decides *whether* a shopper may act; yaya-sales decides *what the action costs, whether stock exists, and whether money and inventory actually moved* — authoritative here, never in the model.

**Start here:** [`docs/design/yaya-sales-design.md`](docs/design/yaya-sales-design.md) — full architecture, module map, integration seam, data model, and the migration plan (§12) for extracting commerce out of the yaya-agentic core.

Status: **design proposed**; implementation milestones S0–S6 in the design doc.
