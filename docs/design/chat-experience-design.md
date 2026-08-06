# Chat App Experience — PIN Unlock, Rich Media, Receipt Previews & Community Forum

> **Status:** Implemented (CX-0…CX-4 v1, 2026-08-06). Migrations landed as **V76** (devices+PIN), **V77** (chat attachments), **V78** (receipt templates + wallet), **V79** (forum) — attachments took V77, so receipts/forum shifted one slot from the draft.
> **Implementation deviations (v1):**
> - **PIN removal** requires the *current PIN* (not a fresh OTP as §5.4 drafted): an attacker without the PIN still can't strip it, and no SMS is spent. OTP remains the recovery path via the lockout ladder.
> - **Forum enablement** lives in a dedicated `forum_settings` row surfaced through the feed response's `enabled` flag (and admin settings endpoints), not on `EntryPointCard` — the card record stays untouched; folding the flag into the card is a fast-follow.
> - **Forum posts are text-only** in v1 (body ≤ 2,000 chars, ≤ 3 links); the §6 media gauntlet is session-scoped, so forum images need their own upload path — noted seam, not built.
> - **Share/save-as-image** on receipts and **EXIF strip** on photos are deferred (web-renderer capture and an imaging dependency respectively); receipts have full-screen pinch-zoom, photos upload as-is.
> - **Type scale** kept system fonts (monospace stack for receipt paper); bundling Manrope/JetBrains Mono is cosmetic follow-up.
> - **Trait delivery on resume**: `chat.receipts` (like `sales.store` before it) arrives at session START only — a LIVE session continued after an app restart renders receipts from the wallet, not inline. Same pre-existing gap as commerce config; fix both together.
> **Owner:** Yenfry Herrera Feliz (architect / PM)
> **Audience:** chat-channel + engine engineers working on `yayaagenticchat` and the public `/v1/chat/*` surface
> **Last updated:** 2026-08-06
> **Anchors:** `docs/design/yaya-agentic-design.md` §5.2 (Profile / extensions), §5.3 (HTTP tools), §6.1 (UiHint), §7 (prompt composition), §9 (API surface), §12 (trust boundaries); CLAUDE.md non-negotiables #1, #2, #5, #8.
> **Companion to:** `chat-channel-design.md` (§16 sessions, §20 phone-verified identity), `chat-account-self-service-design.md` (account_id / pass `gen` / resume), `knowledge-file-upload-design.md` (BlobStore + upload gauntlet), `system-messaging-design.md` (trusted senders, chat inbox, attachments), `ecommerce-chat-design.md` + `commerce-redesign-authoring-rendering-paypal.md` (the cart pattern this doc's receipt preview mirrors).
> **Distinct from:** `yaya-agentic-user-accounts-design.md` (admin/operator plane). This doc is entirely the **end-user plane**: the human using `yayaagenticchat`.

---

## 1. Summary

`yayaagenticchat` has quietly become a real product: phone-verified accounts (chat-channel §20), resumable sessions, a storefront with cart and checkout, an inbox for system messages, per-tenant accent theming. But four gaps keep it from feeling like an app people *live in*:

1. **It unlocks once, forever.** OTP verification mints a 30-day device pass stored in plaintext `SharedPreferences`; after that the app never challenges anyone again, and "logout" only exists as sign-out-everywhere. There is no per-device session control and no lightweight re-authentication.
2. **The composer is text-only.** Users cannot send a photo of a damaged product, a voice note, or a PDF — even though the backend already owns a blob store, malware scanning, MIME sniffing, and a Whisper transcriber built for other surfaces.
3. **Structured "documents" the agent produces have no home.** A store chat renders a cart; a lottery chat renders… a paragraph of numbers. Anything ticket- or receipt-shaped deserves the same first-class treatment the cart got — and the mechanism must be *configurable*, because the framework must never learn the word "lottery."
4. **There is no shared space.** Every interaction is 1:1 with the agent. Tenants want a lightweight community surface — announcements, winner posts, user chatter — attached to their chat handle.

This design delivers four capabilities on top of infrastructure that already ships, plus a visual uplift that ties them together:

- **§5 Device PIN unlock & real logout** — first sign-in stays OTP; afterwards a short-lived pass is renewed with an account PIN (server-verified, Argon2id, attempt-limited), skippable per trusted device. Per-device sign-out and a device manager complete the story.
- **§6 Rich media in chat** — photos, files, and hold-to-record voice notes through an upload gauntlet (allowlist → sniff → ClamAV → BlobStore → signed URL), with hard abuse ceilings. Voice notes are transcribed server-side; images reach the model as vision input; extracted document text enters the prompt as delimited untrusted data.
- **§7 Receipt / ticket previews** — a domain-neutral, template-driven `receipt` UiHint rendered as a *printed thermal receipt* (monospace, dashed tear edges, barcode/QR), configured per tenant through a template registry and attached to profiles via the same `Profile.extensions` → `StartResponse.traits` seam the store uses. A lottery ticket is just one template; the core never knows.
- **§8 Community forum** — a per-entry-point feed where verified users and trusted system senders post; reactions, single-level replies, reporting, operator moderation, and the same rate-limit spine as the rest of the chat surface.
- **§9 Visual & navigation uplift** — a bottom-nav shell (Chats · Forum · Discover · Account), a real type scale, light theme, message grouping, and the lock screen that the PIN feature needs anyway.

### 1.1 Non-goals (v1)

- **Not** passwords, email login, passkeys, or biometric SPI work. The phone stays the root credential; PIN is a *convenience* factor below OTP, never above it. (Platform biometrics as a local PIN-autofill is a client nicety, noted in §5.8, not a server concept.)
- **Not** user-to-user direct messages. The forum is a public-per-handle feed, not a social graph.
- **Not** outbound media over WhatsApp/SMS channels (they have their own designs); this is the chat channel only.
- **Not** a print-hardware integration (ESC/POS). The receipt is a *visual* artifact with share/save-as-image; physical printing is a seam (§7.7), not scope.
- **Not** live multi-device session sync. Unchanged from chat-account-self-service §2.2.

---

## 2. Goals

- **G1.** Returning users authenticate with a PIN in ≤2 seconds; OTP is needed only for enrollment, new devices, PIN recovery, and step-up. PIN can be disabled per activated device ("don't ask on this phone").
- **G2.** Logout exists at both granularities: this device (revoke its registration) and everywhere (existing `gen` bump). A device manager lists active devices.
- **G3.** The device credential at rest moves out of plaintext `SharedPreferences` into platform secure storage, and the long-lived bearer is replaced by a short-lived pass + a device-bound refresh secret.
- **G4.** Users can send photos, voice notes, and documents; every byte passes the same gauntlet as knowledge uploads; every send is bounded by per-message, per-session, and per-account ceilings that make the surface useless for abuse.
- **G5.** The agent can hand the user a *document*: any tool can emit a `receipt` UiHint whose layout comes from a tenant-authored template. The rendering vocabulary is generic (rows, key-values, barcodes); binding a template to "lottery ticket" is pure tenant configuration.
- **G6.** Receipts persist: a user can reopen a ticket from the chat or from a "Receipts" wallet after the session ended.
- **G7.** A tenant can switch on a forum for an entry point with one flag; users post/react/reply under their verified account name; integrations post through the existing trusted-sender machinery; operators moderate from the console.
- **G8.** Zero new SPIs in the runtime core; zero paid services (self-hosted constraint holds: MinIO, ClamAV, Tika, Postgres, Redis; Whisper transcription is the existing `MediaTranscriber` port with its already-tracked hosted-model caveat).

---

## 3. Glossary

| Term | Definition |
|---|---|
| **Device registration** | A row in `chat_account_devices`: this `deviceId` belongs to this account, holds a hashed **device secret**, and carries per-device policy (`pin_required`) and revocation state. Created at OTP success. |
| **Device secret** | 32 random bytes returned **once** at enrollment, stored by the app in platform secure storage. Proof of "this is an activated device." Never a bearer on its own for chat calls — only for the unlock endpoint. |
| **Session pass** | The existing device pass (HS256, `DevicePassService`), now short-lived when the account has a PIN. Still sent as `X-Device-Pass`. |
| **PIN** | 4–6 digit account-level secret, Argon2id-hashed server-side. Verified only by the unlock endpoint; convenience factor strictly weaker than OTP. |
| **Unlock** | `deviceSecret (+ PIN when required)` → fresh session pass. The everyday login. |
| **Upload gauntlet** | allowlist → size cap → `ContentTypeSniffer` → `MalwareScanner` → `BlobStore` → asset row + signed URL. Same shape as knowledge-file-upload §4. |
| **Receipt template** | Tenant-authored declarative layout (ordered elements: header, kv, table, barcode, qr, …) with `{{path}}` data binding. Stored in `receipt_templates`, referenced by the `chat.receipts` profile extension. |
| **Receipt instance** | One rendered document: `{templateId, data}` emitted as a `receipt` UiHint by a tool and persisted to `chat_receipts` for the wallet. |
| **Forum** | Per-entry-point public feed. Posts by verified accounts (`USER`) or trusted integration senders (`SYSTEM`); single-level replies, emoji reactions, reports. |

---

## 4. Where this sits — one seam per feature

Nothing here changes the runtime SPIs. Each feature rides an existing seam:

```
Feature            Seam it rides                                   New surface
─────────────────  ──────────────────────────────────────────────  ─────────────────────────────
PIN + devices      chat-channel §20 verify stack                    /v1/chat/verify/pin-unlock,
                   (PhoneVerifier, DevicePassService,               /v1/chat/account/devices/*
                   ChatAccountService, pass gen)
Rich media         knowledge-file-upload BlobStore/ClamAV/Tika,     /v1/chat/sessions/{id}/attachments
                   channel/system Attachment (sealed),
                   MediaTranscriber, MediaController signed URLs
Receipts           Profile.extensions → StartResponse.traits        receipt_templates admin CRUD,
                   (the sales.store pattern) + UiHintPassThrough    /v1/chat/account/receipts
                   (`_uiHint` frames from tools)
Forum              ChatAuthService (L1 identity),                   forum/ package,
                   ChatRateLimiter, system-messaging                /v1/chat/e/{handle}/forum/*
                   trusted senders, MediaLibraryService
```

**Domain-neutrality check (the S7 test):** PIN/devices, media, forum, and receipt *rendering* are all "a way to build chat experiences" — core. Anything that decides *what goes on a ticket* (draw dates, prize tiers) is a business domain — it lives in the tenant's tools/apps and reaches the client only as opaque template + data. `CoreDomainNeutralityTest` gains assertions that no lottery/gaming vocabulary enters the core (§7.6).

---

## 5. Device PIN unlock & real logout

### 5.1 Problem with today's model

The device pass is simultaneously the *refresh credential* and the *session credential*: one 30-day bearer in plaintext `SharedPreferences`. Consequences: (a) anyone holding the phone holds the account for a month; (b) revocation is all-or-nothing (`gen` bump); (c) there is no moment at which the app can *ask who's there* without a full OTP round-trip (which costs an SMS and our toll-fraud budget).

### 5.2 The model: split the credential, add a person-check

Three artifacts replace the single long-lived pass:

| Artifact | Lives | TTL | Proves |
|---|---|---|---|
| Device secret | app secure storage + hashed in `chat_account_devices` | until device revoked | *activated device* |
| PIN | user's head + Argon2id hash on `chat_accounts` | until changed | *right person* |
| Session pass | memory + secure storage | **24 h** (PIN-holding accounts) / 30 d (no PIN) | *current session* |

Everyday flow: app opens → pass still valid? go. Expired? → lock screen → PIN → `pin-unlock` → fresh 24 h pass. OTP appears only for: first enrollment, a new/reinstalled device, PIN recovery, and the destructive ceremonies that already demand it (change phone, delete account).

**Why server-verified PIN, not a client-side lock.** A purely local PIN (hash in prefs gating a stored bearer) is theater: the bearer is right there. Server-side verification gives real attempt-limiting, real lockout, and lets the PIN gate *token issuance* rather than UI. The device secret keeps the PIN's tiny keyspace safe: an attacker without an enrolled device cannot brute-force PINs at all, because the unlock endpoint requires the secret first.

### 5.3 Data model (migration `V76__chat_account_devices_pin.sql`)

```sql
ALTER TABLE chat_accounts
  ADD COLUMN pin_hash TEXT,            -- Argon2id; NULL = no PIN set
  ADD COLUMN pin_set_at TIMESTAMPTZ,
  ADD COLUMN pin_failed_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN pin_locked_until TIMESTAMPTZ;

CREATE TABLE chat_account_devices (
  account_id        UUID NOT NULL REFERENCES chat_accounts(id),
  device_id         TEXT NOT NULL,               -- the app's existing stable deviceId
  device_secret_hash TEXT NOT NULL,              -- SHA-256 of the 32-byte secret
  label             TEXT,                        -- e.g. "Chrome · macOS", set from UA at enroll
  pin_required      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMPTZ NOT NULL,
  last_seen_at      TIMESTAMPTZ NOT NULL,
  revoked_at        TIMESTAMPTZ,
  PRIMARY KEY (account_id, device_id)
);
```

Argon2id reuses the parameters already standardized in the repo (operator plane): id, t=3, m=64MB, p=4.

### 5.4 API

All under the existing verify/account controllers; every OTP-sending path keeps the §20.4 toll-fraud ceilings.

- `POST /v1/chat/verify/check` — **extended response:** on success also enrolls the device and returns `deviceSecret` (once) alongside `devicePass`. If the account has a PIN and this is a *new* device, the response flags `pinSetupRequired=false, pinExists=true` so the app can explain future unlocks.
- `POST /v1/chat/account/pin` `{pin}` (pass-authenticated) — set/change PIN. Setting a PIN drops future pass TTLs to 24 h.
- `DELETE /v1/chat/account/pin` — requires a fresh OTP check token (PIN removal is a security *downgrade*; the weaker factor may not remove itself). Restores 30 d TTL behavior.
- `POST /v1/chat/verify/pin-unlock` `{deviceId, deviceSecret, pin?}` — **unauthenticated** (this *is* the login). Verifies: device row exists, not revoked, secret hash matches, account not deleted, `gen` current; then, if `pin_required` on this device *and* the account has a PIN, verifies the PIN under the lockout policy. Success → fresh session pass + `last_seen_at` touch. Failure modes are indistinguishable (`401`, no "which part failed") to avoid enumeration.
- `PUT /v1/chat/account/devices/{deviceId}` `{pinRequired}` — the "make the PIN optional on this device" toggle. Turning PIN **off** for a device requires PIN (or OTP) in-hand — a step-up header — so a snatched unlocked phone can't self-trust.
- `GET /v1/chat/account/devices` — device manager list (label, lastSeen, current-device marker).
- `POST /v1/chat/account/devices/{deviceId}/revoke` — single-device logout. Revoking the *current* device is plain "log out." `sign-out-everywhere` (gen bump) stays as-is and additionally marks all rows revoked.

**Lockout policy:** 5 consecutive PIN failures → 15 min lock; next 5 → 1 h; then locked-pending-OTP. Any successful OTP `check` resets the counter and (optionally, user-chosen) sets a new PIN — that *is* the "forgot PIN" flow. Counters live on the account (not the device) so an attacker can't rotate devices to reset them. All attempts flow through `ChatRateLimiter` dims (`pin-ip`, `pin-acct`) too.

**Backward compatibility / lazy migration:** existing long-lived passes keep working until natural expiry. On first launch with this release, a pass-holding app calls `POST /v1/chat/account/devices/enroll` (pass-authenticated) to obtain its device secret, then migrates storage. No forced re-OTP.

### 5.5 Client changes

- Add `flutter_secure_storage`; move `devicePass`, `deviceSecret` there (`DirectoryStore` keeps non-secrets). Web fallback note: on web, secure storage degrades to localStorage — acceptable; the 24 h TTL + server-side PIN is precisely what makes that tolerable.
- **Lock screen**: new pre-router gate state in `app.dart` — `verified && passExpired && pinRequired` → PIN pad (branded, digits-only, shake-on-error, "Forgot PIN? Verify by SMS" escape). Auto-unlock silently when `pinRequired=false` for this device (call `pin-unlock` with no pin).
- **Account → Security** section: set/change/remove PIN, per-device PIN toggle, device list with revoke, "Log out" (this device) joining the existing "Sign out everywhere."
- Optional local nicety: `local_auth` biometric that autofills the PIN entry (the PIN is still what the server checks; biometrics never become a server-side factor).

### 5.6 Trust notes

- PIN never rides ordinary chat calls; only `pin-unlock` sees it. It is never logged; the hash never leaves the DB.
- Session-pass claims are unchanged (`acct`, `gen`, `deviceId` already exist) — `VerifiedDeviceAuthenticator` additionally rejects passes whose device row is revoked (one indexed lookup, cached ≤60 s; revocation latency ≤1 min is acceptable and documented).
- The unlock endpoint is the new hottest brute-force target: per-IP and per-account Redis ceilings *plus* the Argon2id cost make online guessing of a 4–6 digit space behind a 32-byte secret a non-issue.

---

## 6. Rich media in chat (photos, files, voice notes)

### 6.1 Flow — upload first, then reference

Media never rides the message SSE call. Two steps, mirroring how every channel in the repo already treats media ("bytes never ride the request", `OutboundMedia`):

```
1. POST /v1/chat/sessions/{sid}/attachments      (multipart; pass/JWT-authenticated)
     → gauntlet: MIME allowlist → size cap → ContentTypeSniffer (never trust declared type)
       → ClamAV scan → EXIF strip (images) → BlobStore.store → chat_attachments row
     → 201 { assetId, kind, contentType, sizeBytes, url (signed, short-lived), transcript? }

2. POST /v1/chat/sessions/{sid}/messages  body: { text?, attachmentIds: [..] }
     → engine composes the user turn from text + attachments (§6.3)
```

Implementation notes: WebFlux `FilePart` handling runs on `boundedElastic` (the embedded-signup lesson); storage is the existing `BlobStore` (MinIO / `DbBlobStore`); serving reuses `MediaController`'s signed-grant pattern (`GET /v1/media/{token}`), so the app's existing `_AttachmentView` renders these with zero new client transport code. Attachment classification reuses the sealed `channel/system/Attachment` (`Image | Video | Audio | Document`).

### 6.2 Limits (the abuse contract)

All enforceable numbers live in `AbusePolicy` (per entry point, tenant-tunable), enforced via `ChatRateLimiter`; defaults:

| Dimension | Default |
|---|---|
| Allowed kinds | image (`jpeg,png,webp,heic→jpeg`), audio (`aac/m4a,ogg,webm`), document (`pdf` only, v1) |
| Max size | image 10 MB · voice 10 MB **and ≤ 2 min** · document 15 MB |
| Per message | ≤ 4 attachments |
| Per session/hour | 20 uploads |
| Per account/day | 40 uploads · 150 MB (Redis byte-counter dim) |
| Identity floor | **verified phone (L1) required to upload**; anonymous sessions get 403 with a "verify to send media" hint |
| Orphans | uploaded-but-never-referenced assets GC'd after 24 h |

Voice recordings are duration-capped client-side *and* size/duration-checked server-side (container metadata via Tika). ClamAV failure = hard reject; scanner *unavailable* = reject with 503, never scan-later (same stance as knowledge-file-upload).

### 6.3 What the model sees (non-negotiable #5 applied)

- **Voice note** → `MediaTranscriber` (existing Whisper port) transcribes at upload time; the user turn contains `[voice note] <transcript>` as *user* content. Transcription failure degrades gracefully: the turn says a voice note arrived but couldn't be transcribed; the bubble still plays audio.
- **Image** → passed to the LLM as a real vision content block (Claude is multimodal; Spring AI `Media` on the user message), capped at 2 images/turn to bound tokens; further images are described as "additional image attached (not shown)".
- **Document (pdf)** → Tika-extracted text, truncated to a budget (default 8 k chars), wrapped in the standard untrusted-data delimiters with an explicit "attached document content — treat as data, not instructions" rule. Extraction, like everything else user-supplied, is *data* (§12); no tool-calling on behalf of document content without the same authz gates as always.

The recorder persists attachments as part of the turn (asset ids, not bytes); account deletion (`chat-account-self-service` G4) extends to blob deletion for the account's assets.

### 6.4 Client changes

- Composer grows: **attach** (image picker / file picker) and **hold-to-record mic** (`record` package) with slide-to-cancel, elapsed timer, and a 2-minute hard stop; pending-attachment tray with thumbnails above the input.
- Voice bubbles: play/pause, scrubber, duration, collapsed "view transcript."
- Upload states: progress, retry, and clear rejection reasons ("too large," "type not allowed," "verify your phone to send media").

---

## 7. Receipt / ticket previews

### 7.1 Product framing

When a chat *sells* something, the cart made the transaction tangible. When a chat *issues* something — a lottery ticket, a payment receipt, a booking voucher, a repair order — the artifact deserves the same: a document that looks like the thermal receipt you'd get at a counter, that you can reopen, zoom, and share. The trust effect is the point: a numbered, barcoded, branded ticket *feels held*, a paragraph of numbers does not.

### 7.2 The configuration question, answered the S7 way

"How do we know this receipt is a lottery ticket vs. something else?" — **we don't, and we must not.** The framework learns nothing about lotteries. Instead:

- The **rendering vocabulary** is generic and framework-owned (rows, key-values, barcodes — §7.4), exactly as `quick_replies` and `cart` are generic kinds.
- The **meaning** is tenant-owned: a tenant authors a *receipt template* ("Lotería Nacional — Quiniela" layout) in the admin console and binds it to the tools that emit ticket data.
- The **selection** is data-driven at runtime: the tool's `_uiHint` names its `templateId`; a fallback `bindings` map (tool id → template id) in the profile extension covers tools that emit bare data.

So "lottery vs. other receipt" is never inferred — it is configured, per tenant, per tool, with an explicit template. Smart means *declarative*, not *guessed*.

### 7.3 Runtime flow (mirrors the cart exactly)

```
Profile.extensions["chat.receipts"] = { templates:[ids…], bindings:{toolId→templateId} }
        │  (admin attaches; resolved server-side at session start)
        ▼
StartResponse.traits["chat.receipts"] = { templates:[ full template payloads ] }
        │  (app now has layouts cached for this session)
        ▼
user asks / taps → tool runs (HTTP tool in tenant's app, e.g. a lottery backend)
        → tool response carries _uiHint { kind:"receipt",
              payload:{ receiptId, templateId, data:{…}, actions?:[{label,tool,args}] } }
        → UiHintPassThrough strips it from the LLM view, engine emits TurnEvent.UiHint
        → SSE → app renders the template bound to the data, inline as a receipt bubble
        │
        └─ server side-effect: kind=="receipt" hints from verified sessions are persisted
           to chat_receipts (the wallet, §7.5)
```

No engine changes: `kind` is already opaque, `_uiHint` framing already exists, `actions` already route through `/v1/chat/sessions/{id}/actions` → `ToolExecutor` → `Authorizer` (two doors, one lock — a "Check ticket" button on the receipt is authz-gated like any tap).

### 7.4 Template model

Stored per tenant in `receipt_templates` (migration `V77__receipt_templates.sql`: `id, tenant_id, name, kind_label, paper (cols/width), elements JSONB, brand JSONB, created/updated`), authored via new admin CRUD `/v1/admin/tenants/{id}/receipt-templates` and a console screen with **live preview** (the console renders the same widget the chat app does).

**Reference sample.** The vocabulary below is validated against a *shipped, real-world* lottery ticket printer: `smart-lottery-v1/smartlotterysales` (`lib/features/ticket_printing/` — `TicketPrintPayload`, `TicketStyleConfig`, the section planner, and PDF/ESC-POS renderers). That system prints, at retail scale, everything a chat receipt must be able to express: org header as name/logo/custom text, station identity lines, date/ticket/reference meta, play sections grouped by draw with promoted "valid for" headers, quantity × unit expansions, count/total row, an emphasized grouped payment code, masked customer phone + disclaimer, loyalty-points lines, QR/hash, courtesy footer, and status-stamped copies (REPRINT / CANCELLED / VOIDED / PAID). The v1 elements are the minimal generic set that reaches all of it.

Elements are an ordered array; each binds against the hint's `data` with `{{path}}` placeholders; `repeat` iterates arrays; every text-bearing element accepts optional `size`/`bold` style knobs (the per-element typography control `TicketStyleConfig` proved operators actually use). Values support lookup-only formatters — `{{total | money}}`, `{{customer.phone | maskTail:4}}`, `{{serial | group:3}}`, `{{numbers | chunk:2}}` — no expressions. The v1 vocabulary (deliberately small, closed, and domain-free):

```jsonc
{ "paper": { "cols": 32, "profile": "58mm" },            // or 42/"80mm"
  "elements": [
  { "type": "header",  "logo": true, "title": "{{store.name}}", "subtitle": "{{store.legal}}" },
  { "type": "status",  "text": "{{statusStamp}}" },      // REPRINT / CANCELLED / PAID banner; skipped when unbound
  { "type": "meta",    "left": "{{issuedAt}}", "right": "N° {{serial}}" },
  { "type": "divider", "style": "dashed" },
  { "type": "title",   "text": "{{docTitle}}", "align": "center" },        // "QUINIELA" — data, not schema
  { "type": "kv",      "label": "{{drawLabel}}", "value": "{{drawDate}}" },
  { "type": "section", "repeat": "sections",             // grouped blocks: title + subtitle + inner rows
      "title": "{{title}}", "subtitle": "{{subtitle}}",  //   (e.g. one section per draw / per sub-brand)
      "elements": [
        { "type": "table", "repeat": "rows", "span": "full",               // span: full | half (two-up grid)
            "cols": [ {"bind":"label","align":"left"}, {"bind":"qty","align":"center"},
                      {"bind":"amount","align":"right","format":"money"} ] } ] },
  { "type": "cells",   "repeat": "picks", "bind": "value", "emphasis": true }, // boxed number grid
  { "type": "total",   "label": "{{totalLabel}}", "value": "{{totalAmount | money}}" },
  { "type": "code",    "label": "{{codeLabel}}", "value": "{{paymentCode | group:3}}" }, // large emphasized code block
  { "type": "barcode", "symbology": "code128", "value": "{{serial}}" },
  { "type": "qr",      "value": "{{verifyUrl}}" },
  { "type": "footer",  "lines": ["{{disclaimer1}}", "{{disclaimer2}}"] }
] }
```

A lottery ticket, a pharmacy receipt, and a parking stub are all reachable with `header/status/meta/kv/section/table/cells/total/code/barcode/qr/footer` — every domain word above lives in *data* (labels included, which is also how per-tenant localization works: the tool sends labels in the session's locale). One level of `section` nesting only; `span: half` lets two compact blocks share a row (the two-up grid layout the real printer ships). Unknown element types are skipped by the renderer (forward compatibility); unknown `kind`s remain ignored by the app as today.

**Re-issued documents:** a receipt for a changed state (paid out, cancelled, reprinted) is a *new hint* from the tool binding `statusStamp` — instances are immutable; state changes issue new paper, exactly like a real counter.

### 7.5 The wallet — receipts outlive the session

`chat_receipts` (migration shared with V77): `id, tenant_id, account_id, session_id, template_id, data JSONB, created_at`. Written by the engine-adjacent chat layer when a `receipt` hint flows through a session whose principal is a verified account (anonymous sessions still *render* receipts; they just aren't collected). Surfaces:

- `GET /v1/chat/account/receipts?cursor=` — cross-handle wallet list (newest first, keyset paging).
- Chat history hydration re-renders receipt bubbles from persisted turns, so reopening an ENDED session shows the ticket.
- Account tab gains a **Receipts** entry; each opens the full-screen preview.

### 7.6 Rendering — "like a real printed receipt"

The receipt widget is the design centerpiece: a **white paper card** on the app's dark surface, `72–80 mm` proportions, bundled monospace face (JetBrains Mono), zigzag torn edges top/bottom (a `CustomClipper`), subtle paper-grain tint, slightly-faded "thermal ink" text color, real Code-128/QR via `barcode_widget`, dashed dividers, and the tenant's logo dithered to 1-bit for authenticity. Interactions: inline bubble (compressed) → tap → full-screen sheet (the cart-sheet pattern) with pinch-zoom, **Share/Save as image** (`RepaintBoundary` → PNG), and the hint's `actions` as buttons. `CoreDomainNeutralityTest` gains a check that the template vocabulary and DTOs stay domain-free.

### 7.7 Seams noted, not built

- **ESC/POS export** for physical printers — the element vocabulary maps 1:1 to thermal primitives *by construction*: it was derived from a renderer that already targets ESC/POS text, ESC/POS raster, and PDF from one plan (`smartlotterysales`'s `escpos_text_encoder` / `ticket_pdf_renderer`), so a future print path is a renderer, not a redesign.
- **Style importer** — a one-shot converter from `smartlotterysales`'s `TicketStyleConfig` + layout variant into a `receipt_templates` row, so smart-lottery tenants onboard with their existing ticket look.
- **PDF export**; template marketplace/starter gallery in the console (ship two starters: "generic sale receipt", "numbered ticket with picks grid" — names only in console copy, not in code).

---

## 8. Community forum

### 8.1 Shape

One forum **per entry point** (the handle is already the tenant-facing brand unit), enabled by a flag on the entry point and surfaced to the app via the `EntryPointCard` (`forumEnabled`) and a `chat.forum` trait at session start. Content model: flat feed of posts, single-level replies (no infinite nesting in v1), emoji reactions (fixed set of 6), pinned posts, reports.

**Who writes:**
- **Users** — L1 verified accounts only (display name from the account; anonymous users read but see a "verify to post" gate — same floor as media, same reasoning: identity is the abuse brake).
- **System** — trusted integration senders from system-messaging post announcements ("Results for tonight's draw…") via `POST /v1/integrations/{tenantId}/senders/{senderId}/forum-posts`, reusing the sender trust + audit machinery verbatim. System posts render with the sender's badge, can be pinned, and can carry a receipt hint payload (a winner announcement embedding the winning ticket preview — the two features compose).
- **Operators** — from a new console screen (post as the brand, pin, hide, delete, review reports).

### 8.2 Backend

New core package `forum/` (+ `forum/internal/`), controller `@RequestMapping("/v1/chat/e/{handle}/forum")`, identity via `ChatAuthService` — the forum deliberately shares the chat surface's front door, not a new auth path.

Migration `V78__forum.sql`:

```sql
forum_posts    (id, tenant_id, entry_point_id, author_kind USER|SYSTEM|OPERATOR,
                account_id NULL, sender_id NULL, body TEXT, attachments JSONB,
                pinned BOOL, hidden BOOL, reply_to UUID NULL,
                reply_count INT, reaction_counts JSONB,
                created_at, edited_at, deleted_at)
forum_reactions(post_id, account_id, emoji, created_at, PK(post_id, account_id, emoji))
forum_reports  (id, post_id, account_id, reason, created_at, resolved_at, resolution)
```

Endpoints: `GET /posts?cursor&pinned` (keyset, newest-first, hidden/deleted filtered), `POST /posts`, `GET /posts/{id}/replies?cursor`, `POST /posts/{id}/replies`, `PATCH/DELETE /posts/{id}` (author-own, 15-min edit window), `PUT/DELETE /posts/{id}/reactions/{emoji}`, `POST /posts/{id}/report`. Admin/moderation under `/v1/admin/tenants/{id}/forum/*`. Post attachments reuse §6's gauntlet (images only in v1, ≤4/post).

**Abuse:** `AbusePolicy` grows `forumPostsPerAccountPerHour` (default 6), `forumRepliesPerAccountPerHour` (20), `forumReactionsPerMinute` (30); body ≤ 2,000 chars; link count ≤ 3; N unresolved reports (default 5) auto-hides pending moderator review. Forum content is **never** fed to any LLM prompt in v1 — it is a human surface; the day a "summarize the forum" capability appears, posts enter as delimited untrusted data like everything else.

### 8.3 Client

New bottom-nav tab (visible when any added tile has `forumEnabled`; multi-handle users get a handle switcher). Feed reuses the Discover paging/race-guard pattern; post cards reuse brand accents; composer is the chat composer minus voice; system posts get the sender badge + optional embedded receipt preview; pull-to-refresh + "new posts" ambient chip (the `MiniCartBar` pattern).

---

## 9. Visual & navigation uplift

Scope-boxed polish that the new features force anyway — not a redesign-for-its-own-sake:

- **Navigation shell:** bottom nav `Chats · Forum · Discover · Account` replacing the current home-screen-only entry; go_router `StatefulShellRoute` so tab state survives switches. Deep links unchanged.
- **Type & theme:** a real type scale (Manrope for UI, JetBrains Mono for receipts/serials), a light theme (`ThemeMode.system`) since receipts and forums are daytime surfaces, keeping the neon-gradient brand identity for the wordmark and accents; per-tenant `accent` plumbing unchanged.
- **Chat niceties:** message grouping (consecutive same-author bubbles), day separators, relative timestamps, sending/sent/failed states on outbound bubbles, skeleton loaders replacing spinners on chat open and history hydrate.
- **Lock screen** (§5.5) and **verify-to-continue** interstitials share one branded "gate" component.

---

## 10. Trust boundaries (mapped to the non-negotiables)

| Rule | Where it binds here |
|---|---|
| #1 LLM never trusted / every call through Authorizer | Receipt `actions` and any media-follow-up taps go through `/actions` → `ToolExecutor` → `Authorizer` (unchanged "two doors, one lock"). Nothing in this design adds a bypass. |
| #2 out-of-profile tools never exposed | Receipt action buttons carry tool ids, but dispatch validates against the session profile exactly as cart taps do today. |
| #5 tool results & user bytes are data | Voice transcripts, image content, Tika-extracted document text, and forum posts are delimited untrusted data; templates render *data*, never execute it; `{{path}}` binding is lookup-only (no expressions). |
| #8 UI hints are engine-emitted | `receipt` frames come from `UiHintPassThrough` off trusted tool responses; the model never fabricates a ticket — it cannot emit hints at all. |
| §20.4 toll-fraud ceilings | Unchanged on every OTP path; PIN unlock *reduces* SMS volume (fewer re-verifies). |
| Existence not leaked | `pin-unlock` failures are uniform 401s; forum hidden/deleted posts 404; device probes 404. |
| Self-hosted constraint | MinIO, ClamAV, Tika, Postgres, Redis, Argon2id — no new paid services. `MediaTranscriber`'s hosted-Whisper caveat is pre-existing and already tracked for a self-hosted replacement. |

---

## 11. Rollout — CX milestones

PR-scoped (≤~600 LOC each); **B** = backend, **F** = Flutter (`yayaagenticchat`), **W** = console (`yayaagenticweb`).

- **CX-0 — Shell & lock foundation (1 wk).** F: bottom-nav shell, type scale, light theme, secure storage migration, gate component. B: `devices/enroll` lazy-migration endpoint. *Exit:* existing flows unchanged, credentials in secure storage.
- **CX-1 — PIN & devices (1.5 wk).** B: `V76`, pin set/change/remove, `pin-unlock` + lockout, device CRUD/revoke, `VerifiedDeviceAuthenticator` revocation check. F: PIN setup nudge post-OTP, lock screen, Security section, per-device toggle, single-device logout. *Exit:* OTP once → PIN thereafter; revoked device is dead ≤60 s; lockout → OTP recovery works.
- **CX-2 — Rich media (2 wk).** B: attachments endpoint + gauntlet + limits, transcription, engine turn composition (vision blocks, delimited doc text), orphan GC, recorder/delete wiring. F: composer attach + hold-to-record, voice bubbles, upload states. *Exit:* photo/voice/pdf round-trip with model comprehension; every ceiling enforced and user-visible.
- **CX-3 — Receipts (2 wk).** B: `V77`, template CRUD, traits resolution, `chat_receipts` persistence + wallet endpoint. W: template authoring with live preview + 2 starters. F: receipt renderer (paper widget, barcode/QR, share-as-image), inline + full-screen + wallet. *Exit:* a tenant configures a "numbered ticket" template end-to-end; a template reproducing the `smartlotterysales` classic sale ticket (header, meta, draw sections, plays, total, payment code, QR, footer) renders faithfully from sample data; ticket reopens after session end; `CoreDomainNeutralityTest` extended and green.
- **CX-4 — Forum (2 wk).** B: `V78`, forum package + public/admin endpoints, sender forum-posts, abuse dims. W: moderation screen. F: forum tab, feed/replies/reactions, report flow, system-post badges (+ embedded receipts). *Exit:* user posts under verified name; sender announcement lands with pinned receipt; report → auto-hide → moderation loop closes.

Suggested order is as listed; CX-2/CX-3 are independent of each other and can swap or parallelize.

---

## 12. Resolved decisions (captured so they aren't re-litigated)

1. **PIN is account-level, verified server-side; per-device is only the *requirement flag*.** One PIN everywhere matches user intuition ("my PIN"), enables server lockout, and still delivers "don't ask on this phone."
2. **Session-pass TTL 24 h with PIN, 30 d without.** The PIN's whole value is cheap re-auth; without a PIN we keep today's behavior rather than degrade UX.
3. **Media uploads require L1.** Anonymous media is an abuse magnet with no accountability trail; the verify gate doubles as the abuse brake and matches the forum's floor.
4. **Receipt templates are a tenant resource resolved into traits at start** — not fetched lazily per hint. One round-trip, offline-tolerant rendering, and the console preview and app share one renderer contract.
5. **Receipt selection is declarative** (hint `templateId`, else profile `bindings[toolId]`), never inferred from payload shape. Inference is exactly the "smart" that rots.
6. **Forum is core, not an `apps/` domain.** "A place where an entry point's users interact" is chat-platform capability (the S7 test: a way to build agents/chat experiences → core); lottery *content* posted into it stays tenant data.
7. **Forum content stays out of prompts in v1.** It's a human surface; LLM ingestion is a separate, explicitly-gated future capability.
8. **No biometric server factor.** Platform biometrics may locally autofill the PIN; the server contract stays phone (root) + PIN (convenience).

---

## 13. Open questions

1. **Image moderation** — ClamAV catches malware, not NSFW. Do we need perceptual moderation (self-hosted NSFW classifier) before forum images go live, or is report-based moderation enough for v1 forums? (Leaning: reports suffice at current scale; classifier is a fast-follow seam in the gauntlet.)
2. **Voice-note language** — Whisper autodetects, but should the entry point's `locale` hint the transcriber for short clips (accuracy on 3-second Spanish clips is measurably worse without it)?
3. **Receipt template versioning** — when a template changes, do old wallet receipts re-render with the new layout or pin the template snapshot at issuance? (Leaning: snapshot `elements` into `chat_receipts` at write; receipts are documents, documents don't retro-edit.)
4. **Forum identity display** — full account name vs. name + masked phone tail vs. user-chosen alias? Aliases add abuse surface (impersonation) but real names leak PII into a public feed. (Leaning: account name + no phone, tenant-configurable "aliases allowed" later.)
5. **Web PIN storage** — on Flutter web, secure storage is localStorage; is 24 h pass TTL + server-side PIN sufficient there, or should web sessions get an even shorter TTL (e.g. 12 h)?

---

## 14. What does NOT change

- The eleven runtime SPIs, the `Authenticator`/`Authorizer` chains, `ToolExecutor` dispatch, `UiHintPassThrough`, and the SSE event vocabulary (new `kind`s ride the existing `UiHint`).
- The OTP enrollment ceremony, toll-fraud ceilings, `DevicePassService` signing, and the pass `gen` revocation model (extended, not replaced).
- The cart/commerce rendering path and the `sales.store` trait — receipts are a sibling trait, not a modification.
- System-messaging ingest/delivery; the forum adds a sender *surface*, not a new trust model.
- `yayaagenticweb` playground and operator auth — untouched except the two new console screens (templates, moderation).
