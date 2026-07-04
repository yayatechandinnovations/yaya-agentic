# Chat App — End-User Account Self-Service & Session Persistence

> **Status:** Proposal (draft 1)
> **Owner:** Yenfry Herrera Feliz (architect / PM)
> **Audience:** chat-channel + engine engineers working on `yayaagenticchat` and the public `/v1/chat/*` surface
> **Last updated:** 2026-07-03
> **Companion to:** `docs/design/chat-channel-design.md` (§20 phone-verified identity), `docs/design/yaya-agentic-design.md` (§5.7 Session & Memory, §5.9 Conversation Recording, §12 non-negotiables)
> **Distinct from:** `docs/design/yaya-agentic-user-accounts-design.md` — that doc is the **admin/operator plane** (operators who sign into the console). This doc is the **end-user plane**: the human chatting in `yayaagenticchat`.

> **Naming note.** In this framework **Profile** is a reserved primitive — the *role* a bot fronts (`retail-customer`, `merchant-agent`; design §5.2). The end user's "edit my profile" is therefore modelled here as an **Account**, never a Profile. When this doc says "account" it means the end-user's phone-verified identity; when it says "profile" it means the framework role. They never mix.

---

## 1. Summary

`yayaagenticchat` already gives every device a **phone-verified identity** (chat-channel §20): first-run onboarding collects phone + name, Twilio Verify sends an OTP, and a successful check mints a **device pass** — a platform-signed HS256 JWT the app presents on every request to be recognized as `chat:+E164`. That identity is persisted in one place: the `chat_accounts` table (`phone_e164` PK, `name`). But today the account is **write-once and invisible to the user**: there is no screen to see or edit it, no way to change the phone that *is* the identity, no way to delete it, and — critically — **no server-side link between the account and the conversations it has held.** Sessions are keyed by `principal_subject` string and remembered only by an on-device `sessionId` that the app stores but never actually resumes (chat-channel §16.1, open).

This design turns that write-once record into a **real account the user owns**. Four capabilities, plus the architectural move that makes the hard ones safe:

1. **Edit name** — a settings screen; re-mints the pass so the new name flows to the profile.
2. **Change phone number** — a **dual-OTP** ceremony: prove control of the **old** number *first* (intent is the right person), then prove control of the **new** number, then migrate — **without losing history.**
3. **Delete account** — OTP-confirmed, PII-scrubbed, device-passes revoked, conversations deleted via the `ConversationRecorder` SPI (GDPR path).
4. **Persistent, resumable chats** — a returning user (even days later, even on a reinstalled device) finds their chats where they left them, and on opening one is offered **"Continue"** *or* **"Start new."**

**The enabling move (§5):** introduce a **stable `account_id` (UUID)** as the durable identity, with the **phone as a mutable attribute**, and **link sessions to `account_id`** rather than to the phone string. This is the single decision that makes phone-change lossless and cross-device resume possible — everything else composes on top of infrastructure that already ships.

### 1.1 Non-goals (v1)

- **Not** a password/email login. The phone (verified by OTP) remains the sole credential; we are not adding a second factor or a username.
- **Not** cross-account merge. If a user verifies a second phone, that's a second account — no linking, no merge UI.
- **Not** end-to-end message backup/export UI for the user (the recorder *export* path exists and we wire the delete/export **API**, but a user-facing "download my data" screen is deferred).
- **Not** a change to the eleven runtime SPIs (design §5). We reuse `PhoneVerifier`, `DevicePassService`, `ChatAccountService`, `ChatRateLimiter`, and `ConversationRecorder` as-is or with additive methods only.

---

## 2. Goals & Non-Goals

### 2.1 Goals (v1)

- **G1.** An in-app **Account** screen: view phone + name, edit name, change phone, delete account, sign out.
- **G2.** **Edit name** updates `chat_accounts.name` and re-mints the device pass so the new name reaches `EntityMemory` / the profile on the next session.
- **G3.** **Change phone** requires, in order: (a) an OTP to the **current** number, verified, then (b) an OTP to the **new** number, verified — then migrates the account. The old number's OTP is the "right person" gate; the new number's OTP proves control of the destination.
- **G4.** **Delete account** is OTP-confirmed, soft-deletes + PII-scrubs the account, **revokes all outstanding device passes**, and deletes the account's conversations via `ConversationRecorder.deleteSession`.
- **G5.** **Chat persistence:** a returning verified user's added chats and their most-recent conversation per chat are recovered **server-side, keyed to the account** — surviving app restart, and (after re-verify) a device reinstall or a new device.
- **G6.** **Resume decision:** opening a chat with a live, non-expired session offers **Continue** (hydrate prior turns, keep going) or **Start new** (fresh session, old one ended) — the model never invents this; it's engine/app-driven.
- **G7.** Every new public surface inherits the **toll-fraud rate limits** (chat-channel §20.4) — no new endpoint sends an SMS without the per-IP / per-phone / global-daily ceilings.

### 2.2 Non-Goals (v1)

- **Not** editable beyond name + phone (no avatar, no address book) — *seam:* `chat_accounts` is the extension point.
- **Not** phone-change without controlling the old number (no "lost my old SIM" recovery flow) — *seam:* a support-mediated recovery is a later, audited path. See §14.1.
- **Not** multi-device *live* session sync (two devices streaming the same session at once). Resume is "pick up where you left off," not real-time fan-out.

---

## 3. Glossary

| Term | Definition |
|---|---|
| **Account** | The end user's durable, phone-verified identity in the chat app. New in this design: a stable `account_id` (UUID) with phone as a mutable attribute. Backed by `chat_accounts`. |
| **Device pass** | Platform-signed HS256 JWT minted on OTP success (chat-channel §20). Claims `sub=chat:+E164`, `phone`, `name`, `deviceId`. This design adds `acct` (account id) and `gen` (revocation generation). |
| **Pass generation (`gen`)** | A monotonically increasing integer on the account. A device pass is valid only if its `gen` matches the account's current `gen`. Bumping `gen` (on delete, phone-change, or explicit "sign out everywhere") **revokes every outstanding pass** without a per-token deny-list. |
| **Phone-change ceremony** | The dual-OTP state machine (§6.2): old-number OTP → new-number OTP → migrate. Persisted in `chat_phone_changes`. |
| **Resumable session** | A session for `(account_id, entry_point)` with `ended_at IS NULL` whose last activity is within the entry point's idle window. The candidate the app offers to "Continue." |
| **Directory tile** | A chat the user has added (handle + branding). Today on-device only; this design mirrors it server-side, keyed to the account, for cross-device recovery. |

---

## 4. Where this sits — the identity model (recap + the gap)

The three-layer model from chat-channel §20.1 is unchanged; this design hardens **L1**:

| Layer | Principal | Trust authority | Owns |
|---|---|---|---|
| **L0** anonymous | `chat:{handle}:anon:{deviceId}` | none | nothing |
| **L1** verified-phone **account** | `chat:+E164` (+ stable `account_id`) | **platform** (Twilio Verify) | name, phone, added chats, session history |
| **L2** tenant account | JWT subject | tenant (OIDC / resolver) | tenant-side account actions |

**The gap this closes.** L1 was a *fact* ("this device holds a verified phone") but not an *account* ("this identity, which I can manage and whose history follows me"). Two concrete problems:

1. **Phone == identity.** Because `chat_accounts.phone_e164` is the primary key *and* the principal subject is `chat:+E164`, changing the phone means changing the identity — orphaning every session recorded under the old subject. That makes a safe phone-change impossible without the §5 move.
2. **No account ↔ conversation link.** `sessions.principal_subject` is just a string; nothing joins "this account" to "its conversations." Resume works only if the *same install* still has the `sessionId` cached (chat-channel §16.1). Reinstall = amnesia.

Both are fixed by making `account_id` the durable key and stamping it on sessions.

---

## 5. The enabling move — stable `account_id` + session linkage

### 5.1 Account becomes id-keyed, phone becomes an attribute

`chat_accounts` is re-shaped (migration in §9.1) so the **UUID is the identity** and the **phone is mutable**:

```
chat_accounts
  id            UUID  PK              -- NEW: the durable identity
  phone_e164    VARCHAR(20) UNIQUE    -- was PK; now a mutable unique attribute
  name          VARCHAR(120)
  status        VARCHAR(16)           -- NEW: ACTIVE | DELETED
  pass_gen      INT   NOT NULL = 1    -- NEW: device-pass revocation generation
  created_at, updated_at, deleted_at
```

`ChatAccountService` keeps its two existing methods and gains id-aware ones (`findByPhone`, `rename`, `changePhone`, `softDelete`, `bumpGeneration`). `completeVerification` now upserts by phone but returns the `account_id` too, so the verify flow can stamp it on the pass.

### 5.2 Device pass carries `acct` + `gen`

`DevicePassService.issue(...)` adds two claims — `acct` (the account UUID) and `gen` (the account's current `pass_gen`). Verification (`VerifiedDeviceAuthenticator`, the chat analog of `WhatsAppPhoneAuthenticator`) gains **one** new check:

> the pass is valid **iff** signature + expiry + type pass **and** `gen` equals the account's current `pass_gen`.

This is the revocation primitive. Delete, phone-change, and "sign out everywhere" all just `pass_gen++`, and every previously-minted pass stops authenticating on its next use — no per-token blocklist. The generation lookup is a single indexed read on `chat_accounts` cached in Redis (key `acct:gen:{id}`, short TTL, evicted on bump), so the hot path stays cheap. Passes with **no** `acct`/`gen` claim (issued before this ships) are grandfathered for one pass-TTL window (30 days) by falling back to phone lookup, then expire naturally.

### 5.3 Sessions link to the account

`sessions` gains two nullable columns, written by `PublicChatController` at session start when a valid device pass is present:

```
sessions
  ...
  chat_account_id  UUID NULL         -- NEW: the L1 account that started this session
  entry_point_id   VARCHAR(64) NULL  -- NEW: which entry point (for per-chat resume)
```

`chat_account_id` is the join that makes history follow the account across devices and across phone changes; `entry_point_id` scopes "the latest session for *this* chat." Both are **NULL for pure L0 anonymous** sessions — anonymity is preserved (chat-channel §16.3: anonymous turns stay anonymous). Index: `(chat_account_id, entry_point_id, ended_at, created_at DESC)`.

> **Why not rewrite `principal_subject` on phone change?** Because the subject is embedded in recorded turns, audit rows, and possibly token-at-rest metadata; rewriting it is a lossy, cross-table migration per user. Linking by a stable `account_id` sidesteps all of it — old sessions keep their old subject string, but they're still *this account's* sessions.

---

## 6. Account self-service flows

All three flows require a **valid L1 device pass** (the caller must already be the account holder). All OTP sends reuse `PhoneVerifier` + `ChatRateLimiter` under the §20.4 ceilings.

### 6.1 Edit name

```
App (Account screen) → PATCH /v1/chat/account  { name }        [Authorization: device pass]
  PublicChatController:
    verify pass → account_id, phone
    ChatAccountService.rename(account_id, name)                 -- updates chat_accounts.name
    DevicePassService.issue(new VerifiedDevice(phone, name, deviceId, account_id, gen))
    → 200 { pass, name }                                        -- app swaps its stored pass
```

- No OTP — the caller already proved the phone at pass-mint time and re-proves it every request via the signed pass. Renaming is low-risk and self-service.
- The new name reaches the profile/LLM on the **next** session (it enters via `EntityMemory`, self-asserted, per the §20.4 rule — never auto-projected). A live session keeps the old name until it's resumed; acceptable and noted in-app.

### 6.2 Change phone number — the dual-OTP ceremony

This is the load-bearing flow. Requirement, verbatim: *a code to the **old** number to confirm intent by the right person, then a confirmation code on the **new** number.* Modeled as a short-lived state machine persisted in `chat_phone_changes`.

```
             ┌──────────── start ───────────┐
             ▼                               │
   ┌───────────────┐   verify-old ok   ┌──────────────┐  verify-new ok  ┌──────────┐
   │  OLD_PENDING  │ ────────────────▶ │  NEW_PENDING │ ──────────────▶ │ COMPLETE │
   │ (OTP→old #)   │                   │ (OTP→new #)  │                 │ migrate  │
   └───────┬───────┘                   └──────┬───────┘                 └──────────┘
           │ expire / abort / too many attempts │
           ▼                                    ▼
       ┌──────────────────────────────────────────┐
       │                ABORTED                     │
       └───────────────────────────────────────────┘
```

**Step 1 — start (OTP to the *old* number).**
```
POST /v1/chat/account/phone-change/start  { newPhoneE164 }     [device pass]
  verify pass → account_id, oldPhone
  validate: newPhone is E.164, != oldPhone,
            and NOT already an ACTIVE account  → else 409 "phone_unavailable"  (§11 threat note)
  rate-limit: verify-start-{ip,phone(old),global}
  PhoneVerifier.start(oldPhone)                                -- code to the CURRENT number
  insert chat_phone_changes(id, account_id, oldPhone, newPhone, state=OLD_PENDING, expires_at=now+15m)
  → 200 { changeId, state: "OLD_PENDING" }
```
Sending to the **old** number first is the "right person" gate: only whoever controls the number currently on the account can initiate a move.

**Step 2 — verify old, which triggers the new-number OTP.**
```
POST /v1/chat/account/phone-change/verify-old  { changeId, code }   [device pass]
  load change (must be OLD_PENDING, not expired, belongs to caller's account)
  rate-limit: verify-check-phone(oldPhone)
  PhoneVerifier.check(oldPhone, code)  → approved?
    yes: rate-limit verify-start-{ip,phone(new),global}
         PhoneVerifier.start(newPhone)                          -- code to the NEW number
         state = NEW_PENDING; old_verified_at = now
         → 200 { state: "NEW_PENDING" }
    no:  attempts++; → 401 "verify_denied"  (abort after N)
```

**Step 3 — verify new, then migrate.**
```
POST /v1/chat/account/phone-change/verify-new  { changeId, code }   [device pass]
  load change (must be NEW_PENDING, not expired)
  rate-limit: verify-check-phone(newPhone)
  PhoneVerifier.check(newPhone, code) → approved?
    yes: re-check newPhone STILL unclaimed (race guard) → 409 if taken
         ChatAccountService.changePhone(account_id, newPhone):
           UPDATE chat_accounts SET phone_e164=newPhone, pass_gen = pass_gen+1
         state = COMPLETE
         DevicePassService.issue(phone=newPhone, gen=new)       -- fresh pass for the caller
         → 200 { pass, phone: newPhone }
    no:  attempts++; → 401
```

**Why history survives.** Because sessions are joined by `chat_account_id` (§5.3), *not* by the phone string, the migration touches exactly one row (`chat_accounts`) plus a `pass_gen` bump. Every prior conversation, every directory tile, stays attached. The `pass_gen++` **revokes the old device's pass** — a nice property: if the phone changed because the old device/SIM is gone, its pass is now dead. The caller (who completed the ceremony) gets a fresh pass in the response.

**Helpers:** `POST …/phone-change/resend` (re-send current step's OTP, rate-limited), `POST …/phone-change/abort` (→ ABORTED), automatic expiry at 15 min. Only one active change per account (starting a new one aborts the prior).

### 6.3 Delete account

```
POST /v1/chat/account/delete/start                              [device pass]
  verify pass → account_id, phone
  rate-limit: verify-start-{ip,phone,global}
  PhoneVerifier.start(phone)                                    -- confirm intent via OTP
  → 200 { state: "PENDING" }

POST /v1/chat/account/delete/confirm  { code }                 [device pass]
  rate-limit: verify-check-phone(phone)
  PhoneVerifier.check(phone, code) → approved?
    yes: ChatAccountService.softDelete(account_id):
           status=DELETED, deleted_at=now, name=NULL (PII scrub), pass_gen++   -- revokes all passes
         delete chat_account_tiles WHERE account_id=…
         enqueue conversation delete: for each session WHERE chat_account_id=…
           ConversationRecorder.deleteSession(sessionId, DeleteRequest)        -- SPI, design §5.9
         → 204
```

- **Revocation is immediate** via `pass_gen++` — the deleting device's pass (and any other device on the account) stops authenticating on next use.
- **Conversation deletion goes through the recorder SPI**, never direct SQL (non-negotiable §5.9 / CLAUDE.md #4). We reuse the existing `deleteSession` operation; a background worker drains the enqueued session ids so a user with many sessions gets a fast `204` and eventual full erasure.
- **Soft-then-hard.** The row is soft-deleted + scrubbed immediately; a scheduled job hard-deletes the `chat_accounts` row after a short grace window (matches the admin-plane GDPR posture in `yaya-agentic-user-accounts-design.md` §11). The phone frees up for a fresh registration once hard-deleted.
- The app clears local storage (device pass, phone, name, tiles) and returns to first-run onboarding.

---

## 7. Chat persistence & session resume

### 7.1 Server-side directory (tiles) backup

Today the added-chats list lives only in `SharedPreferences` (`yaya.chat.tiles`). We mirror it server-side, keyed to the account, so a re-verified device recovers it:

```
chat_account_tiles
  account_id   UUID  → chat_accounts(id)
  entry_point_id VARCHAR(64)  → chat_entry_points(id)
  handle       VARCHAR(64)             -- last-known handle (handles rotate; entry_point_id is the stable key)
  added_at     TIMESTAMPTZ
  PRIMARY KEY (account_id, entry_point_id)
```

- **Write:** when a verified user adds a chat, the app calls `PUT /v1/chat/account/tiles/{entryPointId}`; on remove, `DELETE`. Best-effort — on-device store stays the fast path and offline cache; the server copy is the recovery source.
- **Read on cold start:** `GET /v1/chat/account/tiles` returns the account's tiles (re-resolved to current branding). The app merges server + local (union), so nothing is lost if the user added chats while offline.

### 7.2 Session continuity & the Continue-vs-New decision

The engine already records sessions durably (design §5.7, §5.9); what's missing is a way to *find the right one to resume*. With `chat_account_id` + `entry_point_id` on `sessions` (§5.3), the app asks:

```
GET /v1/chat/e/{handle}/sessions/latest                        [device pass]
  resolve handle → entry_point_id
  find latest session WHERE chat_account_id = acct AND entry_point_id = ep
       ORDER BY created_at DESC LIMIT 1
  classify:
    LIVE   → ended_at IS NULL AND (now - last_turn_at) < entryPoint.idleTimeout
    ENDED  → otherwise (a session exists but is closed/stale)
    NONE   → no session for this account+entry point
  → 200 { status, sessionId?, lastActivityAt?, turnCount?, preview? }
```

**The decision, in the app:**

| `status` | What the app shows |
|---|---|
| `LIVE` | Chat opens with a banner: **"Continue where you left off"** + a **"Start new chat"** action. Continue → hydrate history (below) and resume streaming into the same `sessionId`. Start new → `POST …/sessions`, and the old session is ended. |
| `ENDED` | Show the prior transcript read-only above a fresh composer; the first message silently starts a new session. (i.e. "here's your history, say something to continue"). |
| `NONE` | Normal first-time greeting via `POST …/sessions`. |

**Hydrating history.** Resume needs the prior turns. We extend the existing `GET /v1/chat/sessions/{id}` (which today only checks aliveness) to optionally page recorded turns via the recorder:

```
GET /v1/chat/sessions/{id}?history=true&limit=50               [device pass, must own the session]
  authorize: session.chat_account_id == caller.account_id   → else 404 (no existence leak)
  ConversationRecorder.loadSession(id, LoadOptions(lastN=limit))   -- SPI, design §5.9
  → 200 { status, turns: [{ role, text, citations, quickReplies?, ... }], commerce? }
```

Ownership is enforced against `chat_account_id`; a mismatch returns **404, not 403** (same existence-hiding posture as the admin plane and handles). The engine still never touches storage directly — reads go through `ConversationRecorder.loadSession` (non-negotiable §5.9).

**"Even if they come later."** Because the candidate lives server-side and is classified by the entry point's idle window, the resume offer is correct whether the user returns in 2 minutes (LIVE) or 2 days (likely ENDED → transcript + fresh session). App restart, force-quit, or device swap (after re-verify) all resolve to the same server truth.

---

## 8. API surface

All routes are public (`/v1/chat/*`), all require a valid device pass in `Authorization` (except where noted), all mutating SMS-sending routes carry the §20.4 rate limits.

| Method | Path | Purpose |
|---|---|---|
| `GET`   | `/v1/chat/account` | View my account `{ phone, name, createdAt }` |
| `PATCH` | `/v1/chat/account` | Edit name → re-minted pass (§6.1) |
| `POST`  | `/v1/chat/account/phone-change/start` | Begin phone change; OTP → **old** number (§6.2) |
| `POST`  | `/v1/chat/account/phone-change/verify-old` | Verify old-number code; OTP → **new** number |
| `POST`  | `/v1/chat/account/phone-change/verify-new` | Verify new-number code; migrate; re-minted pass |
| `POST`  | `/v1/chat/account/phone-change/resend` | Resend current step's OTP |
| `POST`  | `/v1/chat/account/phone-change/abort` | Cancel the ceremony |
| `POST`  | `/v1/chat/account/delete/start` | OTP → account number to confirm intent (§6.3) |
| `POST`  | `/v1/chat/account/delete/confirm` | Verify; soft-delete + scrub + revoke + erase conversations |
| `POST`  | `/v1/chat/account/sign-out-everywhere` | `pass_gen++` (revoke all passes) without deleting |
| `GET`   | `/v1/chat/account/tiles` | My server-side chat directory (§7.1) |
| `PUT`   | `/v1/chat/account/tiles/{entryPointId}` | Add/keep a chat server-side |
| `DELETE`| `/v1/chat/account/tiles/{entryPointId}` | Remove a chat server-side |
| `GET`   | `/v1/chat/e/{handle}/sessions/latest` | Resume candidate for this chat (§7.2) |
| `GET`   | `/v1/chat/sessions/{id}?history=true` | Hydrate prior turns (extends existing endpoint) |

New DTOs live in `PublicChatDtos`; the controller stays `PublicChatController` (or a sibling `ChatAccountController` under the same package to keep it focused — recommended, mirrors `ChatVerifyController`).

---

## 9. Data model (Postgres, migrations V6x+)

### 9.1 Re-shape `chat_accounts` (id-keyed)

```sql
-- Migrate chat_accounts from phone-PK to UUID-PK with phone as a unique attribute.
ALTER TABLE chat_accounts ADD COLUMN id UUID;
UPDATE chat_accounts SET id = gen_random_uuid() WHERE id IS NULL;
ALTER TABLE chat_accounts ALTER COLUMN id SET NOT NULL;

ALTER TABLE chat_accounts ADD COLUMN status   VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE chat_accounts ADD COLUMN pass_gen INT         NOT NULL DEFAULT 1;
ALTER TABLE chat_accounts ADD COLUMN deleted_at TIMESTAMPTZ;

-- Swap the primary key: phone stops being the identity, becomes a unique attribute.
ALTER TABLE chat_accounts DROP CONSTRAINT chat_accounts_pkey;
ALTER TABLE chat_accounts ADD  PRIMARY KEY (id);
ALTER TABLE chat_accounts ADD  CONSTRAINT chat_accounts_phone_uq UNIQUE (phone_e164);
-- Partial-unique so a freed (DELETED) phone can be re-registered:
-- enforce uniqueness only among ACTIVE rows.
DROP   INDEX IF EXISTS chat_accounts_phone_uq;
CREATE UNIQUE INDEX chat_accounts_active_phone_uq
   ON chat_accounts (phone_e164) WHERE status = 'ACTIVE';
```

### 9.2 Phone-change ceremony state

```sql
CREATE TABLE chat_phone_changes (
  id             UUID PRIMARY KEY,
  account_id     UUID NOT NULL REFERENCES chat_accounts(id) ON DELETE CASCADE,
  old_phone      VARCHAR(20) NOT NULL,
  new_phone      VARCHAR(20) NOT NULL,
  state          VARCHAR(16) NOT NULL,     -- OLD_PENDING | NEW_PENDING | COMPLETE | ABORTED
  old_verified_at TIMESTAMPTZ,
  attempts       INT NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at     TIMESTAMPTZ NOT NULL
);
-- At most one in-flight change per account.
CREATE UNIQUE INDEX chat_phone_changes_active_uq
   ON chat_phone_changes (account_id) WHERE state IN ('OLD_PENDING','NEW_PENDING');
```

### 9.3 Server-side directory + session linkage

```sql
CREATE TABLE chat_account_tiles (
  account_id     UUID NOT NULL REFERENCES chat_accounts(id) ON DELETE CASCADE,
  entry_point_id VARCHAR(64) NOT NULL REFERENCES chat_entry_points(id) ON DELETE CASCADE,
  handle         VARCHAR(64),
  added_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, entry_point_id)
);

ALTER TABLE sessions ADD COLUMN chat_account_id UUID;      -- L1 account (NULL for anon)
ALTER TABLE sessions ADD COLUMN entry_point_id  VARCHAR(64);
CREATE INDEX sessions_account_resume_ix
  ON sessions (chat_account_id, entry_point_id, ended_at, created_at DESC);
```

> Note: `sessions.chat_account_id` is intentionally **not** a hard FK — sessions can outlive a hard-deleted account (they're erased via the recorder on its own schedule), and the recorder owns the `sessions` lifecycle (design §5.9). It's a soft link + index.

---

## 10. App (Flutter) changes — `yayaagenticchat`

New feature folder `lib/features/account/`:

- **`account_screen.dart`** — reached from a new avatar/gear entry in the directory header. Shows phone (read-only) + name (inline-editable) + **Change phone number**, **Delete account**, **Sign out** rows.
- **`change_phone_flow.dart`** — a 3-step wizard mirroring §6.2: enter new number → enter code sent to **old** number → enter code sent to **new** number → success. Reuses the OTP input widget from `onboarding_screen.dart`. On success, swap the stored device pass. The old-number step carries a **"lost access to your old number? contact support"** link (decision §14.1) — there is no self-serve bypass.
- **`delete_account_dialog.dart`** — explains consequences, sends OTP, confirms with code, then clears `DirectoryStore` and routes back to onboarding.

Data layer:

- **`chat_api.dart`** gains the §8 endpoints; the `dio` client already injects `X-Device-Pass` / bearer.
- **`directory_store.dart`** stays the local cache; a new `AccountRepository` reconciles it with `/v1/chat/account/tiles` on cold start (union merge, §7.1).
- **`chat_controller.dart`** — on chat open, call `…/sessions/latest`. If `LIVE`, render the **Continue / Start new** banner; Continue hydrates via `…/sessions/{id}?history=true` and resumes streaming into the existing `sessionId`; Start new calls `POST …/sessions`. This is where today's dead-stored `sessionId` finally gets used (closes chat-channel §16.1).
- Localize new strings in `app_en.arb` / `app_es.arb` (the app is already en/es).

---

## 11. Security & trust boundaries

Alignment to the master non-negotiables (design §12 / CLAUDE.md) and the chat-channel abuse posture:

| # | Rule | How this design complies |
|---|---|---|
| §12 #4 | Engine never touches storage directly | History hydrate uses `ConversationRecorder.loadSession`; delete uses `ConversationRecorder.deleteSession`. No direct SQL on turns. |
| §20.4 | Toll-fraud ceilings on every SMS send | `phone-change/*` and `delete/start` reuse `ChatRateLimiter` with the exact `verify-start-{ip,phone,global}` + `verify-check-phone` keys already gating `/verify/*`. |
| §20.4 | Phone/name are PII, self-asserted | Name change flows to the LLM only via `EntityMemory`, never auto-projected; phone never auto-projected. |
| Existence not leaked | Session-history + resume endpoints authorize by `chat_account_id` and return **404** on mismatch, matching the handle/tenant existence-hiding posture. |
| Two-reason denials | AuthZ denials on any tool reached during a resumed session still carry distinct `userSafeReason`/`auditReason` — unchanged. |

**Design-specific threats:**

| Threat | Mitigation |
|---|---|
| **Account takeover via phone change** | The **old-number OTP is mandatory and first** — an attacker holding only a stolen device pass (not the SIM) cannot complete step 1's `verify-old`. Completion also requires the new-number OTP. Two independent SMS proofs. |
| **Hijacking a victim's number as the "new" number** | `start` rejects a `newPhone` that's already an ACTIVE account (`409 phone_unavailable`), and step 3 re-checks under a race guard. Sending an OTP to an arbitrary new number is rate-limited per that number and globally; it delivers no account access without the code. |
| **Existence leak via phone-change start** | `409 phone_unavailable` does reveal that *some* account owns the target number — but only to a caller who already holds a valid pass for their *own* account, and only for a number they typed. Bounded; logged. Alternative considered: always proceed and fail at completion — rejected because it would SMS-spam numbers that belong to others. |
| **Stale/stolen device pass after delete or phone change** | `pass_gen++` on delete, phone-change, and sign-out-everywhere revokes **all** outstanding passes; the `gen` check (cached in Redis, evicted on bump) enforces it on the next request. |
| **SMS pumping via the new surfaces** | Every send goes through the same ceilings as `/verify/*`; the ceremony caps attempts and expires in 15 min; one in-flight change per account. |
| **GDPR erasure completeness** | Delete scrubs PII immediately, enqueues recorder `deleteSession` for every linked session, and hard-deletes the account row after the grace window. Audit rows keep only the non-PII `account_id`. |

---

## 12. Observability

- **Metrics:** phone-change starts / old-verified / completed / aborted / expired; delete starts / confirms; resume-candidate outcomes (`LIVE`/`ENDED`/`NONE`); Continue-vs-New taps; pass-generation bumps (revocations).
- **Audit:** one row per account-state change (`NAME_CHANGE`, `PHONE_CHANGE`, `DELETE`, `SIGN_OUT_ALL`) with `account_id`, client IP, timestamp — no phone/name in the audit body (PII stays in the scrubbable `chat_accounts` row).
- **Reuse:** the existing chat rate-limit rejection metrics and, where the entry point is authenticated, the `ChannelAuthTrace` inspector already cover the auth surface.

---

## 13. Milestone breakdown (A0 → A3)

Following the `C0–C5` / `S0–S5` convention.

- **A0 — Account identity core (0.5 wk).** `chat_accounts` re-shape (V6x: UUID PK, `status`, `pass_gen`, partial-unique phone); `DevicePassService` `acct`+`gen` claims + `VerifiedDeviceAuthenticator` generation check with grandfather window; `ChatAccountService` id-aware methods. No user-facing change yet; existing verify flow keeps working.
- **A1 — Self-service backend (1 wk).** `ChatAccountController`: view/rename, phone-change state machine (`chat_phone_changes`), delete (soft-delete + scrub + `pass_gen++` + recorder delete worker), sign-out-everywhere. Full rate-limit coverage. Backend tests: dual-OTP happy path + every abort/expiry/collision branch; revocation.
- **A2 — Persistence & resume (1 wk).** `sessions.chat_account_id`/`entry_point_id` stamping at start; `chat_account_tiles` + tile endpoints; `…/sessions/latest`; history hydrate on `GET …/sessions/{id}`. Recorder `loadSession` paging wired.
- **A3 — App (1.5 wk).** Account screen, change-phone wizard, delete dialog, server-tile reconciliation, Continue-vs-New banner + history hydration in `chat_controller`. en/es strings. `flutter analyze` clean.

### Acceptance criteria

- **Dual-OTP correctness:** phone change succeeds only when *both* OTPs verify, in order; skipping/forging either leaves the account untouched; history and tiles are intact post-change; the old pass is revoked and the new pass authenticates.
- **Delete completeness:** after confirm, the pass no longer authenticates (401), the account is scrubbed, tiles are gone, and every linked session is deleted via the recorder; the phone can be re-registered fresh.
- **Resume:** a user who force-quits mid-chat and reopens gets `LIVE` + Continue with prior turns; a user returning after the idle window gets `ENDED` transcript + fresh session; a reinstalled device recovers tiles after re-verify.
- **Isolation:** requesting another account's session (`…/sessions/{id}?history=true`) returns 404.
- **Rate limits:** the new SMS-sending endpoints trip the same ceilings as `/verify/*` under load.

---

## 14. Decisions (formerly open questions)

Resolved with the product owner; captured so the implementing engineer doesn't re-litigate them.

1. **Lost-old-number recovery → out of scope for v1; contact support.** The phone change requires controlling the old number. A user who lost the old SIM must **contact support** — a heavily-audited, support-mediated recovery is a later path (§2.2 seam). v1 ships the self-service dual-OTP flow only; the app surfaces a "lost access to your old number? contact support" link rather than a self-serve escape hatch.
2. **Delete grace window → 30 days.** Between soft-delete/scrub and hard-delete + phone-freeing we hold **30 days**, matching the admin-plane GDPR posture (`yaya-agentic-user-accounts-design.md` §11). A user who re-registers the same phone before hard-delete lands is handled by the partial-unique index in §9.1 (uniqueness is enforced only among `ACTIVE` rows, so the scrubbed `DELETED` row does not block a fresh registration).
3. **ENDED resume UX → full read-only transcript in v1.** On `status = ENDED`, render the prior transcript read-only above a fresh composer; the first new message starts a new session. A `ConversationSummarizer`-backed "last conversation" summary card is a later polish pass, not v1.
4. **Tiles source of truth → union-merge, on-device as fast cache.** Server copy is the recovery source; local `DirectoryStore` stays the offline/fast path. Cold start unions the two so offline adds aren't lost (§7.1). Revisit only if divergence proves confusing in practice.
5. **Name-change latency → acceptable as-is.** A live session keeps the old name until it's resumed; the next session picks up the renamed value via `EntityMemory`. No push into a live session's `workingMemory` in v1.

---

## 15. What does NOT change

- The **eleven runtime SPIs** (design §5) are untouched. This design **reuses** `PhoneVerifier`, `DevicePassService`, `ChatAccountService`, `ChatRateLimiter`, and `ConversationRecorder` (via existing `loadSession` / `deleteSession`); it adds no new SPI.
- The **three-layer identity model** (L0/L1/L2, chat-channel §20.1) is unchanged — L1 is *hardened* (id-keyed, revocable, self-serviceable), not redefined. L2 (OIDC/resolver) composes on top exactly as before.
- All **CLAUDE.md non-negotiables** hold: the LLM is never trusted; out-of-profile tools are never offered; denials carry two reasons; the engine touches storage only via `ConversationRecorder`; tool results & retrieved chunks stay untrusted data; quick-replies stay engine `UiHint`s; the `Personality → Profile → variable-suffix` prompt order is untouched.

This design adds a **self-service account ring** around the existing phone-verified identity and finally makes chat history follow the account — nothing inside the conversation runtime's trust boundary moves.
```