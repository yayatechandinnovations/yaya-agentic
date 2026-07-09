# yaya-sales · stores — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.stores` (§6 row 11)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §6.1 (channels & regions), §6.2 (tax policy home), §9 (tables), §16 Q7 (multi-store).
**Consumes:** platform (IDs).
**Consumed by:** every module (configuration): pricing (region/tax/price list), payments (tenders/connected accounts), fulfillment (zones/locations), catalog (channel publishing), orders (policies).

---

## 1. Purpose

Own the **selling-context configuration** — the aggregates every other module reads. A store belongs to a tenant (a tenant may own many stores; `StoreId ≠ TenantId`, parent §16 Q7) and pins its channels, regions/markets, locations, policies, and branding.

## 2. Scope

**IN:** `Store` (default currency, policies: deduction point, backorder, soft/checkout TTLs, branding); **`SalesChannel`** (`WEB_CHAT|WHATSAPP|SMS|POS`); **`Region`/Market** (currency, tax policy inclusive/exclusive, allowed tenders, shipping zones, price list); `Location`s; connected-account settings; `(channel, region)` resolution rules.

**OUT:** the pricing math (→ pricing reads `region.tax`), the tender engines (→ payments), the zone rate calc (→ fulfillment) — stores holds the *config*, others hold the *behavior*.

## 3. Responsibilities & key types

See parent §6.1: `SalesChannel`/`ChannelKind`, `Region` (the (currency, tax, tenders, zones, priceList) bundle), and store policies. Region resolution: derive from ship-to, fall back to channel default (parent §16 Q11).

## 4. Data model (owned tables)

`stores(id, tenant_id, …)`, `locations`, `sales_channels`, `regions`, `channel_product_availability` — parent §9.

## 5. API surface (§10.1, admin)

`GET/POST/PUT /v1/sales/admin/channels[/{id}]`, `…/regions[/{id}]`, `GET/POST …/locations`. Store CRUD + policies (**TBD** endpoint shape).

## 6. SPIs

None published; provides config-read APIs to sibling modules (in-process module API).

## 7. Invariants

- A checkout resolves to **exactly one** `(channel, region)`; tenders/currency/tax/zones all follow from it (parent §6.1).
- Every yaya-sales row is `store_id`-scoped; a profile binding points at one store.
- v1 seeds one `WEB_CHAT` channel + one region from the store's currency, so a simple store needs no extra setup.

## 8. Open questions (module-local)

- **TBD:** per-channel theming/branding (vNext) and geo auto-detect of region.
- **TBD:** cross-store shared catalog (vNext) — keep `store_id` scoping so it's non-breaking.
