# Commerce Redesign — Product Authoring at Scale, Clickable Storefront Rendering, PayPal — Design

**Status:** Proposed (production-ready target)
**Owner:** (you)
**Anchors:** design `§5.2` (Profile), `§5.3` (Capability & Tool), `§5.8` (Knowledge Sources — the pattern the catalog mirrors), `§6.1`/`§9` (UiHint events + client-initiated actions), `§7` (Prompt composition), `§12` (Trust boundaries). CLAUDE.md non-negotiables **#1** (LLM never trusted), **#4** (engine never writes storage directly), **#5** (tool/retrieved data is untrusted), **#6** (explicit `AuthForwarding`), **#8** (quick-replies/UI are engine-emitted UI hints).
**Relationship to prior work:** **Extends** [`ecommerce-chat-design.md`](./ecommerce-chat-design.md) (the storefront/cart/checkout spine — backend + playground shipped). It **supersedes** three parts of that doc: §7.1's "products embedded in the catalog `location_json`" authoring model (Pillar A), §10.1's "carousel is the catalog entry" rendering model (Pillar B), and §11/Q#7's payment-provider set (Pillar C — adds PayPal). No SPI is added or removed; **no sealed type's shape changes** (PayPal is a new `PaymentProvider` implementation whose result maps onto the existing `CheckoutOutcome.HostedUrl` variant).

---

## 1. Problem — three prod-blockers found while dogfooding the store

The commerce spine works end-to-end in the playground, but a senior-dev pass against a *real* merchant workflow surfaced three defects that keep it out of production. Each is a first-class pillar of this redesign.

### Pillar A — Authoring 100 products is not viable

Today a catalog's products live **inline, embedded in the catalog's versioned `location_json` JSONB blob** (`CatalogLocation.Inline(List<Product>)`). The admin console edits them as a stack of `ExpansionTile`s inside a ~40%-width form pane. Consequences:

- **No list, no pagination, no search.** Every product is an expandable card; 100 products is ~7,000px of scrolling in a narrow panel, one product expanded at a time.
- **Editing one field rewrites the whole catalog** and **bumps a whole catalog version** (config-versioning machinery misapplied to operational data). Fixing one typo in product #57's price re-serializes all 100 products into a new immutable version.
- **No bulk path.** Nobody hand-types 100 products in a web form. There is no CSV import/export — the way catalogs are actually maintained.
- **The `Database` location that was meant for exactly this was never built.** `CatalogLocation.Database` exists as a sealed variant but has **no `products` table and no loader** — only `InlineCatalogLoader` ships. Managed, scalable product storage is a design promise that was never implemented.

### Pillar B — The storefront renders as raw JSON, not a clickable card

From the chat surface, a shopper who triggers the catalog sees **a JSON blob of the catalog descriptor dumped as a text bubble**, not a tappable storefront. The rich renderers *already exist* (`ProductCarousel`, `ProductCardExpanded`, `CartSheet`) — so this is **a transport/emission failure, not a missing widget**: the descriptor is reaching the client as a `token` (prose) frame instead of a `ui_hint` frame with `kind:"product_catalog"`. Compounding it, there is **no catalog-first entry**: the design only ever emits an inline *carousel* of results. What a shopper expects — and what the product owner explicitly wants — is **a single clickable storefront card ("Browse products") that opens a real browse view** (grid + search + filters + pagination), with the carousel reserved for "here are a few results" mid-conversation.

### Pillar C — Checkout has no PayPal

Checkout supports dLocal (LatAm MoR) and Stripe Connect. **PayPal is missing**, and for a Dominican-Republic-first merchant that is a gap: PayPal is available in the DR (and ~200 markets), is a payment method shoppers already trust, needs no card-data handling on our side (redirect-to-approve), and — via PayPal Commerce Platform — supports marketplace onboarding + platform fees, the same shape as Stripe Connect / dLocal Platforms.

**The bet:** these three fixes turn commerce from "demoable" into "a merchant can actually load their catalog, a shopper can actually browse and tap it, and both DR and international shoppers can actually pay."

---

## 2. Guiding constraints (unchanged from the spine — restated so the redesign obeys them)

1. **The model never touches money.** Price/total/tax come from the store of record (now the managed `products` table for `Database` catalogs, or the merchant API for `HttpApi`), never from LLM tool args. `Money` stays **integer minor units** (§4 #1). Pillar A moves *where* the price is stored; it does not move it into the model's reach.
2. **Rendering is data, never prose.** Catalog / storefront / product / cart are **engine-emitted `UiHint`s** the client renders; the model captions, never enumerates (§4 #8). Pillar B is, at its core, **enforcing this constraint that is currently being violated**.
3. **Two doors, one lock.** Browsing the storefront, tapping a facet, paging, adding to cart — all converge on the same `ToolExecutor` + `Authorizer` via the chat turn or the `POST /actions` channel. New storefront affordances are `clientInvocable` tools, not a bypass (§9 of the spine).
4. **The framework orchestrates payment; a licensed PSP charges it.** PayPal joins dLocal/Stripe under the existing `PaymentProvider` SPI; no card data enters yaya-agentic; funds route PSP→merchant; we never sit in the flow of funds (§11 of the spine).
5. **Self-hosted, zero paid services where an OSS option exists** ([[self_hosted_no_paid_services]]). The `products` table is Postgres (already run); search/facets reuse Postgres (GIN/tsvector) with pgvector optional and deferred. The only unavoidable external remains the licensed PSP for money movement — a justified exception.
6. **Per-catalog access, config-versioned; product data is operational, not versioned.** The catalog *config* stays versioned like `profiles`; the *products* become mutable rows. Separating these two lifecycles is the crux of Pillar A.

---

## 3. Design overview — one picture

```
 ADMIN (yayaagenticweb)                         RUNTIME (Spring)                    SHOPPER (yayaagenticchat)
 ─────────────────────                          ────────────────                    ─────────────────────────
 Catalog workspace                              product_catalogs (VERSIONED config) storefront card  ── tap ──┐
   ├─ settings (name, currency, cover) ───────► location = Database(logicalName)     "Browse products"        │
   ├─ PRODUCT TABLE  (server-paginated)                 │                                                     ▼
   │    thumb│SKU│name│price│avail│⋯  ◄──────────┐      │  DatabaseCatalogLoader    full BROWSE view (sheet/route)
   │    quick-add row  (SKU,name,price,stock)    │      ▼   + ProductSearchService    grid + search + facets +
   │    Import CSV / Export CSV                  │  products (MUTABLE rows)            pagination  ── tap card ─┐
   └─ FULL PRODUCT EDITOR (own route/drawer) ────┘  (tenant,catalog,product_id, …)          │                 ▼
        all fields + variants + gallery              price_minor BIGINT (authoritative) ProductCardExpanded (exists)
                     │                                       │                                 │ add-to-cart
                     ▼ single-row PUT (no version bump)      ▼ engine drains PendingUiHints    ▼
        POST/PUT/DELETE /v1/admin/product-catalogs           as ui_hint frames  ───────────►  Cart → checkout
              /{id}/products (+ :bulk, :import)              (NEVER as token prose)                 │
                                                                                                    ▼
                                          PaymentProviderRouter → { dLocal | Stripe | PayPal(new) } → HostedUrl/Intent
```

Everything the model could touch stays a *proposal* through the executor+authz lock. Product prices live in a mutable, merchant-authoritative table the model can't write. Rendering is `ui_hint` frames the client paints. Payment adds one provider behind the existing router.

---

## Pillar A — Managed product authoring at scale

### A.1 The core move: separate catalog *config* (versioned) from product *data* (mutable rows)

The single mistake to undo is storing operational inventory inside a versioned config blob. We promote the already-declared `CatalogLocation.Database` variant to a real, first-class, backing store:

- **`product_catalogs`** stays exactly as it is — a **versioned config** row (name, currency, cover image, `sync`/`search`/`access` policy, and now `location = Database(logicalName)`). Editing catalog *settings* still bumps a version, like `profiles`. It no longer carries product rows.
- **`products`** (new) is **mutable operational data** — one row per product, edited in place. Changing a price is a single-row `UPDATE`, not a catalog version bump. This is what makes per-product editing, pagination, and 100-product authoring sane.

`Inline` stays for seed/test/tiny fixed catalogs; `HttpApi` stays for merchants with their own product API. **`Database` becomes the default for admin-authored catalogs.**

### A.2 New table (migration **V71**)

IDs follow the codebase split: catalog/product ids are `VARCHAR` (config-id style); the table is tenant + catalog scoped.

```sql
-- V71__products.sql
CREATE TABLE products (
    tenant_id         VARCHAR(64)  NOT NULL,
    catalog_id        VARCHAR(128) NOT NULL,          -- FK-by-convention to product_catalogs.id
    product_id        VARCHAR(128) NOT NULL,          -- merchant SKU / code (stable, human-authored)
    title             VARCHAR(512) NOT NULL,
    short_description TEXT,
    long_description  TEXT,
    price_minor       BIGINT       NOT NULL,          -- integer minor units — never float (#1)
    currency          VARCHAR(8)   NOT NULL,
    category          VARCHAR(255),
    attributes_json   JSONB        NOT NULL DEFAULT '{}',   -- facet source: {"ingredient":"castor oil", ...}
    variants_json     JSONB        NOT NULL DEFAULT '[]',   -- [{variantId,label,priceMinor,sku,availability}]
    image_urls_json   JSONB        NOT NULL DEFAULT '[]',
    availability      VARCHAR(16)  NOT NULL DEFAULT 'IN_STOCK',  -- IN_STOCK|LOW|OUT|PREORDER
    inventory         INTEGER,                        -- nullable; drives availability when tracked
    detail_url        VARCHAR(1024),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE|ARCHIVED (archive != delete)
    sort_order        INTEGER      NOT NULL DEFAULT 0,
    search_tsv        tsvector,                        -- keyword search (title+desc+category+attrs)
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, catalog_id, product_id)
);
CREATE INDEX products_catalog_status_idx ON products (tenant_id, catalog_id, status, sort_order);
CREATE INDEX products_tsv_idx            ON products USING GIN (search_tsv);
CREATE INDEX products_attrs_idx          ON products USING GIN (attributes_json jsonb_path_ops);
-- pgvector embedding column is DEFERRED (semantic "shop by vibe" is a growth item, not launch).
```

Notes:
- **`status = ARCHIVED` instead of hard delete.** A product that has appeared in a past order/cart must not vanish — archive removes it from search + admin default list but preserves referential history (mirrors how the codebase soft-retires config).
- **`search_tsv`** is maintained by a trigger (or on write) — Postgres full-text keyword search, no paid search dependency (constraint #5).
- **Availability** is derived from `inventory` when tracked, else authored directly.

### A.3 New backend: loader + search + product registry

- **`DatabaseCatalogLoader implements CatalogLoader`** — the missing loader. `supports(CatalogLocation.Database)`; resolves products by reading the `products` table for `(tenant, catalogId)`. Slots into the existing `CatalogLoaderRegistry` (selection is by `supports()`, so this is additive — no `switch` edit, per the sealed-type discipline).
- **`ProductSearchService`** gains a **`Database` branch**: keyword (`search_tsv @@ plainto_tsquery`) + structured filters (category, attribute containment on `attributes_json`, `price_minor` range) + **server-side pagination** (`LIMIT/OFFSET` or keyset by `sort_order,product_id`), returning the same `Product[]` + facets descriptor the client already renders. Facets are computed with a `GROUP BY` over `attributes_json`/`category` instead of in-memory. Per-catalog `access` gating unchanged (#7 analog).
- **`ProductRegistry`** (new, JPA-backed `PostgresProductRegistry`) — CRUD + paged query for the *admin* side. This is the read/write authority the admin API sits on. Distinct from `ProductCatalogRegistry` (which stays config-only).

`Product` (the render+cart contract), `Money`, `ProductProjector` (still rejects float prices) are **unchanged** — the loader maps a `products` row → the same `Product` record, so **chat rendering and cart pricing are untouched by this pillar.**

### A.4 New admin API — product CRUD with pagination, search, bulk

Under the existing `/v1/admin/product-catalogs/{catalogId}` config resource, products become a **paged sub-collection** (a config-versioned parent owning mutable children — the REST shape that matches the lifecycle split in A.1):

| Method & path | Purpose |
|---|---|
| `GET  …/{catalogId}/products?page&size&q&category&availability&sort` | **Server-paginated** list → `{items:[ProductRow], page, size, total}`. `q` = keyword; filters map to the indexes above. |
| `POST …/{catalogId}/products` | Create one (used by the quick-add row and the full editor). |
| `GET  …/{catalogId}/products/{productId}` | Full product for the editor. |
| `PUT  …/{catalogId}/products/{productId}` | Update one — **single-row, no catalog version bump.** |
| `POST …/{catalogId}/products/{productId}:archive` / `:restore` | Soft archive / restore. |
| `POST …/{catalogId}/products:bulk` | Upsert many (create/update) in one call — backs CSV import; row-level results `{ok,errors:[{row,reason}]}`. |
| `POST …/{catalogId}/products:import` | Multipart CSV upload → parse → **preview** (dry-run validation, per-row errors) → client confirms → `:bulk`. |
| `GET  …/{catalogId}/products:export` | Stream CSV of the catalog (round-trip editing in a spreadsheet — how 100s of products are really maintained). |

The **image upload** endpoint (`POST /v1/admin/commerce/images`) is reused for product galleries. `AuthForwarding` is N/A here (these are first-party admin calls under operator auth), but the operator-auth guard on `/v1/admin/**` applies as for every other admin resource.

**Back-compat / migration path for existing `Inline` catalogs:** a one-shot **"Convert to managed"** action reads the inline products out of `location_json`, inserts them as `products` rows, and flips the catalog's `location` to `Database(logicalName=catalogId)`. Existing `Inline`/`HttpApi` catalogs keep working via their existing loaders; no forced migration.

### A.5 Admin UX redesign — a catalog *workspace*, not a form

Replace the single cramped form with a **catalog workspace** (reusing `AdminTwoPane`, the `audit_screen.dart` page+filter provider pattern, and `uploadCommerceImage`):

**Left rail — Catalogs.** The existing catalog list (name · currency · product count · status). Select → workspace.

**Right — Catalog workspace**, three zones:

1. **Settings (collapsible header).** Name, currency, cover image, access, location kind. Small — the products are the point, not the settings. Saving settings is the only thing that bumps a catalog version.

2. **Product table (the primary surface).** Server-paginated, dense, spreadsheet-like:
   - Columns: **thumbnail · SKU/code · name · price · availability (or inventory) · status · row-actions (Edit · Duplicate · Archive)**.
   - **Search box** (keyword) + **filter chips** (category, availability) + **sort** (name/price/updated) — all driven by the paged API (`q`, `category`, `availability`, `sort`, `page`, `size`), reset to page 0 on filter change (the audit-screen pattern).
   - **Pagination footer** ("page X of Y", prev/next, page-size 25/50).
   - **Sticky quick-add row** at the top: `SKU · name · price · initial stock` → Enter creates the product and stays on the list, cursor back in SKU. This is the "type-Enter-repeat" flow that makes bulk hand-entry bearable.
   - **Import CSV / Export CSV** buttons → the `:import` preview dialog (per-row validation before commit) / `:export` download. **This is the real answer to "imagine creating 100 products"** — a spreadsheet round-trip, not 100 web-form saves.
   - **Duplicate** a row → prefilled quick-add (variant-family authoring: "same as that, different scent").

3. **Full product editor (its own route / wide drawer).** Opened from a row's **Edit**. All the fields the list omits: title, short + long description, composition/ingredients, category, **arbitrary attribute key/value pairs** (facet source), **variants** (label · price · SKU · stock, add/remove rows), **image gallery** (upload · reorder · remove, via `uploadCommerceImage`), availability/inventory, detail URL. **Save = single-row `PUT`.** The list stays fast and scannable; the depth lives here — exactly the "few fields in the list, full fields on edit" split requested.

New Flutter models: `ProductRow` (list projection: id, title, priceMinor, currency, thumb, availability, status), `ProductDetail` (full editor shape), `ProductsPage` (`items,page,size,total`) — plain classes like the existing `admin/product_catalog.dart`. New `AdminApi` methods mirror the endpoints in A.4.

---

## Pillar B — Clickable storefront rendering (stop dumping JSON)

Two sub-fixes: **(B.1) make the descriptor always arrive as a `ui_hint` frame** (the reliability bug), and **(B.2) add a catalog-first storefront entry** (the UX the owner wants). The renderers already exist; this pillar is about *what reaches them* and *how the catalog is entered*.

### B.1 Guarantee the transport — a `ui_hint` frame, never token prose

The JSON-dump happens because a commerce descriptor reaches the client as a `token` (assistant prose) frame; the client has no renderer for prose-that-happens-to-be-JSON, so it prints it. Three enforcement points, defense in depth:

1. **Engine/public-chat parity (the real fix).** The playground drains `PendingUiHints` and emits `ui_hint` SSE frames (`kind` + `payload`). The **public chat SSE path (`/v1/chat/sessions/{id}/messages` and `/v1/chat/sessions/{id}/actions`) must do the identical drain** — audit both controllers so commerce payloads are emitted as `ui_hint`, never folded into the assistant text stream. Add a **contract test**: "a `search_products` turn on the public chat endpoint yields ≥1 `ui_hint{kind:product_catalog}` frame and **zero** catalog JSON in any `token` frame." This is the guard that keeps the bug fixed.
2. **The model never gets the descriptor to echo.** `search_products`' tool result returns the rich descriptor to the **engine** (for UiHint emission) and only a **compact, price-free caption hint** to the **model context** (e.g. `"12 results for 'body care' rendered as cards"`). The model literally cannot paste a price list it never received (reinforces #1/#8). The prompt rule ("caption only; the UI renders products; never list products/prices in prose") stays, but we stop *relying* on the model's compliance by not handing it the data.
3. **Client defensive fallback (belt-and-suspenders).** In the chat bubble, before rendering `message.text`, if the text parses as a commerce descriptor (`kind` in the known set, or `items`+`facets` present) route it to the correct widget instead of a text bubble, and log a telemetry warning ("descriptor arrived as text — transport regression"). This makes a future transport regression *degrade to a rendered card*, not a JSON dump, and the telemetry makes it visible.

### B.2 Catalog-first entry — a storefront card that opens a real browse view

Today the only catalog rendering is an inline **carousel of results**. Add a distinct **storefront entry** so the shopper gets "a clickable catalog card → tap → see the products":

- **New UiHint kind `storefront`** (a string value on the existing `UiHint` — no new event type, mirrors how `product_catalog`/`cart` were added). Payload: `{catalogId, displayName, coverImageUrl, currency, topCategories:[…], productCount}`. Emitted (a) at session start for a commerce profile as the opening affordance, and (b) whenever the shopper says "show me the store / what do you sell". The client renders a single **storefront card**: cover image + store name + a **"Browse products"** CTA (+ optional category chips).
- **Tap → full browse view.** A `StorefrontBrowseSheet` (bottom sheet / route **over the live chat**, same expand-in-place discipline as `ProductCardExpanded`): a **grid** of product cards + **search field** + **facet filters** + **pagination / infinite scroll**. It is driven entirely by the `search_products` **`clientInvocable`** tool through `POST /actions` (two doors, one lock) — paging and filtering are client-initiated actions, each returning a fresh `product_catalog` descriptor the sheet appends/replaces. Tapping a card opens the existing `ProductCardExpanded`; add-to-cart / cart / checkout are unchanged.
- **Carousel keeps its job.** The inline `ProductCarousel` remains for *conversational* results ("here are three under $20") mid-chat. So: **storefront card = the front door to the whole catalog; carousel = a few results in the flow.** Two presentations over the same `Product`/descriptor contract; no model involvement in either.

This directly delivers the requested experience — **a catalog widget/card you click to see products** — and it composes cleanly with Pillar A: the browse view's grid/search/pagination is powered by the same `ProductSearchService` `Database` branch that the admin table uses, so shopper-facing search and operator-facing search share one query path.

### B.3 What changes on the client (yayaagenticchat)

- New `StorefrontCard` widget + `Storefront` model (from the `storefront` UiHint) — the clickable entry.
- New `StorefrontBrowseSheet` (grid + search + facet bar + paging) — reuses `ProductCompactCard`, `FacetBar`, `ProductImage`, and the `invokeAction('search_products', …)` path that already exists for "Load more".
- Chat controller: handle `ui_hint{kind:storefront}` → `message.storefront`; the bubble renders `StorefrontCard` (like it renders `ProductCarousel` for `catalog`). Add the B.1.3 defensive fallback in `_Bubble`.
- No change to cart/checkout widgets.

---

## Pillar C — PayPal checkout

### C.1 New provider, existing SPI, existing sealed result

- **`PayPalPaymentProvider implements PaymentProvider`** — `name() = "paypal"`. `createCheckout(CheckoutRequest)` calls **PayPal Orders v2**: `POST /v2/checkout/orders` with the **cart-authoritative** total (integer minor units → PayPal's decimal string, currency-aware), `intent=CAPTURE`, and the merchant's connected account via **PayPal Commerce Platform** (partner attribution / `PayPal-Partner-Attribution-Id`; multiparty `payee.merchant_id`). PayPal returns an **approval link** (`links[].rel == "approve"`), which maps to the **existing `CheckoutOutcome.HostedUrl`** — the client already handles `HostedUrl` by launching the URL. **No new sealed variant; the client checkout handler is unchanged.**
- **Capture + status.** On shopper return / PayPal webhook (`CHECKOUT.ORDER.APPROVED` → `PAYMENT.CAPTURE.COMPLETED`), a new **`POST /v1/commerce/webhooks/paypal`** verifies the webhook signature, captures the order (`/v2/checkout/orders/{id}/capture` when not auto-captured), and hands to the existing **`PaymentStatusService`** (`PENDING → PAID`, cart clear) — the exact path dLocal's webhook already uses. Order rows land in `commerce_orders` with `provider="paypal"`, `orderRef = PayPal order id`.
- **Sandbox by default.** Like dLocal/Stripe, with no credentials it fabricates a deterministic sandbox approval URL so the flow is demoable without a live account. Live mode uses OAuth2 client-credentials (`clientId`/`clientSecret`) against the PayPal REST base.
- **`PayPalProperties`** — `clientId`, `clientSecret`, `sandbox`(bool), `partnerAttributionId`/`bnCode`, webhook id (for signature verification). Mirrors `DLocalProperties`/`StripeProperties`.

### C.2 Routing & selection

Extend **`PaymentRoutingProperties` + `PaymentProviderRouter`** to select among `{dlocal, stripe, paypal}` per tenant/region. Recommended default preference for a **DR-first** deployment: **PayPal or dLocal** (both cover the DR; Stripe does not support DR merchant signup — see spine Q#7). Selection stays config-driven (`effectiveProvider()`), and `CommerceConfig.connectedAccountId` generalizes to "the merchant's account on the selected PSP" (PayPal merchant id / dLocal sub-account / Stripe connected account). Admin: the profile **Commerce** section gains a **payment-provider selector** (dLocal / Stripe / PayPal) + the corresponding merchant-account id field.

### C.3 Trust posture (unchanged)

PayPal keeps the framework **out of the flow of funds and out of PCI scope**: redirect-to-approve means no card data touches us; funds settle PSP→merchant; the optional platform fee rides PayPal's multiparty model. This is the same justified self-hosting exception as the other PSPs ([[self_hosted_no_paid_services]]) — money movement can't be self-hosted.

---

## 4. Data model / migrations (next free = **V71**)

- **V71 `products`** — the managed product table (A.2). New.
- **V72 (optional) `products.embedding vector`** — pgvector column for semantic product search, **deferred** to the growth phase (keyword + facets ship first; no paid dependency at launch).
- **No change** to `product_catalogs` shape (it keeps `location_json`; `Database` catalogs simply store `{"kind":"Database","logicalName":"…"}` there instead of an inline product array). **No change** to `commerce_carts`, `commerce_orders` (PayPal reuses `commerce_orders` with `provider="paypal"`). **No sealed type shape change.** `UiHint.kind` gains one string value (`storefront`).

---

## 5. Trust boundaries — mapped to the non-negotiables (delta from the spine)

| Boundary | Control | Non-negotiable |
|---|---|---|
| Product price now lives in a DB the admin edits | Still integer `price_minor`; still merchant-authoritative; model still has no price field on `add_to_cart`; loader maps row→`Product`. Moving storage doesn't move trust. | **#1** |
| Catalog descriptor rendered | **Enforced** as `ui_hint` frames on the public chat path (contract test); model never receives the descriptor to echo; client fallback renders-not-dumps. | **#8, #5** |
| Storefront browse: paging/filtering by tap | `search_products` is `clientInvocable`, in-profile, Authorizer-gated via `POST /actions`. Browse is untrusted proposals. | **#1/#2** |
| Admin product CRUD | Under `/v1/admin/**` operator auth; tenant-scoped rows; no LLM involvement. | (admin plane) |
| PayPal checkout | `checkout` stays `confirmable`; PayPal via `PaymentProvider` SPI; no card data; funds PSP→merchant; webhook signature-verified. | §11 spine |
| Storage writes (products, orders, cart) | Product CRUD via `ProductRegistry`; order/cart state via existing recorders/services — engine never writes storage directly. | **#4** |

---

## 6. Rollout (ordered by dependency; each phase production-grade)

1. **A — managed products backend.** `products` table (V71), `PostgresProductRegistry`/`ProductRegistry`, `DatabaseCatalogLoader`, `ProductSearchService` `Database` branch (keyword + facets + pagination), product CRUD API (`…/{catalogId}/products` + `:bulk`/`:import`/`:export`), "Convert to managed" for existing `Inline` catalogs. *Managed catalogs become real; chat rendering unchanged because the `Product` contract is unchanged.*
2. **A — admin workspace UI.** Catalog workspace: paginated product **table** + search/filter/sort + quick-add + CSV import(preview)/export + **full product editor** route. *Authoring 100 products becomes viable.*
3. **B — transport fix + defensive render.** Public-chat `ui_hint` drain parity + contract test; compact/price-free tool result to the model; client fallback-render. *The JSON dump is gone and can't silently regress.*
4. **B — storefront-first UX.** `storefront` UiHint + `StorefrontCard` + `StorefrontBrowseSheet` (grid/search/facets/paging over `POST /actions`). *The requested "click the catalog card → see the products" lands.*
5. **C — PayPal.** `PayPalPaymentProvider` (sandbox default → live), `/webhooks/paypal`, router + admin provider selector, DR default preference. *DR + international shoppers can pay via PayPal.*
6. **Growth (deferred).** pgvector semantic product search (V72), abandoned-cart recovery over WhatsApp/SMS (durable `commerce_carts` already exists), cross-sell, facet analytics (spine §16).

Phases 1–2 fix authoring; 3–4 fix rendering; 5 adds PayPal; 6 is upside.

---

## 7. Value-add ideas (beyond the ask)

1. **CSV round-trip as the canonical bulk workflow.** Export → edit in Sheets/Excel → import-with-preview is how merchants actually manage catalogs; making it first-class (not an afterthought) is the single biggest authoring-throughput win.
2. **Variant-family authoring.** "Duplicate" + a variants grid means a 12-scent soap line is one product with 12 variants, not 12 rows — fewer SKUs to hand-manage.
3. **Inventory-aware storefront.** `availability`/`inventory` greys out `OUT` items in the browse grid and lets the model say "that's out — here's a close match" (no phantom stock).
4. **Storefront deep-link.** The `storefront` card doubles as a shareable entry ("tap to shop") reusable by the WhatsApp/SMS senders — the store card is channel-agnostic chrome.
5. **PayPal + dLocal side-by-side for DR.** Offer the shopper both a card path (dLocal) and a PayPal button — measurable conversion lift where PayPal trust is high.
6. **Bulk image import.** Pair CSV import with a zip-of-images upload keyed by SKU, so a 100-product load is two files, not 100 gallery uploads.

---

## 7b. Implementation status

| Pillar | Landed | Tests |
|---|---|---|
| **A — managed products backend** | `products` table (**V71**); `ProductEntity`/`ProductRepository`; `ManagedProduct`/`ProductRegistry`/`PostgresProductRegistry` (paged/filtered CRUD via `Specification`); `DatabaseCatalogLoader` (the loader the sealed variant always promised); `ProductAdminController` (`/{catalogId}/products` + `/bulk` + `/export.csv` + `/{id}/archive|restore`); `CatalogAdminController` now accepts `DATABASE` + counts managed products + `POST /{id}/convert-to-managed`. | `ProductAdminIT` (create/edit/archive/page/export + bulk row-errors + inline→managed convert), `CatalogAdminIT` (DATABASE accepted) — green |
| **A — admin workspace UI** | `ManagedProductsScreen` (paged table + search/status/sort + quick-add row + CSV import-preview/export + pagination); `ProductEditorDialog` (all fields + attributes + variants + gallery); `product.dart` models + `AdminApi` product methods; `csv_web.dart`/`csv_codec.dart`; catalogs screen gains a **Managed** segment (now the default), **Manage products** / **Convert to managed** card actions; route `/admin/product-catalogs/:id/products`. | `flutter analyze` clean (info-only) |
| **B — rendering fix + browse** | Client defensive fallback (`_salvageCatalogFromText` renders a leaked descriptor as a card, never JSON prose); `StorefrontCard` catalog-first entry → **full `StorefrontBrowseSheet`** (dedicated grid + search + instant facet filter + server-paged "Load more" over the live chat, via `ChatController.searchCatalog` which captures the `product_catalog` payload without polluting the message list); confirmed backend SSE already emits `ui_hint` identically on public-chat and playground (`WebSseChannelAdapter`), so the bug was client-side. | `WebSseChannelAdapterTest` (regression guard: `UiHint→ui_hint`, never `token`); `flutter analyze` clean |
| **C — PayPal + per-profile PSP** | `PayPalPaymentProvider` (Orders v2 → approval URL → existing `CheckoutOutcome.HostedUrl`, no new sealed variant); `PayPalProperties` (+ registered in `CoreConfiguration`); `PayPalWebhookController` (`/v1/commerce/webhooks/paypal`, verify-webhook-signature when wired, else sandbox). **Per-profile PSP override:** `CommerceConfig.paymentProvider` (nullable, back-compat ctor); `PaymentProviderRouter.resolve(pref)` falls back to the framework default; `CheckoutToolHandler` resolves once per checkout and records the resolved provider on the audit row; admin profile **Commerce** section gains a provider dropdown + merchant-account field. | `PayPalPaymentProviderTest`, `PaymentProviderRouterTest`, `PaymentProviderWiringTest`, `CheckoutToolHandlerTest.perProfilePaymentProviderOverrideSelectsThatPsp` — green |

**Added test coverage:** `WebSseChannelAdapterTest` (Pillar B transport contract), `products_csv_test.dart` (Pillar A CSV import: header-mapped parse, decimal→minor conversion, row-error reporting, quoted-field handling), `CheckoutToolHandlerTest` per-profile PSP override.

**Payment provider selection — resolved (per-profile).** A profile's `CommerceConfig.paymentProvider` (`dlocal|stripe|paypal`, blank = framework default) now routes its checkout, so different profiles/tenants can use different PSPs. `PaymentProviderRouter.resolve(pref)` is the seam; `select()`/`name()` stay request-free for the generic `@Primary` path and the auto heuristic is unchanged. The framework-wide `yaya.agentic.commerce.payment.provider` remains the default when a profile doesn't pin one.

**Known scope-bounded follow-ups (documented, not silently skipped):**
- **Shopper search over a `Database` catalog is loader-based (in-memory).** `DatabaseCatalogLoader` loads the catalog's active rows and `ProductSearchService` filters/paginates/facets them in memory — identical to the `Inline` path, correct and fine at the stated scale (hundreds). A very large catalog (thousands) wants a server-paged `Database` branch in `ProductSearchService` querying `ProductRegistry` directly. The **admin** table is already server-paged; only the shopper browse path is in-memory.
- **Live-PSP verification** of PayPal order/webhook signing against a real sandbox account (needs real credentials) — the same live-seam caveat dLocal carries (open Q#4).

**Always-on demo store (for presentations).** `DemoStoreBootstrap` (@Order 80) seeds the full chain **through the managed path this redesign added**: a `Database` catalog `demo-store`, **12 managed products** (a coffee roaster — beans with 250g/1kg variants, brewing gear, accessories/gift; `roast`/`origin`/`method` facets; picsum image URLs so cards render), a commerce-bearing `demo-store@1` profile (reusing the shared browse/cart/checkout capabilities), and an **ACTIVE chat entry point** + its `MatchAny` binding — reachable from the end-user chat front door, not just the playground. Every step is guarded (registry/handle lookup), so it plants the demo on an empty tenant and thereafter leaves operator edits alone (distinct from `RetailStoreBootstrap`, which seeds an Inline catalog for the playground only, with no channel).

**Per-account provisioning (all demos, every workspace).** A workspace's `tenant_id` is its own slug (from signup), never `"default"` — so demos seeded only into `"default"` are invisible to real accounts. Fix: each demo bootstrap (hello-world+FAQ, retail-store, demo-store, start-flow) exposes a tenant-parameterized `seedInto(tenant)` (its `run()` still seeds `"default"` at startup); `TenantDemoProvisioner` calls them in order for any tenant. It provisions on **new-workspace creation** (a `WorkspaceCreatedEvent` published by `DefaultOperatorAccountService`/`WorkspaceService`, consumed after commit) and **backfills every existing tenant at startup** — gated by `yaya.agentic.demo.provision-per-workspace` (default `true`). Each demo runs in its own transaction, independently guarded, so one failure (e.g. knowledge ingestion without an embedding key) never blocks the rest. The chat-entry-point `handle` is globally unique, so non-default tenants get a scoped handle (`<slug>-demo-store`). Proof: `DemoStoreBootstrapIT` — catalog+products, commerce profile, ACTIVE entry point, shopper search reaching the `Database` catalog, idempotent re-run, and `provisionsAllDemosIntoAnArbitraryTenant` (all demos land in a non-default tenant with a scoped handle); `TeamsRolesIT` exercises signup→verify→provision.

**Deferred growth (from the original ecommerce design §16, out of this redesign's scope):** pgvector semantic product search (V72), abandoned-cart recovery, cross-sell, facet analytics.

## 8. Open questions

1. **CSV schema.** Fixed columns vs a header-mapped importer (map "Precio" → `price_minor`)? Leaning header-mapped with a saved mapping per tenant, so a merchant's existing spreadsheet imports without reformatting. Price cell is decimal in the sheet, converted to minor units on import (with per-row validation for bad money).
2. **Product delete semantics.** Confirm **archive-only** (no hard delete) given order/cart history references — or allow hard delete for never-ordered products? Leaning archive-only, with a purge job for never-referenced archived rows.
3. **Storefront card timing.** Emit `storefront` automatically at session start for every commerce profile, or only on intent ("show me the store")? Leaning: emit at start (it also signals "this is a shop"), suppressible per profile.
4. **PayPal marketplace module for DR.** Confirm PayPal **Commerce Platform (multiparty / partner referrals)** is enabled for DR-domiciled merchants and its fee/onboarding shape before committing to the platform-fee path (the PayPal analog of spine Q#7's dLocal-Platforms caveat). Fallback: standard PayPal (merchant-of-record is the merchant, no platform fee).
5. **Pagination strategy for very large catalogs.** `LIMIT/OFFSET` (simple, fine to ~10k) vs keyset (`sort_order,product_id`) for deep pages. Leaning keyset for the shopper browse (infinite scroll), offset for the admin table (jump-to-page). 
6. **Semantic search timing.** Ship keyword+facets at launch (Postgres only, zero paid dep) and add pgvector (V72) as growth — or index embeddings from day one? Leaning defer, per the self-hosted-first posture.
```
