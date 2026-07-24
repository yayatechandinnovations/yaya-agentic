# Milestone S7 — Decouple the store domain from yaya-agentic

**Goal:** Remove all remaining store/commerce vocabulary and data from
`com.yayatechandinnovations.yayaagentic.*` so the framework is genuinely
domain-neutral. The store domain lives only in `apps/yaya-sales`, reached through
the published seams (HTTP `sales.*` tools, `_uiHint` pass-through, and a generic
profile-extension client trait). Finishes the decommission tail begun by `V73`.

**Status:** 🟩 Code decoupling done (Phase 0–4 + docs); Phase 5 data move is an
operational maintenance-window step (runbook shipped).
**Depends on:** yaya-sales S0–S6 (done); `V73__decommission_commerce.sql` (done)

> **Landed:** `CoreDomainNeutralityTest` guard; `Profile.extensions` replacing
> `CommerceBinding` (deleted) across core/persistence/API + `V74`; `demos/retail`
> removed; both Flutter apps read the `sales.store` trait; design §12.3/§16 Q1 +
> CLAUDE.md updated. Phase 5 (data move) tooling **already exists** —
> `apps/yaya-sales/deploy/migrate/{export,import,drop}-legacy-commerce.*` +
> `apps/yaya-sales/docs/RUNBOOK.md` (10-step cutover, opening-balance seed §12.2,
> reconciliation-gated manual drop). Execution is an operator maintenance-window
> step, not run here.
**Design refs:** yaya-sales design §11 (integration seams), §12 (migration),
§16 Q1 (profile-shape decision — **revised here**); CLAUDE.md non-negotiable
"domain → app; core stays domain-neutral".

## Context — what is already done vs. what remains

The heavy extraction (yaya-sales S0–S4) already landed: in-core `commerce/*`
Java is deleted, `V73` renamed the commerce tables to `legacy_*`, and
`CommerceBinding` is already a thin pointer. What still couples the store domain
into the neutral core:

1. **Store vocabulary in core** (~8 files): `profile/CommerceBinding`,
   `Profile.commerce` / `isStore()`, `engine/StartSessionResult.commerce`,
   `api/dto/PublicChatDtos.CommerceInfo`, commerce-flavored Javadoc on
   `dispatchAction` / `PendingUiHints`. (The `_uiHint` dispatch itself is already
   kind-agnostic — only the doc names `product_catalog`/`cart`.)
2. **`demos/retail/*`** (510 LOC) + `RetailCustomerBootstrap`'s
   `find_order`/`track_shipment`/`start_return` registration — a retail business
   demo baked into the framework.
3. **Decommission tail is unbuilt:** `legacy_*` tables still exist; there is no
   per-tenant export tooling and no final DROP migration (§12.2).
4. **`yayaagenticweb/.../playground/commerce_widgets.dart`** — admin/playground
   store widget.

**Stays (per §12.1):** the engine, `ToolExecutor`, HTTP dispatch, `Authorizer`,
working memory, the generic `/v1/chat/sessions/{id}/actions` tap channel, and the
**chat storefront renderer** (`yayaagenticchat/.../commerce/*`) — it renders
UI-hint frames whose *source* moved to yaya-sales but whose wire format didn't.

## Decisions taken in this milestone

- **Revises §16 Q1.** Instead of keeping a thin typed `CommerceBinding`, the core
  gets a **generic `ProfileExtension`** (`kind` + opaque JSON `payload`). The word
  "commerce" leaves core entirely; yaya-sales supplies the `sales.store` payload
  shape. Rationale: honors the CLAUDE.md domain-neutrality non-negotiable, which a
  typed `Commerce*` core type violates.
- **Includes the data tail.** Build the one-shot per-tenant export + opening-balance
  seed, then drop `legacy_*`.

## Scope (IN)

- Generic `ProfileExtension` mechanism replacing `CommerceBinding` end to end
  (core → persistence → API DTOs → Flutter clients).
- Removal of `demos/retail` and its bootstrap wiring from core.
- Generalize/relocate the admin/playground store widget.
- Per-tenant `legacy_* → yaya-sales` export + opening-balance ledger seed + a
  `V75` drop migration.
- A permanent domain-neutrality guard test.

## Out of scope (NOT here)

- Moving the chat storefront renderer — it stays (§12.1).
- Any change to yaya-sales' own domain model (it already owns the domain).
- New store features (catalog, pricing, fulfillment) — those live in yaya-sales.

## Backend deliverables

- **B0 — Neutrality guard (starts red).** ArchUnit/test asserting no
  `commerce|cart|checkout|storefront|psp|store` identifiers in `profile`,
  `engine`, `api`, `core` packages (excluding `recorder`, `knowledge` "catalog",
  and the M0 tool-catalog). Baselines today's violations; each B below turns a
  slice green.
- **B1 — `ProfileExtension` record.** `record ProfileExtension(String kind,
  com.fasterxml.jackson.databind.JsonNode payload)`. Core never interprets
  `payload`.
- **B2 — `Profile` carries extensions.** Replace `CommerceBinding commerce` with
  `Map<String, JsonNode> extensions`; delete `isStore()`. Preserve the two
  back-compat constructors (drop the `CommerceBinding` overload; keep the
  no-extension one). Update the ~6 `.commerce()` callers.
- **B3 — `StartSessionResult.clientTraits`.** Replace `commerce` with
  `Map<String,Object> clientTraits`; `DefaultConversationEngine` emits
  `profile.extensions` verbatim at session start. Engine stops knowing what a
  store is. Neutralize commerce Javadoc on `dispatchAction`/`PendingUiHints`.
- **B4 — API DTOs.** Replace `PublicChatDtos.CommerceInfo` (and the
  `PlaygroundChatController`/`PublicChatController` uses) with a generic `traits`
  map in `StartResponse`. `ActionRequest` is already generic — keep, neutralize
  docs. Same for `AdminDtos` commerce fields → extension shape.
- **B5 — Persistence + `V74`.** Migration renames `profiles.commerce_json` →
  `profiles.extensions_json` and transforms each non-null row `{…}` →
  `{"sales.store": {…}}` (lenient read of retired `CommerceConfig`). Update
  `ProfileEntity`, `PostgresProfileRegistry`, `AdminController`. Dual-read the old
  shape during rollout. **Delete `CommerceBinding.java`.**
- **B6 — Remove `demos/retail`.** Delete the 6 files; unwire the retail tools from
  `RetailCustomerBootstrap` (keep `M0Catalog` — it is the framework tool registry,
  not a product catalog). Relocate as a yaya-sales example if still wanted.
- **B7 — Export/import tooling (§12.2). ALREADY EXISTS — reuse, don't rebuild.**
  `apps/yaya-sales/deploy/migrate/export-legacy-commerce.sql` +
  `import-legacy-commerce.sh` do the per-tenant move (products/catalogs/orders →
  yaya-sales admin API) and seed one opening-balance `ADJUSTMENT` per SKU
  ("opening balance (legacy import)"). `apps/yaya-sales/docs/RUNBOOK.md` is the
  10-step cutover with the reconciliation checks (step 9).
- **B8 — Legacy drop. ALREADY EXISTS as a deliberately-manual script.**
  `apps/yaya-sales/deploy/migrate/drop-legacy-commerce.sql` — run only after the
  RUNBOOK step-9 reconciliation passes and `legacy_orders_archive.csv` is stored.
  Intentionally NOT under `db/migration` so Flyway can't auto-delete unmigrated
  data. Promote to a real `V75__drop_legacy_commerce.sql` once every tenant is cut
  over.

## Flutter deliverables

- **F1 — `yayaagenticchat`.** Read `traits['sales.store']` (storeId/cartEnabled/
  checkoutTool) instead of the typed `commerce`/`CommerceConfig` block. Renderer
  unchanged; only the source field changes.
- **F2 — chat renderer harden.** Assert no client-side price/total math (server
  values only); Stripe dep is hand-off/render only; gate the commerce feature
  behind the `sales.store` trait.
- **F3 — `yayaagenticweb`.** Profile authoring writes the `sales.store` extension
  via a generic extension editor (store picker). Generalize or move
  `commerce_widgets.dart`: keep if it previews UI-hint frames (rename to generic),
  move to yaya-sales console if it authors catalogs/PSP.

## Acceptance criteria

- [ ] Core compiles with **zero `Commerce*` types**; the B0 guard is green for
      `profile`/`engine`/`api`/`core`/`demos`.
- [ ] `./mvnw test` green; app boots; an existing store profile still renders the
      cart affordance at session start (via the `sales.store` trait).
- [ ] Already-persisted `commerce_json` rows resolve after `V74` with no operator
      re-authoring.
- [ ] Per-tenant export loads catalog/orders into yaya-sales and reconciles (row
      counts + Σ on-hand match); ledger≡summary and no-oversell (§14) hold on
      seeded data.
- [ ] `legacy_*` tables dropped by `V75`.

## Risks & open questions

- **`Profile` is a widely-used core record** — B2 ripples. Do B1–B5 behind the
  `V74` dual-read window so no persisted profile breaks mid-rollout.
- **B7/B8 are destructive and one-way.** Reconciliation sign-off gates `V75`;
  snapshot the DB before the drop.
- **Trait contract is now client-interpreted.** The `sales.store` kind + payload
  shape is a shared contract between yaya-sales, the chat app, and the admin app —
  document it once (design §11.4) rather than three times.

## Exit checklist

- [ ] All acceptance criteria green.
- [ ] B0 guard promoted to a permanent ArchUnit rule.
- [ ] yaya-sales design §12.3/§16 Q1 updated to record the revision; CLAUDE.md
      package map drops `CommerceBinding`, adds `ProfileExtension`.
- [ ] Memory notes updated ([yaya_sales_implementation], [commerce_redesign]).
