# Admin Console UX Uplift — design

**Status:** Implemented · 2026-11-22 (see §15 for where the build differs from the proposal)
**Scope:** `yayaagenticweb` (Flutter admin console + playground). Small, additive backend changes in `api/` (§9 of `yaya-agentic-design.md`); no SPI, engine, or recorder changes.
**Mockups:** Design canvas "Yaya Agentic Console UX" — https://claude.ai/artifact/B2G9Fkp659gjBfTKofGtSh (Tools list + editor sheet + ⌘K, Profile full-page editor, live Knowledge list, form kit before/after).

---

## 1. Why

The console has grown to ~30 destinations and ~125 hand-written screens/widgets. Every admin screen follows the same pattern (`AdminTwoPane`: a list on the left 60%, a create form on the right 40%), which made it fast to build but now makes it hard to use. What operators run into:

| # | Problem | Where it comes from |
|---|---|---|
| P1 | **Data goes stale silently.** A list is fetched once and only refetched when *this* tab mutates it. A teammate's change, an ingestion finishing, a WhatsApp sender getting approved — none show up until a hard reload. Switching tenant blanks the list to a spinner. | `FutureProvider` per list (51 of them), refreshed only by the 67 `ref.invalidate` calls after local saves. Three ad-hoc `Timer.periodic` polls (`knowledge_sources_screen.dart:49`, `whatsapp_playground_screen.dart:78`, verify-email cooldown) are the only "live" surfaces. |
| P2 | **Forms fight the operator.** Validation runs on Save and reports through a snackbar (`showSnack(context, 'id is required', error: true)` in ~10 screens) that vanishes and doesn't point at the field. Only 12 `validator:`s exist across 144 `TextField`s. Save sits at the bottom of a long scroll inside a 40% pane. Nothing warns before leaving with unsaved edits (0 `PopScope`/dirty guards). | `tools_screen.dart:127-140`, `capabilities_screen.dart:80`, `oidc_issuers_screen.dart:76`, … |
| P3 | **Labels and fields are hard to read.** Labels are wire names (`inputSchemaJson`, `bean name`, `system prompt fragment`). Inputs are `isDense` floating-label outlines; the label disappears into the border once a value is typed. Secondary text uses `colorScheme.outline` (`#7A757F` from the `0xFF6750A4` seed): ≈4.3:1 on `surface` and ≈3.4:1 on `surfaceContainerHighest` — both below WCAG AA (4.5:1). Helper text is 12px. Error snackbars set `backgroundColor: errorContainer` but keep M3's default `onInverseSurface` text, so the message is light text on light pink — close to unreadable. Light theme only. | `theme.dart`, `admin_shared.dart` (`showSnack`; `DetailRow` uses `labelSmall` + `outline`), `InputDecoration(labelText: 'id')` everywhere |
| P4 | **Hard to find things.** 30 destinations in 6 groups, no search. "Inbound channels" holds 9 unrelated items (senders, templates, forum, opt-outs…). The tenant context is a raw-id dropdown visible only to platform admins. | `router.dart` `_navItems` |
| P5 | **Lists don't scale.** No search, filter, or sort. Records are expand-in-place cards. Errors render as bare red text with no retry. Empty states say "No tools yet." with no next step. | Every `AdminTwoPane` list |
| P6 | **"Clone & edit" is a confusing edit model.** Editing a versioned resource means cloning it into the side form, which silently becomes "save as v(N+1)". Operators don't know whether they're creating or editing, or what happens to running sessions. | `EditableRecordCard` |

## 2. Goals / non-goals

**Goals**

1. What's on screen is current within ~2s of a change anywhere — no manual refresh needed, ever.
2. An operator can create a tool, a capability, or a profile without consulting the design doc for field meanings, and never sees a server-side validation error the client could have caught.
3. Every label, value and helper meets WCAG 2.2 AA contrast; every field's label is visible at all times.
4. Any destination or record is ≤2 keystrokes away (⌘K).
5. One shared kit (`lib/ui/`) so new screens get all of the above for free.

**Non-goals**

- Redesigning the end-user chat (`yayaagenticchat`) or the playground's conversation surface (the playground gets the shell/nav/refresh improvements only).
- Changing resource semantics (versioning, tenancy, authz). The UI explains them better; it does not change them.
- Real-time collaborative editing. We detect and surface conflicts; we don't merge keystrokes.

## 3. Principles

1. **Fresh by default.** The UI keeps itself current; "Refresh" is an escape hatch, not a workflow.
2. **Never move content under the cursor.** Live updates are announced (pill, highlight), not jump-inserted.
3. **Errors live next to their cause.** A snackbar is for outcomes ("Tool created"), never for validation.
4. **Say it in the operator's words, keep the wire name nearby.** "Tool ID" with a `id` key chip; operators who script the API still map 1:1.
5. **The primary button says what will happen.** "Create tool", "Publish v5" — not "Save".
6. **Show consequences before they happen.** Auth forwarding, publishing a version, deleting a secret: the effect is spelled out at the control.
7. **Legible first, dense second.** Comfortable forms; dense tables only where scanning many rows is the job.

---

## 4. Pillar A — Always-fresh data

### A1. Stale-while-revalidate everywhere

Lists keep showing their last value while they refetch. A 2px progress line under the page header indicates a fetch; the list never blanks. Tenant switch keeps the old rows dimmed (`Opacity 0.4`, pointer-disabled) with a skeleton overlay until the new tenant's rows arrive, so the operator sees *that* the context changed rather than a blank flash.

Implementation: a `LiveAsyncBuilder` wrapper over `AsyncValue.when(skipLoadingOnReload: true, skipLoadingOnRefresh: true)` that renders `isRefreshing`/`isReloading` as the progress line.

### A2. Refetch triggers (no backend change)

A list provider is considered stale after **30s** and is refetched when any of these fire:

| Trigger | Source |
|---|---|
| Route entered | `GoRouter` listener in the shell |
| Browser tab becomes visible / window refocused | `visibilitychange` + `focus` via `package:web` |
| Network/back-end reconnects | `adminEventsProvider` reconnect (A3) |
| Tenant or workspace switches | existing `currentTenantOrNull` watch |
| Manual | `R` shortcut or the Sync indicator's "Refresh now" |

This alone fixes most of P1 and ships in UX-1 before the change feed exists.

### A3. Admin change feed (small backend addition)

`GET /v1/admin/events?tenant={id}` — `text/event-stream`, operator-authenticated, same workspace-access check as the tenant endpoints (`61c91c1`).

```json
event: resource-changed
id: 8f1c…            // monotonically increasing per tenant
data: {"tenant":"acme-retail","kind":"TOOL","id":"sales_checkout",
       "version":4,"op":"CREATED","at":"2026-11-22T14:03:11Z"}
```

- **Source.** A Hibernate post-commit listener (`adminevents.AdminChangeEntityListener`) maps admin-visible entity classes to resource kinds. One integration point covers every controller *and* background writers (ingestion jobs, Twilio/Meta callbacks), and post-commit means the UI never sees a rolled-back change. Bulk JPQL updates bypass Hibernate events; the focus/30 s refetch (A2) is the backstop for those.
- **Payload is metadata only** — kind, id, version, op, time (no actor in v1: the entity listener doesn't see the request). Never secret values, prompt text, or schemas; the client refetches through the normal authorized list endpoint. This keeps the feed out of the authz story entirely: it reveals nothing the operator couldn't already list.
- **Replay.** Per-tenant in-memory ring buffer (last 500 events). Client reconnects with `Last-Event-ID`; if the id has aged out, the server sends `event: reset` and the client invalidates everything for that tenant.
- **Heartbeat** every 25s (`: ping`); the response sets `X-Accel-Buffering: no`, and `deploy/nginx.site.conf` already proxies `/v1/` unbuffered with a 3600 s read timeout. Streams end after 30 min and the browser reconnects, so the access check re-runs per connection.
- **Multi-instance.** v1 is per-instance; the ring buffer + A2 triggers cover the gap. Cross-instance fan-out over Redis pub/sub (already in the stack) is UX-2b, same as the deferred API-key broadcast.
- **Client.** `adminFeedProvider` opens a native browser `EventSource` with credentials (operator auth is the session cookie, so no header is needed; a long-lived dio XHR would hit the 30 s receive timeout and buffer the whole stream). The browser reconnects itself with `Last-Event-ID`; after a hard close the client reconnects with backoff and passes `?lastEventId=`.
- **Invalidation.** No central map: each list provider calls `liveResource(ref, kind)`, which listens to a per-kind signal and invalidates itself (lazily for providers nobody watches). Signals are leaf providers the feed writes into — never derived from the feed — because the feed watches the tenant, the tenant comes from a live list, and a derived signal closes a cycle Riverpod only partly detects.

This is an addition to the API surface (§9), not a new SPI; the engine and `ConversationRecorder` are untouched.

### A4. Status polling for transient states

Some state changes come from outside the admin API (Twilio callbacks, ingestion workers). One helper replaces the ad-hoc timers:

```dart
pollWhile<T>(
  provider: knowledgeSourcesProvider,
  busy: (list) => list.any((s) => s.status.isTransient),   // INGESTING, PENDING_VERIFICATION, …
  interval: Backoff(start: Duration(seconds: 2), max: Duration(seconds: 10)),
)
```

Pauses while the tab is hidden; stops when nothing is transient. When the change feed also emits status changes (UX-2), the poll becomes the fallback.

### A5. Presenting changes

| Change | Presentation |
|---|---|
| New rows from someone else | A "2 new tools · Show" pill at the top of the table. Rows are inserted when clicked (or immediately if the list is scrolled to top and nothing is selected). |
| Row updated | Row updates in place; 2s highlight; "Updated by Ana · just now" in the Updated column. |
| Row deleted | Fades out; if its detail sheet is open, the sheet shows "Deleted by Ana" and becomes read-only. |
| **Record you're editing changed** | Editor banner: *"Ana published v5 while you were editing. Your changes are still here."* → **Review v5** (diff) / **Keep my changes** (will publish v6 on top of v5). Driven by the editor's `baseVersion` vs. incoming event version; the backend enforces it with an optional `baseVersion` → `409 VERSION_CONFLICT` (§11). |

### A6. Sync indicator

Top bar, right of the breadcrumb: **● Live** (green) · **Syncing…** · **Reconnecting** (amber) · **Offline — showing data from 14:02** (grey). Tooltip shows last sync; click → "Refresh now". Text + icon, never colour alone.

---

## 5. Pillar B — Forms that are easy

### B1. Editors get room

The 40% create pane goes away. Lists become full width; create/edit opens in one of two containers:

| Container | Used for | Why |
|---|---|---|
| **Side sheet** (560–720px, right) | Capabilities, auth bindings, OIDC issuers, JWT secrets, identity routes, recording strategies, templates, opt-outs | ≤ ~10 fields; the list stays visible for context. |
| **Full-page editor** (`/admin/<kind>/new`, `/admin/<kind>/:id/edit`) | Profiles, tools, knowledge sources, start flows, WhatsApp senders, auth resolvers | Many fields, sections, JSON. Deep-linkable; browser Back works; shareable URL for "look at this profile". |

Both share the same form kit and sticky action bar.

### B2. Field anatomy

```
Tool ID *                                   id      ← label (14px/500) + required mark · wire-key chip (mono, muted)
┌────────────────────────────────────────┐
│ sales.checkout                         │         ← 44px input, persistent label above (no floating label)
└────────────────────────────────────────┘
Lowercase, dots allowed. Profiles reference     ← helper, 13px, onSurfaceVariant (≥4.5:1), up to 3 lines
tools by this ID. Learn more ↗
```

- Label always above the input. Required fields carry `*` (announced as "required"); optional fields say "Optional". The form header carries the legend once.
- Placeholder text is an *example* (`e.g. https://api.acme.com/orders/{orderId}`), never the instruction.
- Error replaces the helper in `error` colour with an icon; the input border goes `error` 2px.
- Labels are human; the wire key chip keeps the API mapping visible. A single `labels.dart` per feature holds label/helper/key triples so copy is reviewable in one place.

### B3. Validation

- Synchronous validators on every field that has a rule the backend enforces (required, id pattern, URL template syntax, JSON parse, JSON-Schema shape, number ranges, cross-field rules).
- **When:** on blur first; once a field has shown an error it re-validates on every change so the error clears as soon as it's fixed.
- **On submit:** validate all; if anything fails, show an **error summary** at the top of the form ("2 things need attention" with one link per error), scroll to and focus the first invalid field, and announce via `SemanticsService.announce`.
- **Server errors** map back to fields: `AdminExceptionHandler` adds an optional `fieldErrors: {"httpSpec.urlTemplate": "…"}` for bean-validation failures (§11). Anything unmapped goes in the summary — never a snackbar.

### B4. Sections and progressive disclosure

Long forms are split into titled sections (e.g. Tool: *Basics · Transport · Request · Schemas · Behavior · Advanced*). Full-page editors show a sticky section index with per-section state (✓ complete · "1 error"). *Advanced* is collapsed by default and remembers its state. Conditional fields appear directly under the control that reveals them (HTTP fields under the transport choice).

### B5. Purpose-built inputs

| Widget | Replaces | Behaviour |
|---|---|---|
| `YResourcePicker` | free-text ids & bare dropdowns | Searchable; rows show display name, mono id, version, status. "+ Create new…" opens a nested sheet and selects the result on save. (Extends the existing dropdown-binding convention.) |
| `YChipsField` | checkbox lists of capabilities/tools | Selected items as removable chips; type to add; shows "used by" counts. |
| `YKeyValueField` | `k=v lines` header textareas | Row table with add/remove, duplicate-key and empty-key errors, template-variable autocomplete in values. |
| `YJsonField` | plain JSON textareas | Monospace, line numbers, live parse status ("Valid JSON" / "Line 4: expected `}`"), **Format** button, JSON-Schema awareness for schema fields (warns on missing `type`). |
| `YTemplateField` | URL/body template text fields | Highlights `{var}` placeholders, offers known variables (`{principal.sub}`, input properties from the input schema), errors on unknown vars. |
| `YSecretField` | password-ish text fields | Masked with reveal; on edit shows "Stored · leave blank to keep current"; copy-once on create. |
| `YEnumCards` | segmented buttons / dropdowns for consequential enums | Radio cards with a one-line consequence. Auth forwarding: **None** (tool gets no user token) · **User's token** (the verified principal's token is sent to this URL — only for services you trust with it) · **Service token**. Non-negotiable #6 becomes visible instead of a dropdown value. |
| `YSwitchField` | `SwitchListTile`s | Title + description; can be **disabled with a reason** ("Needs Confirmable on") instead of letting the server reject the combination (e.g. Plannable ⇒ Confirmable + confirmPrompt, not with Secret input). |

### B6. Save model

- **Sticky action bar** at the bottom of every editor, always visible: dirty indicator ("3 unsaved changes"), secondary **Cancel/Discard**, primary that names the outcome (**Create tool**, **Publish v5**). Button shows inline progress while saving; disabled only while saving (never disabled for invalid — clicking it reveals the error summary, which is more discoverable).
- `⌘/Ctrl+S` submits; `Esc` closes a sheet (with the guard below).
- **Edit replaces "Clone & edit".** Versioned resources get a normal **Edit** that opens the editor prefilled. The action bar reads **Publish v5**, and a *Publish preview* panel shows the diff vs. the current version and the consequence: *"New sessions use v5. Sessions already running keep v4."* **Duplicate** (new id) is a separate, explicit action.

### B7. Never lose work

- **Unsaved-changes guard:** `GoRoute.onExit` for full-page editors, sheet-close confirm, and `beforeunload` on the web — "Discard 3 unsaved changes?".
- **Draft autosave:** every 2s while dirty, the form snapshot is written to `shared_preferences` keyed by `(workspace, tenant, kind, id|new)`. Reopening the editor offers "Restored your unsaved draft from 10 min ago · Discard draft". Secret fields are never persisted to drafts.

### B8. Destructive actions

Confirm dialogs name the object and consequence ("Delete JWT secret `acme-hs256`? Tokens signed with it stop working immediately."). High-blast-radius actions (archive tenant, rotate/delete a secret, delete a profile with live sessions) require typing the id. Soft-deletable things get an **Undo** toast instead of a dialog.

---

## 6. Pillar C — Visible and legible

Rewrite `theme.dart` around explicit tokens (`lib/ui/tokens.dart`), light **and** dark (`ThemeMode.system` + override in Settings).

| Token | Rule |
|---|---|
| Text, secondary | `onSurfaceVariant` tuned to ≥ 4.5:1 on `surface` and `surfaceContainer`. **`outline` is never a text colour** (a test enforces it, below). |
| Type scale | Body 14px, helper 13px, labels 14px/500, page title 24px/600. 12px only for badges/metadata in tables. |
| Inputs | 44px min height, label above, 1px border (`outlineVariant` darkened to ≥ 3:1 vs. surface for non-text contrast), 2px `primary` focus ring. `isDense` only in table filter bars. |
| Status | Chips always icon + word (● Active, ⟳ Ingesting, ⚠ Failed). Colours differ in lightness as well as hue. |
| Monospace | IDs, versions, JSON in Roboto Mono (Apache-2.0), bundled under the family name `monospace` so every existing `fontFamily: 'monospace'` picks it up — the web renderer has no system mono font. |
| Density | One `VisualDensity` token; tables use compact rows (40px), forms comfortable. |
| Motion | 150–200ms; honour `MediaQuery.disableAnimations`. |

`outline` is retuned to ≥4.5:1 on every surface (light and dark) instead of being banned, because 60+ existing call sites use it as text; `test/ui/contrast_test.dart` computes the real ratios of every text/surface and status pair from the theme and fails the build if any drops below AA.

## 7. Pillar D — Find and navigate

- **Command palette (`⌘K` / `Ctrl+K`, also a search box at the top of the nav).** Three groups: **Go to** (destinations), **Records** (profiles, tools, capabilities, knowledge sources… matched by id and display name from already-loaded providers; server-side search later), **Actions** (Create tool, Switch tenant…, Open profile in Playground, Toggle theme). Recents shown on open.
- **Context chip** for every operator, not just platform admins: *Workspace · Tenant* with the tenant's display name (id in mono beneath), searchable switcher. The tenant-scoped empty state stays, but the switcher is reachable from it inline.
- **Breadcrumbs** in the top bar (`Agent design / Tools / sales.checkout`).
- **IA tweak:** split *Inbound channels* (9 items) into **Channels** (Network chat, WhatsApp senders, SMS, System inbound) and **Messaging** (Chats, Forum, WhatsApp templates, Receipt templates, Opt-outs).
- **Cross-links:** a tool shows "Used by 2 capabilities"; a capability shows its profiles; a profile has **Open in Playground** — the graph the design doc describes (§5.2–§5.3) becomes navigable.

## 8. Pillar E — Lists

`YTable` replaces the expand-card lists:

- Search (client-side, debounced 150ms, matches id/name/description), filter chips (e.g. Transport: HTTP · Bean; Confirmable), sortable columns, sticky header, URL-persisted query (`?q=&kind=HTTP`) so filtered views are linkable.
- Row click → detail sheet with tabs **Overview · Versions · Used by · Raw JSON**; actions **Edit**, **Duplicate**, **Delete** in the sheet header.
- **Loading:** skeleton rows on first load only (A1 covers refetches). **Empty:** one-line explanation of the resource + primary CTA ("Tools are the actions a capability can take. Create your first tool"). **Error:** what failed, **Retry**, and "Copy details" (status, path, message) for bug reports.
- Pagination/virtualisation above 100 rows (lists are unpaged server-side today; client-side is fine at current sizes).

## 9. Feedback and accessibility

- Toasts for outcomes, 4s, with an action where useful ("Tool created · **View**"; "Deleted · **Undo**"). Errors that need action stay on screen until dismissed.
- Optimistic UI for toggles in tables (status, enabled) with rollback + inline error on failure.
- Keyboard: logical tab order, visible focus, `Esc` closes overlays, `Enter` submits single-field dialogs, `/` focuses table search, `⌘K` palette, `⌘S` save.
- Semantics labels on every icon button; 44×44 hit targets; error announcements; screen-reader names include the required/optional state.

---

## 10. Client architecture

```
lib/ui/                         design system — no feature imports
├── tokens.dart, theme.dart       light/dark ThemeData from tokens
├── form/                         YForm, YFormSection, YField + inputs (B5), YActionBar, YErrorSummary
├── data/                         YTable, YDetailSheet, YEmptyState, YErrorState, YSkeleton
├── shell/                        YSideNav, YCommandPalette, YContextChip, YSyncIndicator, YBreadcrumbs
└── feedback/                     toasts, confirm/typed-confirm dialogs
lib/app/live/
├── admin_events.dart             StreamProvider over /v1/admin/events (reuses api/sse_client.dart)
├── live_list_provider.dart       liveListProvider<T>(kind:, fetch:) — staleness, triggers, registration
├── invalidation_map.dart         AdminResourceKind → providers
└── poll_while.dart
```

**Providers** stay Riverpod `FutureProvider`s, created through one factory so every list gets A1–A4 for free:

```dart
final toolsProvider = liveListProvider<ToolResponse>(
  kind: AdminResourceKind.tool,
  fetch: (api, tenant) => api.listTools(tenant: tenant),
);
```

**Form state** moves out of a dozen `TextEditingController`s + hand-written `_cancelClone()` resets into one controller per editor:

```dart
class ToolFormController extends AdminFormController<ToolDraft> {
  ToolFormController(super.initial, {super.baseVersion});
  @override
  Map<String, String?> validate(ToolDraft d) => {
    'id': Validators.resourceId(d.id),
    'urlTemplate': d.kind == ToolKind.http ? Validators.urlTemplate(d.urlTemplate) : null,
    'inputSchema': Validators.jsonSchema(d.inputSchema),
  };
}
```

`AdminFormController` owns `initial`, `current`, `isDirty`/`dirtyCount`, `errors`, `touched`, `baseVersion`, draft persistence, and `toRequest()`. Resetting is `controller.reset()` — no field-by-field clears.

Existing `AdminTwoPane`, `FormCard`, `EditableRecordCard` and `showSnack` stay until the last screen migrates, then are deleted.

## 11. Backend changes (all additive, `api/`)

1. `AdminEventsController` — `GET /v1/admin/events` SSE (A3) + `AdminResourceChangedEvent` published from admin write paths after commit.
2. `AdminExceptionHandler` — add optional `fieldErrors` map for `MethodArgumentNotValidException` / domain validation; `error`/`message` unchanged (the public chat front door keeps its exact shape).
3. Versioned creates accept an optional `baseVersion`; if it isn't the latest, respond `409 {error:"VERSION_CONFLICT", currentVersion}`. Omitted → today's behaviour, so other clients are unaffected.
4. nginx: `proxy_buffering off` for `/v1/admin/events`.

No change to SPIs, `ConversationEngine`, recorders, or authz. Non-negotiables unaffected; #6 (explicit `AuthForwarding`) gets *more* visible in the UI.

## 12. Rollout

Each step is shippable on its own; screens migrate one PR at a time.

| Step | Delivers | Acceptance |
|---|---|---|
| **UX-0 Foundation** | `lib/ui/` tokens, light/dark theme, `YField` + basic inputs, `YActionBar`, contrast test; one-line fix to `showSnack` so error text uses `onErrorContainer` (can ship immediately) | Contrast test green; Settings has theme toggle; Tools screen uses new fields in the existing layout |
| **UX-1 Fresh data (client)** | `liveListProvider`, SWR, A2 triggers, `pollWhile`, Sync indicator (Live = "polling") | Changing a tool in tab B shows in tab A within 30s or on focus; tenant switch never blanks the list; old timers deleted |
| **UX-2 Change feed** | Backend events endpoint + client; A5 presentation; `baseVersion` 409 | Change in tab B visible in tab A < 2s; editing-conflict banner appears; nginx streams without buffering |
| **UX-3 Forms** | Sheets + full-page editors, validation + error summary, `fieldErrors`, B5 inputs, dirty guard, drafts, Edit/Publish model | Tools, Profiles, Capabilities, Knowledge migrated; no `showSnack` for validation in those screens; leaving a dirty editor prompts |
| **UX-4 Lists & nav** | `YTable` + detail sheet, ⌘K palette, context chip for all operators, breadcrumbs, IA split | Any destination/record reachable via ⌘K; filters persist in URL |
| **UX-5 Sweep** | Remaining screens migrated; `AdminTwoPane`/`FormCard`/`EditableRecordCard` deleted | `grep AdminTwoPane lib/` empty |

Migration order (by operator traffic): Tools → Profiles → Capabilities → Knowledge → Auth bindings → OIDC/JWT → Identity routing → WhatsApp senders/templates → the rest.

## 13. How we'll know

- Task test (5 operators, before/after): create an HTTP tool with a header and user-token forwarding, add it to a capability, attach to a profile, try it in Playground. Target: −50% time, zero "where did my change go?" moments.
- Server-side 400s from admin create endpoints drop to near zero (client catches them first).
- Manual-refresh usage (Sync indicator "Refresh now" + `R`) is rare — if it isn't, A2/A3 are missing a trigger.

## 14. Risks and open questions

- **SSE behind proxies/CDNs** may buffer or cut idle connections → heartbeat, `X-Accel-Buffering: no`, and A2 triggers as the floor.
- **Multi-instance deployments** see per-instance events until Redis fan-out (UX-2b). Acceptable because A2 bounds staleness at 30s.
- **Drafts on shared machines** — drafts are per operator + workspace and never contain secrets; cleared on sign-out.
- **Open:** sheet vs. full page threshold per resource (proposed in B1 — confirm with operators). Should the palette search the server (cross-tenant for platform admins) in UX-4 or later? Dark mode default: follow system (proposed) or light?

## 15. As built — differences from the proposal

- **Change feed source:** Hibernate post-commit listener instead of per-controller `ApplicationEvent`s (one integration point; also catches background status changes). No actor in the payload.
- **Feed transport:** native `EventSource` (cookie auth) rather than a dio stream.
- **Contrast:** retuned `outline` + a ratio test rather than banning `outline` as text.
- **Mono font:** Roboto Mono from the Flutter SDK (Apache-2.0) instead of JetBrains Mono.
- **Keyboard:** ⌘K/Ctrl+K and ⌘S/Ctrl+S only. Single-key shortcuts (`/`, `R`) were dropped — Flutter can deliver plain characters to ancestor shortcuts while a text field is focused.
- **Table filters** are not persisted to the URL yet.
- **Also fixed along the way:** error toasts were light text on light pink; reloading any page dropped you on the playground (now `/loading?returnTo=`); republishing a tool silently dropped `captures`, `bindings`, `suggestions`, `mediaOutputs`, `tableOutputs`, `cardFields`, and a capability its `menu`; tool and capability responses never carried `createdAt`.
- **Kit guide:** `docs/guides/admin-console-ui-kit.md`.
