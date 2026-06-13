n# Playground "Act-as" Authentication — Design (v1)

> Companion to `yaya-agentic-design.md` (§5.3 HTTP tools, §5.4 Authentication,
> §12 safety) and `operator-auth-design.md` (the two-plane principle).
> Resolves the playground 403 reported by the Flutter console when a profile
> calls an HTTP tool with `AuthForwarding.PRINCIPAL_TOKEN` against a tenant
> that requires a real end-user token.

| | |
|---|---|
| **Status** | 🟡 Proposed |
| **Scope** | Give the Flutter playground a typed, auditable way to supply an **end-user credential** for a session start — separate from the operator session cookie — so HTTP tools configured with `PRINCIPAL_TOKEN` (or profiles whose `Authenticator` rejects anonymous) actually authenticate against the tenant app. Cover three transport shapes (raw token, signed identity, on-behalf service token), one UX surface, and the audit + safety boundary. |
| **Out of scope (v1)** | Per-tool credential overrides; OAuth popup against the tenant's own OIDC; refresh-token rotation in the playground; long-lived "test user" vaults stored in yaya-agentic. All are layered on top of this design without breaking it. |

---

## 1. The one-paragraph summary

A playground session today carries the **operator's** identity (cookie session for `/v1/admin/**`) but no **end-user** identity. When the engine dispatches an HTTP tool with `AuthForwarding.PRINCIPAL_TOKEN`, it forwards the inbound `Authorization` header — which in the playground is empty (the operator authenticates via cookie). The tenant app sees an unauthenticated call and returns 403. v1 adds a typed `actAs` field to the start-session request that the playground (and only the playground, in v1) can populate with one of three discriminated shapes: a **raw bearer token** the operator pastes, a **short-lived signed identity envelope** yaya-agentic mints for an operator who has impersonation permission, or a **short-lived service token** for the same. The `SessionController` materializes whichever shape into the right header on the way into the engine; the engine and dispatchers are unchanged. Tokens never travel via `hints`, never enter prompts, never enter audit bodies in plaintext, and the operator's cookie never crosses planes.

---

## 2. Why the playground 403 is a design problem, not a bug

The two-plane principle from `operator-auth-design.md §2` is intentional: the operator's `YAYA_SESSION` cookie is an **administrative** credential, not a runtime `Principal`. The current playground flow exposes the seam:

| Step | What the playground sends | What yaya-agentic does | What the tenant app receives |
|---|---|---|---|
| `POST /v1/sessions` | `Cookie: YAYA_SESSION=…` (operator) | Authenticator chain → `NoopAuthenticator` (anonymous) → `Principal(subject="anonymous")` | — |
| `POST /v1/sessions/{id}/messages` | same | Engine starts a turn. `DefaultConversationEngine` line 148 captures `Authorization` header → empty. `ExecutionContext.attributes["inboundAuthorization"]` is **absent**. | — |
| LLM proposes an HTTP tool with `AuthForwarding.PRINCIPAL_TOKEN` | — | `HttpToolDispatcher` line 196–202: reads `inboundAuthorization`, finds null, attaches **no** `Authorization` header. | `POST /v1/orders` with no credentials → **403** |

The 403 is the tenant doing exactly what it should. The fix is not to relax the dispatcher (that would silently downgrade auth); it is to give the playground a typed channel for end-user credentials that arrives at the dispatcher already shaped as a valid `Authorization` (or `X-Yaya-Identity`, or `X-Yaya-Service-Token`) header — exactly what a production caller would attach.

**The hint trap.** `StartConversationRequest.hints` (`profile/StartConversationRequest.java:12`) is `Map<String, Object>`. It is shaped to feed prompt composition and profile resolution. Putting a token in `hints` would let the model see it, let the audit row record it in plaintext, and would silently survive a `RecordingStrategy.FanOut` to a third recorder. Credentials need their **own typed slot** with explicit redaction.

---

## 3. The non-negotiables this design preserves

These are not new — they come from `yaya-agentic-design.md §12` and `operator-auth-design.md §2`. The design only matters if it preserves them:

1. **The operator session cookie never becomes a runtime `Principal`.** Operator auth and end-user auth stay on separate planes.
2. **`hints` is prompt material, not a credential carrier.** Tokens travel in a typed field.
3. **The engine is unchanged.** The end-user credential enters the engine the same way a production credential does — through `AuthContext.headers` — so the `Authenticator` chain, `ExecutionContext` plumbing, and dispatcher forwarding behave identically in playground and production. One code path, one behavior.
4. **No tool reads its own auth.** All forwarding is still decided by `HttpToolSpec.AuthForwarding` at dispatch time.
5. **Tenant origin enforcement still runs.** `OriginEnforcer.requirePermitted` (`SessionController.java:81`) is unaffected.
6. **The LLM is never trusted.** Nothing in `actAs` reaches the model.

---

## 4. The `actAs` request field

A new optional top-level field on the start-session DTO. **Discriminated by `kind`**, not by `hints` key, so the controller dispatches with an exhaustive `switch`:

```jsonc
// POST /v1/sessions
{
  "tenant": "acme",
  "profileId": "retail-customer",
  "profileVersion": 3,
  "channel": "playground",
  "hints": {...},
  "actAs": {                                  // optional; absent → anonymous, same as today
    "kind": "raw-token",                      // "raw-token" | "signed-identity" | "service-token"
    ...                                       // shape per kind below
  }
}
```

### 4.1 The three shapes

**`raw-token` — operator pastes a credential they already have.**
The lowest-trust path. The operator goes to the tenant app, signs in as a real test user, copies the bearer token from devtools, pastes it. No permission needed beyond playground access; the operator is supplying a credential the tenant already minted.

```jsonc
{ "kind": "raw-token", "scheme": "Bearer", "token": "eyJhbGc…" }
```

Materialized to: `Authorization: Bearer eyJhbGc…` on the inbound `AuthContext.headers`. Whichever `Authenticator` in the chain matches (typically `OidcAuthenticator`) verifies and produces the `Principal`. `PRINCIPAL_TOKEN` forwarding then carries the same token to the tenant.

**`signed-identity` — yaya-agentic mints a `DelegatedHost` envelope on the operator's behalf.**
The most powerful path. The tenant already trusts yaya-agentic's Ed25519 public key (that is how `DelegatedHostAuthenticator` is wired today — `DelegatedHostAuthenticator.java:39–112`). For tenants integrated this way, the operator does not need a token at all: they declare "act as subject `u_123` with scopes `[order:read, order:write]`" and yaya-agentic constructs and signs the same envelope the host application would have signed in production.

```jsonc
{
  "kind": "signed-identity",
  "subject": "u_123",
  "scopes": ["order:read", "order:write"],
  "claims": { "displayName": "Test Customer 123" },     // optional, additive
  "ttlSeconds": 300                                       // capped at 600 by config
}
```

Materialized to: `X-Yaya-Identity: <base64url(json)>` and `X-Yaya-Identity-Sig: <base64url(ed25519)>` on the inbound `AuthContext.headers`. The chain hits `DelegatedHostAuthenticator` (`@Order(300)`), which verifies the signature against its own public key and produces the `Principal`. Downstream HTTP tools with `PRINCIPAL_TOKEN` forward the **same envelope** to the tenant — which trusts the same signature and accepts it.

**This is the headline answer to the 403.** It works for any tenant already using `DelegatedHostAuthenticator` with zero new code on the tenant side and zero stored secrets in yaya-agentic.

**`service-token` — short-lived HMAC service token.**
For tenants integrated via `ServiceTokenAuthenticator` (`@Order(200)`). yaya-agentic constructs `tenantId.subject.exp.scopesCsv.hmac` and attaches it as `X-Yaya-Service-Token`. Same shape the dispatcher already uses for `AuthForwarding.SERVICE_TOKEN`, just minted explicitly at session start instead of per-tool-call.

```jsonc
{ "kind": "service-token", "subject": "u_123", "scopes": ["order:read"], "ttlSeconds": 300 }
```

### 4.2 Why exactly these three

Each shape maps 1:1 to an **existing** `Authenticator` implementation. We are not inventing a new auth pathway; we are giving the operator a way to *supply* the input that authenticator already knows how to verify. If a tenant is wired to a fourth authenticator we add later (SAML assertion, mTLS client cert), this design extends by adding a fourth `kind` — the controller's `switch` is exhaustive and the compiler will flag every site that needs to handle it.

---

## 5. Backend wiring

### 5.1 DTOs and controller

A new sealed `ActAs` type under `auth/playground/`:

```java
// auth/playground/ActAs.java
public sealed interface ActAs
        permits ActAs.RawToken, ActAs.SignedIdentity, ActAs.ServiceToken {

    record RawToken(String scheme, String token) implements ActAs {}

    record SignedIdentity(String subject, List<String> scopes,
                          Map<String, Object> claims, int ttlSeconds) implements ActAs {}

    record ServiceToken(String subject, List<String> scopes, int ttlSeconds) implements ActAs {}
}
```

`SessionDtos.StartSessionRequest` grows an `Optional<ActAs> actAs()` field, deserialized via Jackson polymorphism on `kind`.

`SessionController.start` (`api/SessionController.java:40–59`) gains one new step **between** the existing `enforceOriginMono` and `engine.start`:

```java
AuthContext auth = authContext(tenant, exchange);
auth = actAsMaterializer.applyIfPresent(auth, body.actAs(), operatorSession(exchange));
StartSessionResult result = engine.start(req, auth);
```

`actAsMaterializer.applyIfPresent`:

1. If `actAs` is empty → return `auth` unchanged (anonymous, same as today).
2. If present → check `operatorSession` has the right permission for this `kind` and `tenant` (see §6). Deny with 403 + audit row if not.
3. Switch on `kind`, build the right header(s), and return a new `AuthContext` with those headers merged in. **Original operator headers are dropped from the runtime `AuthContext`** — only the materialized end-user headers pass on. The operator's cookie does not cross the seam.

### 5.2 What the engine sees

Nothing changes. `DefaultConversationEngine.send` line 145–148 already reads `Authorization` from `AuthContext.headers`. The `DelegatedHostAuthenticator` already reads `X-Yaya-Identity`. The `ServiceTokenAuthenticator` already reads `X-Yaya-Service-Token`. `ExecutionContext.attributes["inboundAuthorization"]` is populated naturally and the `HttpToolDispatcher` (`tool/dispatch/HttpToolDispatcher.java:196`) forwards `PRINCIPAL_TOKEN` exactly as today.

This is the design's whole point: **the runtime is identical to production**, because by the time a request leaves the controller, it is shaped like a production request.

### 5.3 Token freshness across a session

A `signed-identity` or `service-token` minted at session start has a short TTL (default 300s, cap 600s). The session may outlive it. v1 handles this by:

- The materializer stamps the **inbound `AuthContext`** with the expiring credential. The `Principal` carries `claims.exp` (as it does today for delegated/service-token cases).
- On every `POST /sessions/{id}/messages`, the controller re-runs `actAsMaterializer` using the **session's stored `actAs` spec** (persisted on session start in working memory only, alongside `pending_confirm` etc. — never to the recorder). If the spec is `raw-token` the same token is reused as-is. If it is `signed-identity` or `service-token`, a fresh envelope is minted with a fresh `exp`.
- If the operator session has been revoked or lost the impersonation permission since the start, re-mint fails closed; the next message returns 401 and the playground shows "Act-as session expired, restart the playground."

This keeps the playground feeling continuous without ever stashing a long-lived end-user credential.

---

## 6. Operator authorization gate

Not every operator should be able to impersonate. The three `kind`s map to three operator permission tiers:

| `actAs.kind` | Operator permission required | Why |
|---|---|---|
| `raw-token` | `playground:run` (the existing playground permission) | The operator supplied a token they already had. No privilege escalation; if anything they're disclosing their own credential to the runtime. |
| `signed-identity` | `playground:impersonate` per tenant | yaya-agentic mints a tenant-trusted envelope from nothing but the operator's intent. This is the powerful one. Default: granted to admin role only, ungranted to viewer/playground roles. |
| `service-token` | `playground:impersonate` per tenant | Same reason — a token forged on the operator's say-so. |

Granting and revoking `playground:impersonate` is done in the admin console (M5 surface). Until M5 ships, the permission is granted via a config-only allow-list — same pattern as the bootstrap operator (`operator-auth-design.md §4`).

**Deny output is opaque.** Per `yaya-agentic-design.md §5.5`, denial returns a `userSafeReason` ("Impersonation is not enabled for this operator") and logs a separate `auditReason` ("operator alice@acme.com lacks playground:impersonate on tenant acme"). They are different strings, by design.

---

## 7. Audit, recording, and the data boundary

### 7.1 What never gets recorded

- Raw bearer tokens (any `kind`)
- The minted Ed25519 signature bytes
- The minted HMAC token bytes

`ConversationRecorder` writes nothing about `actAs` beyond a sanitized **descriptor**:

```jsonc
"actAs": {
  "kind": "signed-identity",
  "subject": "u_123",
  "scopes": ["order:read", "order:write"],
  "mintedBy": "operator:alice@acme.com",
  "mintedAt": "2026-06-13T19:11:02Z",
  "ttlSeconds": 300
}
```

This is enough for "who acted as whom" without ever recording the credential itself.

### 7.2 What does get a dedicated audit row

A new `playground_impersonation_audit` table:

| column | purpose |
|---|---|
| `ulid` | correlation id, returned in the start-session response so the operator can quote it |
| `operator_subject` | who clicked impersonate |
| `tenant_id` | which tenant they were acting on |
| `kind` | `raw-token` / `signed-identity` / `service-token` |
| `acted_as_subject` | for the two minted kinds; null for `raw-token` (we don't decode the bearer) |
| `scopes_csv` | for the two minted kinds |
| `started_at`, `ended_at` | session bracket |
| `outcome` | `granted` / `denied:<auditReason>` |

This is the audit surface compliance asks for: who pretended to be whom, when, why we let them. It is queryable from the admin console regardless of `RecordingStrategy`.

---

## 8. Flutter playground UX

The playground today (`yayaagenticweb/lib/features/playground/playground_screen.dart` + `playground_controller.dart`) shows a profile picker and starts a session. We add **one** panel above the start button: "Act as".

```
┌────────────────────────────────────────────────────────────┐
│ Act as                                                  ▾ │
│   ○ Anonymous (default)                                    │
│   ○ Paste a token                                          │
│     ┌──────────────────────────────────────────────┐       │
│     │ Bearer ▾   eyJhbGciOi...                     │       │
│     └──────────────────────────────────────────────┘       │
│   ● Impersonate a user                  [requires perm]    │
│     subject: u_123                                         │
│     scopes:  order:read × order:write ×              + add │
│     ttl:     5 min  ▾                                      │
│                                                            │
│        Envelope ▾  Signed identity (delegated host)        │
│                    Service token (HMAC)                    │
└────────────────────────────────────────────────────────────┘
                              [ Start session ]
```

State management:

- A new `actAsProvider` (Riverpod) holds the current draft, **scoped per tenant** and **per operator session**. Persisted to `sessionStorage` (cleared on tab close), never `localStorage`. Tokens never live longer than the tab.
- The `Impersonate a user` option is disabled (and labeled "requires permission") if the operator's tenant permission set doesn't include `playground:impersonate`.
- A persistent banner appears at the top of the playground while an impersonated session is active:
  `▮ Acting as u_123 on tenant acme  ·  envelope expires in 04:21  ·  end session`
- When a session ends — by operator click, by inactivity, or because re-mint failed — the banner clears and the form drops back to whatever the operator last chose.

### 8.1 The minimal-change path

The whole UX is one new widget (`act_as_panel.dart`) imported into `playground_screen.dart`, one new provider, and one new field on `StartSessionRequest` (`yayaagenticweb/lib/models/start_session.dart`). No routing change, no new screen.

---

## 9. The thing we considered and rejected

**"Just forward the operator's `Authorization` header."** This is what the 403 path is begging us to do. It would conflate operator auth with end-user auth, violate `operator-auth-design.md §2`, and ship an operator's cookie to the tenant app. Hard no.

**"Stash credentials in `hints`."** Hints are prompt material. Tokens in prompts is a no-ship. Hard no.

**"Open the tenant's OIDC in a popup, complete a real login, capture the token."** Real, production-faithful, and the right answer for many tenants. Requires the tenant to register the playground as an OIDC client and configure CORS for the popup origin. Defer to a v2 addendum; `raw-token` covers the same ground manually in v1, and `signed-identity` covers the case where the tenant doesn't *want* to expose OIDC to a non-production surface.

**"Per-tool credential override."** Tempting for debugging a single tool, but it lets the operator put one tool on identity A and another on identity B in the same conversation. That is a session model the engine does not have, and probably should never have. Hard no.

**"Long-lived 'test user' vault: store named credentials on yaya-agentic and let the operator pick by name."** Convenient but it makes yaya-agentic a credential store, which is a much bigger security surface than the rest of the system. Deferred. If we ever build this, it sits behind the same `playground:impersonate` permission and reuses the same `actAs.kind` materialization — the vault just hydrates the request body before the controller sees it.

---

## 10. Failure modes and their visible behavior

| Failure | What the operator sees | What we log |
|---|---|---|
| `actAs` present, operator lacks `playground:impersonate` | 403 with `userSafeReason: "Impersonation is not enabled for this operator"`; playground toast | `playground_impersonation_audit` row, `outcome=denied:no-permission` |
| `actAs.kind = signed-identity` but `DelegatedHostAuthenticator` is disabled on this deployment | 422 with `userSafeReason: "Signed-identity impersonation is not configured for tenant acme"` | log only |
| `raw-token` is malformed or expired | 401 from the runtime `Authenticator` (same as production) | normal authenticator audit |
| TTL re-mint fails mid-session | next message returns 401; playground banner flips to "Act-as expired, restart the playground" | `playground_impersonation_audit.ended_at = now()`, outcome stays `granted` |
| Operator session revoked mid-impersonation | next message returns 401; playground bounces to login | operator-auth audit row + impersonation audit row close |
| Tenant has no `host_base_url` configured (per `tenant-registry-design.md`) | start-session succeeds; first HTTP tool call returns 422 with "Tenant has no host base URL" — same path as today, unchanged | unchanged |

---

## 11. Open questions

1. **Should `signed-identity` and `service-token` be allowed to *exceed* the operator's own scopes?** Argument for yes: the operator is testing a profile, not acting as themselves. Argument for no: an operator could impersonate a high-scope user they should not be testing as. v1 leans **yes with a deny-list**: the impersonation permission carries an optional `max-scope-set` per tenant; if absent, no cap; if present, requested scopes must be a subset. Worth pressure-testing with whichever tenant lights up first.

2. **Should the playground show the operator the rendered HTTP tool call (headers redacted) for debugging?** A clear UX win. The Inspector panel already exists in the playground state. Suggest adding a "headers added by yaya-agentic" row that lists `Authorization: Bearer …` / `X-Yaya-Identity: …` / `X-Yaya-Service-Token: …` with values masked except for the last 6 chars. Independent feature; would land in the same Flutter PR.

3. **Should `raw-token` accept non-Bearer schemes?** v1 allows `scheme: "Bearer" | "Basic"`. Anything else is a 422 — explicit allow-list prevents the operator from pasting `Negotiate <kerberos blob>` and expecting it to work end-to-end.

---

## 12. Test plan

| Layer | Test |
|---|---|
| Unit | `ActAsMaterializer.applyIfPresent` for each `kind`: produces the right header, drops operator headers, denies when permission missing. |
| Unit | `SignedIdentity` materializer produces a byte-for-byte match against a `DelegatedHostAuthenticator` round-trip (verify our own envelope with our own public key). |
| Integration | Profile with one `PRINCIPAL_TOKEN` HTTP tool, mocked tenant: anonymous start → tool returns 403 (regression for today's bug); `signed-identity` start → tool returns 200, mock tenant asserts the `X-Yaya-Identity` envelope decodes to the requested subject + scopes. |
| Integration | `raw-token` start with a real OIDC test JWT against a local Keycloak fixture — same flow used in M4. |
| Integration | TTL expiry: start with `ttlSeconds=2`, sleep 3s, send a message → expect re-mint, fresh `exp` in the verified `Principal.claims`. |
| Integration | Operator revoked mid-session: start with impersonation, drop the operator session in DB, send a message → 401 + audit row closed. |
| Widget | `act_as_panel.dart` shows/hides the Impersonate option based on `playground:impersonate` permission; banner appears while a session is active; tokens never serialized to `localStorage`. |
| Security | Greps confirm no `actAs` field reaches `PromptBuilder`, `ConversationRecorder.appendTurn`, or any log statement except the dedicated impersonation audit. |

---

## 13. Migration

None required in the engine, the dispatchers, or the existing `Authenticator` SPI. The change is additive:

1. `core` — no change.
2. `auth/playground/` — new package: `ActAs`, `ActAsMaterializer`, `PlaygroundImpersonationAudit`.
3. `api/SessionController` — one new line in `start`, one new field on `StartSessionRequest`.
4. `persistence/` — one new Flyway migration for `playground_impersonation_audit`.
5. `yayaagenticweb/` — one new widget, one new provider, one field on the request model.

Existing playground sessions continue to work without `actAs` (anonymous, exactly as today). The 403 only goes away on sessions that opt in.