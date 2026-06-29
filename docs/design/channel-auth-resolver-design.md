# Channel Auth Resolver (v1)

> **Supersedes** `whatsapp-channel-auth-design.md` (Layer 1 — default principal
> policy) and `http-phone-resolver-design.md` in full. Both are kept in-tree
> as historical record but should not be implemented from this point forward.
>
> The step-up runtime (Layer 2 of the old doc) and the WhatsApp playground
> chooser (Layer 3) remain in scope and unchanged — this doc replaces only
> the identity-resolution mechanism that sits in front of them.
>
> Companion to `yaya-agentic-design.md` §5.4 (Authenticator chain), §5.5
> (Authorizer / AuthzDecision), §5.6 (profile resolver chain),
> `whatsapp-channel-design.md` §8 (ChannelBinding), `operator-auth-design.md`
> (per-tenant JWT trust roots), and `tenant-registry-design.md` (hostBaseUrl
> + inboundOriginAllowlist already wired on every tenant).

| | |
|---|---|
| **Status** | 🟡 Proposed — replaces the W-A1→W-A9 identity SPI |
| **Owner** | Yenfri Herrera |
| **Audience** | Engineering team shipping inbound-channel-backed profiles; tenant operators integrating their identity system; PM stakeholders evaluating identity-gated WhatsApp use cases |
| **Last updated** | 2026-06-27 |
| **Scope** | A single per-binding **AuthResolver** abstraction that takes a verified channel identity (today: a WhatsApp phone) and returns a tenant-issued JWT, which yaya-agentic then verifies via the *existing* per-tenant JWT trust roots (`tenant_jwt_secrets` HS-family, `tenant_oidc_issuers` RS/ES-family). Two implementations: `BEAN` (in-process Spring `@Component` — for embedded/library deployments and the playground) and `HTTP` (POST to `tenant.hostBaseUrl + resolver.path` — for SaaS tenants who can't ship Java). One per-tenant DB-backed config table with workspace-admin CRUD. A Redis-backed JWT cache keyed on `(tenant, sender, callerPhone)` with TTL bounded by the JWT's own `exp`, evictable explicitly. Per-turn + reactive-401 refresh. Profile pinning via a `_profile_id` claim on the returned JWT, honoring the existing identity-driven profile resolver. |
| **Out of scope (v1)** | Multi-region failover URLs. mTLS-authenticated webhook calls (HMAC-signed JWT only). End-user account linking across channels. Multi-factor step-up (still in `whatsapp-channel-auth-design.md` Layer 2 — orthogonal). Inbound voice biometric. Per-tool-call resolver overrides. Cross-tenant resolvers. Webhook response streaming. |

---

## 1. The one-paragraph summary

Inbound channel identity (today: a verified WhatsApp phone number) needs to
become a **verified tenant-side identity** that the engine can use for the
whole session — including for outbound tool calls that need
`AuthForwarding.PRINCIPAL_TOKEN`. The W-A series shipped a SPI
(`PhoneToPrincipalResolver`) that returned a freshly-built `Principal`, plus
a `LookupPrincipal` binding policy that materialized it with reserved-claim
guards, a five-minute resolution cache, and a flotilla of supporting types.
That model worked but conflated two concerns — *fetching tenant identity*
and *minting a verified `Principal`* — and forced us to reinvent claim
validation that the existing `Authenticator` chain already does. v1
collapses both into **AuthResolver**: a thin SPI whose only job is to return
a tenant-signed JWT for the (tenant, sender, callerPhone) triple. The JWT
flows through the existing per-tenant verification chain — same trust roots
as inbound API calls — and produces the same `Principal` the rest of the
engine already understands. The resolver has two transports: an in-process
Spring bean (library / playground use) and an HTTP POST to a path under the
tenant's already-configured `hostBaseUrl` (SaaS-tenant use). Tenants
self-serve their HTTP resolvers via per-tenant admin CRUD — no operator
yaml. Tokens are cached in Redis with TTL bounded by their own `exp`,
refreshed proactively at turn start and reactively on tool 401, and
invalidated either by an explicit admin API or by a 404 response from the
resolver itself. Profile pinning piggybacks the existing identity-driven
profile resolver via a `_profile_id` claim on the JWT — no new wire
mechanism. The net effect: a deeply simpler model that subtracts ~1,500
lines of bespoke W-A code, eliminates a custom claim-validation surface,
and lets tenant operators register and rotate their own identity webhooks
from the admin console without filing a ticket.

---

## 2. Why we are rewriting the W-A series so soon after shipping it

W-A1 → W-A9 are correct in *what* they enforce but wrong in *how many
moving parts* they require to enforce it. Three structural smells made the
rewrite necessary:

1. **Two parallel "verify identity" pipelines.** Inbound API calls go
   through `Authenticator` → `AuthContext` → `Principal`. WhatsApp messages
   went through `PhoneToPrincipalResolver` → reserved-claim guard →
   `PrincipalPolicyMaterializer` → `Principal`. Same end result, two
   completely different code paths, two different sets of trust roots
   ("the resolver said so" vs. "the JWT verified"). Any future channel
   (web chat, SMS, voice) would have had to choose a side or write a
   third. **Collapse: identity always becomes a JWT we verify with the
   tenant's existing JWT trust. No second mechanism.**
2. **Conflated identity-fetching and `Principal`-minting.** The resolver
   SPI made the tenant build a `Principal` — subject, scopes, claims map —
   which meant they had to know what reserved keys to avoid, what scope
   shape we expected, etc. **Decouple: the resolver returns a token. The
   `Authenticator` chain mints the `Principal`. The tenant's mental model
   is "I produced a JWT" — the same mental model they already have for
   their REST APIs.**
3. **No tenant self-service.** The HTTP variant we were about to ship
   stored its config in `application.yaml`, meaning every new tenant
   needed a platform-operator deploy. **Move to: tenant ADMINs register
   resolvers themselves from the admin console — same pattern as
   `tenant_jwt_secrets` and `tenant_oidc_issuers`.**

The W-A1 → W-A9 work was not wasted: the inbound dispatch worker pipeline,
step-up audit table, secondary-comms admin, `OtpVerifier`, the operator
runbook, the `inboundOriginAllowlist` + `hostBaseUrl` infrastructure, and
the Authenticator chain itself all survive. We are deleting one specific
slice — the identity-resolution slice — and replacing it with something
smaller that reuses more.

---

## 3. The non-negotiables this design preserves

These come from `yaya-agentic-design.md` §12, `whatsapp-channel-design.md`
§2, `operator-auth-design.md` §2, and the original W-A design's §3. The
shape changes; the guarantees do not:

1. **The LLM never participates in identity resolution.** The engine fires
   the resolver deterministically at session start (and refresh) before
   any LLM turn runs. The model is never asked to interpret a token,
   choose a resolver, or judge whether a refresh should happen.
2. **Credentials never travel over the inbound channel.** No JWT, OTP, or
   bearer is ever sent down WhatsApp. The resolver call is yaya → tenant
   over TLS; the response token lives server-side only.
3. **`Deny` reasons stay split.** The existing `AuthzDecision.Deny`
   `userSafeReason` / `auditReason` split is untouched — resolvers
   produce identity, not denials. A failed resolution surfaces as an
   `AuthenticationException` with `userSafeReason` ("we couldn't verify
   you") and a distinct `auditReason` ("resolver acme-customer-lookup
   returned 404 for +1555…").
4. **No tool reads its own auth.** Tools see the verified `Principal`
   produced by the auth chain; if 401 comes back from a tool's downstream
   API, the engine — not the tool — triggers the reactive refresh path.
5. **Reserved channel claims still win.** `channel.kind`, `channel.sender`,
   `phone`, etc. are stamped by the channel webhook *after* the resolver
   returns. A misbehaving tenant cannot override them by minting a JWT
   that includes them — the stamping is a post-verification merge with
   reserved keys taking precedence.
6. **Tokens are session-bound at the storage layer; identity is
   triple-bound at the cache layer.** The session record holds the
   currently active JWT and dies with the session. The Redis cache holds
   the JWT for `(tenant, sender, callerPhone)` and survives across
   sessions until the JWT's own `exp` retires it.
7. **No cross-tenant or cross-binding identity.** Cache keys are scoped
   to `(tenantId, senderId, callerPhoneSha256)`. A user who appears on
   sender A in tenant X never shares a cached JWT with sender B in tenant
   X, let alone a sender in tenant Y.
8. **Profile selection is session-immutable.** The `_profile_id` claim on
   the JWT at session start decides the profile for the whole session.
   Refresh tokens may carry an updated `_profile_id` — the engine logs
   the change but does NOT swap profiles mid-session. Profile changes
   take effect on the next session.

---

## 4. The model at a glance

```
┌────────────────────────────────────────────────────────────────────────┐
│ Inbound WhatsApp message — sender=+15551112222, caller=+15551234567    │
└────────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ChannelBinding lookup                                                  │
│   binding.authResolverId  →  channel_auth_resolvers row                │
│   binding.onUnresolved    →  REJECT | ANONYMOUS                        │
└────────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────────────────────────────────────┐
│ JWT cache (Redis)                                                      │
│   key = auth-resolver-token:{tenantId}:{senderId}:{sha256(caller)}     │
│   HIT, exp - now > refresh_window → use cached JWT                     │
│   else → resolver call                                                 │
└────────────────────────────────────────────────────────────────────────┘
              │ cache miss / refresh
              ▼
┌────────────────────────────────────────────────────────────────────────┐
│ AuthResolver dispatch                                                  │
│   ├─ kind: BEAN  → @Component.resolve(...) returns Optional<String>   │
│   └─ kind: HTTP  → POST {hostBaseUrl}{path}                            │
│                     body: { sessionId, senderPhone, callerPhone,       │
│                             channel, tenantId, reason, timestamp }     │
│                     outbound auth: SIGNED_JWT | BEARER | NONE          │
│                     response: { token } or 404                         │
└────────────────────────────────────────────────────────────────────────┘
              │ Optional<String token>
              ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Authenticator chain (existing)                                         │
│   OidcAuthenticator  (per-tenant_oidc_issuers)                         │
│   SharedSecretJwtAuthenticator  (per-tenant_jwt_secrets, HS-family)   │
│   produces a verified Principal                                        │
└────────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Channel claim stamping (reserved keys win)                             │
│   merge phone, channel.kind, channel.sender, locale, … into Principal  │
└────────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Session start                                                          │
│   sessions.auth_token_enc ← AES-256-GCM(JWT)                           │
│   profile resolution: _profile_id claim → identity-driven resolver     │
│   Engine takes over                                                    │
└────────────────────────────────────────────────────────────────────────┘
```

Mid-session lifecycle is covered in §9.

---

## 5. The SPI

```java
package com.yayatechandinnovations.yayaagentic.channel.auth;

/**
 * Resolves a verified channel identity to a tenant-issued bearer JWT.
 *
 * <p>Implementations do NOT verify the JWT they return — that is the
 * Authenticator chain's job. They DO produce a token the chain will accept:
 * signed with one of the tenant's registered JWT secrets (HS256/384/512)
 * or by a configured OIDC issuer (RS256/ES256).
 *
 * <p>The contract is intentionally narrow: produce a token for this
 * (tenant, sender, callerPhone) triple. Claims, profile pinning,
 * expiration policy, scope set — all live INSIDE the JWT, not in the
 * resolver's return shape.
 */
public interface AuthResolver {

    /**
     * Tenant-local identifier, e.g. "acme-customer-lookup". The binding's
     * authResolverId column references this. Combined with tenantId in
     * lookup; the same id may exist in different tenants.
     */
    String name();

    /** Operator-facing one-line description for admin UI. */
    default String description() { return name(); }

    /**
     * @return Optional.empty() to signal "we do not know this user." The
     *         binding's onUnresolved flag decides whether the inbound
     *         message proceeds as anonymous or is rejected.
     * @throws AuthResolverException for transport/protocol failures
     *         (timeout, 5xx, malformed response). Distinct from
     *         "user not found" — these surface as auth errors, not
     *         anonymous downgrades.
     */
    Optional<String> resolve(ResolveRequest req) throws AuthResolverException;

    record ResolveRequest(
            Ids.TenantId tenant,
            SenderId sender,
            String e164CallerPhone,
            Ids.SessionId session,
            String channel,             // "whatsapp", "sms", … extensible
            ResolveReason reason,       // INITIAL | REFRESH
            Instant requestedAt) {}

    enum ResolveReason { INITIAL, REFRESH }
}
```

Two implementations ship in-tree.

### 5.1 BeanAuthResolver

A direct dispatch to a tenant-supplied `@Component`. Used by:
- Tenants who consume yaya-agentic as a library (M2/M3 audience).
- The WhatsApp playground (a `StaticBeanAuthResolver` mints a pre-canned
  JWT for an operator-chosen test user).
- Integration tests.

The admin UI lists registered beans as a dropdown when the operator picks
`kind=BEAN`. The bean is looked up by name at resolve time, not at config
time — beans can be added without restarting the admin form.

### 5.2 HttpAuthResolver

A single platform-shipped bean. At resolve time it:
1. Loads its config row from `channel_auth_resolvers`.
2. Loads `tenants.host_base_url` and validates `host_base_url + path`
   passes the `inbound_origin_allowlist` (already shipped — `a9131f5`).
3. Builds the request body.
4. Signs the outbound call per `service_auth_kind`.
5. POSTs via `WebClient` with the configured timeout.
6. Parses the JSON response, extracts `token`, returns it.

The HTTP impl is detailed in §6.

---

## 6. The HTTP wire contract

### 6.1 Outbound (yaya → tenant)

```http
POST https://acme.example.com/yaya/resolve-identity HTTP/1.1
Host: acme.example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIs…          ← see §6.3
Content-Type: application/json
X-Yaya-Tenant: acme
X-Yaya-Request-Id: rq_01HVXP7Q9...
X-Yaya-Idempotency-Key: rq_01HVXP7Q9...               ← retried calls reuse

{
  "sessionId":  "sess_01HVXP6PJYV3FK2A0PCNB3JM4F",
  "senderPhone": "+15551112222",
  "callerPhone": "+15551234567",
  "channel":     "whatsapp",
  "tenantId":    "acme",
  "reason":      "initial",
  "timestamp":   "2026-06-27T14:30:00Z"
}
```

Notes on the request body:
- `sessionId` is the new yaya session, not a tenant identifier. Useful
  for tenant audit logs that want to correlate "user X resolved for which
  conversation."
- `reason: "refresh"` is a hint, not a contract — tenants who don't care
  treat both identically. Tenants who do (e.g. skip a fresh DB read on
  refresh) can short-circuit.
- `timestamp` is the moment yaya issued the request, in ISO-8601 UTC.
  Used by the tenant to reject replay if they want to enforce skew.
- No PII beyond the two phone numbers. No message content. No tool args.

### 6.2 Inbound (tenant → yaya)

**Success:**
```http
200 OK
Content-Type: application/json

{ "token": "eyJhbGciOiJIUzI1NiIs..." }
```

The token MUST verify against one of the tenant's registered JWT trust
roots:
- A row in `tenant_jwt_secrets` (HS256/384/512 with the matching `kid`).
- A row in `tenant_oidc_issuers` (RS256/ES256 with a matching issuer
  + key id).

If neither matches → treat as `AuthResolverException` ("token returned
but rejected by verifier") → 502-shaped audit event → apply the binding's
`onUnresolved` policy.

**User not found:**
```http
404 Not Found
```

No body required. The binding's `onUnresolved` field decides what happens:
- `REJECT`: end the inbound dispatch, log audit, message is dropped (no
  user-facing reply — the user gave us a phone we don't recognize, we
  treat that as a closed-class denial, same as a non-existent endpoint).
- `ANONYMOUS`: proceed with the baseline channel-only Principal (the
  `whatsapp:+15551234567` subject with `channel:whatsapp` + `channel:read`
  scopes — exactly today's behavior pre-W-A).

**Transient failure (timeout, 5xx, network):**
- Single retry with `X-Yaya-Idempotency-Key` unchanged after a 250ms
  delay. If the retry also fails → `AuthResolverException` → audit →
  apply `onUnresolved`.

**Other responses:** treat 401/403 from the tenant's webhook as a config
error (we didn't sign correctly) — surface to audit with the response
status, do not retry.

### 6.3 How yaya authenticates ITSELF to the tenant

Per-resolver `service_auth_kind`:

| Kind | What yaya sends | What the tenant verifies |
|---|---|---|
| `NONE` | No `Authorization` header | Network-level trust (internal VPC, mTLS at gateway, etc.) |
| `BEARER` | `Authorization: Bearer <static_token>` | Constant-time compare against expected value |
| `SIGNED_JWT` | `Authorization: Bearer <yaya_jwt>` | JWT signature using shared HMAC secret, validate `iss`, `aud`, `exp`, `iat`, `jti` |

`SIGNED_JWT` is the **recommended default** for HTTP resolvers because the
JWT carries per-request signed metadata (yaya issuer, tenant audience,
short expiry, unique `jti`) that prevents replay better than a static
bearer.

The `SIGNED_JWT` payload yaya mints:

```json
{
  "iss": "yaya-agentic",
  "aud": "acme",                 ← the tenant id
  "sub": "auth-resolver:acme-customer-lookup",
  "iat": 1719498600,
  "exp": 1719498630,             ← 30s lifetime — single-call use
  "jti": "rq_01HVXP7Q9..."        ← same as X-Yaya-Request-Id
}
```

Signed with `service_secret_enc` (decrypted at use time via
`JwtSecretCipher`). The tenant verifies with the same shared secret —
which they receive once at resolver-creation time and which yaya stores
encrypted at rest.

> **Amended by `channel-auth-resolver-signing-kid.md`:** the SIGNED_JWT JOSE
> header carries an optional per-resolver `kid` (`service_jwt_kid`) so a
> verifier that selects its key by key-id can find it, and the `aud` claim
> can be overridden per resolver (`service_jwt_audience`) when the tenant's
> verifier expects an audience other than the tenant id.

### 6.4 URL composition rules

`tenant.host_base_url` is the per-tenant origin (e.g.
`https://acme.example.com`) — already a column on `tenants`, surfaced in
the admin console workspaces General tab.

`channel_auth_resolvers.http_path` is **just the path**, e.g.
`/yaya/resolve-identity`, validated at write time:
- Must start with `/`
- Must not contain `://`
- Must not contain `?` or `#` (query/fragment) — keep the surface narrow

Composed URL = `host_base_url + path`. The composed URL must pass the
`inbound_origin_allowlist` check that already gates outbound HTTP tools
(`1b86587`) — this prevents a misconfigured tenant from pointing the
resolver at `localhost` or an internal-only origin and exfiltrating
identity beyond their boundary.

---

## 7. The `channel_auth_resolvers` table

```sql
CREATE TABLE channel_auth_resolvers (
    id                          BIGSERIAL    PRIMARY KEY,
    tenant_id                   VARCHAR(64)  NOT NULL
                                              REFERENCES tenants(id)
                                              ON DELETE CASCADE,
    name                        VARCHAR(64)  NOT NULL,
    description                 VARCHAR(255),
    kind                        VARCHAR(16)  NOT NULL,           -- 'BEAN' | 'HTTP'

    -- BEAN only
    bean_name                   VARCHAR(128),

    -- HTTP only
    http_path                   VARCHAR(256),
    service_auth_kind           VARCHAR(32),                     -- 'NONE' | 'BEARER' | 'SIGNED_JWT'
    service_secret_enc          BYTEA,                           -- AES-256-GCM via JwtSecretCipher
    timeout_ms                  INT          NOT NULL DEFAULT 3000,

    -- Caching & refresh policy (per resolver)
    cache_enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    max_cache_seconds           INT,                              -- nullable; null = use JWT exp
    refresh_window_seconds      INT          NOT NULL DEFAULT 60,
    max_token_age_seconds       INT          NOT NULL DEFAULT 3600,

    -- Failure policy
    on_refresh_failure          VARCHAR(32)  NOT NULL DEFAULT 'REJECT',
                                              -- 'REJECT' | 'DOWNGRADE_TO_ANONYMOUS'

    status                      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- 'ACTIVE' | 'DEACTIVATED'

    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, name),

    CHECK (kind IN ('BEAN','HTTP')),
    CHECK ((kind = 'BEAN' AND bean_name IS NOT NULL AND http_path IS NULL)
        OR (kind = 'HTTP' AND http_path IS NOT NULL AND bean_name IS NULL)),
    CHECK (service_auth_kind IS NULL
        OR service_auth_kind IN ('NONE','BEARER','SIGNED_JWT')),
    CHECK (on_refresh_failure IN ('REJECT','DOWNGRADE_TO_ANONYMOUS')),
    CHECK (status IN ('ACTIVE','DEACTIVATED')),
    CHECK (timeout_ms BETWEEN 100 AND 30000),
    CHECK (refresh_window_seconds BETWEEN 0 AND 600),
    CHECK (max_token_age_seconds BETWEEN 60 AND 86400)
);

CREATE INDEX channel_auth_resolvers_tenant_status_idx
    ON channel_auth_resolvers(tenant_id, status);
```

### Bindings change

```sql
ALTER TABLE channel_bindings
    -- drop the W-A1 columns
    DROP COLUMN default_principal_policy_kind,
    DROP COLUMN lookup_principal_resolver_id,
    DROP COLUMN stamped_subject_template,
    DROP COLUMN deny_on_unknown,
    DROP COLUMN extra_policy_claims_json,

    -- add the new pair
    ADD COLUMN auth_resolver_id    BIGINT
                                    REFERENCES channel_auth_resolvers(id)
                                    ON DELETE SET NULL,
    ADD COLUMN on_unresolved        VARCHAR(16) NOT NULL DEFAULT 'REJECT';

ALTER TABLE channel_bindings
    ADD CONSTRAINT channel_bindings_on_unresolved_chk
        CHECK (on_unresolved IN ('REJECT','ANONYMOUS'));
```

`auth_resolver_id` is nullable: a binding without one falls back to today's
baseline `whatsapp:+E164` Principal exactly as it does pre-W-A. This is
the natural "FAQ-shaped bot" config — explicitly chosen, not implicit.

### Sessions change

```sql
ALTER TABLE sessions
    ADD COLUMN auth_token_enc BYTEA;     -- AES-256-GCM(JWT), nullable
```

Wiped on session end (set to NULL in the same transaction that closes the
session). NULL during sessions that ran with no resolver bound (the
ANONYMOUS path).

---

## 8. Redis-backed JWT cache

### Key + value

```
key   = auth-resolver-token:{tenantId}:{senderId}:{sha256_lower_hex(callerPhone)}
value = AES-256-GCM(JWT bytes)           ← same JwtSecretCipher as DB at-rest
TTL   = min(jwt.exp - now, max_cache_seconds_override) - refresh_window
```

We **never** put the raw phone in the Redis key — SHA-256 is enough to
namespace correctly and avoids treating Redis keyspace as PII at rest.
The value is encrypted so a Redis snapshot doesn't surface a JWT.

### Hit / miss / refresh

```
On session start:
  cached = REDIS GET key
  if cached:
    jwt = decrypt(cached)
    if jwt.exp - now > refresh_window:
      use cached JWT — NO resolver call ✨
    else:
      resolver.resolve(INITIAL) → new JWT → REDIS SET key value EX ttl
  else:
    resolver.resolve(INITIAL) → new JWT → REDIS SET key value EX ttl
```

### Eviction paths

1. **Natural TTL expiry** — Redis kills the key.
2. **Reactive 401** — when an outbound tool call using the JWT returns
   401, the executor (a) DELETEs the cache key, (b) calls
   `resolver.resolve(REFRESH)`, (c) retries the tool exactly once. No
   infinite loops; a second 401 surfaces as a denial.
3. **Resolver 404 on refresh** — user is gone tenant-side. DELETE key.
   Apply `on_refresh_failure`.
4. **Explicit invalidate API** —
   `POST /v1/admin/tenants/{t}/auth-resolvers/invalidate-cache`
   with body `{ callerPhone, senderId? }`. Deletes one or more keys
   in one round-trip. Workspace ADMIN only, audited.
5. **Resolver row deletion** — all cache keys for that resolver are
   pattern-deleted on admin DELETE. This is a one-time large operation;
   we do it in the DELETE handler before returning 204.

### Why TTL is bounded by the JWT's own `exp`

If the tenant mints a JWT with `exp = now + 1h`, the JWT is *itself* a
declaration of "this credential is valid for an hour." Caching past that
would mean we'd be answering questions with a credential the issuer
considered expired. Conversely, caching well short of that wastes the
issuer's intent. Aligning the TTL with `exp` is the only honest choice.

`max_cache_seconds` is the per-resolver knob for tenants who issue
long-lived tokens but want yaya to re-check identity more often than the
JWT itself requires. Banking flows might set this to 60s.

---

## 9. Session lifecycle

```
1. Webhook receives WhatsApp message for sender S.
2. Channel auth chain mints the baseline `whatsapp:+E164` Principal.
3. Inbound dispatch worker:
   a. Resolves binding for S → has auth_resolver_id? If NO → skip to (5)
      with the baseline Principal (binding chose FAQ mode).
   b. Looks up session for (tenant, sender, callerPhone) — fresh session
      OR continuation of an in-flight conversation? (Per-binding session
      inactivity rule — already in whatsapp-channel-design.md.)
   c. If existing session has auth_token_enc, decode & check exp:
        - exp - now > refresh_window → use stored token, skip resolver
        - else → run resolver in REFRESH mode
      If fresh session → check Redis cache (§8). If hit → use cached.
      If miss → run resolver in INITIAL mode.
   d. Resolver returns Optional<String>:
        - present → JWT verification via Authenticator chain → Principal
        - empty + on_unresolved=ANONYMOUS → baseline Principal
        - empty + on_unresolved=REJECT → drop message, audit, done
        - exception → on_refresh_failure flag governs (REJECT default)
   e. Persist JWT to sessions.auth_token_enc.
   f. Persist JWT to Redis cache (key as in §8), TTL bounded by exp.
4. Profile resolution:
   - JWT carries `_profile_id` claim? → identity-driven profile resolver
     picks it up. Otherwise binding's static profile OR fallback chain.
5. Engine.send(message, Principal, profile, sessionId)
   - LLM turn runs.
   - Tool calls fire with the JWT in `AuthForwarding.PRINCIPAL_TOKEN`.
   - If a tool returns 401:
       * Evict cache key.
       * resolver.resolve(REFRESH). New JWT, replace sessions row, replace
         cache entry, retry the tool once.
       * Second 401 → escalate to Authorizer denial, surface to LLM.
6. Session ends (natural timeout, user "/end" command, etc.):
   - UPDATE sessions SET auth_token_enc = NULL.
   - Cache key stays in Redis until natural TTL — next session for the
     same caller will reuse it.
```

### Cold-path latency budget

The "first message of a new session, no cache hit" path must complete the
identity resolve + verify + session-persist before the engine produces a
visible response. Targets:

| Stage | Budget | Notes |
|---|---|---|
| Cache lookup (miss) | <5ms | Single Redis GET |
| Resolver call (BEAN) | <5ms | In-process |
| Resolver call (HTTP) | <300ms | Default timeout 3s, p99 should be far lower |
| JWT verification | <20ms | OIDC discovery is cached after first call |
| Session + cache persist | <10ms | One DB write, one Redis SETEX |
| **Total cold path** | **<340ms (HTTP) / <40ms (BEAN)** | |

Anything over 500ms is a typing-indicator candidate (`whatsapp-channel-design.md` §11).

### Warm-path latency

```
Cache hit: <10ms total (Redis GET + decrypt + JWT exp check)
```

That's the value of the cache — for chatty conversations across multiple
sessions in an hour, every session after the first one starts in
single-digit milliseconds.

---

## 10. Profile pinning via `_profile_id` claim

Already supported by `IdentityProfileResolver` (`yaya-agentic-design.md`
§5.6) — it reads JWT claims and routes to a profile via the
`identity_profile_mappings` table. Two ways to use it:

1. **Claim-driven mapping (preferred).** Tenant's resolver returns a JWT
   with claim `_profile_id: "retail-vip"`. The
   `identity_profile_mappings` row says `claim_key="_profile_id",
   claim_value="retail-vip", profile_id="profile_retail_vip"`. Done.
2. **Static binding profile.** Binding pins a single profile regardless
   of who's calling. Used by single-profile bots ("the support bot").

When both are set: the JWT claim wins. This is consistent with the
existing precedence rule.

### Why mid-session refresh tokens are profile-immutable

The session's prompt, tool list, and conversation history are pinned to
the profile at start. If a refresh token carried a different
`_profile_id`, swapping mid-session would mean:
- The conversation history was produced with profile A's system prompt.
- The next turn would use profile B's system prompt and tool list.
- The LLM would see a discontinuity it has no way to reason about.

We log the change to audit (`profile_id_changed_during_session`) and
continue with the original profile. The next *new* session picks up the
new profile. This is the same posture the engine already takes for any
identity-driven config: changes apply to the next session boundary, not
mid-conversation.

---

## 11. Admin surface

### Endpoints

```
GET    /v1/admin/tenants/{t}/auth-resolvers                 (VIEWER)
GET    /v1/admin/tenants/{t}/auth-resolvers/{name}          (VIEWER)
POST   /v1/admin/tenants/{t}/auth-resolvers                 (ADMIN)
PUT    /v1/admin/tenants/{t}/auth-resolvers/{name}          (ADMIN)
DELETE /v1/admin/tenants/{t}/auth-resolvers/{name}          (ADMIN)
POST   /v1/admin/tenants/{t}/auth-resolvers/{name}/test     (ADMIN)
POST   /v1/admin/tenants/{t}/auth-resolvers/invalidate-cache (ADMIN)
```

The test endpoint accepts `{ callerPhone: "+15555550100", senderId?: ... }`
and fires a real resolver call, returning:

```json
{
  "ok": true,
  "transport": "HTTP",
  "url": "https://acme.example.com/yaya/resolve-identity",
  "request": { /* exact body sent */ },
  "response": {
    "status": 200,
    "elapsedMs": 142,
    "token_received": true,
    "verification": {
      "ok": true,
      "kid": "k-2026-q2",
      "issuer": "https://acme.example.com/auth",
      "subject": "u_42",
      "scopes": ["customer:read", "orders:write"],
      "exp": "2026-06-27T15:30:00Z",
      "profileId": "retail-vip"
    }
  }
}
```

The test endpoint is the workflow win: tenant operators can validate
"my webhook is wired correctly" without sending a real WhatsApp message,
and they see exactly what the JWT-verifier saw. Half the integration
debugging time disappears.

### Secrets handling on the wire

- POST/PUT request body accepts `serviceSecret` as a plaintext string
  (over TLS, one-time, like generating an API key in Stripe).
- GET response NEVER returns `serviceSecret`. It returns
  `{ serviceSecretMasked: "••••••••••••a3f2" }`.
- A "rotate secret" admin action regenerates and stores. The new secret
  is shown ONCE in the response, then masked thereafter. Same UX pattern
  as `tenant_jwt_secrets`.

### Flutter form

A new "Auth Resolvers" tab in the workspace settings screen, mirroring
the existing "JWT Secrets" and "OIDC Issuers" tabs. List → create
dialog → test button → secret-rotation modal. The binding form's
`Authentication` section gains a dropdown populated from this list when
the operator picks `auth_resolver_id`.

---

## 12. Trust boundaries

Everyone who touches a JWT, in order, and what they can and cannot do:

| Actor | Can | Cannot |
|---|---|---|
| Tenant's auth-resolver webhook | Mint a JWT for any of their users; choose `_profile_id`; set `exp` up to `max_token_age_seconds` | Stamp reserved channel claims (overridden post-verify); sign with another tenant's key (would fail verification); mint a JWT for a different tenant (would fail `aud` check, see §13) |
| The yaya `HttpAuthResolver` | Compose the call to `host_base_url + path` after allowlist check; sign with `service_secret`; cache the response token | Skip the allowlist; reuse another tenant's secret; log the JWT (zero-leak greptest, see §13) |
| The Authenticator chain | Verify the JWT against tenant's registered trust roots; produce a `Principal` | Decide what to do on failure — that's the binding's `on_unresolved` |
| The engine | Use the verified `Principal` for the session; forward the JWT to tools per `AuthForwarding`; trigger refresh | Mutate the JWT; relax expiry; share JWTs across sessions of different (tenant, sender, caller) |
| The Redis cache | Hold the encrypted JWT for the TTL window | Be the source of truth for "is this user logged in" — the resolver is |
| The admin API | CRUD the resolver config; rotate secrets; invalidate the cache | Reveal the secret after first creation; bypass workspace role gating |

The audit story:
- Every resolver call is logged to a new `auth_resolver_calls` table:
  `(id, tenant_id, resolver_name, session_id, sender, caller_sha256,
  reason, started_at, elapsed_ms, status, http_status, error_summary)`.
  Retention 90 days, queryable from the admin console.
- Cache hits / misses surface as `cache_state ∈ {HIT, MISS, REFRESH}` on
  the audit row; HITs do not trigger a resolver call so they log just the
  decode + verify step.
- Cache invalidations log to `audit_authz` with `kind="cache_invalidate"`,
  the actor, and the (sender, caller) tuple invalidated.

---

## 13. Security non-negotiables (greptest-defended)

These are the OTP-style rules from the W-A9 hardening, restated for this
design and defended by a `ChannelAuthResolverSecuritySweepTest`:

1. **No JWT cleartext in any log line.** Tokens that pass through logs
   become `<jwt:redacted>`. The sweep test calls the full resolver
   pipeline, captures logback output, and fails if any byte sequence
   matches `eyJ…` and a JWT-shaped pattern.
2. **No raw `callerPhone` in cache keys** — SHA-256 only. The sweep
   asserts the Redis keyspace contains only hex-encoded suffixes.
3. **`service_secret_enc` is never returned to clients.** The admin
   controller's response DTOs are tested to assert `serviceSecret` is
   absent and `serviceSecretMasked` is present.
4. **`aud` claim must match the resolving tenant.** A tenant who minted a
   JWT with `aud="otherTenant"` cannot use that JWT against their own
   resolver; the `Authenticator` chain already enforces this for inbound
   API calls and the same enforcement applies here.
5. **Outbound URL allowlist always runs.** A `BeforeAdvice`-style test
   asserts every `HttpAuthResolver` call goes through
   `InboundOriginAllowlistService.assertAllowed(...)` before `WebClient`
   fires. A regression that skips it is a test failure.
6. **Cache is never consulted past `exp`.** The sweep fast-forwards a
   `Clock` past `jwt.exp` and asserts the cache GET path treats the
   value as expired even if Redis hasn't reaped it yet (defense in depth
   against Redis TTL clock skew).
7. **Resolver bean lookup is exact-match.** A binding referencing
   `bean_name="acme-customer-lookup"` cannot accidentally resolve to a
   bean named `acme-customer-lookup-staging` even if Spring's prefix
   matching would normally allow it.

---

## 14. Failure modes

| Scenario | What happens |
|---|---|
| Resolver call times out (default 3s) | One retry with same `X-Yaya-Idempotency-Key` after 250ms. Two-failure threshold → `AuthResolverException` → `on_unresolved` applies. Audit row: `status="timeout"`. |
| Tenant returns 5xx | Same as timeout — single retry, then audit + `on_unresolved`. |
| Tenant returns 401/403 to yaya | No retry — config issue. Audit row: `status="self_auth_failed"`. Operator must check `service_secret` / `service_auth_kind`. |
| Tenant returns 200 with invalid JWT | `AuthResolverException`. Audit row: `status="bad_token"`. `on_unresolved` applies. |
| Tenant returns 200 with empty body | Same as 404 (user not found). `on_unresolved` applies. |
| Tenant returns 404 | "User not found." `on_unresolved` applies. |
| JWT exp claim missing | Use `max_token_age_seconds` (default 1h) from session start as the effective exp. Audit row carries `synthetic_exp=true`. |
| JWT exp claim absurdly long (> 24h) | Capped at `max_token_age_seconds` for cache TTL purposes. JWT itself remains usable until its own exp during the session; the cache just won't extend it. |
| Outbound URL fails allowlist | Hard failure at config-write time (PUT/POST rejects). At runtime, defensive recheck — should never trigger unless allowlist tightened after resolver creation. |
| Redis unreachable | Resolver call falls through (cache disabled this request). Audit row carries `cache_state="redis_unavailable"`. Operators alert; identity still flows, just less efficiently. |
| Tool call 401 mid-session | Cache evict, resolver REFRESH, retry tool once. Second 401 → Authorizer denial → LLM gets a "this action could not be completed" tool result. |
| Tool call 401 + resolver also fails on refresh | `on_refresh_failure` flag. REJECT (default) → end session with denial; DOWNGRADE_TO_ANONYMOUS → continue session without the JWT, downstream Authorizers will deny identity-gated tools naturally. |

---

## 15. Cutover plan

Single PR series. Nothing in production depends on the W-A1→W-A9
identity SPI (confirmed) so we rip and replace without a deprecation
window.

### PR 1 — design + schema (this doc + Flyway migrations)
- This doc lands.
- Flyway migration creates `channel_auth_resolvers`,
  `auth_resolver_calls`, modifies `channel_bindings` (drop W-A columns,
  add `auth_resolver_id` + `on_unresolved`), adds `sessions.auth_token_enc`.
- `whatsapp-channel-auth-design.md` and `http-phone-resolver-design.md`
  get a `🚫 Superseded by channel-auth-resolver-design.md` banner at the
  top of each.

### PR 2 — rip the W-A identity SPIs
- Delete: `PhoneToPrincipalResolver`, `PhoneToPrincipalResolverRegistry`,
  `StaticPhoneToPrincipalResolver`,
  `PrincipalPolicyMaterializer`, `DefaultPrincipalPolicy.LookupPrincipal`,
  `DefaultPrincipalPolicy.PhoneAsPrincipal` (collapses to plain
  baseline-when-no-resolver), all related tests.
- Update `WhatsAppPhoneAuthenticator` to produce ONLY the baseline
  `whatsapp:+E164` Principal; identity enrichment is now the
  resolver's job downstream.
- Update `ChannelBinding` model to its new shape.
- This PR shrinks the codebase materially.

### PR 3 — `AuthResolver` SPI + impls
- New package `com.yayatechandinnovations.yayaagentic.channel.auth`.
- `AuthResolver` SPI, `BeanAuthResolver` adapter,
  `HttpAuthResolver` bean (single bean reading from
  `channel_auth_resolvers` per-call).
- `AuthResolverRegistry` (loads by `(tenantId, name)`, very thin — no
  cache, the JWT cache is separate).
- `TenantAuthResolverRepository` JPA repo.
- Unit tests of SPI dispatch.

### PR 4 — JWT cache (Redis-backed)
- New `Cache<AuthResolverToken>` service.
- Configuration property `yaya.agentic.auth-resolver.cache.enabled`
  (default true) for emergency disable.
- Cache instrumentation + metrics (`hit`, `miss`, `refresh`, `invalidate`).
- Greptest from §13 lands here.

### PR 5 — admin CRUD + test probe + invalidate
- `TenantAuthResolverController` with five endpoints + test probe +
  invalidate-cache.
- Secret handling per §11 (mask, rotate, one-time-reveal).
- Integration tests against H2 + Testcontainers Redis.

### PR 6 — engine wiring (session lifecycle)
- Wire `ConversationEngine` to call resolver at session start, persist
  JWT to session, refresh at turn start when exp approaches, evict
  cache + retry on tool 401.
- Update `IdentityProfileResolver` integration (no code change — the
  JWT already flows through it; just verify in integration tests).

### PR 7 — Flutter admin form
- New "Auth Resolvers" tab in workspace settings.
- Test probe button surfacing the response shape from §11.
- Binding form dropdown wired to GET endpoint.
- Widget tests.

### PR 8 — runbook + acceptance
- Update operator runbook with the new resolver lifecycle.
- New tenant-facing guide: "Implementing a yaya-agentic auth resolver
  webhook" with a worked example in Node, Python, and Java.
- Final pass on the security greptest from §13.

Estimated total: ~4 weeks at the cadence W-A1→W-A9 ran at, with PR 6
(engine wiring) being the most intricate piece.

---

## 16. Acceptance criteria

- [ ] `channel_auth_resolvers` table exists with CRUD via admin endpoints,
      gated on workspace ADMIN.
- [ ] Bindings can reference an `auth_resolver_id` and an
      `on_unresolved` policy.
- [ ] A new WhatsApp session against a binding with a `BEAN` resolver
      calls the bean once and uses its JWT for the whole session.
- [ ] A new session against a binding with an `HTTP` resolver POSTs to
      `{hostBaseUrl}{path}` with the wire body in §6.1, authenticates
      itself per `service_auth_kind`, and verifies the returned JWT
      against `tenant_jwt_secrets` OR `tenant_oidc_issuers` before use.
- [ ] A second session for the same `(tenant, sender, callerPhone)`
      within the JWT's exp window does NOT call the resolver — Redis hit
      observable in metrics.
- [ ] A tool 401 mid-session evicts the cache, re-resolves, retries the
      tool once. Verified end-to-end.
- [ ] An admin invalidate-cache call removes the entry such that the
      next session re-resolves.
- [ ] `_profile_id` claim on the JWT routes to the matching profile via
      the existing `IdentityProfileResolver` + `identity_profile_mappings`.
- [ ] Mid-session refresh tokens with changed `_profile_id` log the
      attempt and continue on the original profile.
- [ ] All seven greptests from §13 pass in CI.
- [ ] Operator runbook + tenant integration guide land.
- [ ] Test probe in the admin UI returns the response shape from §11 for
      both BEAN and HTTP resolvers.

---

## 17. Open questions

1. **Should `BeanAuthResolver` show in the admin form to non-library
   tenants?** Today most tenants who land on the admin form are SaaS
   tenants who cannot ship a bean — exposing the option is just
   confusing. Lean: hide `kind=BEAN` from the form unless a Spring
   property (`yaya.agentic.auth-resolver.bean-resolvers-enabled=true`)
   is set on the deployment. Library tenants flip the flag; SaaS
   tenants don't see it.
2. **Test-probe sentinel phone format.** Should it be a hard-coded
   `+15555550100`-style number or a tenant-chosen "known good"
   sentinel? Hard-coded is simpler; tenant-chosen lets them actually
   reach a row in their user table for a realistic verification. Lean:
   tenant-chosen, defaulted to `+15555550100`.
3. **Idempotency-key reuse on refresh.** Today the
   `X-Yaya-Idempotency-Key` is per-call. On refresh, we mint a new key.
   Should the key be `(sessionId, "refresh-N")` so the tenant can
   detect "you've asked for a refresh 12 times this session"? Probably
   yes — it's a signal of "yaya is misbehaving" the tenant can flag.
4. **JWT signing-algorithm declaration.** Should
   `channel_auth_resolvers` carry an expected `algorithm` column
   (HS256 / RS256 / ES256) for early-fail? Today the chain figures it
   out from the JWT header. Eager declaration would let the test probe
   catch "your token said HS256 but your trust root is OIDC" faster.
   Lean: optional column, used as a hint by the test probe only, not
   enforced at verify time.
5. **WhatsApp playground "act-as" + AuthResolver.** Playground today
   picks a Principal directly. Should it instead pick an AuthResolver
   + a sentinel `callerPhone` so it exercises the full path? Probably
   yes for fidelity; trade-off is more setup steps. Punt to a Layer-3
   follow-up PR after the engine wiring is stable.

---

## 18. Cross-references

- `yaya-agentic-design.md` §5.4, §5.5, §5.6, §12
- `whatsapp-channel-design.md` §2, §8, §11
- `whatsapp-channel-auth-design.md` Layer 2 (step-up — retained, see §1
  banner of that doc) and Layer 3 (playground chooser — retained)
- `operator-auth-design.md` §2 (per-tenant trust roots), §4
  (`JwtSecretCipher`)
- `tenant-registry-design.md` (hostBaseUrl, inboundOriginAllowlist)
- `playground-actas-auth-design.md` (the pattern this design will
  mirror in the WhatsApp playground)
- `entity-memory-design.md` §3–4 (how the JWT's identity claims project
  into the prompt — unchanged by this design)
