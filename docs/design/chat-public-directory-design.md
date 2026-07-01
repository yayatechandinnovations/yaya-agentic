# Chat Public Directory — Searchable Catalog of Published Chats (v1)

> Companion to `chat-channel-design.md`. That doc establishes the **chat entry point**
> (a branded, publicly addressable surface that speaks AS a tenant profile) and the
> **on-device directory** (the tiles a user has added by scanning a QR / following a
> deep link). This doc adds a **server-side, searchable, paginated catalog** of
> entry points an operator has explicitly chosen to **publish for discovery**, so an
> end user can *find* a chat without already holding its QR.
>
> Companion to `yaya-agentic-design.md` §5.2 (Profile), §5.8 (the non-enumeration
> stance this feature deliberately relaxes — see §2), §9 (API surface), §12 (Safety).

| | |
|---|---|
| **Status** | 🟡 Design |
| **Owner** | Yenfry Herrera |
| **Audience** | Engineering building yaya-agentic; PM stakeholders evaluating chat discovery / go-to-market |
| **Last updated** | 2026-06-30 |
| **Scope** | A **public, end-user-facing** directory in `yayaagenticchat/`: a "Discover" surface where a person searches/filters **published** chats (branded tiles) and adds one to their on-device directory — the same confirm-card → add flow the QR scanner uses. Backed by a public, cacheable, **keyset-paginated** search endpoint over the subset of entry points an operator has marked **discoverable**. Operators opt an entry point into the catalog (and set its catalog metadata) from the admin console. |
| **Out of scope (v1)** | Personalized ranking / recommendations. Full-text search inside conversation history. Geo / proximity search. Server-side per-user favorites (the directory of *added* tiles stays on-device per `chat-channel-design.md`). Paid placement / featured slots (seam left in §5). Category taxonomy management UI beyond a free-text/enum field. |

---

## 1. Problem & goal

Today the **only** on-ramp to a chat is possession of its handle: scan a QR, follow a
deep link, or paste a link (`chat-channel-design.md` §11). That is correct for the
"a business hands you its QR" flow, but it gives a user **no way to discover** a chat
they don't already have a link to. As the number of published tenant chats grows, an
operator's natural question becomes *"how do people find my chat if they didn't get my
QR?"* — and the end user's is *"is there a directory I can search?"*.

**Goal.** Give the end user a **Discover** surface: type a few characters, see matching
published chats, tap one, add it. The search must be:

- **Opt-in and safe** — only chats the operator explicitly publishes for discovery appear.
- **Efficient** — index-backed; no full-table scans; bounded work per request.
- **Paginated** — infinite-scroll friendly via **stable keyset cursors**, not `OFFSET`.

Non-goals: turning the whole entry-point space into a public phone book (see §2).

---

## 2. The trust decision: discovery is opt-in, per entry point

`chat-channel-design.md` §6.1 makes handles **unguessable so the entry-point space is
not enumerable** — "a scraper cannot walk `/e/1, /e/2, …`", and an unknown handle and a
deactivated handle return the same opaque 404. A public *searchable directory* is, by
definition, controlled enumeration. We reconcile the two with a single rule:

> **Only an entry point that is `ACTIVE` *and* has `discoverable = true` appears in the
> directory. Everything else keeps the §6.1 guarantee unchanged.**

Consequences, stated plainly so they are not lost in code:

- `discoverable` defaults to **`false`**. Publishing a chat (`DRAFT → ACTIVE`) does **not**
  list it. Listing is a **second, explicit** operator action. Nothing leaks by default.
- The directory exposes only **branding** already public on the confirm card
  (`display_name`, `logo_url`, `accent_color_hex`, a catalog `tagline`,
  `auth_mode`, and the `handle`). It never exposes `auth_config`, `abuse_policy`,
  `session_policy`, internal ids, or tenant identity beyond the display name.
- A non-discoverable entry point's **handle stays unguessable**. Discovery does not weaken
  the capability model for anything the operator didn't opt in.
- `PAUSED` / `DRAFT` / `DEACTIVATED` are **never** in the catalog, regardless of the flag —
  the flag is a *preference*; the status gate is the *authority* (mirrors the front-door
  rule that only `ACTIVE` starts sessions).

This is the one genuinely new trust surface in this doc; the rest is mechanics.

---

## 3. Data model

Extend `chat_entry_points` with a discoverability flag, a one-line catalog blurb, and
a search-optimized structure. New migration `V50__chat_directory.sql`. (A `category`
filter facet was considered and deliberately deferred — see §5.) 

```sql
ALTER TABLE chat_entry_points
    ADD COLUMN discoverable BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN tagline      VARCHAR(140);                -- one-line catalog blurb (distinct from welcome_text)

-- Trigram matching for consumer "search as you type" (substring + typo tolerance) on the
-- name/tagline. FTS (tsvector) is the alternative — see §4.3 for why trigram is primary here.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Normalized search text: name + tagline, lower-cased. Generated so it can never drift
-- from the source columns.
ALTER TABLE chat_entry_points
    ADD COLUMN search_text TEXT
    GENERATED ALWAYS AS (
        lower(coalesce(display_name,'') || ' ' || coalesce(tagline,''))
    ) STORED;

-- The hot set is small (ACTIVE + discoverable only). A PARTIAL index keeps the index
-- itself tiny and makes both browse (keyset over name,id) and trigram search index-only.
CREATE INDEX chat_dir_browse_idx
    ON chat_entry_points (display_name, id)
    WHERE status = 'ACTIVE' AND discoverable;

CREATE INDEX chat_dir_search_idx
    ON chat_entry_points USING gin (search_text gin_trgm_ops)
    WHERE status = 'ACTIVE' AND discoverable;
```

Why **partial** indexes: the catalog is a fraction of all entry points (only opted-in,
active ones), and it is the only thing the public endpoint reads. Partial indexes bound
index size and I/O to exactly that hot set, and let Postgres serve browse pages index-only.

---

## 4. Query design

### 4.1 The endpoint

```
GET /v1/chat/directory
      ?q=<free text, optional>
      &cursor=<opaque, optional>
      &limit=<1..50, default 20>
      &locale=<optional, for future ranking; ignored in v1>
```

Public (under `/v1/chat/**`, outside operator auth), **cacheable** with a short TTL, and
**rate-limited by client IP** via the existing `ChatRateLimiter` (§6). Returns:

```json
{
  "items": [
    { "handle": "e_7f3a…", "displayName": "Buy Yaya Market", "logoUrl": "/v1/chat/logos/…",
      "accentColorHex": "#0A7", "tagline": "Groceries, delivered", "authMode": "ANONYMOUS_ONLY" }
  ],
  "nextCursor": "b64(display_name|id)"   // absent when the last page is reached
}
```

`items` is a **trimmed** projection (`DirectoryCard`) — strictly the public branding of
§2. `nextCursor` is opaque; the client passes it back verbatim.

### 4.2 Keyset pagination (not OFFSET)

`OFFSET n` re-scans and discards `n` rows every page and drifts when rows are inserted
mid-scroll — wrong for a public, growing, infinite-scroll list. We use **keyset**:

- **Sort key:** `(display_name ASC, id ASC)` — `id` breaks ties so the order is total and
  stable. This is exactly `chat_dir_browse_idx`, so each page is an index range scan.
- **Cursor:** opaque base64 of the last row's `(display_name, id)`. The next page is:

```sql
SELECT … FROM chat_entry_points
WHERE status = 'ACTIVE' AND discoverable
  AND (:q IS NULL OR search_text LIKE '%' || lower(:q) || '%')   -- trigram-accelerated
  AND (:cursorName IS NULL OR (display_name, id) > (:cursorName, :cursorId))
ORDER BY display_name, id
LIMIT :limit + 1;   -- fetch one extra to know whether a nextCursor exists
```

Fetch `limit + 1`; if the extra row comes back, emit `nextCursor` from the `limit`-th row
and drop the extra. Cost per page is `O(limit)` index entries regardless of how deep the
user scrolls — the property `OFFSET` cannot give.

### 4.3 Search: trigram primary, FTS alternative

Consumer directory search is **type-ahead** — short, partial, typo-prone ("groc",
"yaay"). That favors **`pg_trgm`** (substring `LIKE`/`%`, `similarity()` for fuzzy)
over word-oriented **FTS** (`tsvector`/`to_tsquery`), which excels at whole-word/phrase
queries with stemming but does not do substring or short-prefix well without extra work.
We therefore make **trigram primary** (§3) and keep FTS as a documented alternative if we
later want phrase/word-rank semantics.

- **v1 ordering:** search reuses the `(display_name, id)` keyset — results are the
  trigram-filtered subset in alphabetical order. Predictable and trivially cursorable.
- **Enhancement (§5):** rank by `similarity(search_text, :q) DESC`, keyset over
  `(score, id)` — stable *for a fixed `q`* because the score is deterministic per row, so
  the cursor stays valid across pages of the same query.

`q` is **sanitized**: trimmed, length-capped (e.g. 64 chars), and passed **only** as a
bound parameter to `LIKE '%'||lower(:q)||'%'` (or `similarity`) — never concatenated into
SQL and never into a `to_tsquery` expression, so there is no query-language injection.
Empty/whitespace `q` ⇒ pure browse.

---

## 5. Seams left for later

- **Ranking / featured placement.** The `(score, id)` keyset in §4.3 is the hook. A future
  `featured_rank` column (operator- or billing-driven) slots in ahead of `score` in the
  sort tuple without changing the cursor contract.
- **Redis first-page cache.** The commonest request is the *empty-query first page* (the
  browse landing). Cache `directory:first:{limit}` → first page JSON in Redis (already
  a dependency) with a short TTL (e.g. 30–60 s) and evict on any entry-point publish /
  discoverable / branding change event. Deeper pages and text queries go straight to the
  index (already `O(limit)`).
- **Category / filter facet (deferred).** A coarse `category` bucket surfaced as filter chips
  was cut from v1: text search over name + tagline already satisfies "search/filter," and a
  useful category needs a *taxonomy* (who defines buckets, how operators pick, what "Other"
  means) — overhead disproportionate to v1. Adding it later is a pure additive migration
  (nullable column + partial index) with **no** change to the cursor contract or endpoint
  shape. If reintroduced, prefer a fixed small enum over free text so filtering is usable
  without a management UI.

---

## 6. Abuse & safety

The directory is public and enumerable **by design** now, so it needs the front door's
abuse posture:

- **Rate limit by IP** on `GET /v1/chat/directory` via `ChatRateLimiter` (sliding window,
  keyed `chat:rl:dir:{ip}`), returning the same typed `429 { reason: "rate_limited",
  retryAfterSeconds }` the app already renders calmly (§7.1 of the chat design). This
  bounds scraping throughput even though the data is intentionally listable.
- **Short cache TTL** (§5) both cuts DB load and naturally rate-limits crawl freshness.
- **Only public branding leaves the server** (§2) — the `DirectoryCard` projection is the
  enforcement point; the full `ChatEntryPoint` never reaches this controller path.
- **Status is authority, flag is preference** (§2) — a paused/deactivated chat drops out of
  results immediately regardless of `discoverable`, because the `WHERE status='ACTIVE'`
  predicate is on the index itself.
- **No new identity leakage** — non-discoverable entry points and their handles are exactly
  as unguessable as before; this feature adds *zero* signal about them.

---

## 7. Backend surface (what to build)

| Layer | Change |
|---|---|
| Migration | `V50__chat_directory.sql` (§3): `discoverable`, `tagline`, generated `search_text`, partial trigram + browse indexes. |
| Repository | `ChatEntryPointRepository` gains a keyset directory query (native/`@Query`) returning a projection; params `(q, cursorName, cursorId, limit+1)`. |
| Service | `ChatDirectoryService.search(DirectoryQuery) → DirectoryPage` — sanitizes `q`, decodes/encodes the opaque cursor, applies `limit+1` and derives `nextCursor`. |
| DTO | `DirectoryCard` (trimmed public projection) + `DirectoryPage { items, nextCursor }`. |
| Controller | New public `@GetMapping("/v1/chat/directory")` on `PublicChatController` (or a sibling `ChatDirectoryController` under the same `/v1/chat` public prefix), rate-limited by IP. |
| Admin | `ChatEntryPointDtos.CreateRequest`/`UpdateRequest` gain `discoverable`, `category`, `tagline`; `EntryPointResponse` echoes them. `ChatEntryPointService` persists them. Admin editor in `yayaagenticweb` gets a "List in public directory" toggle + tagline field. |

Cursor codec: `base64url(display_name + ' ' + id)` (NUL delimiter — never valid in a
`display_name`); reject a malformed cursor with `400` rather than silently ignoring it.

---

## 8. Frontend surface (`yayaagenticchat`)

A new **Discover** screen alongside the existing directory (`/discover` route):

- **Search field** with debounce (~250 ms) → calls `GET /v1/chat/directory?q=…`.
- **Infinite scroll** driven by `nextCursor`: a Riverpod paging notifier holds
  `items + cursor + loading`, appends the next page when the list nears its end, and stops
  when `nextCursor` is absent.
- **Tap a result** → the **same** `ChatConfirmCard` the scanner/deep-link use
  (`chat_confirm_card.dart`) → `addFromCard` → on-device directory → open chat. Discovery is
  just a *third* on-ramp feeding the identical confirm/add path; no new session machinery.
- **Empty/again states** reuse existing calm patterns (no raw errors; `429` → "try again
  shortly").

`chat_api.dart` gains `Future<DirectoryPage> directory({String? q, String? cursor,
int limit})`. Wire an entry to Discover from the directory app bar / a bottom tab.

---

## 9. Efficiency budget (why this stays cheap)

- **Bounded rows per request:** every query is `LIMIT :limit+1` (≤ 51 rows). No unbounded
  result sets.
- **Index-only hot set:** partial indexes cover exactly `ACTIVE ∧ discoverable`; browse
  pages are index range scans, text search is a GIN-accelerated trigram filter.
- **No OFFSET drift/rescan:** keyset makes page *N* cost the same as page 1.
- **Cache the common case:** empty-query first page is cacheable (§5), absorbing the bulk of
  "just opened Discover" traffic.
- **Rate-limited:** IP sliding window caps worst-case scrape/DB pressure (§6).

---

## 10. Testing

- **Repo/keyset:** seed > `limit` discoverable rows; assert full walk via `nextCursor`
  visits every row exactly once, in `(display_name, id)` order, with **no duplicates and no
  gaps** across page boundaries (including duplicate `display_name` values — the `id`
  tie-break is the case that catches naive keyset bugs).
- **Visibility matrix:** rows in each `status × discoverable` combination; assert **only**
  `ACTIVE ∧ discoverable` appear; flipping `discoverable` off or pausing removes a row from
  the next page.
- **Projection:** response JSON contains **only** `DirectoryCard` fields — assert absence of
  `auth_config`, `abuse_policy`, `session_policy`, internal id, tenant id.
- **Search:** substring + case-insensitive + trailing-space `q`; injection-shaped `q`
  (`%`, `'`, ` `, `:*`) returns safe results, never errors.
- **Cursor:** malformed/oversized cursor → `400`; a cursor from a different sort is rejected.
- **Rate limit:** exceeding the IP window → typed `429` with `Retry-After`.
- **Flutter:** paging notifier appends pages and halts on null `nextCursor`; debounce
  collapses rapid keystrokes into a single request; a discovered card flows through the same
  confirm→add path as a scanned one.

---

## 11. Rollout

1. **D0 — schema + read path.** `V50` migration; repository keyset query; `ChatDirectoryService`;
   public endpoint + rate limit; `DirectoryCard`/`DirectoryPage`. Ships dark: with zero
   discoverable rows the endpoint returns an empty page.
2. **D1 — operator opt-in.** Admin DTO fields + editor toggle/tagline. Now operators
   can list chats; still no consumer UI, so listing is inert.
3. **D2 — consumer Discover.** The `yayaagenticchat` Discover screen + paging. Feature-flag the
   nav entry so it can be dark-launched.
4. **D3 (later, §5).** Redis first-page cache; similarity ranking; featured placement.

Each step is independently shippable and safe: nothing is discoverable until an operator
flips a per-entry-point flag that defaults off.

---

## 12. Open questions

1. **Cross-tenant catalog vs. per-deployment.** v1 assumes one global catalog across all
   tenants in a deployment (a yaya-branded shell hosting many tenants, per the chat design's
   "one shell, many tiles"). A white-label single-tenant build would scope the directory to
   one tenant — trivial extra `WHERE tenant_id = ?`, but the product decision (is Discover a
   yaya-wide phone book or a tenant's own catalog?) should be explicit.
2. **Should `AUTH_REQUIRED` chats be discoverable at all?** They *can* be listed (the confirm
   card handles the login gate), but a directory full of "sign in to continue" tiles may be
   poor UX. Option: allow it but badge them, or exclude by default.
3. **Ranking signal** when we add it (§4.3): pure `similarity`, or blend in recency /
   popularity (session-start counts already exist in metrics)?
