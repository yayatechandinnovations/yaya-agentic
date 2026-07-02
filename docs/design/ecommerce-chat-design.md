# Commerce Chat — Conversational Storefront, Cart & Checkout — Design

**Status:** Proposed (production-ready target — not a throwaway MVP)
**Owner:** (you)
**Anchors:** design `§5.2` (Profile), `§5.3` (Capability & Tool), `§5.7` (Session & Memory), `§5.8` (Knowledge Sources — the pattern we mirror), `§6.1`/`§9` (UiHint events), `§7` (Prompt composition), `§12` (Trust boundaries). CLAUDE.md non-negotiables **#1** (LLM never trusted), **#2** (no out-of-profile tools), **#5** (tool/retrieved data is untrusted), **#6** (explicit `AuthForwarding`), **#8** (quick-replies are engine-emitted UI hints).
**Supersedes nothing.** Adds a `ProductCatalog` primitive (mirroring `KnowledgeSource`), a session-scoped **cart** in working memory, a family of **commerce tools**, three new **`UiHint` kinds**, and one new **client-initiated action channel**. No existing SPI or sealed type changes shape; the engine loop is untouched except for two new emit points that follow the existing `quick_replies` pattern.

---

## 1. Problem

yaya-agentic is a framework for **profile-driven, auth-aware conversational agents**. Today a profile can *talk about* anything (RAG over a knowledge base) and *do* things (tools through the Authorizer chain), but it cannot **sell**. There is no notion of a product, a catalog, a cart, or a checkout. A customer who wants to build a bot that helps their end-users **buy** — the single most direct way a bot makes its owner money — has to hand-roll all of it: bolt a product API onto HTTP tools, format product lists as plain prose (which the model mangles, hallucinates prices into, and can't make clickable), and track cart state somewhere off-platform.

The result is the worst of both worlds: the model narrates a store instead of *rendering* one, and the "cart" is a fiction the model tries to remember in prose. That breaks on three axes:

1. **No rich rendering.** "Show me your body-care products" should paint a scrollable grid of tappable product cards — not a numbered markdown list the model retypes (and miscounts) every turn.
2. **No durable, trustworthy cart.** A cart the model "remembers" in text drifts, loses items, and — fatally — lets the *model* decide prices. Money state cannot live in the model's imagination.
3. **No dual-control.** Real storefronts let the shopper act two ways at once: *talk* ("add two of the castor-oil soap") **and** *tap* (hit − on a cart line, tap "Checkout"). Today only the talk path exists, and it doesn't persist.

**We want:** a shopper, inside a normal yaya-agentic chat, can browse a catalog rendered as rich cards, filter it instantly ("just the castor-oil ones", "only soaps") *without a model round-trip when the filter is trivial* and *with* the model when it isn't ("something under $20 for a dry scalp"), maintain a live cart they can see and edit from either the chat or the cart widget, and check out — **all without ever leaving the conversation**, and with every price, every authorization, and every charge enforced by the framework, never the model.

### Product framing — why this is the highest-leverage feature we can ship

Every other capability in yaya-agentic helps a customer *serve* their users. This one helps their users **give the customer money**, which is the metric every customer actually reports up. Concretely it unlocks:

- **Conversational commerce** for merchants who live on WhatsApp / SMS / web chat (the exact channels yaya-agentic already fronts) and have no storefront — the chat *is* the store.
- **Assisted selling** — the model does what a good sales associate does: narrows a huge catalog by intent ("gifts for oily skin under $30"), cross-sells ("this pairs with…"), and recovers abandoned carts — while the deterministic UI does what a catalog page does: renders, filters, totals.
- **Guided checkout** with the two-phase confirm we *already* have (§5.3 `confirmable`) as the guardrail before any charge.

The bet: the customers who make the most money from a bot are the ones whose bot can *close a sale*. This is the feature that turns the admin console from "configure a support agent" into "stand up a store you talk to."

---

## 2. The core insight — this is a composition of primitives yaya-agentic already has

The reason this feature *fits* is that a conversational storefront decomposes almost entirely into things the framework already does well. We are mostly **naming and wiring** existing machinery, not inventing new machinery.

| Storefront need | Existing yaya-agentic primitive | What's new |
|---|---|---|
| Render a product grid / product card / cart **as UI, not prose** | `TurnEvent.UiHint(kind, payload)` — the engine already streams structured, non-LLM UI payloads (`quick_replies`, `confirm`, `entity_conflict`) that the client renders as chrome (non-negotiable **#8**) | Three new `kind` values: `product_catalog`, `product_card`, `cart`. **No new event type, no wire-format change.** |
| Add / remove / checkout as **guarded actions** on the merchant's own backend | `ToolHandlerRef.Http` + `HttpToolSpec` (method, URL, `ResponseProjection`, explicit `AuthForwarding`) → schema validation → **Authorizer chain** → dispatch (§5.3, non-negotiables **#1/#2/#6**). `confirmable` two-phase confirm already exists for money-movers | A family of **HTTP** commerce `ToolDescriptor`s pointing at the merchant's store API; a `confirmable` `checkout`. |
| A **live, mutable cart** across turns | The merchant backend owns the cart; `WorkingMemory` (Redis, `yaya:wm:{sessionId}`, idle-TTL) threads a **cart handle** + cached snapshot across turns | One `cartHandle` key (+ snapshot) in working memory; managed Redis cart only as a fallback for merchants with no cart API. |
| A **searchable, access-gated catalog** | `KnowledgeSource` pattern: id + `SourceLocation` (sealed) + retrieval policy + `AccessRequirement`, plus the pgvector/embedding pipeline for semantic search | A parallel `ProductCatalog` primitive that reuses the same shapes and can reuse pgvector for semantic product search. |
| Point tools at an external system with **no code** | `ToolHandlerRef.Http` is already the "SaaS extension" transport — operators wire a URL + projection + auth in the admin console; the framework calls out, no bean, no deploy | Commerce tools ship as HTTP specs by default — merchants integrate their store API, not our internals. |
| Deciding **which chat is a store** | `Profile` already *is* "the role the user is talking to" — it pins capabilities, knowledge sources, auth. A `ChatEntryPoint` merely **routes** to a profile. | A `commerce` block on `Profile` (catalog refs + currency + checkout tool). No mode flag. |
| **Never leaving the chat** | Everything is a `UiHint` inside the message stream; the text input never yields the screen | Client renders catalog/cart inline; facets travel in the payload for instant local filtering. |

> The whole design is: **the catalog is a `KnowledgeSource`-shaped thing, the cart lives in the merchant's backend (threaded by a working-memory handle), the actions are HTTP tools, and the rendering is `UiHint`s.** The only genuinely new surface is a *client-initiated action channel* (§9) so a tap on the cart is enforced by the exact same tool+authz path as a sentence in the chat.

---

## 3. The decision you asked for: profile-level, not chat-config-level

You asked where we decide a chat is e-commerce — at the **profile definition** or at **chat creation/configuration**.

**Recommendation: commerce is a Profile-level trait. The chat entry point (front door) only *routes* to that profile; it never decides "is this commerce."**

### Why the Profile is the right home

1. **It's already the definition of "the role."** Per the design, a Profile "pins a system prompt, a list of capabilities (with backing tools), a set of knowledge sources for RAG, and an auth binding." A storefront is exactly that shape: a role whose capabilities are *browse / cart / checkout*, whose "knowledge sources" include a **product catalog**, and whose auth binding gates payment. Commerce is not a *new kind of thing* — it's a *fully-specified Profile*.
2. **The front door is deliberately dumb.** `ChatEntryPoint` "pins one tenant profile" and is "the Chat-channel analog of a WhatsApp/SMS sender." Its job is *routing a branded surface → a profile*. The **same** commerce profile must behave identically whether reached via web chat, a WhatsApp sender, or SMS. If "is-commerce" lived on the entry point, you'd have to re-declare it per door, and two doors to the same role could disagree — a bug factory. Routing config should never redefine the role it routes to.
3. **Composition over flags — the house style.** yaya-agentic avoids boolean modes; behavior *emerges* from what a profile is composed of. A profile is "commerce-enabled" **because** it lists commerce capabilities and binds ≥1 `ProductCatalog` — not because someone flipped `isEcommerce = true`. (We still add a small typed `commerce` block to the Profile — see below — but it's *configuration for* commerce, carried by the role, not a separate toggle living on the channel.)
4. **Auth lives with the role.** Payment/checkout scopes belong to the profile's `authBinding`, alongside the tools that need them. Splitting "commerce-ness" onto the channel would divorce the capability from the authorization that guards it.

### What each layer owns

```
ChatEntryPoint / WhatsApp sender / SMS sender   →  ROUTE ONLY: pick the profile (unchanged today)
        │  (branding, auth mode, abuse policy, "which profile")
        ▼
Profile  (the role)  ──────────────────────────►  DECIDES COMMERCE:
        │   commerce = { catalogIds[], currency, checkoutToolId, cartEnabled }
        │   capabilities = [browse_catalog, manage_cart, checkout, …]
        │   knowledgeSources = [ …product FAQ… ]   (unchanged)
        ▼
Client (chat widget)  ─────────────────────────►  RENDERS COMMERCE CHROME:
            reads the commerce block at session start → shows cart badge, enables product UiHints
```

### How the client learns it's a store (no new transport)

The client must render a persistent cart affordance the moment a commerce chat opens. `StartSessionResult` already returns `greeting` + `quickReplies` to the client at session start; we surface the profile's `commerce` block there too (a small additive field, or via `Session.profile` metadata the client already fetches). So: **the profile declares commerce, the client discovers it at session start, the entry point stays a pure router.** This is the clean, idiomatic answer and it costs one additive field on an existing response.

> **Rejected alternative — per-chat toggle at session creation.** Tempting for "let a caller spin up a one-off store," but it (a) duplicates role state onto the channel, (b) lets the same role render differently per door, (c) orphans the payment auth from the capability, and (d) violates composition-over-flags. If a customer genuinely wants two commerce variants, that's **two profiles** (or two profile versions) — which the versioned `ProfileRegistry` already supports.

---

## 4. Guiding constraints

1. **The model never touches money.** Prices, line totals, cart totals, and tax come **from the merchant's backend of record** (or, in the managed fallback, the indexed catalog), never from LLM tool arguments (non-negotiable **#1**). `add_to_cart` accepts `{productId, variantId, qty}` — *never* a price. A model that "helpfully" proposes a price is ignored; the authoritative number is whatever the merchant's cart API returns. This is the single most important rule in the doc.
2. **Two doors, one lock.** The chat path (LLM proposes a tool) and the tap path (client invokes an action) converge on the **same** `ToolExecutor` + **same** `Authorizer` chain. A tap is not a trusted shortcut — it is an untrusted proposal, exactly like a model tool call (non-negotiables **#1/#2**).
3. **Rendering is data, never prose.** Products and carts are emitted as `UiHint` payloads and rendered by the client. The model is *told* not to enumerate them in text (mirrors the quick-replies rule, non-negotiable **#8**).
4. **The framework orchestrates payment; a licensed PSP charges it — via a *managed* first-party integration, not a processor we build.** We never take a card number and never sit in the flow of funds. `checkout` creates a hosted-checkout / payment intent on the merchant's **connected account** through a blessed PSP capability (Stripe Connect-style); card data, PCI, KYC, settlement, and chargeback liability stay with the PSP. Becoming a payment processor is explicitly rejected — it *maximizes* regulatory exposure (§11).
5. **Self-hosted, zero paid services** wherever a local option exists (the standing directive from the knowledge-upload design). The cart is the merchant's own backend (or Redis in the managed fallback — already run). The catalog is the merchant's API (or Postgres + pgvector for indexed mode — already run); semantic search reuses the *existing* embedding pipeline. The one unavoidable external is the licensed PSP for money movement (§11) — a justified exception, since a money-transmitter license cannot be self-hosted.
6. **Per-catalog access, like per-source access.** A `ProductCatalog` carries an `AccessRequirement`; ineligible catalogs are silently dropped from search — existence is not leaked (mirrors non-negotiable **#7** for knowledge).

---

## 5. Goals / Non-goals

**Goals**
- A profile can be composed into a **conversational storefront**: browse → filter → cart → checkout, entirely inside the chat stream.
- Products render as **rich, tappable cards / grids**; carts render as an **editable widget**; both are `UiHint`s.
- **Instant client-side filtering** by facets that ship in the payload ("castor oil", "soap", price bands) **plus** model-driven search for anything a chip can't express — the model is never a bottleneck for a trivial filter, never absent for a hard one.
- A **single, merchant-authoritative cart** mutable from **both** chat and UI, with prices the model can't forge (the merchant's backend, not us, is the source of truth).
- **Guarded checkout** via the existing `confirmable` two-phase confirm, into a **managed PSP** integration (§11).
- Multi-tenant, access-gated catalogs; per-tenant currency.

**Non-goals**
- **Not** a payment processor. No card handling, no PCI scope (§11).
- **Not** an inventory/OMS/ERP. We integrate a merchant's product & order backend via HTTP tools; we don't own stock, fulfilment, or accounting.
- **Not** a merchandising CMS. Catalog seed + sync, product cards, and search — not a rich product-page authoring tool.
- **Not** changing the engine loop, the SPI inventory, or any sealed type's *shape* (we add `UiHint.kind` string values and, if we choose, one new `SourceLocation`-style sealed family for catalogs — a re-read-the-design action scoped in §7).

---

## 6. Design overview

```
        ┌──────────────────────────── one chat session (never left) ────────────────────────────┐
        │                                                                                        │
  shopper types                     shopper taps                          shopper taps          │
  "show me body care"              a facet chip                          − on a cart line        │
        │                          "Castor oil"                                │                 │
        ▼                               │                                      ▼                 │
  ┌─────────────┐                       │  (NO round-trip: client filters   ┌───────────────────┐│
  │  LLM turn   │                       │   the already-loaded product set  │ POST /sessions/{id}/│
  │ proposes    │                       │   using facets in the payload)    │      actions        ││
  │ search_     │                       │                                   │ {tool:remove_from_  ││
  │ products    │                       ▼                                   │  cart, args:{line}} ││
  └──────┬──────┘              instant local filter                        └─────────┬──────────┘│
         │                                                                            │            │
         ▼                         ── same lock for both doors ──                     ▼            │
  ┌──────────────────────────  ToolExecutor  ──►  Authorizer chain  ──►  typed dispatch ────────┐ │
  │  search_products (HTTP)          add_to_cart / remove / set_qty / view / clear (HTTP)       │ │
  │        │                                     │                                              │ │
  │        ▼                                     ▼                                              │ │
  │  merchant product/search API     merchant CART API  (owns cart, inventory, price, promos)  │ │
  │  → ResponseProjection →          yaya:wm:{sessionId}.cartHandle threads it; snapshot cached │ │
  │  Product[] + facets              → project response → cart   (price is the MERCHANT's word) │ │
  └──────────────┬───────────────────────────────────────────────┬────────────────────────────┘ │
                 ▼                                                 ▼                               │
      engine emits UiHint                                 engine emits UiHint                     │
   ("product_catalog", {items, facets})                  ("cart", {lines, totals})                │
                 │                                                 │                               │
                 ▼                                                 ▼                               │
        client renders a grid of                        client renders the editable               │
        tappable product cards + filter chips           cart widget + a persistent badge          │
        │                                                                                          │
        └──── "Checkout" (tap or say) → checkout tool (confirmable) → confirm UiHint → PSP hand-off┘
```

Everything above the "same lock" line is *proposal* (untrusted — model or client). Everything below is *enforcement* (framework). The product grid, the cart widget, and the checkout confirm are all `UiHint`s in the one message stream — the conversation is never left.

---

## 7. New & extended domain types

### 7.1 `ProductCatalog` — a `KnowledgeSource`-shaped primitive (`knowledge`-adjacent, new `commerce` package)

We mirror `KnowledgeSource` deliberately so operators, storage, and access-gating all feel familiar.

```java
package com.yayatechandinnovations.yayaagentic.commerce;

public record ProductCatalog(
        Ids.ProductCatalogId id,          // new typed id (core/Ids.java) — VARCHAR, tenant-scoped, versioned
        Ids.TenantId tenant,
        String displayName,
        CatalogLocation location,         // sealed — where products come from
        CatalogSyncPolicy sync,           // pull cadence / TTL for HttpApi & Database sources
        RetrievalPolicy search,           // reuse knowledge RetrievalPolicy: topK, semantic on/off
        PermissionRequirement access,     // per-catalog gate (mirrors KnowledgeSource.access, #7)
        String currency                   // ISO-4217 default; a profile may override
) {}
```

`CatalogLocation` is a **sealed** family (adding it is a re-read-the-design action; *this doc is that re-read* — the only `switch` over it is the loader registry, which selects by `supports()`, so new variants are additive):

```java
public sealed interface CatalogLocation permits Inline, HttpApi, Database {
    // Seed / test / small fixed catalogs — pasted product JSON (like SourceLocation.Inline)
    record Inline(List<Product> products) implements CatalogLocation {}

    // The common production case: the merchant already has a product API.
    // PATH-ONLY url (like HttpToolSpec) + a ResponseProjection to map their JSON → Product.
    record HttpApi(HttpToolSpec listSpec, HttpToolSpec getSpec,
                   ProductProjection projection) implements CatalogLocation {}

    // A managed products table we own & index (for merchants with no API),
    // fed by the admin console or a CSV/file import (reuses the knowledge blob/upload path).
    record Database(String logicalName) implements CatalogLocation {}
}
```

A `CatalogLoader` SPI (parallel to `KnowledgeLoader`) resolves a location to `Product`s; `search_products` runs a **hybrid** query — exact structured filters (category, attributes, price) **plus optional pgvector semantic rank** when the query is natural-language ("gift for a dry scalp"). Semantic reuse is free: products get embedded through the *existing* ingestion pipeline; no new paid dependency.

### 7.2 `Product` — the render + cart contract

```java
public record Product(
        Ids.ProductId id,
        String title,
        String shortDescription,
        Money price,                       // {amountMinor:long, currency} — integer minor units, never float
        List<String> imageUrls,
        String category,                   // primary facet
        Map<String,String> attributes,     // facet source: {"ingredient":"castor oil","form":"soap", ...}
        List<Variant> variants,            // size/scent/etc.; each variant carries its own price/sku/stock
        Availability availability,         // IN_STOCK | LOW | OUT | PREORDER
        String detailUrl                   // optional deep link to a full product page
) {
    record Variant(String variantId, String label, Money price, String sku, Availability availability) {}
}
```

**Money is integer minor units** (cents) with an explicit currency — never a floating-point price, never a model-supplied number. This is the type that makes constraint #1 enforceable.

The same `Product` payload backs **two render states** — a *compact* card (hero image, title, price, one CTA) shown inline in the stream, and an *expanded* card (full gallery, description, variants, qty stepper) shown on tap — so expanding is a client-side view change, not a refetch (§10). The record carries enough for both; for large catalogs the compact fields can ship light and heavy detail can be lazy-loaded via `get_product` on tap.

### 7.3 The cart — merchant-owned by default; one handle in working memory

The cart is **not** the model's memory and, by default, **not even ours** — the merchant's commerce backend owns it (§8). yaya-agentic keeps one small key in `WorkingMemory` (Redis, idle-TTL): the **cart handle** (the merchant's cart id/token), plus the last-rendered snapshot for prompt context and instant re-display across turns:

```jsonc
// working memory: cartHandle + cached snapshot  (source of truth = merchant backend)
{
  "cartHandle": "mrc_cart_9f3…",              // opaque merchant cart id/token
  "snapshot": {
    "currency": "USD",
    "lines": [ { "lineId": "l1", "productId": "p_44", "title": "Castor Oil Soap",
                 "image": "https://…", "qty": 2, "unitPriceMinor": 1299, "lineTotalMinor": 2598 } ],
    "subtotalMinor": 2598, "updatedAt": "2026-07-01T12:00:00Z"
  }
}
```

Every mutation calls the merchant's cart API (passing `cartHandle`) and refreshes the snapshot from its response — so price and total are always the merchant's authoritative numbers, never the model's and never a stale local compute. (Tax/shipping come from the merchant at `checkout`.) In the *managed fallback* (§8), the same `snapshot` shape is the cart of record, priced from the indexed catalog.

> **Durability note.** For merchant-owned carts the merchant's backend *is* the durable store; we persist `cartHandle` + snapshot alongside the turn (via `ConversationRecorder`) so a returning shopper reconnects to the same merchant cart (abandoned-cart recovery, §16). For the managed fallback, Redis is the hot path and the optional `carts` projection table (§14) is the durable fallback — the split the codebase already uses for session state.

### 7.4 `commerce` block on `Profile` (additive)

```java
// Profile gains one nullable field (all existing profiles: commerce == null → not a store)
record CommerceConfig(
        List<Ids.ProductCatalogId> catalogs,   // like knowledgeSources, but for products
        String currency,                        // profile-level currency (overrides catalog default)
        Ids.ToolId checkoutTool,                // which tool finalizes an order (HTTP, confirmable)
        boolean cartEnabled                     // renders the persistent cart affordance
) {}
```

This is the *only* Profile change, it's nullable/back-compatible, and it's what `StartSessionResult` surfaces to the client (§3). "Is this a store?" == `profile.commerce != null && cartEnabled`.

---

## 8. Commerce tools — HTTP-first (yaya-agentic is an extension, not an embed)

yaya-agentic is a SaaS that merchants **wire to their existing systems** (Shopify, WooCommerce, Medusa, commercetools, or a custom backend) — they don't write Java handlers inside the framework. So the commerce tools are **`HttpToolSpec`-backed by default**: each calls the merchant's commerce backend, and a `ResponseProjection` (the mechanism HTTP tools already use, plus the `ProductProjection` in §7.1) maps the merchant's JSON into the **normalized `Product` / `cart` contract** the client renderers expect. Configuration is pure admin-console data — URL template, projection, `AuthForwarding` — **no code, no deploy.**

They remain ordinary `ToolDescriptor`s: exposed to the LLM only when the profile lists them (non-negotiable **#2**) and guarded by the Authorizer chain. **The merchant backend owns the cart** (it's the only system that truly knows inventory, price, promotions, tax); yaya-agentic holds only a **cart handle** — the merchant's cart id/token — in `WorkingMemory` (`yaya:wm:{sessionId}.cartHandle`) to thread it across turns, plus the last snapshot for prompt context. This makes prices *even harder* for the model to forge: they come from the merchant's authoritative response, not from the model and not from us.

| Tool | Input (model may supply) | HTTP call → projection | Emits |
|---|---|---|---|
| `search_products` | `{query?, category?, attributes?, priceMax?, page?}` | `GET/POST` merchant search API across the profile's `catalogs` (access-gated per catalog); project → `Product[]` + facets | `product_catalog` UiHint (items **+ facets**) |
| `get_product` | `{productId}` | `GET` merchant product API; project → full `Product` | `product_card` UiHint |
| `add_to_cart` | `{productId, variantId?, qty}` | `POST` merchant cart API (with `cartHandle`); merchant returns the authoritative cart → project | `cart` UiHint |
| `remove_from_cart` | `{lineId}` | `POST/DELETE` merchant cart API; project returned cart | `cart` UiHint |
| `set_cart_quantity` | `{lineId, qty}` | `POST/PATCH` merchant cart API; project returned cart | `cart` UiHint |
| `view_cart` | `{}` | `GET` merchant cart API (`cartHandle`); project | `cart` UiHint |
| `clear_cart` | `{}` | `POST/DELETE` merchant cart API; project | `cart` UiHint |
| `checkout` | `{fulfilment?, note?}` | `confirmable=true`; on confirm, `PaymentProvider` creates a payment intent / checkout session on the merchant's connected account (§11) | `confirm` UiHint (existing) → `checkout_result` |

Notes:
- **`add_to_cart` never trusts a price.** Its input schema has no `price` field; the total is whatever the merchant's cart API returns, projected into `cart`. The model names a product + qty; price and total are the merchant's word — Constraint #1, now enforced by an external authority.
- **Explicit `AuthForwarding` on every call** (non-negotiable **#6**) — typically `SERVICE_TOKEN` to the merchant backend, or `PRINCIPAL_TOKEN` when the shopper is authenticated to their own account so cart/order bind to them. Never silent.
- **`checkout` is `confirmable`.** The money-mover triggers the *existing* two-phase `confirm` UiHint — "Place order — 2 items, $25.98?" — before `PaymentProvider` creates the charge intent (§11).
- **Follow-up hints** ("View cart", "Keep shopping", "Checkout") ride the existing `Capability.followUpHints` → `quick_replies` mechanism. No new machinery.

### Who owns the cart — HTTP (default) vs managed (fallback)

- **HTTP / merchant-owned (default, recommended).** The merchant already runs a commerce backend with a cart; yaya-agentic threads its `cartHandle` and renders its responses. One source of truth, always-fresh inventory/price/promotions, and yaya-agentic stays a thin conversational layer — the extension model you want.
- **Managed / yaya-agentic-owned (optional fallback).** For a merchant with *only a product feed and no cart API* (an `Inline`/`Database` catalog), yaya-agentic can run the cart itself: the cart tools become framework `Bean` handlers over a Redis cart (`yaya:wm:{sessionId}.cart`), pricing re-derived from the indexed catalog. This is the original in-house cart, demoted to a fallback and selected by the catalog's location kind. Checkout still hands off via `PaymentProvider`.

Both modes emit the identical `cart` UiHint, so the client renderer and the `POST /actions` channel don't care which is in play.

### New capabilities (what operators compose)

```
browse_catalog : tools=[search_products, get_product]        followUpHints=["Add to cart","Show soaps only"]
manage_cart    : tools=[add_to_cart, remove_from_cart, set_cart_quantity, view_cart, clear_cart]
checkout       : tools=[checkout]     requires: payment scope on the profile's authBinding
```

A profile is a store when it lists these (or a subset) and binds a catalog. Nothing else flips.

---

## 9. Two doors, one lock — the client-initiated action channel (the one genuinely new surface)

The shopper must be able to edit the cart by **tapping** (−/＋ on a cart line, "Remove", "Checkout") — not only by talking. A tap must **not** bypass authorization or the tool executor. So we add a single endpoint that lets the *client* invoke a **whitelisted** commerce tool directly, through the exact same enforcement path as an LLM tool call:

```
POST /v1/sessions/{id}/actions
  body: { tool: "remove_from_cart", args: { lineId: "l1" } }
  → resolve session + profile + principal (same as a message)
  → GUARD: tool ∈ profile capabilities?              (non-negotiable #2 — else 403, no leak)
  → GUARD: tool.policy.clientInvocable == true?       (NEW flag — see below)
  → Authorizer.authorize(principal, tool.requires, args, ctx)   (non-negotiable #1)
  → ToolExecutor.execute(...)  → HTTP call to merchant cart API (same tool the LLM would call)
  → record the turn via ConversationRecorder          (engine never writes storage directly, #4)
  → stream back TurnEvent(s): ToolResult + cart UiHint  (same SSE shape as a message turn)
```

Two safety additions:

1. **`ToolPolicy.clientInvocable` (new boolean, default `false`).** Not every tool may be tapped directly. Cart edits: `true`. A raw `charge_card`: `false` — it must go through the model + confirm flow. This is the tap-path analog of "out-of-profile tools are never sent to the LLM": *non-client-invocable tools are never reachable from the action channel.* Default-false keeps every existing tool locked.
2. **`checkout` via the action channel still confirms.** Tapping "Checkout" invokes `checkout` (which is `confirmable`), so the client still gets the `confirm` UiHint and an explicit approval before the PSP hand-off. Tap starts it; it does not skip the guardrail.

This channel is small and mirrors an existing pattern: the confirm flow *already* has the client send back an approval that runs a pending tool. `POST /actions` generalizes "client tells the engine to run a specific, permitted tool." **One lock (executor + authz), two doors (chat, tap).**

---

## 10. Rendering & filtering UX — compact cards, expand-in-place, live filters

### 10.1 Two render states (progressive disclosure) — the chat is a short-space surface

Chat is vertically constrained, so a full product card per item would flood the stream. Products render in **two states over one payload**:

- **Compact card** (inline): hero image + title + price + one primary CTA (**Add** / **View**). Multiple compacts render as a **horizontal, swipeable carousel** (the WhatsApp/Messenger product-carousel pattern) — space-efficient and thumb-friendly. This is what `product_catalog` paints.
- **Expanded card** (on tap): full image gallery, full description, variant selector, quantity stepper, availability, Add-to-cart. Rendered as an **in-place overlay / bottom sheet on top of the chat** — the conversation stays visible and live underneath. *Never* a navigation away. This is `product_card`.

The rule that keeps it snappy and on-promise: **expansion is a client-side view change, not a round-trip.** The `product_catalog` payload carries enough for compact render *and* inline expansion. Only for very large catalogs (where shipping full detail for every card is wasteful) does a tap trigger `get_product` (a client-invocable action, §9) to lazy-load heavy detail — and even then the chat never yields. These map to three new **message renderers** in the client: `ProductCarousel` (compact), `ProductCardExpanded`, `CartWidget` (§15).

### 10.2 "Never kill the LLM interaction" — the filtering UX

This is the experience you emphasized: browse a catalog, filter it ("castor oil ones", "just soaps"), keep chatting — the model is never a bottleneck for a trivial filter and never absent for a hard one. We get this by making **facets travel with the payload** and supporting both filtering modes:

**`product_catalog` UiHint payload:**
```jsonc
{
  "items": [ /* Product cards: id,title,price,image,category,attributes,availability */ ],
  "facets": [                                   // ← derived server-side from the returned set
    { "key": "ingredient", "label": "Ingredient",
      "values": [ {"v":"castor oil","count":12}, {"v":"shea","count":7} ] },
    { "key": "form", "label": "Type",
      "values": [ {"v":"soap","count":9}, {"v":"oil","count":6} ] },
    { "key": "price", "label": "Price",
      "buckets": [ {"label":"< $10","max":1000}, {"label":"$10–20","min":1000,"max":2000} ] }
  ],
  "page": { "index": 0, "hasMore": true },
  "query": { "echo": "body care" }              // so the client can show "12 results for body care"
}
```

- **Trivial filter → instant, no round-trip.** The client renders `facets` as tappable chips. Tapping "Castor oil" or "Soap" filters the *already-loaded* items locally — **zero latency, zero model call.** The conversation stays live the whole time; the input box never yields.
- **Hard filter → the model.** "Something under $20 for a dry scalp" isn't a chip; the shopper just types it. The LLM calls `search_products` with `{query, priceMax:2000}`, and the engine emits a *new* `product_catalog` UiHint that updates the grid. Same widget, refreshed.
- **Pagination** rides the same tool (`page`) or a client-invocable `search_products` action ("Load more") — either door.

Why this is the right split: chips are for the *predictable* dimensions the data exposes (category, ingredient, form, price band) — instant and free. The model is for *intent* the data doesn't index ("gift-worthy", "for sensitive skin", "matches what I bought last time"). The shopper never has to choose which one to use — both are always available in the same surface. **That's** never killing the interaction: the deterministic UI handles the fast path, the model handles the smart path, and neither one blocks the other.

**Prompt rule (non-negotiable #8 analog, §7 prompt composition):** the profile/personality system prompt instructs the model — *"When you surface products or the cart, the UI renders them as cards; give a one-line caption, do NOT list products, prices, or cart contents in prose."* The model captions ("Here's our body-care range — tap any to see details, or tell me what you're after"); it does not retype the grid. This keeps the model out of price-narration (constraint #1) and keeps the render authoritative.

### 10.3 Where the cart lives in the UI — your question, answered

You asked where the cart icon sits for a commerce profile and whether it should be always visible once shopping starts. Recommendation: **two coordinated surfaces, not one** — because a chat has both a *frame* (header/composer, always on screen) and a *stream* (scrolls away).

1. **Persistent cart button in the chat header (top-right), with a live badge.** Shown for the whole session the moment the `commerce` block says `cartEnabled` (§3) — *before* the first add, so the affordance also signals "this is a shop." The badge shows item count (and optionally subtotal) once the cart is non-empty. Tapping opens the full `CartWidget` as a **bottom sheet / side panel over the live chat** (same expand-in-place pattern as product cards, §10.1) — never a navigation away. This is the always-reachable anchor, independent of scroll position. It's also the conventional place every shopper already looks (top-right = cart).

2. **A sticky mini-cart bar above the composer, appearing once the cart has items** — a slim `🛒 2 items · $25.98 · Checkout →` strip pinned just above the text input. This is the better-than-just-an-icon suggestion: on a phone-shaped chat it's **thumb-reachable**, it keeps the running total in view while the shopper keeps browsing, and it doubles as the primary **checkout CTA** without the shopper hunting for the header. It collapses to nothing when the cart is empty, so it never steals space pre-shopping.

Plus the **inline `cart` UiHint** already in the stream after every mutation ("Added ✓ — cart now 2 items, $25.98", with quick − / Checkout actions) gives *contextual* confirmation in the flow. So: inline hint = *what just changed*; sticky bar = *ambient total + checkout*; header button = *always-reachable full cart*. All three open the same `CartWidget` and are wired to the same `POST /actions` channel (§9).

Why not a single always-on floating button (FAB)? A FAB floats over the message stream and collides with product carousels and the composer on a narrow chat surface. The header + sticky-bar split keeps the stream clean and puts the checkout CTA exactly where the thumb is.

---

## 11. Checkout & payment — a managed PSP capability, never a processor

You raised the real risk: if every merchant wires their *own* payment service via a raw HTTP tool, we get N inconsistent integrations, each a fraud/regulatory surface we don't control. The fix is **not** to build payment processing into yaya-agentic — that *maximizes* the exposure you want to avoid. The fix is to make the *blessed, first-party* payment path the *easy* path, built on a **licensed PSP's platform model**.

Three readings of "payment as part of yaya-agentic" — only the middle one is right:

| Option | What it means | Verdict |
|---|---|---|
| **A. Be the processor** | yaya-agentic sits in the flow of funds / is a PayFac / money transmitter | **No.** PCI-DSS Level 1, per-state money-transmitter licensing, KYC/AML, acquiring-bank relationships, settlement, reserves, chargeback liability. A company-defining regulated pivot — the *opposite* of avoiding regulatory burden. |
| **B. Managed PSP capability** | yaya-agentic owns ONE vetted integration to a licensed PSP's **platform** product (Stripe Connect / Adyen for Platforms). Merchants onboard as connected accounts via the PSP's hosted KYC. | **Yes — recommended.** First-party, consistent, fraud tooling on by default, optional platform fee (revenue); the *PSP* carries funds, PCI, KYC, settlement, chargebacks. |
| **C. BYO hand-off** | each merchant configures their own PSP via a raw `checkout` HTTP tool (the framework's generic HTTP-tool path) | **Keep as an advanced escape hatch** — off by default, explicit risk ownership — never the blessed path. |

### Why B answers your concern

- **"Avoid regulatory things"** → the *licensed PSP* holds the licenses; yaya-agentic stays a software orchestrator, never in the flow of funds, never touching card data (hosted checkout / PSP SDK). Building processing *in* (A) does the reverse.
- **"Fraud from people introducing their own service"** → the blessed path is one integration with the PSP's fraud engine (e.g. Stripe Radar) on by default. Make the safe path the default; gate/warn on C.
- **Speed to money + consistency** → "turn on payments" = the merchant completes the PSP's hosted onboarding once; no hand-rolled payment glue.
- **Platform revenue** → a Connect-style `application_fee` lets yaya-agentic monetize GMV *without* becoming a transmitter.

### How it plugs into the existing spine (unchanged)

`checkout` stays a `confirmable` tool. What changes is *who* it calls — a first-party `PaymentProvider`, not a merchant-supplied URL:

```
shopper: "checkout"  (say or tap)
  → checkout tool (confirmable, cart total is merchant-authoritative)
  → confirm UiHint: "Place order — 2 items, $25.98. Confirm?"    (existing two-phase confirm)
  → on confirm → PaymentProvider.createIntent(tenant, connectedAccount, cartTotal, currency)
       └─ creates a PSP Checkout Session / PaymentIntent ON THE MERCHANT'S CONNECTED ACCOUNT
  → returns ONE of:
       (a) hosted-checkout URL      → client opens the PSP's PCI-compliant payment page
       (b) payment intent / secret  → client mounts the PSP drop-in (Stripe/Adyen/etc.)
       (c) order id + invoice/COD   → checkout_result UiHint, cart cleared
  → PSP handles card entry, 3-DS, fraud, capture, settlement (funds → merchant; optional fee → platform)
  → PSP webhook → order status → cart cleared, turn recorded (auditable via ConversationRecorder)
```

A new **`PaymentProvider` SPI** abstracts the PSP (default `StripeConnectPaymentProvider`; `DLocalPaymentProvider` for the Dominican Republic and LatAm, where Stripe has no coverage; Azul/CardNet as a direct-acquirer fallback — see open Q#7) — the same SPI discipline as `BlobStore` / `Retriever`. Card data never enters yaya-agentic; funds route PSP→merchant; the framework orchestrates (assembles the order from the cart of record, gates it behind confirm) and optionally skims a platform fee.

### Liability tier

Prefer the PSP's **least-liability** connected-account model (Stripe **Express/Standard**, where the connected account is merchant-of-record and Stripe carries most compliance/liability) over Custom. yaya-agentic is a *platform*, not the merchant of record.

### Honest caveats

B is not zero-obligation: a platform agreement with the PSP, facilitating connected-account onboarding, and tax on *your* platform fees are real — but modest, and orders of magnitude below A. And this is a **justified exception to the self-hosted-OSS constraint** ([[self_hosted_no_paid_services]]): regulated money movement *cannot* be self-hosted — a licensed provider is mandatory. That's a different category from MinIO-vs-S3; the constraint is about avoiding paid infra where OSS suffices, not about self-hosting a money-transmitter license (which is not a thing that exists).

---

## 12. Trust boundaries — mapped to the non-negotiables

| Boundary | Control | Non-negotiable |
|---|---|---|
| Model proposes prices/totals | Ignored. `add_to_cart` schema has no price; handler re-derives `Money` from catalog. Totals computed server-side each mutation. | **#1** |
| Model calls a store tool for a non-store profile | Impossible — tools not in the profile's capabilities are never sent to the LLM. | **#2** |
| Client taps to invoke a tool | `POST /actions` requires tool ∈ profile **and** `clientInvocable==true` **and** passes Authorizer. Tap = untrusted proposal. | **#1/#2** |
| Product data / catalog API responses as instructions | Products are rendered as data in `UiHint` payloads and wrapped as untrusted in any prompt context; never interpreted as instructions. | **#5** |
| Checkout forwards a token | `AuthForwarding` explicit per `checkout` spec (`NONE`/`PRINCIPAL_TOKEN`/`SERVICE_TOKEN`). Never silent. | **#6** |
| Catalog visibility | `ProductCatalog.access` runs the Authorizer per catalog; ineligible catalogs silently dropped from search — existence not leaked. | **#7 (analog)** |
| Rendering trust | Grid/cart/checkout are engine-emitted `UiHint`s; the model is told not to format them inline. | **#8** |
| Storage access | Cart lives in `WorkingMemory`; order history through `ConversationRecorder`. Engine never writes storage directly. | **#4** |
| Money movement | `checkout` is `confirmable` (two-phase confirm) and non-bypassable; the framework is never in the flow of funds; PCI/KYC/settlement/liability sit with a licensed PSP (managed integration, not a processor we build). | §11, §12 safety |

---

## 13. Prompt composition (§7)

Commerce adds three things to the prompt, in the **existing** Personality → Profile → variable-suffix order (do not reorder — it's tuned for prompt caching):

1. **Personality/profile rule (stable prefix):** the "don't narrate products/cart in prose; the UI renders them; caption only" rule. Stable → cached.
2. **Capability catalog + tool schemas (stable):** the commerce capabilities/tools, unchanged from how every tool is exposed.
3. **Cart state (variable suffix):** the current cart summary (item count, subtotal — *not* a re-listing) is injected as working-memory context so the model can reason ("you have 2 items; want to check out?") without re-deriving it. Goes in the variable region so the cached prefix isn't invalidated per mutation.

---

## 14. Data model / migrations (next free = **V53+**; V52 is current)

Reuse first; add only what's genuinely new. IDs follow the codebase split: **domain config ids are `VARCHAR`** (tenant/profile/tool style), **session-scoped rows are `UUID`**.

- **`product_catalogs`** (`VARCHAR` id, versioned like `profiles`/`tools_registry`): `tenant_id`, `id`, `version`, `display_name`, `location_json` (jsonb — the `CatalogLocation`), `sync_json`, `search_json`, `access_json`, `currency`, `status`, timestamps. Mirrors `profiles`/`knowledge_sources` conventions exactly.
- **`products`** (only when `CatalogLocation.Database`): `tenant_id`, `catalog_id`, `product_id`, `title`, `price_minor BIGINT`, `currency`, `attributes_json`, `variants_json`, `availability`, `detail_url`, `embedding vector(…)` (reuse pgvector for semantic search), search/facet indexes. `HttpApi`/`Inline` catalogs need no rows.
- **`carts`** (optional durable projection for abandoned-cart recovery, §16): `session_id UUID PK`, `tenant_id`, `profile_id`, `cart_json jsonb`, `updated_at`. Redis stays the hot path; this is the durable fallback + analytics source. If we defer recovery, we skip this table (cart lives only in Redis + turn history).
- **`orders`** (optional, if we track order outcomes locally rather than only in the merchant's system): `id UUID`, `session_id`, `tenant_id`, `external_order_id`, `amount_minor`, `currency`, `status`, `created_at`.
- **`Profile.commerce`**: additive `commerce_json jsonb` column on `profiles` (nullable) — a new migration, no backfill (null = not a store).

No change to `sessions`/`turns`/`intent_frames`. No change to any sealed type's *shape*; `UiHint.kind` gains string values only.

---

## 15. Admin console & config surface (Flutter)

The admin console already configures profiles, capabilities, tools, and knowledge sources; commerce slots into the same screens:

- **Catalog screen** (new, modeled on `knowledge_sources_screen.dart`): create a `ProductCatalog`, choose a `CatalogLocation` (`INLINE` seed / `HTTP_API` map-a-response / `DATABASE` upload-a-CSV via the existing blob/upload path), set currency, set `access`, trigger sync/index, see status badge — all reusing the knowledge-source UX patterns.
- **Profile editor:** a "Commerce" section that binds catalog(s), currency, the checkout tool, and `cartEnabled` → writes the `commerce` block. This is the screen where an operator turns a profile into a store — **the single place the decision (§3) is made.**
- **Capability/tool editor:** commerce tools are ordinary tools; the only new field surfaced is `clientInvocable` (a checkbox) and, for `checkout`, `confirmable` (already exists).
- **Playground:** the existing test playground renders the new `UiHint` kinds (product grid, product card, cart) so operators can exercise browse → filter → cart → checkout end-to-end via the same SSE API a production client uses.

The web *chat widget* (production surface, not in this repo) reads the `commerce` block from `StartSessionResult` and renders cart chrome + three new **message renderers** in the stream: `ProductCarousel` (compact, swipeable) → tap opens `ProductCardExpanded` as an in-place overlay/sheet with the chat still live underneath (§10.1), and `CartWidget` (editable, wired to the `POST /actions` channel §9). Progressive disclosure keeps the short-space chat uncluttered while every product stays one tap from full detail.

---

## 16. Value-add ideas (beyond the ask — these are what make customers *money*)

These are natural, cheap extensions once the primitives above exist; call out for prioritization:

1. **Assisted cross-sell / upsell.** After `add_to_cart`, the model (given catalog access) suggests complements ("pairs with our shea butter") via a `product_catalog` UiHint. Pure prompt + tool reuse; measurable AOV lift.
2. **Abandoned-cart recovery.** With the durable `carts` projection (§14) and the existing outbound channels (WhatsApp/SMS senders), a scheduled job re-engages idle carts ("still want your 2 items?"). This is where the durable cart pays off.
3. **Personalized re-order.** If the shopper is authenticated (principal), `PRINCIPAL_TOKEN` to the merchant order API surfaces "buy it again" — reusing the exact auth-forwarding path.
4. **Semantic merchandising.** The pgvector product embeddings enable "shop by vibe" ("something calming, under $20") — the smart-path filter §10 already leans on.
5. **Facet analytics.** Because facets are computed server-side, we can log which chips shoppers tap → tells the merchant what to stock. Zero extra instrumentation.
6. **Inventory-aware messaging.** `Availability` in the product model lets the UI grey out OUT items and the model say "that one's out — here's a close match," instead of adding phantom stock.

---

## 17. Resolved decisions

| # | Question | Decision |
|---|---|---|
| 1 | **Where is "is-commerce" decided?** | **Profile-level** — a `commerce` block on `Profile` (catalogs + currency + checkout tool + cartEnabled). The chat entry point only routes to the profile; it never decides commerce (§3). |
| 2 | Product rendering | **`UiHint` kinds** (`product_catalog`, `product_card`, `cart`) — engine-emitted structured data, client-rendered. Model captions, never enumerates (§10, #8). |
| 3 | Tool transport | **HTTP-first** — commerce tools are `HttpToolSpec`s pointing at the merchant's store API (yaya-agentic is an *extension*, not an embed; no code, no deploy). Bean handlers only in the managed fallback (§8). |
| 4 | Cart ownership & storage | **Merchant-owned by default** — the merchant backend is the cart of record; `WorkingMemory` threads a `cartHandle` + snapshot. Managed Redis cart is a fallback for merchants with no cart API (§7.3, §8). |
| 5 | Cart editable from chat **and** UI | **Yes** — a `POST /sessions/{id}/actions` channel invokes whitelisted (`clientInvocable`) tools through the same executor + Authorizer as chat. Two doors, one lock (§9). |
| 6 | Price integrity | **Merchant-authoritative** — `add_to_cart` has no price field; price/total come from the merchant's cart API response; `Money` in integer minor units. The model never touches money (§4 #1, §12). |
| 7 | Filtering UX | **Facets travel in the payload** → instant client-side chip filtering for trivial cases; model-driven `search_products` for intent the chips can't express. Neither blocks the other (§10). |
| 8 | Catalog primitive | **`ProductCatalog` mirrors `KnowledgeSource`** — sealed `CatalogLocation` (`Inline`/`HttpApi`/`Database`), per-catalog `access`, reuse pgvector for semantic search (§7.1). |
| 9 | Payment | **Managed PSP capability, never a processor** — a blessed first-party `PaymentProvider` integration to a licensed PSP's platform model (Stripe Connect-style) is the default; `checkout` stays `confirmable`; funds/PCI/KYC/liability stay with the PSP; BYO PSP is an off-by-default escape hatch; becoming a processor is rejected (§11). |
| 10 | Product card rendering | **Two-state progressive disclosure** — a compact card in a swipeable carousel expands in-place (overlay/sheet, chat stays live) from payload data; heavy detail lazy-loaded via `get_product` only when needed. New client renderers: `ProductCarousel`, `ProductCardExpanded`, `CartWidget` (§10.1, §15). |
| 11 | Cart UI placement | **Header cart button + badge** (always on for a commerce profile) **plus a sticky mini-cart bar above the composer** (appears with items, doubles as checkout CTA) **plus inline `cart` hints** after each change — all open the same `CartWidget` (§10.3). |
| — | Cost posture | Reuse Redis + Postgres + pgvector + the existing embedding pipeline. No new paid dependency introduced by this design (§4 #5). |

---

## 18. Open questions

1. **Guest vs authenticated shoppers.** Web chat allows anonymous sessions; checkout for a guest needs either a merchant guest-checkout flow or a lightweight identity capture. Which do we require, and does the profile's `authMode` gate it?
2. **Catalog freshness for `HttpApi`.** Live pass-through per query (freshest, slower, no semantic search) vs periodic sync into `products` (fast + semantic, staleness window). Per-catalog `CatalogSyncPolicy` lets an operator choose — default?
3. **Multi-catalog merge & currency.** If a profile binds two catalogs with different currencies, do we forbid it, or require a profile-level currency and reject mismatches at bind time? (Leaning: profile currency wins; bind-time validation.)
4. **Cart TTL vs abandoned-cart recovery.** ✅ **Resolved / shipped.** The durable `commerce_carts` projection (V57) is written-through on every cart mutation, so the cart survives the working-memory idle-TTL and the PSP webhook can clear it after expiry. The outbound *recovery* sweep (re-engage idle carts over WhatsApp/SMS) is the remaining growth piece (§16.2).
5. **Tax/shipping display before checkout.** Do we call the merchant backend for a quote at cart time (accurate, chatty) or only at checkout (simple, "final total at checkout")? Leaning: quote at checkout, show "+ tax/shipping" note earlier.
6. **`checkout_result` as a new UiHint kind** — ✅ **Resolved / shipped** as its own `kind` (`checkout_result`), carrying `orderRef` + authoritative `amountMinor`/`currency` + a `mode` of `hosted_url` | `payment_intent` | `order_placed`. Never any card data.
7. **PSP choice & regions.** The `PaymentProvider` SPI is per-tenant/per-region and swappable. **Default = Stripe Connect** for supported markets (US/EU/MX/BR/…). **Dominican Republic (and LatAm broadly) = dLocal** (`DLocalPaymentProvider`): it's the Connect-equivalent platform PSP — onboarding, sub-accounts, split payments, one API — and, as the *local* merchant-of-record, it keeps yaya-agentic out of DR regulatory scope. Stripe does **not** support DR business signup, so a DR merchant cannot be their own Stripe connected account. **Azul (Banco Popular) / CardNet** are an optional "bring-your-own-local-acquirer" fallback for a merchant that already banks locally and wants to be their own MoR — lower local fees, but per-merchant integration and dated DX. *Open:* which region ships after DR, and confirm with dLocal that the **Platforms/split** module (not just collections) is enabled for DR + its pricing before committing.
8. **Do we ever allow BYO payment (option C)?** Keeping the raw-HTTP `checkout` escape hatch is flexible but re-opens the inconsistency/fraud surface. Ship it off-by-default with a risk acknowledgement, or forbid it entirely and require the managed capability?
9. **Compact payload weight.** Ship full product detail inline for instant expansion (heavier `product_catalog` payloads) vs lazy-load via `get_product` on tap (lighter stream, one extra action on expand)? Likely per-catalog, tuned by catalog size.

---

## 19. Rollout (ordered by dependency; every phase production-grade)

1. **Domain + contracts.** `Ids.ProductCatalogId`/`ProductId`, `Product`/`Money`, normalized `cart` contract + `cartHandle` threading in `WorkingMemory`, `ProductCatalog` + `CatalogLocation` (sealed), `commerce` block on `Profile` + `commerce_json` migration. The managed **Bean** cart (fallback) proves the `cart` UiHint + price-integrity spine without any external dependency. Playground renders `cart`. *Proves the shape; no merchant wiring yet.*
2. **HTTP commerce tools + rendering.** Commerce tools as `HttpToolSpec`s with `ResponseProjection` → merchant store API (the default transport); `search_products`/`get_product`/cart ops, `product_catalog`/`product_card`/`cart` UiHints **with facets**, `product_catalogs` migration, `HttpApi` catalog location + explicit `AuthForwarding`; optional `Database`/pgvector indexed mode for semantic rank. Client-side facet filtering in the playground. *The extension model + "browse → filter → cart without leaving chat" lands here.*
3. **Two doors.** `ToolPolicy.clientInvocable` + `POST /sessions/{id}/actions` through executor + Authorizer; cart editable by tap. *Dual-control lands.*
4. **Checkout.** `PaymentProvider` SPI + `StripeConnectPaymentProvider` (managed, connected-account model), merchant onboarding via the PSP's hosted KYC; `checkout` tool (`confirmable`) calls it, confirm UiHint reuse, hosted-checkout/intent return, PSP webhook → `checkout_result`, optional `orders` table, cart clear on success. *Money moves — behind the confirm gate, via a licensed PSP, never the framework.*
5. **Admin + growth.** Flutter catalog screen + profile commerce section + `clientInvocable` checkbox; durable `carts` projection; abandoned-cart recovery over existing WhatsApp/SMS senders; cross-sell prompts; facet analytics (§16).

Phases 1–2 deliver the demoable heart (browse → filter → cart, prices the model can't forge); 3–4 make it transact; 5 makes it a product and makes customers money.

---

## 20. Implementation status (backend complete)

The backend/framework side of all five phases is implemented and tested (unit + Testcontainers integration), seeded into a runnable demo (`retail-store@1`):

| Phase | What landed |
|---|---|
| 1 | `Money`/`Product`/`ProductCatalog`/`CatalogLocation` (sealed), `Cart` + `CartStore` (working-memory threading), `ManagedCart` (price re-derived from the catalog, never the model), `CommerceConfig` on `Profile` (+ `commerce_json`, V54). |
| 2 | `FacetComputer`, `ProductProjector` (merchant JSON → `Product`, rejects float prices), `CatalogLoader` SPI + `InlineCatalogLoader`, `ProductCatalogRegistry` (+ `product_catalogs`, V55), `ProductSearchService` (per-catalog access gating), 7 managed Bean commerce tools, `PendingUiHints` → engine drain emitting `product_catalog`/`product_card`/`cart`. |
| 3 | `ToolPolicy.clientInvocable`, `POST /v1/sessions/{id}/actions` → same `ToolExecutor` + Authorizer, membership + invocability guards (403, no leak). |
| 4 | `PaymentProvider` SPI, `DLocalPaymentProvider` (sandbox default, live V2-HMAC-SHA256), confirmable `checkout` tool, `checkout_result` UiHint, `commerce_orders` (V56). |
| 5 (backend) | Durable `commerce_carts` projection (V57, write-through), `PaymentStatusService`, `POST /v1/commerce/webhooks/dlocal` (PENDING → PAID + cart clear), `CatalogAdminController` (`/v1/admin/product-catalogs`), profile `commerce` block on the admin profile API. |
| 5 (Flutter) | Playground renders all four commerce UiHint kinds (product carousel + instant facet filtering, expanded card, cart bar + editable sheet, checkout result) and drives the action channel; admin **Product Catalogs** screen; profile-editor **Commerce** section (bind catalogs + currency + checkout tool + cart-enabled); `clientInvocable` switch in the tool editor. |

**Remaining (growth + go-live):** the outbound **abandoned-cart recovery** sweep over the WhatsApp/SMS senders (the durable `commerce_carts` source now exists); **cross-sell** prompts and **facet analytics** (§16); and **live-dLocal verification** of the payment/webhook signing + payer/country fields against a real sandbox account (open Q#7). The production end-user chat widget is, by design, not in this repo — the admin playground is the in-repo surface, and it now renders the full flow.
