# Channel auth resolver — operator + tenant runbook

Companion to `docs/design/channel-auth-resolver-design.md`. Covers the
parts of the auth-resolver system operators actually touch — registering
a tenant resolver, rotating secrets, debugging via the audit table — and
the integration contract tenants need to satisfy when implementing the
webhook on their side.

If you're investigating an auth-resolver issue right now, jump to §5
(failure modes cheat sheet).

---

## 1. Architecture in 60 seconds

An auth resolver turns a verified inbound channel caller (today: a
WhatsApp phone) into a **tenant-issued JWT**. yaya-agentic doesn't run
any tenant identity logic itself — the JWT flows through the existing
per-tenant trust roots (`tenant_jwt_secrets` for HS-family,
`tenant_oidc_issuers` for RS/ES-family) and the engine sees a single
coherent Principal for the session.

```
WhatsApp inbound (caller=+15551234567 on sender=+15551112222)
        │
        ▼
ChannelBinding lookup — has auth_resolver_id?
   NO  → engine sees the baseline whatsapp:+E164 principal (FAQ mode)
   YES → continue
        │
        ▼
Redis cache hit? (key = auth-resolver-token:tenant:sender:sha256(caller))
   HIT  → use cached JWT
   MISS → AuthResolver.resolve()
            │
            ├─ kind=BEAN: dispatch to a @Component AuthResolver bean
            └─ kind=HTTP: POST tenant.host_base_url + http_path
                          with SIGNED_JWT (recommended), BEARER, or NONE auth
        │
        ▼
AuthenticatorChain verifies the JWT (per-tenant trust root)
        │
        ▼
Channel-reserved claims (phone, channel.kind, channel.sender, locale,
twilioContactSid) stamped on TOP of the verified principal — design §3 #5
        │
        ▼
sessions.auth_token_ciphertext persisted (AES-256-GCM via JwtSecretCipher)
Engine.start() — profile resolved via _profile_id claim if present
```

Storage tables: `channel_auth_resolvers` (config),
`auth_resolver_calls` (audit), `channel_bindings.auth_resolver_id` +
`on_unresolved` (binding linkage), `sessions.auth_token_ciphertext` /
`auth_token_iv` (per-session JWT). JWT cleartext NEVER lands in logs or
the audit table — that's policed by the security greptest defined in
`docs/design/channel-auth-resolver-design.md` §13.

---

## 2. Operator: configuring a tenant for resolver-backed identity

Two transports ship in-tree. Pick based on your deployment model:

- **HTTP** (default for SaaS) — tenants implement a webhook on their
  side. The operator registers it via the admin console.
- **BEAN** (library deployments only) — tenants ship a Spring
  `@Component` that implements `AuthResolver`. The bean name appears in
  the admin form's kind=BEAN dropdown.

### 2.1 HTTP resolver

1. Confirm the tenant has `host_base_url` set on `/v1/admin/tenants/{id}`
   — the workspace's "General" tab. The resolver's `httpPath` is composed
   onto this base. If it's blank, the form will surface a config error
   on first dispatch.
2. **Admin UI**: tenant workspace → "Auth resolvers" → "Add resolver".
   Pick `kind=HTTP`. Fields:
   - **Name** — stable identifier (`acme-customer-lookup`). Bindings
     reference this by id.
   - **HTTP path** — must start with `/`, no `://`/`?`/`#`. e.g.
     `/yaya/resolve-identity`.
   - **Service auth** — `SIGNED_JWT` (recommended), `BEARER` (static
     token), or `NONE` (network-level trust only — internal VPC or
     mTLS at gateway).
   - **Service secret** — the shared secret. Stored AES-256-GCM at rest.
     For `SIGNED_JWT` and `BEARER`, required on create.
   - **Timeout** — per-call, ms. Default 3000. Raise to 5000+ if the
     tenant's webhook does an external lookup.
   - **Cache** — leave enabled in prod. The TTL is bounded by the JWT's
     own `exp` minus `refresh_window_seconds`.
   - **On refresh failure** — `REJECT` (default) ends the session on
     mid-conversation auth failure; `DOWNGRADE_TO_ANONYMOUS` continues
     without identity (downstream Authorizers deny identity-gated tools
     naturally).
3. Click **Add**. The form returns with the secret masked
   (`••••••••••••<last-4>`) — the plaintext is not recoverable through
   the API. Save the original somewhere your tenant operator can paste
   it on their side. **There is no "show secret" endpoint by design.**
4. Click **Test** on the new row. The probe input dialog defaults to
   `+15555550100` as the sentinel caller; tenants who want to validate
   against a real customer phone can override here. The response surfaces
   transport, elapsed_ms, and the JWT verification block (subject,
   issuer, audience, exp, profileId, scopes). A green "Probe OK" with a
   `verification.ok=true` means the pipeline works end-to-end.

### 2.2 Attaching the resolver to a binding

Same workspace → "Channel bindings" → edit the row that should run
identity → "Identity (auth resolver)" section → pick the resolver from
the dropdown → pick `on_unresolved`:

- `REJECT` (default) — when the resolver returns 404 ("we don't know
  this caller"), drop the message. No user-facing reply. The audit row
  records the denial.
- `ANONYMOUS` — fall back to the baseline `whatsapp:+E164` principal.
  Identity-gated tools still get denied at the Authorizer; only the
  channel-scope ones (`channel:read`, `channel:whatsapp`) work.

Bindings without a resolver attached continue to run FAQ-mode (no
identity). This is the natural shape for support / lookup bots.

### 2.3 Rotating a secret

Edit the resolver in the admin UI → paste a new value in the
**New service secret** field → save. The mask updates to the new tail.
**Important**: rotating the secret invalidates all the cached tokens
that were minted under the old secret — click **Invalidate cached
tokens** on the list view to wipe them eagerly, or wait for natural TTL.

For an emergency rotation (secret leaked), do it in this order:

1. Rotate the secret on the tenant's webhook first.
2. Rotate it in the admin UI second.
3. Click "Invalidate cached tokens" to wipe Redis.

Inverting steps 1 and 2 leaves a window where yaya-agentic signs with
the new secret but the webhook verifies with the old — every dispatch
fails until step 1 lands.

### 2.4 BEAN resolver (library deployments)

When yaya-agentic is run as an embedded library (not SaaS), tenants can
ship a Spring `@Component` that implements `AuthResolver`:

```java
@Component
public class AcmeCustomerLookup implements AuthResolver {
    private final UserRepo users;
    private final TokenIssuer tokens;

    AcmeCustomerLookup(UserRepo users, TokenIssuer tokens) {
        this.users = users; this.tokens = tokens;
    }

    @Override public String name() { return "acme-customer-lookup"; }

    @Override
    public Optional<String> resolve(ResolveRequest req) {
        return users.findByPhone(req.e164CallerPhone())
                .map(u -> tokens.mintFor(u, req.session()));
    }
}
```

The bean name (`name()`) appears in the admin form's kind=BEAN dropdown
once the deployment boots. Bind it the same way as an HTTP resolver.

---

## 3. Tenant integration guide — the HTTP webhook

The tenant's webhook receives a POST from yaya-agentic on every cache
miss + refresh:

```http
POST https://acme.example.com/yaya/resolve-identity HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzI1NiIs…
Content-Type: application/json
X-Yaya-Tenant: acme
X-Yaya-Request-Id: rq_01HVXP7Q9...
X-Yaya-Idempotency-Key: rq_01HVXP7Q9...

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

Required response shapes:

```http
200 OK
Content-Type: application/json

{ "token": "eyJhbGciOiJIUzI1NiIs..." }
```

— OR —

```http
404 Not Found
```

The token MUST verify against one of the tenant's registered trust
roots:

- A row in `tenant_jwt_secrets` (HS256/384/512 with matching `iss`).
  Manage via `/v1/admin/tenants/{tenantId}/jwt-secrets`.
- A row in `tenant_oidc_issuers` (RS256/ES256 with matching `iss` and
  JWKS endpoint). Manage via `/v1/admin/tenants/{tenantId}/oidc-issuers`.

Profile pinning is optional — include a `_profile_id` claim on the JWT
and route to the matching profile via
`identity_profile_mappings`. The session's profile is locked at start;
refresh tokens with a different `_profile_id` are logged but do NOT
swap the profile mid-session.

### 3.1 Verifying the SIGNED_JWT yaya sends

When `service_auth_kind=SIGNED_JWT`, the `Authorization: Bearer` header
contains a HS256-signed JWT:

```json
{
  "iss": "yaya-agentic",
  "aud": "acme",                            ← your tenant id
  "sub": "auth-resolver:acme-customer-lookup",
  "iat": 1719498600,
  "exp": 1719498630,                        ← 30s lifetime
  "jti": "rq_01HVXP7Q9..."                  ← X-Yaya-Request-Id
}
```

Verify with the shared secret you gave to the operator, asserting
`iss="yaya-agentic"`, `aud=<your tenant id>`, `exp > now`, and (optional)
that `jti` hasn't been seen in the last 60s for replay protection.

### 3.2 Worked example — Node.js

```js
import express from 'express';
import jwt from 'jsonwebtoken';

const SHARED_SECRET = process.env.YAYA_RESOLVER_SECRET;
const TENANT_SIGNING_KEY = process.env.TENANT_JWT_SIGNING_KEY;

const app = express();
app.use(express.json());

app.post('/yaya/resolve-identity', async (req, res) => {
  // 1) Verify yaya's outbound signature
  const bearer = (req.headers.authorization ?? '').replace(/^Bearer /, '');
  try {
    const claims = jwt.verify(bearer, SHARED_SECRET, {
      issuer: 'yaya-agentic',
      audience: 'acme',
      algorithms: ['HS256'],
    });
    if (claims.sub !== 'auth-resolver:acme-customer-lookup') throw new Error('wrong sub');
  } catch (e) {
    return res.status(401).end();
  }

  // 2) Resolve the caller
  const { callerPhone } = req.body;
  const user = await users.findByPhone(callerPhone);
  if (!user) return res.status(404).end();

  // 3) Mint your tenant JWT and return it
  const token = jwt.sign(
    {
      sub: user.id,
      _profile_id: user.profileSlug,
      scope: 'customer:read orders:read',
    },
    TENANT_SIGNING_KEY,
    { algorithm: 'HS256', issuer: 'acme-identity', expiresIn: '15m' }
  );
  res.json({ token });
});
```

### 3.3 Worked example — Python (FastAPI)

```python
import os, time
from fastapi import FastAPI, Header, HTTPException, Request
from jose import jwt

SHARED = os.environ['YAYA_RESOLVER_SECRET']
SIGNING = os.environ['TENANT_JWT_SIGNING_KEY']

app = FastAPI()

@app.post('/yaya/resolve-identity')
async def resolve(req: Request, authorization: str = Header(default='')):
    bearer = authorization.removeprefix('Bearer ')
    try:
        claims = jwt.decode(bearer, SHARED, audience='acme',
                            algorithms=['HS256'], issuer='yaya-agentic')
        assert claims['sub'].startswith('auth-resolver:')
    except Exception:
        raise HTTPException(401)

    body = await req.json()
    user = lookup(body['callerPhone'])
    if not user:
        raise HTTPException(404)

    token = jwt.encode({
        'iss': 'acme-identity',
        'sub': user.id,
        '_profile_id': user.profile_slug,
        'scope': 'customer:read orders:read',
        'iat': int(time.time()),
        'exp': int(time.time()) + 900,
    }, SIGNING, algorithm='HS256')
    return {'token': token}
```

---

## 4. Operator: debugging + monitoring

### 4.1 The admin console **Test** button

The first stop for any "is this thing wired correctly" question. Surfaces:

- **Transport + URL** — confirms the composed URL matches what you
  expect (`host_base_url + http_path`).
- **Elapsed_ms** — slow probe (>500ms) means the tenant's webhook is
  slow under no load; production p99 will be worse.
- **JWT verification block** — `verification.ok=false` with a reason
  like "no authenticator recognised the JWT" means the tenant's JWT
  doesn't match any of your `tenant_jwt_secrets` or `tenant_oidc_issuers`
  rows. Check `iss` and the trust-root config.

### 4.2 `auth_resolver_calls` audit table

Every dispatch (including cache hits) writes a row:

```sql
SELECT started_at, resolver_name, reason, cache_state, status,
       http_status, elapsed_ms, error_summary
  FROM auth_resolver_calls
 WHERE tenant_id = 'acme'
 ORDER BY started_at DESC
 LIMIT 50;
```

Useful filters:

```sql
-- Look up calls for a specific caller (phone hashed; compute SHA-256
-- hex of the E.164 phone lowercased — same input the cache uses).
SELECT started_at, status, cache_state, error_summary
  FROM auth_resolver_calls
 WHERE tenant_id = 'acme'
   AND caller_sha256 = '<sha256(callerPhone, lowercase hex)>'
 ORDER BY started_at DESC;

-- Failure rate over the last hour
SELECT status, count(*) FROM auth_resolver_calls
 WHERE tenant_id = 'acme'
   AND started_at > now() - interval '1 hour'
 GROUP BY status;
```

### 4.3 Cache metrics

Micrometer counters under `auth_resolver_cache_total`:

| outcome | meaning |
|---|---|
| `hit` | Cache returned a valid JWT — resolver was not called |
| `miss` | Cache had no key; resolver fired |
| `refresh` | REFRESH-reason dispatch; cache always fires the resolver |
| `expired_on_read` | JWT past its `exp` even though Redis still had the key — defense-in-depth eviction |
| `invalidate` | Eviction (single-key or pattern) |
| `redis_unavailable` | Redis throw caught — cache disabled this request |

A healthy production tenant has `hit / (hit + miss)` above ~0.5 across
chatty conversations.

---

## 5. Failure modes cheat sheet

| Symptom | Cause | Fix |
|---|---|---|
| Probe returns `verification.ok=false, reason="no authenticator recognised the JWT"` | Tenant's JWT `iss` doesn't match any `tenant_jwt_secrets` / `tenant_oidc_issuers` row | Register the issuer; verify the JWT's actual `iss` claim |
| Probe returns `status="self_auth_failed", httpStatus=401` | Tenant's webhook rejects yaya's outbound auth | Confirm the shared secret matches on both sides; check `service_auth_kind` is what the tenant expects |
| Probe returns `status="timeout"` | Tenant webhook >timeout_ms | Raise `timeoutMs` (cap 30000), or have the tenant cache their identity lookup |
| Probe returns `status="allowlist_denied"` | Composed URL fails the SSRF/private-IP guard | Tenant's `host_base_url` is pointing at a private/loopback address; fix the tenant's hostBaseUrl |
| Probe returns `status="bad_token"` | Tenant returned a 200 with a body that's not `{"token": "..."}` JSON | Confirm response shape per §3 |
| Probe OK in admin UI but real WhatsApp inbound fails | Operator forgot to attach the resolver to the binding | Channel bindings → edit binding → pick the resolver |
| Tenant rotated their JWT signing key; sessions are now denying tool calls | Cached tokens were minted under the old key | Click "Invalidate cached tokens" — next session starts re-dispatch the resolver and get freshly signed JWTs |
| Audit shows `cache_state="HIT"` but the user is complaining they can't authenticate | The cached JWT is still within `exp - refresh_window` but the user's tenant-side identity changed (role downgrade, etc.) | Invalidate the cache for that specific (callerPhone, senderId) via the admin `invalidate-cache` endpoint with body `{callerPhone, senderId}` |
| Operator deleted a resolver but bindings still point at it | Backend sets `auth_resolver_id=NULL` via `ON DELETE SET NULL`; binding falls back to FAQ mode (no resolver) on next inbound | Either: pick a different resolver in the binding form, or accept FAQ mode |

---

## 6. Cross-references

- `docs/design/channel-auth-resolver-design.md` — the authoritative
  design doc
- `docs/design/operator-auth-design.md` §2, §4 — per-tenant JWT trust
  roots, `JwtSecretCipher`
- `docs/design/whatsapp-channel-auth-design.md` (Layer 2 — step-up
  retained) — when an identity-gated tool wants OTP, that's the
  step-up flow, NOT the auth resolver
- `docs/design/yaya-agentic-design.md` §5.4 — Authenticator chain;
  §5.6 — `IdentityProfileResolver` reads `_profile_id`
