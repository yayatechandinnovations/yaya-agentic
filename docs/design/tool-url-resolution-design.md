# Tool URL Resolution — Design (v2)

> Companion to `yaya-agentic-design.md` §5.3 (Capability & Tool) and §5.4
> (Authentication SPI). Touches `HttpToolSpec`, `HttpToolDispatcher`,
> `HttpEgressPolicy`, `TenantEntity`.

| | |
|---|---|
| **Status** | 🟡 Proposed (supersedes v1 in git history of this file) |
| **Scope** | One tool definition serves many customer applications. Tools' URLs become relative paths (`/orders/{id}`); the base URL is resolved per-tenant from server-side config, with an opt-in per-request override constrained by a per-tenant allowlist. Auth + AuthZ remain pinned to the same tenant row — never overridable per request. |
| **Out of scope (v1)** | Cryptographically-signed host identities (host-app-signed JWT carrying `{tenant, base}`). Dynamic onboarding without an operator step. Multiple bases per tool (primary + fallback). |

---

## 1. The problem in one paragraph

yaya-agentic is positioned as an extension that multiple **customer applications** mount alongside their own products. Today, supporting two customers with identical tool *contracts* but different backend URLs forces operators to duplicate every tool descriptor per customer — `list-orders-acme` vs `list-orders-beta` differ only by hostname. This bloats the registry, breaks the M5 OpenAPI importer's reusability, and makes upgrading a tool definition an N-way fan-out across customers. We want one `list-orders` tool that dispatches correctly per customer.

---

## 2. The trust principle (read first)

**The handle that identifies a customer must be one yaya-agentic can verify, not one supplied as a URL in a header.** A URL-in-a-header is an attack surface no matter what you "bind" it to:

- A malicious value for the tool base means yaya-agentic forwards the user's auth token to the attacker (`AuthForwarding.PRINCIPAL_TOKEN` paths leak credentials).
- The LLM relays attacker-controlled response bodies back to the user as if they were the real backend's answer.
- Binding the auth endpoint to the same header doesn't help — it just means the *whole* conversation happens on the attacker's host.

**The handle yaya-agentic already trusts: the tenant.** `Ids.TenantId` is verified by the `Authenticator` chain. `auth_bindings` is already per-tenant. We add **per-tenant tool base URL** to the same row, so tool dispatch and auth dispatch are structurally locked to the same operator-configured tenant identity.

Per-request flexibility (one tenant, multiple environments) is preserved as an **opt-in feature**: the tenant configures an *allowlist* of acceptable base URLs, and a per-request header can choose among them. Nothing outside the allowlist is ever honored. Auth is never overridable per request.

---

## 3. Tenant as the source of truth

Today's `tenants` row gains two columns; the rest is already there.

```sql
-- V8__tenants_host_routing.sql
ALTER TABLE tenants
    ADD COLUMN host_base_url              TEXT,
    ADD COLUMN host_base_url_allowlist    JSONB NOT NULL DEFAULT '[]';
```

The complete per-tenant routing picture, all on one row:

```
tenants row
├── id                                  acme
├── host_base_url                       https://api.acme.com           ← NEW
├── host_base_url_allowlist             ["https://*.acme.com"]         ← NEW
├── (existing — auth_bindings join)     authenticator_ref=oidc,
│                                       issuer=https://oidc.acme.com,
│                                       …
└── (existing — recording_strategies)   …
```

Reading the tenant row gives you the entire trust bundle for that customer — auth issuer, tool base, recorder. They cannot drift from each other because they're literally the same row.

---

## 4. URL shapes the tool spec accepts

```
urlTemplate (after template-substitution, before base resolution):

  https://api.acme.com/orders/123     ← absolute, used verbatim (today)
  /orders/123                         ← relative, resolved per tenant (NEW)
  orders/123                          ← REJECTED  (ambiguous)
  //example.com/x                     ← REJECTED  (protocol-relative)
  file:///etc/passwd                  ← REJECTED  (non-http(s) scheme)
  <empty after substitution>          ← REJECTED  (caller passed empty path var)
```

Grammar is tight on purpose: `http(s)://...` absolute or `/`-rooted path. Anything else is a config-save error.

---

## 5. Base URL resolution chain

When the substituted `urlTemplate` is path-shaped, the dispatcher resolves the base in this order. **First match wins. No implicit fallback.**

```
1. Per-request header   X-Yaya-Host-Base-Url
   ── HONORED ONLY when value matches a glob in
       tenants.host_base_url_allowlist for the *current* tenant
   ── Mismatch → request hard-fails with bad_host_base_url_header
       (never silently falls through — silent fall-through hides
        operator typos as wrong-host calls)
   ── Empty allowlist (the default) = header is inert; the tenant
       has explicitly opted out of per-request flexibility

2. Per-tenant default   tenants.host_base_url
   ── Applies to every request that didn't supply a valid header
   ── Set by the operator at tenant creation

3. NO FALLBACK
   ── Hard-fail with no_base_url_resolved + audit row
   ── We never default to localhost, to yaya-agentic's own host, or
       to anything implicit
```

The header **never** picks a base from a *different* tenant's allowlist. Cross-tenant escalation is structurally impossible: the allowlist looked up is the one on `tenants[principal.tenant]`.

---

## 6. Auth is NEVER overridable per request

The single most important guardrail. Recapping the user concern that motivated this design:

> "We could authorize something for a customer based on a malicious authentication/authorization endpoint."

This concern only materializes if some part of the auth resolution were taken from a per-request header. **It isn't, and it won't be:**

- `Authenticator` for tenant `acme` is resolved from the `auth_bindings` row keyed by tenant ID — operator-configured, server-side.
- `Authorizer` chain composition is global + per-tool; never reads inbound headers for its trust anchors.
- The `X-Yaya-Host-Base-Url` header from §5 has **zero** effect on which Authenticator runs, which JWKS is consulted, or which scope vocabulary applies. It only chooses among bases the *operator already trusted* by listing them.

The header chooses **which one of the operator-trusted endpoints** to dispatch tools at; it cannot **introduce** a new trust root. Auth and tool dispatch are bound by being on the same tenant row, not by sharing a per-request URL.

---

## 7. Concatenation semantics (NOT `URI.resolve()`)

Standard RFC 3986 resolution surprises operators by dropping path components from the base:

```
RFC: base=https://api.acme.com/v1  +  path=/orders  →  https://api.acme.com/orders   ← /v1 dropped!
```

Operators write `/orders/{id}` thinking *"append to the base"*. We honor that mental model with a deterministic concat:

```
resolved = trimTrailing(base, '/') + '/' + trimLeading(path, '/')
```

| base                                  | path             | resolved                                          |
|---------------------------------------|------------------|---------------------------------------------------|
| `https://api.acme.com`                | `/orders/123`    | `https://api.acme.com/orders/123`                 |
| `https://api.acme.com/`               | `/orders/123`    | `https://api.acme.com/orders/123`                 |
| `https://api.acme.com/v1`             | `/orders/123`    | `https://api.acme.com/v1/orders/123`              |
| `https://api.acme.com/v1/`            | `/orders/123`    | `https://api.acme.com/v1/orders/123`              |
| `https://api.acme.com/v1`             | `/orders?inc=x`  | `https://api.acme.com/v1/orders?inc=x`            |
| `https://api.acme.com/v1?cv=2` (oops) | `/orders`        | `https://api.acme.com/v1/orders` (query dropped — config-save warning at the time of setting the base) |

---

## 8. End-to-end: one tool, two customers

```
ToolDescriptor (registered ONCE):
  id:          list-orders
  inputSchema: { customerId: string }
  http:
    method:      GET
    urlTemplate: /v1/orders?customerId={customerId}
    response:    $.orders
```

```
Tenant acme:
  host_base_url:           https://api.acme.com
  host_base_url_allowlist: []
  auth_binding:            OIDC, issuer https://oidc.acme.com

Tenant beta:
  host_base_url:           https://prod.beta.io/api
  host_base_url_allowlist: ["https://*.beta.io"]
  auth_binding:            OIDC, issuer https://accounts.beta.io
```

Request from an authenticated user whose principal carries `tenant=acme`, calling `list-orders` with `customerId=u_123`:

```
Substituted urlTemplate:  /v1/orders?customerId=u_123
Resolved base (no header, fall through to default):   https://api.acme.com
Final URL:                https://api.acme.com/v1/orders?customerId=u_123
Egress policy check:      pass (host in tenant allowlist, IP not private)
```

Same exact tool descriptor, for a user whose principal carries `tenant=beta`, supplying `X-Yaya-Host-Base-Url: https://eu.beta.io/api` (regional override):

```
Header allowed by beta's "https://*.beta.io" glob   → YES
Resolved base:            https://eu.beta.io/api
Final URL:                https://eu.beta.io/api/v1/orders?customerId=u_123
Egress policy check:      pass
```

Attacker request for tenant `acme`, supplying `X-Yaya-Host-Base-Url: https://evil.com`:

```
acme's allowlist is empty  → header IGNORED entirely? No — header is REJECTED
Response:                 400  bad_host_base_url_header
Audit row:                tenant=acme, header_value=https://evil.com, rejected
```

Attacker request for tenant `beta`, supplying `X-Yaya-Host-Base-Url: https://evil.com`:

```
beta's allowlist = "https://*.beta.io" — evil.com doesn't match  → REJECTED
Response:                 400  bad_host_base_url_header
```

Tool dispatcher never even called `HttpEgressPolicy` for the bad URL — rejection happens at the base-resolution layer.

---

## 9. Where the tenant identity comes from (and why we trust it)

Today the conversational `Principal.tenant` is set by the `Authenticator` chain. Each shipping `Authenticator` (`OidcAuthenticator`, `DelegatedHostAuthenticator`, `ServiceTokenAuthenticator`) sources the tenant from its respective trust root:

| Authenticator | Tenant source |
|---|---|
| `OidcAuthenticator` | JWT issuer → tenant map (server-side); or a verified custom claim |
| `DelegatedHostAuthenticator` | Signed identity blob's `tenant` field, verified against the configured Ed25519 public key |
| `ServiceTokenAuthenticator` | First field of the HMAC-signed token |
| `NoopAuthenticator` (dev) | Falls back to `default` |

**For this design to be sound, the tenant on `Principal` must be cryptographically verifiable** — not taken from a query param or request body. A separate gap exists here (`SessionController` currently reads `body.tenant()` directly when no Principal-bound tenant is available), and it should be tightened *before* relying on tenant for tool routing. Tracked in §13 open question 1.

---

## 10. Code touch points

| File | Change |
|---|---|
| `tool/HttpToolSpec.java` | No schema change — `urlTemplate` keeps its type. Add Javadoc documenting the two shapes. New boolean `pinBaseToTenantDefault` (default `false`) — when `true`, the per-request header is ignored even when allowlisted. |
| `tool/dispatch/HttpToolDispatcher.java` | After template substitution, if URL is path-shaped, call `BaseUrlResolver.resolve(tenant, requestCtx)`. Existing `HttpEgressPolicy.check()` runs unchanged on the resolved absolute URL. |
| `tool/dispatch/BaseUrlResolver.java` | NEW. Single method `Resolved resolve(Ids.TenantId, RequestContext)`. Implementation reads tenant row → checks header against allowlist → falls back to default → throws `NoBaseUrlException`. |
| `tool/dispatch/RequestContext.java` | NEW. `record RequestContext(Map<String,String> headers, String traceId)`. Passes inbound headers down to the dispatcher. |
| `core/ExecutionContext.java` | Carries `RequestContext`. |
| `engine/ConversationEngine` API surface | Captures inbound request headers and threads them through `ExecutionContext`. |
| `persistence/TenantEntity.java` + V8 migration | New nullable `host_base_url` + `host_base_url_allowlist` columns. |
| `api/AdminController` | New `PUT /v1/admin/tenants/{id}/host-routing` accepting `{ hostBaseUrl, hostBaseUrlAllowlist }`. Save-time validation: base must be `http(s)://host[:port][/path]`, no query/fragment, no control chars. |
| `tool/dispatch/HttpEgressPolicy.java` | **Unchanged.** Receives the already-resolved absolute URL. |

The `BaseUrlResolver` is intentionally **not** a Spring chain — there's only one strategy. If we add a signed-identity option later (§14), it gets factored into a chain at that point.

---

## 11. Security ledger

| Concern | Mitigation |
|---|---|
| **Cross-tenant escalation via the header.** A request for tenant A supplies a header value that points at tenant B's host. | The allowlist looked up is *the one on the current principal's tenant row*. There is no global allowlist. Tenant A's allowlist can never include tenant B's hosts unless an operator explicitly says so (and tenants are operator-isolated). |
| **Open redirect-style abuse via the header.** | We never *redirect* the user there — it's an outbound server-side call. The allowlist constrains the analogous "tool call to wherever the attacker wants" pattern. |
| **SSRF / private-network egress via a header value that's syntactically valid.** | Resolved absolute URL still passes through the existing `HttpEgressPolicy` (allowlist + private-IP guard). Defense in depth. |
| **Path traversal via template variable.** `{orderId}` = `../admin`. | `HttpToolDispatcher` URL-encodes template substitutions before resolution. Regression test asserts the resolved URL doesn't escape the intended path. |
| **Base ends with a query string / fragment.** | Stripped on concat. Save-time validation rejects bases with `?` or `#`. |
| **Header injection via CR/LF in the base value.** | Save-time validation rejects bases containing control chars. The HTTP client would reject them too, but a clear save-time error is friendlier. |
| **Scheme downgrade.** Operator sets `http://` in a tenant where everything else assumes HTTPS. | If the tenant's auth binding requires HTTPS (the typical case), the same `requireHttps` flag applies at save time for the host base URL. |
| **Privileged tool sees attacker-allowlisted base.** A tenant has both a benign sandbox and a privileged prod host in its allowlist; attacker forces the prod path. | The tool spec's `pinBaseToTenantDefault: true` opts a tool out of per-request override entirely. Operators set this on tools that touch privileged operations. |
| **Tenant on `Principal` is forged.** | This design depends on a cryptographically-verified tenant. The current `SessionController` body-supplied `tenant` is a pre-existing gap; tightening it is a prerequisite (§13.1). |
| **Audit obscurity.** Operator can't tell *why* a tool fired against host X. | Every dispatch records `{tenant, resolved_base, base_source ∈ {header, tenant-default}}` in the existing tool-call audit row. Replay sees the same destination. |

---

## 12. Admin UI

- **Tenants screen (new section, today's tenants screen is mostly read-only)** — per-tenant form: *Host base URL* + *Allowlist of acceptable per-request bases* (glob editor, default empty). Yellow banner explaining the per-request header opt-in.
- **Tools screen** — URL field accepts either shape; helper text: *"Absolute URL (`https://...`) or path (`/...`) resolved against the tenant's host base."* Inline lint flags ambiguous inputs (`orders` with no leading `/`, `file://`, etc.).
- **Tool form — advanced** — `pinBaseToTenantDefault` checkbox: *"Ignore per-request `X-Yaya-Host-Base-Url` for this tool"* — default unchecked. Operators flip it on for privileged tools.
- **OpenAPI importer (M5)** — auto-populates `host_base_url` from `servers[0].url` and emits relative `urlTemplate`s for every operation. Operator confirms once at import time.

---

## 13. Open questions

1. **Tenant authenticity on `SessionController`** — today the controller reads `body.tenant()` directly when not set on the Principal. For this design to be safe, tenant MUST come from the verified Principal only (or from a signed handoff). This is a tightening separate from this design, but a **prerequisite**. Tracking it as a blocker for M2.7 ship.
2. **What if the per-request header is set but doesn't match the allowlist** — silent fall-through or hard fail? *Recommendation: hard fail* — silent fall-through turns operator typos into wrong-host calls without an obvious cause.
3. **Should the allowlist accept exact-host entries vs glob patterns?** Recommendation: glob with `*` only, no full regex. Easier to reason about; matches `HttpEgressPolicy`'s existing pattern.
4. **Per-tenant egress allowlist** — today's `yaya.agentic.http-tools.egress-allowlist` is global. Should it move to per-tenant? Out of scope for M2.7 but worth flagging — multi-customer deployments will eventually want it.

---

## 14. Future: signed host-identity for dynamic onboarding

Some deployments will want to add a new customer without an operator step. The right shape — **deferred until a real need surfaces** — is a small extension:

```
X-Yaya-Host-Identity: <signed JWT carrying { tenant_id, host_base_url, exp }>
```

- yaya-agentic verifies the JWT against a *registered signing key* (per-issuer)
- The embedded `tenant_id` becomes the routing key; `host_base_url` is honored without an allowlist check because the signature proves the operator-registered issuer authorized it
- Auth + AuthZ still resolve from server-side tenant config

This adds **dynamic onboarding within a trust boundary the operator established by registering the signing key**. It does NOT add "any host can send any URL" — the trust root remains operator-controlled, just one level up.

Slot for v3 when a customer asks. The `BaseUrlResolver` becomes a chain at that point (signed-identity-resolver at order 50, in front of header-resolver and tenant-default-resolver).

---

## 15. Sequencing

Slot as **M2.7 — Tool URL Resolution**. Estimated effort: ~1 day after the tenant-authenticity prerequisite (§13.1) ships.

### Backend deliverables

- **B7.1** V8 migration: `tenants.host_base_url` + `tenants.host_base_url_allowlist`. Backfill: `NULL` everywhere — existing tools all use absolute URLs.
- **B7.2** `TenantEntity` + repository update.
- **B7.3** `BaseUrlResolver` + `NoBaseUrlException` + `BadHostBaseUrlHeaderException`.
- **B7.4** `RequestContext` record; thread through `ExecutionContext`.
- **B7.5** `HttpToolDispatcher` path: detect path-shape, call resolver, concat semantics per §7.
- **B7.6** `HttpToolSpec.pinBaseToTenantDefault` field + JSON-schema update + migration default.
- **B7.7** Admin REST: `PUT /v1/admin/tenants/{id}/host-routing`. Save-time validation rejects bases with query/fragment, control chars, wrong scheme.
- **B7.8** Audit additions: tool-call audit row carries `resolved_base` + `base_source`.

### Flutter deliverables

- **F7.1** Tenants screen — host-routing section (base + allowlist editor).
- **F7.2** Tool form — URL helper text + ambiguous-URL inline lint.
- **F7.3** Tool form — `pinBaseToTenantDefault` checkbox in advanced section.

### Acceptance criteria

- [ ] A path-shaped `urlTemplate` with no resolved base fails with `failed: no_base_url_resolved`; audit row captured; user sees the engine's standard tool-failure paraphrase.
- [ ] Same tool with `tenants.host_base_url = https://api.example.com`: dispatches there.
- [ ] Per-tenant header override: tenant allowlist includes `https://api-staging.example.com`; header with that value: dispatches against header; audit row records `base_source=header`.
- [ ] Per-tenant header override rejected: header value not in allowlist → 400 `bad_host_base_url_header`. `HttpEgressPolicy` is never even called.
- [ ] Header for tenant A trying to point at tenant B's host (where B's allowlist would have permitted it): rejected (A's allowlist is consulted, not B's).
- [ ] Base ending with `/v1/` plus path `/orders` → `https://api.example.com/v1/orders` (concat, not URI.resolve).
- [ ] Base with a query string at save time: 400 with a clear error.
- [ ] Tool with `pinBaseToTenantDefault: true`: header ignored even when allowlisted; tenant default used.
- [ ] Existing absolute `urlTemplate` values across the entire current test suite continue to dispatch unchanged.
- [ ] Path-traversal regression: `{orderId} = "../admin"` is URL-encoded; resolved URL stays inside the intended path.
- [ ] Two tenants with the same tool descriptor and different `host_base_url` dispatch to their respective hosts; the same `Principal` cannot cross.

### Risks

| Risk | Mitigation |
|---|---|
| **Tenant authenticity gap** (§13.1) lands later than M2.7. | Don't ship M2.7 until tenant on `Principal` is verified. The two go together; calling this out as the gating condition. |
| **Operators expect `URI.resolve` semantics.** | Helper text + worked examples on the tools screen. The concat semantics are simpler — surprise is a one-time onboarding cost. |
| **`pinBaseToTenantDefault` becomes a footgun if defaulted wrong.** | Default `false` for maximum flexibility; ops checklist for new privileged tools to flip it on; UI banner on tools that grant write scopes if not pinned. |

---

## 16. Summary

- The user's safety concern (auth and tool dispatch must come from the same trusted source) is resolved **structurally** by making both per-tenant rather than per-request.
- One tool definition serves many customers because tools become **path-shaped** and the base resolves from `tenants.host_base_url`.
- Per-request flexibility is preserved as an **opt-in** per-tenant allowlist — never able to introduce a new trust root, never able to cross tenants, never able to override auth.
- Auth is **never** overridable per request. Period.
- Adding a new customer is `INSERT INTO tenants` + auth binding + base URL — no tool registry changes.
- Ships as **M2.7**, ~1 day after the §13.1 tenant-authenticity prerequisite lands.
