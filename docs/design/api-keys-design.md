# API Keys — Design (v1)

> Companion to `yaya-agentic-design.md` and `operator-auth-design.md`.
> Touches operator-auth §3 (SPI), §6 (sessions), §7 (filter wiring), §13 (security);
> and user-accounts-design §4 (workspace authorization model), §5.2
> (`user_workspace_memberships`).
>
> Settles a non-goal explicitly carried in `operator-auth-design.md` §14:
> *"API keys for scripts. Defer until a customer asks."* A customer asked.

| | |
|---|---|
| **Status** | 🟡 Proposed |
| **Scope** | Let users mint long-lived bearer credentials from **Settings → API keys** so their apps, scripts, and CI jobs call the yaya-agentic admin / management API without storing a human's email + password. Workspace-scoped, role-bound, revocable, audited. |
| **Out of scope (v1)** | Runtime/end-user `Principal` minting (apps that need to chat **as a user** continue to mint per-tenant JWTs verified by the existing `tenant_jwt_secrets` / `tenant_oidc_issuers` flow — different identity plane, already designed). Personal cross-workspace keys. Fine-grained scope DSL beyond role tier. IP allowlists. OAuth2 client-credentials grant. Key-signing keys / asymmetric tokens. mTLS. |

---

## 1. One-paragraph summary

A workspace owner or admin opens **Settings → API keys**, clicks **Create key**, picks a name, a role (≤ their own), and an optional expiry. The backend mints a single opaque secret `yk_live_<32 chars of base62>`, stores **only its sha256 hash** (plus the first 11 characters as a non-secret prefix for lookup and display), and returns the secret **once**, in that response only. The user copies it into their app's secret store; the UI never shows it again. From then on, the app calls any operator-plane endpoint with `Authorization: Bearer yk_live_<...>` and is treated as a synthetic operator bound to that workspace at that role. Keys are revocable instantly, expire if configured, and every successful call updates `last_used_at` on a fire-and-forget write. That is the entire v1.

---

## 2. Trust planes — where API keys fit (and where they don't)

The platform deliberately keeps two identity planes apart (master design §5.4; `operator-auth-design.md` §2):

| Plane | Who | Today's credentials | This doc adds |
|---|---|---|---|
| **Operator plane** — `/v1/admin/**`, `/v1/auth/me`, `/v1/auth/logout` | Humans (and now scripts) configuring the platform | `YAYA_SESSION` cookie minted by `/v1/auth/login`; bootstrap or user-password (`User`) | **API keys**: a third sign-in form for the same plane, intended for non-interactive callers |
| **End-user / conversational plane** — `/v1/sessions/**` | The person/app talking to a bot | The runtime `Authenticator` chain — `SharedSecretJwtAuthenticator` (HS) or `OidcAuthenticator` (RS/ES), per-tenant trust in `tenant_jwt_secrets` / `tenant_oidc_issuers` | **Unchanged.** API keys do NOT mint a `Principal` and do NOT grant access to `/v1/sessions/**` |

**Why not unify.** Folding API keys into the runtime plane would mean a leaked admin key could impersonate any end-user `Principal`. Keeping them on the operator plane preserves the *"a single shared identity type would be the entire class of token-confusion bugs"* invariant from `operator-auth-design.md` §2. If a customer needs programmatic chat-as-user access later, the answer is a *runtime* API key (separate prefix, separate table, separate filter), not a scope-expansion of this one. We will not paint ourselves into a corner now.

**Why not "personal" keys.** GitHub PATs are user-owned and follow the user across orgs; Vercel tokens similarly. We considered both shapes and chose **workspace-bound** because:
1. Keys survive creator churn — when a user leaves the workspace, production automation does not break and does not silently keep their privileges either; the workspace owner sees the orphaned key and decides.
2. They preserve the workspace-isolation invariant from `yaya-agentic-user-accounts-design.md` §4.1 with **zero new code paths**: an API-key request looks exactly like a user-session request whose `WorkspaceContext` was pre-pinned by the filter.
3. The "which workspace does this admin call hit?" ambiguity that personal keys would create — every admin endpoint becoming `?workspaceId=…`-required for keys but not for cookies — is avoided.

Personal keys remain reintroducible later: a new table `personal_api_keys (user_id, …)` with the same prefix discipline, no rename of anything in this design. Schema seam is left.

---

## 3. The user-visible feature

### 3.1 Settings → API keys (Flutter)

A new section under the existing Settings shell:

```
Settings
├── General                       (existing)
├── Operator authentication       (existing, platform-admin only)
├── API keys                       ← NEW
└── ...
```

Visible to any workspace member; **create / revoke** restricted to `ADMIN`+ (`OWNER` or `ADMIN`). `MEMBER`/`VIEWER` see the list but cannot mint or destroy.

**List view** — one row per key in the current workspace:

| Column | Source |
|---|---|
| Name | user-supplied label |
| Prefix | `yk_live_abc12345…` — first 11 chars + ellipsis (the stored prefix) |
| Role | `OWNER` / `ADMIN` / `MEMBER` / `VIEWER` |
| Created by | display name of `created_by_user_id`, or `(deleted user)` if soft-deleted |
| Created | relative timestamp |
| Last used | relative timestamp + IP suffix, or `never` |
| Expires | absolute date, `never`, or red badge `expired` |
| Status | `active` / `revoked` / `expired` |
| Actions | `Revoke` (with typed-name confirmation modal) |

Empty state pitches the feature in one sentence: *"API keys let your apps, scripts, and CI jobs call the yaya-agentic API without storing a user's password. Each key is bound to this workspace and a role."*

**Create flow** — a modal, three fields:

1. **Name** (required, 3-64 chars, free text) — for the operator's own bookkeeping; surfaced in audit rows so a leaked key can be traced to *which integration*.
2. **Role** (required) — dropdown bounded by `min(creator.role, OWNER)`. Default = `MEMBER` (writes resources but cannot manage members or secrets). Help text mirrors the role matrix in `yaya-agentic-user-accounts-design.md` §4.2.
3. **Expires** (optional) — radio: `Never` (default), `30 days`, `90 days`, `1 year`, `Custom date`. We deliberately default to **never** because forced rotation without secret-manager tooling causes outages; **expiry is a tool the operator opts into**, not a policy we impose in v1.

On submit, the response renders a **show-once panel**:

```
┌─────────────────────────────────────────────────────────────┐
│  Save this key now — you won't see it again                 │
│                                                             │
│   yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3s9d2f6g8h0j2   │
│                                                  [📋 Copy]  │
│                                                             │
│   ✓ I've stored this key                       [ Close ]    │
│                                                             │
│   Test:                                                     │
│     curl -H "Authorization: Bearer yk_live_..." \           │
│          https://api.example.com/v1/admin/profiles          │
└─────────────────────────────────────────────────────────────┘
```

The **Close** button is greyed until the operator ticks the checkbox — small forcing function so nobody dismisses the modal by accident, calls support, and asks us to recover a key we have never stored.

### 3.2 What we never show

- The raw secret after the create modal closes.
- The sha256 hash (it's a server-side detail).
- The `created_by_user_id` UUID (we show the display name; orphaned keys say `(deleted user)`).
- Whether a given prefix exists when an arbitrary string is typed (no "check key" lookup — that's an enumeration helper).

---

## 4. Key format and lifecycle

### 4.1 Token format

```
yk_live_<32 base62 characters>
   │     │
   │     └── 32 chars × log2(62) ≈ 190 bits of entropy. Comfortably above the 128-bit floor.
   └──────── environment marker. Reserved values:
              live → real keys
              test → reserved for future "sandbox workspace" feature; rejected by the filter today
```

The literal `yk_live_` prefix is **scan-friendly**: GitHub secret scanning, TruffleHog, gitleaks etc. let us register a regex (`yk_live_[A-Za-z0-9]{32}`) and a revocation webhook so a key pushed to a public repo is auto-revoked. Cheap insurance. Schema seam: we never claim a token prefix outside `yk_…` to avoid colliding with other systems' tokens.

**Why opaque, not JWT.** JWTs would let us encode workspace + role in the token, saving one DB hit per request. We rejected this because:
- Revocation requires a denylist anyway (the whole point of an API key is the user can kill it instantly), so the DB hit is unavoidable.
- A signed token leaks the workspace UUID + role tier to whoever sees the string — including support logs. Opaque is a smaller side channel.
- Rotating the signing key would mass-invalidate every key in flight. Opaque tokens have no signing key to rotate.
- The DB hit is 32 bytes on a unique B-tree index of (worst-case) 100k rows; Redis-cacheable; not a hot path. JWT verification (HMAC + JSON parse) is not free either.

### 4.2 Storage

```
secret               = "yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3s9d2f6g8h0j2"
key_prefix           = "yk_live_abc"          ← first 11 chars; stored cleartext; indexed
secret_hash          = sha256(secret)         ← 32 bytes; stored as BYTEA; UNIQUE; never logged
```

**No pepper, no argon2.** Unlike a *password* (which is low-entropy and brute-forceable by an attacker who steals the hash), an API key has ~190 bits of entropy. A SHA-256 of a 190-bit secret is uncrackable in practice; argon2 buys nothing and would burn CPU on every authenticated request. We use plain SHA-256 because that's what the threat model actually warrants. (Same reasoning as `operator_sessions.id_hash` — see `operator-auth-design.md` §6.)

**Why store the prefix.** It serves two distinct jobs:
- *Lookup* — the filter parses the bearer token, takes the first 11 chars, looks up `WHERE key_prefix = ?` (single B-tree hit), then verifies sha256 in app. Indexed prefix avoids hashing for every key in the table per request.
- *Display* — the UI shows `yk_live_abc12345…` so the operator can identify a key in a log line without seeing the secret. (Stripe and GitHub do the same, for the same reason.)

11 characters is the smallest prefix that gives us ~16 bits of distinguishability over the variable suffix while still being unique-enough at any plausible key count (collision on the 11-char prefix is a constraint violation we silently retry on create).

### 4.3 Lifecycle

| State | How you get there | Effect on auth |
|---|---|---|
| `active` | created | accepted |
| `revoked` | user clicked Revoke | rejected, audit row `revoked_key` |
| `expired` | `expires_at < now()` and a request arrives | rejected, audit row `expired_key`; row left in place for forensics |
| (deleted) | workspace cascade or 90 days after revoke | row gone; nothing to audit |

**Immutable after create.** Name can be edited (it's a label). Role cannot — to change role, revoke and create new. This sidesteps a class of "I just demoted my prod key" outages where the role downgrade ships before the calling app is ready.

**Last-used.** On every successful request, we enqueue an update to `last_used_at` + `last_used_ip` on a bounded background executor; we *do not* block the request waiting on it. If the queue is full (Redis down, etc.) we drop the update silently — last-used is observability, not correctness.

### 4.4 Revocation

`DELETE /v1/admin/workspaces/{id}/api-keys/{keyId}` flips `revoked_at` and pushes the prefix onto an in-process **revocation broadcast** (Redis pub/sub) so all backend nodes evict their verifier caches within ~50ms. Without the broadcast, a revoked key would keep working on the node that cached it for the cache TTL (default 60s) — too long for "I just leaked this on Twitter."

### 4.5 Rotation

There is no `rotate` endpoint. Rotation = *create new* + roll the calling app over + *revoke old*. We considered a one-click rotate (returns a new secret, gives the old one a 24h grace) and rejected it for v1 because (a) the safe rotation pattern is "two keys briefly coexist, validate the new one in production, then kill the old one" — which the regular create/revoke flow already supports, and (b) a "grace period" introduces a window where two secrets are valid at once, which makes the leak-attribution story worse. We will revisit if customers ask.

---

## 5. Authentication — filter wiring

A new `ApiKeyAuthFilter` runs **alongside** `OperatorAuthWebFilter` (`operator-auth-design.md` §7), at a slightly *higher* precedence so a request bearing both an `Authorization: Bearer yk_*` header and a cookie is treated as the key request (and the cookie ignored — bearer is explicit intent; matching on both would create a class of "I'm logged in as A but accidentally hit the API as B" confusion).

```
Filter chain (operator plane):
─────────────────────────────
  1. CorsWebFilter                              (existing)
  2. ApiKeyAuthFilter        ← NEW              if Authorization: Bearer yk_… present:
                                                  verify → bind Operator + pinnedWorkspaceId → continue
                                                  invalid → 401 with audit_reason
                                                  absent → fall through
  3. OperatorAuthWebFilter   (existing)         cookie-based session, unchanged
  4. CsrfWebFilter           (existing)         SKIP when Operator.source == API_KEY (see §10)
  5. (controllers)
```

### 5.1 Verifier logic

```java
// pseudocode in the filter
String bearer = stripPrefix(request.headers.authorization(), "Bearer ");
if (bearer == null || !bearer.startsWith("yk_")) return chain.continue();   // not ours

if (!bearer.startsWith("yk_live_")) return unauthorized("api_key_env_rejected");
if (bearer.length() != 8 + 32)      return unauthorized("api_key_malformed");

byte[] hash = sha256(bearer);
ApiKey key = cache.get(bearer.substring(0, 11), () ->
    repo.findActiveByPrefixAndHash(prefix, hash)   // single PK hit on UNIQUE(secret_hash)
);
if (key == null)                    return unauthorized("api_key_unknown_or_revoked");
if (key.expired())                  return unauthorized("api_key_expired");

Operator op = new Operator(
    /* subject     */ "apikey:" + key.id,
    /* displayName */ key.name + " (key)",
    /* source      */ Source.API_KEY,
    /* attributes  */ Map.of(
        "user_id",      key.createdByUserId,
        "workspace_id", key.workspaceId,
        "role",         key.role.name(),
        "api_key_id",   key.id),
    /* verifiedAt  */ now()
);
exchange.attributes.put(OperatorContext.ATTR, op);
exchange.attributes.put(WorkspaceContext.PINNED_ATTR, key.workspaceId);
exchange.attributes.put(WorkspaceContext.PINNED_ROLE_ATTR, key.role);
asyncExecutor.submit(() -> repo.touchLastUsed(key.id, clientIp));
return chain.continue();
```

`Operator.Source` gets a new variant `API_KEY` added to the existing enum (`BOOTSTRAP`, `HTTP_DELEGATE`, `USER_PASSWORD`, `USER_OIDC`). The downstream `WorkspaceAccessHelper` (`operator_auth/workspace/WorkspaceAccessHelper.java`) is taught one new branch: **if the operator is API-key-sourced, the pinned workspace short-circuits the membership lookup** — there's no membership row to consult; the key's stored role IS the role.

### 5.2 Cache shape

Redis-backed (Redis is already a hard dependency per `CLAUDE.md`):
- Key: `apikey:v1:<key_prefix>` (the 11-char prefix, NOT the full secret).
- Value: serialized `ApiKey` row.
- TTL: 60s.
- Eviction on the revocation broadcast (§4.4) — and on `expires_at` natural expiry.

The cache holds the **hash**, not the secret. A compromised Redis cannot replay traffic without already having intercepted the bearer header on the wire.

### 5.3 What the filter never does

- Log the bearer token. The whole token, the suffix, anything past the prefix — never. Spring's default access log is configured to mask the `Authorization` header in this filter chain (already done for cookies; extended to bearer).
- Accept a bearer token over plain HTTP outside the `dev` profile. The filter rejects with `audit_reason = api_key_insecure_transport`. Same rule as the HTTP delegate (`operator-auth-design.md` §13).
- Mint a server-side session row. API key requests are stateless; nothing in `operator_sessions` is touched. (This also means an API-key request does not extend a concurrent cookie session's sliding window.)

---

## 6. Authorization

### 6.1 Workspace pinning

API keys are **bound to exactly one workspace at creation time**. The filter stamps `pinnedWorkspaceId` on the exchange. The first thing every admin handler does today is resolve a workspace from the path/body/query and call `WorkspaceAccessGuard.require(userId, workspace, minRole)`. We add one rule to `WorkspaceAccessHelper.require()`:

```java
if (operator.source() == API_KEY) {
    TenantId pinned = exchange.attribute(PINNED_ATTR);
    if (!pinned.equals(resolvedWorkspace)) {
        throw new WorkspaceNotFoundException(resolvedWorkspace, /* userId */ null);
        // 404, not 403 — same existence-leak rule as cross-workspace user requests
    }
    Role keyRole = exchange.attribute(PINNED_ROLE_ATTR);
    if (!keyRole.atLeast(minRole)) {
        throw new ResponseStatusException(FORBIDDEN, "insufficient role");
    }
    return pinned.value();
}
// ...existing user-session branch unchanged
```

Mismatch returns **404**, not 403. An attacker who steals a key for workspace A and tries it against workspace B's resources must not learn whether workspace B exists.

### 6.2 Role inheritance — bounding minting privilege

| Creator role | May mint keys with role | Rationale |
|---|---|---|
| `OWNER` | `OWNER`, `ADMIN`, `MEMBER`, `VIEWER` | full latitude |
| `ADMIN` | `ADMIN`, `MEMBER`, `VIEWER` | cannot escalate beyond self |
| `MEMBER` | — | not allowed to mint keys |
| `VIEWER` | — | not allowed to mint keys |
| `PLATFORM_ADMIN` | all, in any workspace | audited under `audit_user_events.kind = PLATFORM_ADMIN_ACTION` |

A user who is then demoted does *not* automatically downgrade keys they previously minted (those keys are workspace-owned, not user-owned). However, on **demotion below `ADMIN`** the system emails the user and the workspace OWNERs with a list of keys that user created, so the owners can review. Pragmatic middle ground: we don't break running automation, but we do not silently leave a former admin with a backdoor either. (Cascade-revoke on demotion was considered and rejected — too aggressive, breaks prod for the common case of role hygiene cleanup.)

### 6.3 What keys cannot do

Even an `OWNER`-role API key is **forbidden** from the following endpoints, regardless of role tier:

- `POST /v1/admin/workspaces/{id}/api-keys` — minting new keys from a key (one-shot escalation channel; force the operator back to an interactive session). The list/get/revoke endpoints remain accessible to keys; mint does not.
- `DELETE /v1/admin/workspaces/{id}` — workspace deletion requires a human in the loop and a typed-name confirmation.
- `/v1/auth/me`, `/v1/auth/logout` — these are session-oriented; they return 400 for API-key requests.
- The `/v1/admin/auth/strategies/**` operator-auth configuration screen — platform-admin gated even for human owners, so API keys defer to that gate too.

The forbidden list lives in one place (`@RejectForApiKeys` method annotation enforced by an aspect) so adding a new restricted endpoint is one annotation, not a documentation change.

---

## 7. Data model

V26 migration (slot is free given current head at V29 — actual number per migration ordering at impl time):

```sql
CREATE TABLE api_keys (
    id                    UUID PRIMARY KEY,
    workspace_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                  VARCHAR(64) NOT NULL,
    key_prefix            VARCHAR(16) NOT NULL,                     -- "yk_live_abc" — non-secret, indexed
    secret_hash           BYTEA NOT NULL,                           -- sha256(secret); UNIQUE; never logged
    role                  VARCHAR(32) NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER')),
    created_by_user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ,                              -- NULL = never
    last_used_at          TIMESTAMPTZ,
    last_used_ip          INET,
    revoked_at            TIMESTAMPTZ,
    revoked_by_user_id    UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX ix_api_keys_secret_hash ON api_keys(secret_hash);
CREATE INDEX        ix_api_keys_prefix      ON api_keys(key_prefix);
CREATE INDEX        ix_api_keys_by_ws       ON api_keys(workspace_id, revoked_at NULLS FIRST);

-- Hot-path lookup: by_prefix WHERE active. Partial index keeps it small.
CREATE INDEX ix_api_keys_active_lookup
    ON api_keys(key_prefix)
    WHERE revoked_at IS NULL;

CREATE TABLE audit_api_key_events (
    id              BIGSERIAL PRIMARY KEY,
    workspace_id    UUID REFERENCES tenants(id) ON DELETE CASCADE,
    api_key_id      UUID,                                           -- not FK; survives key deletion
    actor_user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    kind            VARCHAR(64) NOT NULL,                           -- CREATE | REVOKE | RENAME | EXPIRE | USE_DENIED
    detail_json     JSONB,
    client_ip       INET,                                           -- full IP — populated only for USE_DENIED (§14 D3)
    client_ip_hash  BYTEA,                                          -- sha256(ip || salt) — populated for CREATE/REVOKE/RENAME
    user_agent      TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (kind = 'USE_DENIED' AND client_ip IS NOT NULL  AND client_ip_hash IS NULL)
     OR (kind <> 'USE_DENIED' AND client_ip IS NULL     AND client_ip_hash IS NOT NULL)
     OR (client_ip IS NULL    AND client_ip_hash IS NULL)          -- both NULL when source IP is unknown
    )
);
CREATE INDEX ON audit_api_key_events(workspace_id, occurred_at DESC);
CREATE INDEX ON audit_api_key_events(api_key_id, occurred_at DESC);
```

**Why a separate audit table** rather than reusing `audit_user_events`: the kinds (`CREATE`, `REVOKE`, `USE_DENIED`) are key-centric, not user-centric, and the join paths differ — most queries are *"show me everything that happened to this key"* (single `api_key_id` lookup) or *"who used a now-revoked key in the 24h before the leak"* (range on `occurred_at` filtered by `api_key_id`). Forcing this into the user-events table would require an `entity_id` column there and an awkward `WHERE entity_kind = 'api_key'` discriminator.

**What we deliberately do NOT log per request**: a row per successful API call. That's a per-RPS write amplification we won't pay in v1. Per-request observability is what access logs are for; per-*event* observability (create, revoke, deny) is what this table is for. If a customer needs per-call attribution, the answer is "send your access logs to your SIEM," not "we'll write to Postgres on the hot path."

The async `last_used_at` update is a single `UPDATE … WHERE id = ?` — cheap, batched if hot, and it gives the *"is this key still in use?"* answer that 90% of operator queries actually want.

---

## 8. API surface

All endpoints are under the existing admin plane and follow the established 404-not-403 convention (`yaya-agentic-user-accounts-design.md` §4.1).

```
# List
GET    /v1/admin/workspaces/{id}/api-keys
       member+. Returns [{id, name, keyPrefix, role, createdAt, expiresAt,
                          lastUsedAt, lastUsedIp, status, createdBy}]
       Status is derived: active | revoked | expired. Secret is NEVER in the response.

# Get one (for the detail/edit screen)
GET    /v1/admin/workspaces/{id}/api-keys/{keyId}
       member+. Same shape as list, single row. 404 if not in workspace.

# Create
POST   /v1/admin/workspaces/{id}/api-keys
       admin+. NOT callable via API key (see §6.3).
       body:   { name, role, expiresAt? }
       201 →   { id, name, keyPrefix, role, createdAt, expiresAt, secret }
                                                                  └── ONLY response that contains the secret.
                                                                      Server stops echoing the field after this turn.
       400 on: role > creator.role; name length/charset; expiresAt in the past.

# Rename (label only)
PATCH  /v1/admin/workspaces/{id}/api-keys/{keyId}
       admin+. body: { name }. 200 on success. Role/expiry not editable.

# Revoke
DELETE /v1/admin/workspaces/{id}/api-keys/{keyId}
       admin+. Idempotent (revoking an already-revoked key returns 204).
       Fires the revocation broadcast (§4.4) before responding.

# Audit
GET    /v1/admin/workspaces/{id}/api-keys/{keyId}/audit
       admin+. Returns recent rows from audit_api_key_events for this key,
       paginated. Limited to creator/owner-visible fields (no IP for VIEWERs).
```

Every mutation writes an `audit_api_key_events` row inside the same transaction so audit and state never disagree.

---

## 9. Flutter — what changes in `yayaagenticweb/`

### 9.1 New feature package

```
lib/features/api_keys/
├── data/
│   └── api_keys_api.dart           list / get / create / patch / revoke
├── domain/
│   ├── api_key.dart                freezed; mirrors §8 list-row
│   └── api_key_create_request.dart freezed { name, role, expiresAt? }
├── application/
│   └── api_keys_controller.dart    Riverpod AsyncNotifier keyed on currentWorkspaceProvider
└── presentation/
    ├── api_keys_screen.dart        list view
    ├── create_api_key_dialog.dart  the 3-field create modal
    └── show_secret_panel.dart      the show-once panel from §3.1
```

### 9.2 Settings shell wiring

A new entry in the existing Settings nav (`yayaagenticweb/lib/features/settings/`). The entry is always visible to members but the action buttons (`Create key`, `Revoke`) are disabled with a tooltip for `MEMBER`/`VIEWER` — server-side authorization is still authoritative; the UI gating is UX, not a trust boundary (mirrors `yaya-agentic-user-accounts-design.md` §9 role-aware UI).

### 9.3 Dio interceptor — unchanged

The Flutter admin app uses the cookie. The new keys feature does NOT make the Flutter app *consume* its own API keys (that would be a needless round-trip through the bearer path on every UI action). The `Authorization` header is reserved for code outside this app. The interceptor stays as-is.

### 9.4 Workspace switcher

API keys list is keyed on `currentWorkspaceProvider`. Switching workspaces invalidates the keys provider and re-fetches. Keys never cross workspaces in any UI surface.

---

## 10. Security considerations & footguns

| Concern | Mitigation |
|---|---|
| **Leaked key in a public repo.** | `yk_live_` prefix is registered with GitHub secret scanning + an internal webhook that calls `DELETE …/api-keys/{id}` automatically on push notification. Audit row records `REVOKE` with `detail_json = {trigger: "secret_scanning", source_url: "..."}`. |
| **Bearer token in browser-app code.** | API keys are not session credentials. We document loudly in the create modal: *"Never put an API key in client-side code or a mobile app. Keys are for server-to-server use."* Future: enforce by adding an `Origin`-rejection rule if the request looks browser-shaped (Sec-Fetch-Mode etc.) — but that breaks legitimate same-origin curl from a workstation, so v1 just educates. |
| **Token in URL query parameter.** | Filter rejects `?api_key=…` with `audit_reason = api_key_in_query_string` and returns 400. URLs end up in webserver access logs, browser histories, referer headers; we make this a hard error so nobody discovers it via "oh it also works that way." |
| **CSRF.** | Bearer-token requests are CSRF-exempt by construction (a CSRF attacker cannot read the bearer to inject it from a cross-origin form). The CSRF filter skips when `Operator.source == API_KEY`. Cookie sessions remain CSRF-checked. |
| **Replay after revoke before cache TTL.** | Revoke writes to DB + publishes prefix on Redis pub/sub; verifier cache evicts within ~50ms across all backend nodes. Worst case (Redis pub/sub flap): the request goes through for at most the cache TTL (60s). Documented; if a customer's threat model needs strict revocation, they set the TTL to 0 — at the cost of a DB hit per request. |
| **DB compromise → key replay.** | We store `sha256(secret)`, not the secret. An attacker with the DB cannot mint a usable bearer. (Same reasoning as `operator_sessions.id_hash`.) |
| **Per-request DB hit.** | `key_prefix` is indexed; lookup is a single B-tree probe (≤ 5 page reads at any plausible key count) followed by a constant-time hash compare. 60s Redis cache keeps the steady-state hit-rate at the cache, not the DB. |
| **Time-of-check vs time-of-use** (key revoked between filter pass and controller execution). | Acceptable. The request is at most one-handler in scope; the next request sees the cache eviction. We are not building a per-statement permission system. |
| **Plaintext bearer over HTTP.** | Rejected outside the `dev` profile. Same rule as the HTTP delegate (`operator-auth-design.md` §13). |
| **Algorithm confusion with operator cookies.** | Bearer header takes precedence over cookie when both are present (§5). An operator who is logged in as user A and pastes a key into a request meant for the UI would get the key's authority, not their own — surprising, but explicit (`Authorization` header is intent). Documented in the API reference. |
| **Phantom keys after workspace cascade delete.** | `ON DELETE CASCADE` on `workspace_id` removes the key rows. Audit rows survive (no FK from audit to api_keys); they're frozen forensic records. |
| **Created_by_user_id deleted (GDPR scrub).** | `ON DELETE SET NULL` on `created_by_user_id`; the key keeps working (it's workspace-owned). UI displays `(deleted user)`. |
| **Brute force on `key_prefix` to find existing prefixes.** | 11 prefix chars × 62-symbol alphabet ≈ 65 bits of entropy. Even without rate limiting, brute force is infeasible. With per-IP rate limiting on `/v1/admin/**` (§11) it's harder still. We still 401 with a generic body so a wrong prefix is indistinguishable from a wrong suffix. |
| **JSON-injection via the `name` field.** | Name is opaque text; surfaced only in UI (HTML-escaped by Flutter), audit JSONB (Postgres-safe), and never substituted into a URL/header/template. Length-limited to 64 chars. |
| **An angry ex-employee with an admin key.** | Workspace OWNERs receive an email digest when *any* user is removed from the workspace, listing keys that user created so the owners can revoke. We don't auto-revoke on offboarding because we don't know whether a specific key powers production (see §6.2). |
| **Mass-mint by a compromised admin account.** | Per-workspace key count cap: 100 active keys per workspace (configurable). Hitting the cap returns 409 with a message pointing at the cap. This is the same approach Stripe uses; the cap is high enough to never bite a real customer and low enough that a runaway script throws a visible error fast. |

---

## 11. Rate limiting

Reuse the Bucket4j + Redis stack from `operator-auth-design.md` §13. Three buckets:

| Bucket | Limit | Rationale |
|---|---|---|
| Per-IP, all `/v1/admin/**` (incl. bearer) | 600 req / min | Existing operator-plane limit; not changed |
| Per-API-key, all endpoints | 60 req / sec **burst**, 600 req / min sustained | Stops a runaway script while leaving headroom for batch jobs |
| Per-IP, failed-auth on `Authorization: Bearer yk_*` | 20 / min | Brute-force defence; on breach the IP is 429'd on all bearer requests for 5 min |

429 responses include `Retry-After` and a small structured body indicating which bucket tripped. We do not leak whether the failure was "unknown key" vs "rate limit" — a 401 is a 401 and a 429 is a 429.

---

## 12. Audit

Two writers:

1. **Synchronous**, inside the same transaction as the state change: `CREATE`, `REVOKE`, `RENAME`. State and audit cannot drift.
2. **Asynchronous**, fire-and-forget bounded executor: `USE_DENIED` on a per-deny basis (expired key, revoked key, wrong workspace, insufficient role). We do NOT write on `USE_ALLOWED` — access logs cover the happy path; this table is the exception log.

`audit_api_key_events.detail_json` carries the variant payload:

```json
// CREATE
{ "role": "MEMBER", "expires_at": "2027-01-01T00:00:00Z" }

// REVOKE
{ "reason": "user_action" | "secret_scanning" | "workspace_delete_cascade",
  "source_url": "https://github.com/foo/bar/commit/abc"   // when secret_scanning
}

// USE_DENIED
{ "reason": "expired_key" | "revoked_key" | "wrong_workspace"
            | "insufficient_role" | "api_key_malformed",
  "requested_workspace": "..."   // when wrong_workspace
}
```

**Client IP handling.** Per §14 D3, the writer populates exactly one of `client_ip` / `client_ip_hash` depending on `kind`:

- `USE_DENIED` → full `client_ip` (security-relevant; SOC analysts join against threat-intel feeds).
- `CREATE` / `REVOKE` / `RENAME` → `client_ip_hash = sha256(ip || yaya.agentic.audit.ip-hash-salt)`; raw IP discarded before the write returns.

The single helper `AuditIpRecorder.record(kind, ip)` enforces the split — no controller chooses; the kind determines the column. The audit endpoint never renders `client_ip_hash` (it is for cross-row correlation only, not display).

A simple audit endpoint (§8) renders this for the operator. A future ops-grade SIEM export sink is out of scope here.

---

## 13. Non-goals (v1) — what we explicitly defer

| Deferred | Why |
|---|---|
| **Personal cross-workspace keys** | Schema seam left in §2. Adds a "which workspace?" ambiguity to every admin endpoint for keys-only callers; not worth the complexity until a customer asks. |
| **Scope DSL finer than role** | The role tier already encodes the dominant axis (read vs write). Sub-scopes (e.g. "this key can only manage profiles, not knowledge sources") add real UX cost — every consumer of an `ADMIN` key today would suddenly need to know which sub-scopes their integration touches. Add when the matrix becomes a real ask. |
| **IP allowlists** | Useful; not v1-table-stakes. The schema for it (`api_key_ip_allowlist (api_key_id, cidr)`) is purely additive when added later. |
| **Runtime / end-user `Principal`-minting keys** | Different identity plane (§2). Conflating them is the single biggest risk in this space; defer to its own design doc when needed. |
| **OAuth2 client-credentials grant** | A more "standard" shape than opaque tokens, but it buys us nothing customers are asking for. Adds a token-endpoint, JWKS, refresh-token loop, scope DSL — all real work, all deferred. |
| **mTLS** | The HTTPS bearer model covers the vast majority of "I want a strong machine credential." mTLS layered on top is additive. |
| **JWT-formatted keys** | Rejected in §4.1, summarised here for searchability. |
| **Key rotation as a single endpoint** | Rejected in §4.5. |
| **Per-request audit row** | Rejected in §7 for write-amplification reasons. |
| **Cascade-revoke on user demotion** | Rejected in §6.2 — too aggressive; we email owners instead. |

---

## 14. Decisions (formerly open questions)

All four open questions are resolved. Captured here so the implementing engineer doesn't re-litigate them.

1. **Cache TTL → 60s, global, single knob.** Per-workspace TTL configuration is deferred. A single `yaya.agentic.api-keys.cache-ttl-seconds` property controls the cluster-wide verifier cache. Customers asking for stricter revocation than the broadcast-eviction (~50ms) already gives them lower the global value; nobody has asked yet. Per-workspace overrides become additive when a real ask lands.
2. **Per-workspace cap → 100, with an env-var escape hatch.** Defensible upper bound; covers "one key per CI runner" patterns with headroom. The 101st create returns `409` with a structured error pointing at the cap. The cap is overridable via `yaya.agentic.api-keys.max-active-per-workspace` (deployment-wide, not per-workspace) — that's the escape hatch we ship *before* customer-visible per-workspace config. If multiple customers ask, we promote the env var to a per-workspace setting.
3. **Audit `client_ip` storage → split by event kind.** `USE_DENIED` events store the **full IP** (security-relevant: this is the row a SOC analyst joins against threat-intel feeds). `CREATE`, `REVOKE`, `RENAME` events store a **SHA-256 hash of the IP with a per-deployment salt** (GDPR-friendlier; still lets us answer "did the same actor mint and revoke this key from the same network?" without storing the raw value long-term). Salt lives in `yaya.agentic.audit.ip-hash-salt` (env var); rotation is documented as a deliberate audit-continuity break.
4. **Email on key creation → yes, creator only.** Sent to the creator's verified email immediately after a `CREATE`, never to other workspace OWNERs (noise on normal day-to-day key churn would teach people to mute it). Subject: *"A new API key was created in workspace `acme-7f3`."* Body lists the key name, prefix, role, creator's IP location (city-level), and a one-click *"This wasn't me — revoke it"* link that hits `DELETE /v1/admin/workspaces/{id}/api-keys/{keyId}` with a single-use token. Reuses the M5a SendGrid wiring (`yaya.agentic.email.*`); no new dependency.

---

## 15. Sequencing — folds into M5 or ships as M5.5

Two ways to land this:

- **M5.5 (recommended)** — ships after `M5a` (user accounts + workspaces) and before `M5b` (hardening). The dependencies (`tenants.id` as UUID, `WorkspaceAccessGuard`, `OperatorContext` populated with `user_id`) are all in place. ~3-4 days of backend + 2 days of Flutter.
- **Folded into M5b** — clean if M5a/M5b are not yet split in the milestone tracker. Same code, different milestone label.

### Backend deliverables

- **B-K1** Migration `V30__api_keys.sql` (or next slot at impl time): `api_keys` + `audit_api_key_events` + indexes.
- **B-K2** `api_keys/` package — `ApiKey` record, `ApiKeyService` (mint / list / revoke), `ApiKeyRepository`, `SecretGenerator` (`SecureRandom` over base62).
- **B-K3** `ApiKeyAuthFilter` (WebFlux) and `Operator.Source.API_KEY` variant; `WorkspaceContext.PINNED_ATTR` / `PINNED_ROLE_ATTR`.
- **B-K4** `WorkspaceAccessHelper` extension — the API-key branch from §6.1.
- **B-K5** `CsrfWebFilter` skip rule when `Operator.source == API_KEY`.
- **B-K6** `ApiKeyController` — five endpoints from §8.
- **B-K7** `@RejectForApiKeys` annotation + aspect; apply to the endpoints in §6.3.
- **B-K8** Redis-backed `ApiKeyCache` (60s TTL) + revocation pub/sub channel.
- **B-K9** Bucket4j rate-limit rules from §11.
- **B-K10** Audit writers (sync for state changes; async for deny events).
- **B-K11** Email send on key creation (§14 D4) reusing the M5a SendGrid wiring; body includes the one-click revoke link backed by a single-use token.
- **B-K11b** `AuditIpRecorder` helper enforcing the §12 / §14 D3 IP split; `yaya.agentic.audit.ip-hash-salt` wired through `YayaAgenticProperties`.
- **B-K11c** `yaya.agentic.api-keys.cache-ttl-seconds` (default `60`) and `yaya.agentic.api-keys.max-active-per-workspace` (default `100`) wired through `YayaAgenticProperties`.
- **B-K12** GitHub secret-scanning webhook handler `POST /v1/integrations/github/secret-scanning` — verifies GitHub signature, looks up prefix, revokes if matched, writes audit row with `trigger: "secret_scanning"`.

### Flutter deliverables

- **F-K1** `lib/features/api_keys/` package per §9.1.
- **F-K2** Settings nav entry; routing.
- **F-K3** List screen with prefix + last-used + revoke.
- **F-K4** Create dialog with name + role + expiry.
- **F-K5** Show-once secret panel with copy button + forcing checkbox.
- **F-K6** Audit drawer per key (recent events from §8 audit endpoint).
- **F-K7** Role-aware gating (creates/revokes disabled for `MEMBER`/`VIEWER`).

### Acceptance criteria

- [ ] `OWNER` in workspace A creates a key with role `MEMBER`; the secret is returned exactly once; the list view shows only the prefix and metadata.
- [ ] A subsequent `GET /v1/admin/workspaces/A/api-keys/{id}` does NOT include the secret in any field.
- [ ] `Authorization: Bearer yk_live_<correct>` against `/v1/admin/workspaces/A/profiles` returns 200; the operator stamp in the audit row records `source = API_KEY` and the key's id.
- [ ] Same bearer against `/v1/admin/workspaces/B/profiles` returns **404** (not 403); audit reason `wrong_workspace`.
- [ ] `MEMBER`-role key against a write endpoint that requires `ADMIN` returns 403; audit reason `insufficient_role`.
- [ ] `MEMBER`-role key against `POST /v1/admin/workspaces/A/api-keys` returns 403; the create-key endpoint is annotated `@RejectForApiKeys` regardless of role tier (§6.3).
- [ ] Revoke flips state; a request with the now-revoked bearer issued >2s later returns 401; audit reason `api_key_unknown_or_revoked`.
- [ ] An expired key is rejected on first request after `expires_at`; audit reason `api_key_expired`; the row is not deleted.
- [ ] A request with `Authorization: Bearer yk_live_<malformed length>` returns 401 with `audit_reason = api_key_malformed` and does NOT log the bearer content.
- [ ] A request with `?api_key=…` returns 400 with `audit_reason = api_key_in_query_string` and does NOT log the value.
- [ ] Plain HTTP bearer in `prod` profile is rejected with `audit_reason = api_key_insecure_transport`.
- [ ] Cookie session and bearer header simultaneously present on the same request → bearer wins; the cookie's session row is not touched.
- [ ] CSRF filter skips when bearer is present (`PUT` with no `X-XSRF-TOKEN` succeeds for an API-key request).
- [ ] Per-workspace key cap of 100 returns 409 on the 101st create with a structured error.
- [ ] An `ADMIN` may not mint an `OWNER` key (400 with `role_exceeds_creator`); `OWNER` may mint at any tier.
- [ ] `created_by_user_id` is preserved across user soft-delete (`SET NULL`) and the key continues to work.
- [ ] Workspace soft-archive cascades `api_keys` rows; subsequent bearer requests return 401.
- [ ] Email is sent to the creator on `CREATE`; not to other workspace members. The email contains a single-use *"This wasn't me — revoke"* link that revokes the key on click.
- [ ] `audit_api_key_events` rows: a `USE_DENIED` row has a non-null `client_ip` and null `client_ip_hash`; a `CREATE`/`REVOKE`/`RENAME` row has a non-null `client_ip_hash` and null `client_ip`. The CHECK constraint refuses any other combination.
- [ ] Two `CREATE` events from the same source IP produce identical `client_ip_hash` values; rotating `yaya.agentic.audit.ip-hash-salt` produces a different hash for the same IP on the next event.
- [ ] GitHub secret-scanning webhook with a valid signature for a known prefix produces a revoked key + an audit row tagged `secret_scanning` within 5s.
- [ ] Cross-user isolation test (extends `yaya-agentic-user-accounts-design.md` §13 M5a test): user A's keys are not visible to user B in any list/get/audit endpoint; lookup by id returns 404.
- [ ] Repository scan test confirms `ApiKeyRepository` either accepts a `WorkspaceContext` or is annotated `@WorkspaceUnscoped` (it's not — every method is workspace-scoped).
- [ ] Rate-limit test: 21 failed-bearer attempts from one IP within a minute triggers a 5-min 429 lockout for all bearer requests from that IP.

---

## 16. Summary of what this design buys

- **One new credential type, on the plane it belongs on.** Operator plane. Not runtime. The wall between the two planes is the most important security property the codebase has; this design reinforces it instead of weakening it.
- **Workspace-scoped from day one.** No "personal key across orgs" ambiguity; isolation is by construction. The single `WorkspaceAccessHelper` branch added in §6.1 is the entire authorization change.
- **Show-once secrets, sha256 storage, prefix-indexed lookup.** Industry-standard for a reason; the existing `operator_sessions.id_hash` pattern is reused, no new crypto primitives.
- **Revocable inside ~50ms across the cluster** via Redis pub/sub on a 60s cache TTL. Belt-and-suspenders if pub/sub flaps.
- **Bounded blast radius:** keys cannot mint keys, cannot configure auth strategies, cannot delete workspaces. The privileged operations stay behind a human in a session.
- **No new SPIs.** The eleven master-design SPIs and the two admin-plane SPIs from user-accounts-design are untouched. We add one filter, one controller, one table, one audit table, and an annotation. That is the entire backend footprint.
- **Reintroducible deferrals.** Personal keys, scope DSL, IP allowlists, runtime keys — every one of them is purely additive on top of this design; nothing here paints them out.

When a customer needs richer machine-to-machine, they don't write a new endpoint to satisfy our contract; they configure a key in Settings, paste the bearer into their app's secret store, and ship.
