# yaya-sales · search — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.search` (§6 row 12)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §5.10-eq (search in module map), §9 (`search_tsv`), self-hosted constraint.
**Consumes:** catalog (products/attributes), inventory (live availability), stores (channel scoping).
**Consumed by:** integration (`products/search` agent tool), storefront browse.

---

## 1. Purpose

Access-gated product search + facets that feed the storefront browse view — fast, and **without a paid embedding dependency** in the critical path.

## 2. Scope

**IN:** full-text search over products (Postgres FTS via `search_tsv`); facet computation from `attributes`; filters (category, attributes, priceMax); pagination; **channel-scoped** availability filtering (only show what's published + in stock); optional pgvector semantic search behind the same interface (feature-flagged, self-hosted).

**OUT:** the product model (→ catalog), the availability numbers (→ inventory; search reads them), ranking-as-merchandising rules (vNext).

## 3. Responsibilities & key types

`ProductSearch { ProductSearchResult search(ProductQuery q) }`, `ProductQuery`, `ProductSearchResult` (items + facets + page), `Facet` (parent §5.10-eq).

## 4. Data model

No owned tables; reads `products.search_tsv` + `attributes_json` (catalog) and `stock_summary` (inventory). Optional pgvector index is search-owned (**TBD**).

## 5. API surface (§10.2, agent-facing)

`POST /v1/sales/agent/products/search` → results + `_uiHint:product_catalog`; `GET /products/{id}` → product with live availability + `_uiHint:product_card`.

## 6. SPIs

- `ProductSearch` (published) — FTS (v1) | pgvector (opt), channel-scoped.

## 7. Invariants

- Never surface a product not published to the calling channel, nor out-of-stock under `DENY` backorder (parent §6.1, §7.4).
- Facet counts reflect the same filtered, channel-scoped set as the results.

## 8. Open questions (module-local)

- **TBD:** self-hosted embedding model for pgvector (parent self-hosted constraint — OpenAI embeddings is the outstanding paid dep to replace).
- **TBD:** typed-attribute faceting once catalog adds attribute schemas.
- **TBD:** relevance tuning / synonyms for FTS.
