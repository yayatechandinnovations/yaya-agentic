# Playground Auth-Flow Inspector (v1)

> Companion to `channel-auth-resolver-design.md` (the resolver SPI, the
> `auth_resolver_calls` audit table, the session JWT lifecycle),
> `yaya-agentic-design.md` §5.3 (Tool / `AuthForwarding`), §5.4–§5.5 (Authn
> chain / AuthzDecision), and the existing playground inspector
> (`InspectorService`, `SessionInspectorController`,
> `PlaygroundWhatsAppController` `…/outbound/{sessionId}/inspector`).
>
> This doc adds a **read-only diagnostics** surface. It changes no trust
> boundary and no resolver behaviour. Its one job: make "why did this tool
> call 401?" answerable from the playground without reading server logs.

| | |
|---|---|
| Status | Design — not yet implemented |
| Scope | New "Auth flow" section in the playground inspector panel (WhatsApp first, SMS-ready), backed by an inspector-snapshot extension |
| Primary user | A tenant operator testing a binding that uses an auth resolver |
| Non-goal | Fixing the resolver/forwarding wiring itself (see §9 — surfaced as a separate, strongly-recommended workstream) |

---

## 1. The one-paragraph summary

When an operator binds a profile to an **auth resolver** and then drives a
conversation from the WhatsApp playground, a tool call can come back `401`
with no explanation. The identity path that produced (or failed to produce)
the principal token is spread across five components and is only visible in
server logs. This design assembles a **per-session auth-flow trace** — binding
match → resolver call → JWT verification → session-token state → tool-call
forwarding — and renders it as a vertical, stage-by-stage timeline inside the
existing inspector panel. Most of the data already exists (the
`auth_resolver_calls` table, the engine's tool-activity log); the rest is
cheap, in-memory, per-session instrumentation. The first thing the panel will
make obvious is the latent wiring gap in §9.

---

## 2. The problem, concretely

Operator report: *"I have a binding to an auth resolver, and from the WhatsApp
playground I still see 401 errors on tool calls, but I don't know why."*

A `401` on a tool call can originate at **six** distinct points, and today the
playground shows none of them:

1. **No binding / wrong binding matched** — the message didn't route through
   the resolver-bearing binding at all.
2. **Resolver never ran or failed** — `config_error`, `timeout`,
   `transport_error`, `allowlist_denied`, `self_auth_failed`, or the resolver
   returned `404` (`not_found` → user not mapped).
3. **JWT minted but rejected by the authn chain** — no matching
   `tenant_jwt_secrets` / `tenant_oidc_issuers` trust root, wrong alg, expired.
4. **`on_unresolved` policy degraded to ANONYMOUS** — the turn ran, but with
   no principal token, so every `PRINCIPAL_TOKEN` tool will 401 downstream.
5. **Token never forwarded** — even on the happy path, the session JWT is
   persisted but not attached to the outbound tool call (see §9). The backend
   sees no `Authorization` header → `401`.
6. **Backend rejected a present token** — expired/insufficient-scope token that
   *was* sent; a genuine downstream auth failure.

The operator currently cannot tell #5 (our bug) from #6 (their backend) from
#3 (their trust-root config). That ambiguity is the whole problem.

---

## 3. What we already capture vs. the blind spots

Grounded in the current code:

| Stage | Already captured | Where | Blind spot |
|---|---|---|---|
| Binding match | binding id + `authResolverId` + `onUnresolved` resolved in worker | `InboundDispatchWorker` step 3 | Not surfaced anywhere per session |
| Resolver call | **fully audited** — resolver, reason, cache state, status, `http_status`, `elapsed_ms`, `error_summary` | `auth_resolver_calls` table (V41), written by `AuthResolverDispatcher.audit(...)` | Not exposed via any API; no `findBySessionId` query |
| JWT verify | decision (`JWT_VERIFIED`/`ANONYMOUS`/`REJECT`) + warn logs | `ChannelAuthIntegrator.resolve(...)` | Decision is computed, never persisted/returned past the worker |
| Session token | encrypted JWT or null | `sessions.auth_token_ciphertext` via `SessionAuthTokenStore.persist(...)` | "did this session get a token?" not exposed; cipher-not-configured only logged |
| Tool call auth | tool id, args, status, result/error (incl. `"HTTP 401 …"` text) | engine `ToolActivity` (just added) + `HttpToolDispatcher.formatHttpFailure` | forwarding **mode**, whether a header was **attached**, and the **status code as a field** are all lost — 401 collapses into generic `FAILED` |

The good news: the expensive part (durable resolver audit) is done. The work is
**assembly + exposure**, plus two small instrumentation additions (stages
binding/verify/token-state, and tool-call auth detail).

---

## 4. The model at a glance

```
                       ┌─────────────────────────────────────────┐
   inbound dispatch ──▶│ ChannelAuthTrace (in-memory, per session)│
   (worker)            │  • binding matched + resolver id + policy │
                       │  • verify decision + reason              │
                       │  • session-token state                  │
                       └─────────────────────────────────────────┘
                                          │
   AuthResolverDispatcher ──▶ auth_resolver_calls (durable, by session_id)
                                          │
   engine tool loop ──▶ ToolActivity (+auth diag: mode, header?, httpStatus)
                                          │
                                          ▼
                        AuthFlowDto  ◀── InspectorService.snapshot(sid)
                                          │
                        InspectorSnapshot.authFlow
                                          │
   GET …/whatsapp-playground/outbound/{clientSessionId}/inspector
                                          │
                                          ▼
                        Flutter _InspectorPanel → "Auth flow" section
```

The inspector endpoint already maps the client-chosen session id to the engine
session id via `PlaygroundSessionRegistry.engineFor(...)`, so the auth-flow
trace keys off the same engine session id as everything else in the snapshot.

---

## 5. The auth-flow stages we render

Each stage is one row in a vertical timeline with a status pill
(`ok` / `skipped` / `warn` / `fail`), a one-line headline, and expandable
detail. A stage is `skipped` (greyed) when not applicable (e.g. FAQ-mode
binding has no resolver stage).

### 5.1 Stage — Binding
- **headline:** matched binding + profile, "resolver: `<name>`" or "FAQ mode (no resolver)".
- **detail:** `authResolverId`, `onUnresolved` policy.
- **fail:** `no_binding_matched` (the worker's `markFailed` reason).

### 5.2 Stage — Identity resolution (from `auth_resolver_calls`)
- **headline:** `<resolver> · <cacheState> · <status> · <elapsed_ms>ms`.
- **detail:** `reason` (INITIAL/REFRESH), `http_status`, `error_summary`,
  caller hash (never the raw phone).
- **status mapping:** `ok`→ok; `not_found`→warn ("caller not mapped to an
  identity"); `timeout`/`transport_error`/`self_auth_failed`/`config_error`/
  `allowlist_denied`→fail.
- May be **multiple rows** (an INITIAL then a REFRESH after a 401 — once §9
  lands).

### 5.3 Stage — JWT verification (from `ChannelAuthTrace`)
- **headline:** decision `JWT_VERIFIED` / `ANONYMOUS` / `REJECT`.
- **detail:** which authenticator matched, or the chain-rejection reason
  ("not recognised by any authenticator — check tenant_jwt_secrets/
  tenant_oidc_issuers" vs "rejected by chain: <safe reason>").
- **warn** on `ANONYMOUS` ("ran without a principal token — `PRINCIPAL_TOKEN`
  tools will not be authenticated"); **fail** on `REJECT`.

### 5.4 Stage — Session token
- **headline:** "JWT stored on session" / "no token on session" / "cipher not
  configured — token not persisted".
- This is the stage that directly explains a downstream 401 when empty.

### 5.5 Stage — Tool call auth (per call, from extended `ToolActivity`)
- **headline:** `<toolId> · <forwarding mode> · header <present|absent> · HTTP <status>`.
- **detail:** the existing args + error text, plus the new auth fields.
- **fail** on `401`; the row visually links back to the empty Session-token
  stage when `authForwarding=PRINCIPAL_TOKEN` and header absent — i.e. it
  *points at the cause*.

---

## 6. Backend changes

All additive; no SPI rename, no sealed-type variant.

### 6.1 `auth_resolver_calls` → expose by session
Add to `AuthResolverCallRepository`:
```java
List<AuthResolverCallEntity> findBySessionIdOrderByStartedAtAsc(UUID sessionId);
```
(`session_id` already exists and is indexed-able; ascending = chronological for
the timeline.)

### 6.2 `ChannelAuthTrace` — in-memory per-session stages
A new `@Component` mirroring `PlaygroundSessionRegistry`'s deployment
assumption (single-instance, bounded `LinkedHashMap`, keyed by **engine**
`SessionId`). Three setters called from `InboundDispatchWorker`:
```java
void recordBinding(SessionId s, String bindingId, String profileId,
                   Long resolverId, String onUnresolved);
void recordVerification(SessionId s, Decision decision, String detail);
void recordSessionToken(SessionId s, TokenState state); // STORED | ABSENT | CIPHER_DISABLED
```
The worker already computes all three (binding step 3, `authResolved.decision()`
step 5b, `authTokenStore.persist` step 3e) — we only add the trace writes. No
new logic in the auth path, so no behavioural risk.

> Why in-memory and not a table: this is operator playground diagnostics, same
> bar as `PlaygroundOutboundSink` and the engine's `lastToolActivity`. The
> durable record that matters for audit (`auth_resolver_calls`) already exists.
> Production channels keep using logs + the audit table.

### 6.3 Tool-call auth diagnostics on `ToolActivity`
Extend the engine's `ConversationEngine.ToolActivity` record (added with the
info panel) with three optional fields: `authForwarding` (NONE/PRINCIPAL_TOKEN/
SERVICE_TOKEN), `authHeaderPresent` (Boolean), `httpStatus` (Integer). To
populate them, `HttpToolDispatcher` must surface what it did:
- it already knows `spec.authForwarding()` and whether it put a header on the
  request (`applyAuthForwarding`);
- on `WebClientResponseException` it has `e.getStatusCode()`.

Carry these out of the dispatcher on the `Turn.ToolResult`. Two options:
- **(a)** add an optional `Map<String,Object> diagnostics` to `Turn.ToolResult`
  (sealed-type-free record; additive), or
- **(b)** a side-channel map in `ExecutionContext` the engine reads after
  dispatch.

**Recommendation: (a).** It keeps the data attached to the result it describes,
flows naturally into `ToolActivity`, and is one nullable field. The
`httpStatus` also lets us finally distinguish 401 from other `FAILED` results
in the tool-activity section generally — useful beyond auth.

### 6.4a Resolver exchange capture (the request/response debug view)

Status/latency from `auth_resolver_calls` answers *whether* the resolver
succeeded, but not *why* it returned no identity. To debug that, `HttpAuthResolver`
captures the actual HTTP exchange — the request yaya sent and the response it
got — into an in-memory `ResolverExchangeStore` (bounded, keyed by
`tenantId|callerSha256`, recovered the same caller-hash way as the audit row).

Redaction happens **at the source**, so the store never holds a usable secret
or token:
- the outbound `Authorization` (yaya's BEARER/SIGNED_JWT service credential to
  the tenant) → `"<KIND service credential, redacted>"`;
- the JWT in a `{"token": "..."}` response → `"<JWT, N bytes, redacted>"`
  (operator-chosen: redact value, not decode-claims).

Captured on success and every error branch (401/403, ≥400, timeout, transport).
Surfaced as an expandable Request/Response tile under the resolver stage. The
request body (`senderPhone`, `callerPhone`, `channel`, `reason`, …) is shown
verbatim — operator-owned PII, the whole point of the debug view.

### 6.4 `AuthFlowDto` + snapshot wiring
- New `InspectorDtos.AuthFlowDto` with: `binding`, `resolverCalls` (list),
  `verification`, `sessionToken`, plus a derived `List<String> hints` (see
  §7).
- `InspectorService.snapshot(sid)` assembles it from §6.1 (repo), §6.2
  (`ChannelAuthTrace`), and the existing `engine.lastToolActivity(sid)` (now
  carrying auth diag). Add `authFlow` to `InspectorSnapshot.of(...)`.
- **No new endpoint.** It rides the existing
  `…/outbound/{sessionId}/inspector` and `/v1/sessions/{id}/inspector`. The
  SSE playground gets the same section for free.

---

## 7. The "where it fails" heuristic (the actual ask)

The panel does more than dump stages — it renders a short, ordered list of
**diagnosis hints** computed server-side in `AuthFlowDto`, so the operator
reads a conclusion, not a puzzle. Rules (first match wins, but all surfaced):

- resolver stage `config_error` → *"Resolver misconfigured: `<error_summary>`. Check the resolver registration."*
- resolver stage `timeout`/`transport_error` → *"Couldn't reach the resolver webhook. Check the URL/allowlist/network."*
- resolver stage `not_found` → *"The caller (`<hash>`) isn't mapped to an identity by the resolver."*
- verify `REJECT`/chain-not-recognised → *"Resolver returned a JWT the trust chain rejects. Register a matching `tenant_jwt_secrets`/`tenant_oidc_issuers`."*
- verify `ANONYMOUS` + a `PRINCIPAL_TOKEN` tool present → *"Turn ran anonymously; `PRINCIPAL_TOKEN` tools have no identity to forward."*
- session token `ABSENT`/`CIPHER_DISABLED` + tool `401` + `authHeaderPresent=false` → *"A token exists upstream but none was forwarded to `<tool>` (see §9 wiring gap)."* / *"Set the session-token cipher key."*
- tool `401` + `authHeaderPresent=true` → *"A token WAS sent and the backend still rejected it — likely expired or missing scopes. This is downstream of yaya."*

These hints are the difference between "I see 401" and "I know which of the six
points failed."

---

## 8. Flutter changes

In `whatsapp_playground_screen.dart`'s `_InspectorPanel` (already added), insert
an **"Auth flow"** section **above** "Tool activity" (identity precedes use):
- a vertical timeline: each stage = leading status dot + headline + tap-to-expand detail (reuse the existing `ExpansionTile`/section idiom);
- a top **hints banner** when `authFlow.hints` is non-empty (amber for warn, red for fail), each hint one line;
- the tool-activity rows gain a small `mode · header · HTTP nnn` trailer from the new diag fields.

`InspectorSnapshot.fromJson` gains `authFlow` (plain class, opaque-ish, same
style as the existing snapshot models). Backwards-compatible: absent →
section hidden.

---

## 9. The latent wiring gap this will expose (strongly recommended companion fix)

The investigation behind this design found that the diagnostics will, on day
one, point at a real gap rather than operator misconfig in the common case:

- `SessionAuthTokenStore.read(...)` exists but is **called nowhere** in
  `channel/` or `engine/`.
- The channel `authHeaders(...)` sets only end-user/sender/locale headers — **no
  `Authorization`** — so `ExecutionContext.attributes["inboundAuthorization"]`
  is null for channel sessions, and `PRINCIPAL_TOKEN` forwarding attaches no
  header (`HttpToolDispatcher.applyAuthForwarding` silently no-ops on a blank
  token).
- The tool-level **401 → resolver REFRESH → retry** path (referenced as
  "PR 6c" in `CLAUDE.md` and `channel-auth-resolver-design.md` §9) is **not
  implemented**; `isTransient` deliberately excludes 401.

So even a perfectly configured resolver yields 401 on `PRINCIPAL_TOKEN` tools.

**This design is observability-only by request** ("create a design first"), and
deliberately does not change the auth path. But the fix is small and the panel
is far more useful next to it:
- **Forward the token:** in `engine.send`, when `inboundAuthorization` is absent,
  fall back to `SessionAuthTokenStore.read(sessionId)` and inject it (Bearer)
  before tool dispatch.
- **Implement 6c:** on a tool `401` with `PRINCIPAL_TOKEN`, call
  `AuthResolverDispatcher.dispatch(..., REFRESH)`, replace the session token +
  cache, retry once; second 401 → surfaced failure. Each REFRESH already
  audits to `auth_resolver_calls`, so the panel shows the retry automatically.

Recommend sequencing: **Phase 1** = this inspector (ships value immediately,
makes the gap undeniable); **Phase 2** = the §9 wiring fix (the panel then
shows the green happy path and any genuine downstream 401s).

---

## 10. Security / trust boundaries

- **Never render the JWT.** The panel shows token *presence/state*, never bytes.
  `error_summary` is already secret-redacted and length-capped (512) at write
  time in `AuthResolverDispatcher`.
- **Caller phone stays hashed.** Surface `caller_sha256` only, matching the
  audit table; never reconstruct the E.164.
- **userSafeReason discipline (design §5.5).** Verify-stage detail uses the
  user-safe wording; the audit-only reason is not shipped to the panel beyond
  the already-redacted `error_summary`.
- **AuthZ unchanged.** The inspector endpoints already require workspace
  `MEMBER` via `workspaceAccess.require` + `tenantGuard`. Auth-flow data is
  tenant-scoped through the session id; no cross-tenant read.
- **No new trust edge.** Read-only assembly of existing state; the LLM never
  sees any of it.

---

## 11. Phasing / PRs

- **PR 1 — data exposure:** `findBySessionIdOrderByStartedAtAsc`, `AuthFlowDto`
  (resolver-calls only, from the durable table), wire into snapshot + a minimal
  Flutter "Auth flow" section. Ships the resolver timeline with zero new
  instrumentation.
- **PR 2 — `ChannelAuthTrace`:** binding/verify/session-token stages + worker
  writes + the §7 hints.
- **PR 3 — tool-call auth diag:** `Turn.ToolResult.diagnostics` →
  `ToolActivity` auth fields → tool-row trailers + the §7 token-not-forwarded
  hint.
- **PR 4 (separate, recommended):** the §9 wiring fix (token forwarding + 6c
  refresh). Not part of the inspector, but the inspector is its acceptance UI.

---

## 12. Non-goals

- Changing resolver behaviour, caching, or the trust chain.
- A durable per-session auth-flow table (the audit table already covers durable
  needs; the rest is ephemeral playground diagnostics).
- Multi-instance fan-out of the in-memory trace (same single-instance
  assumption as `PlaygroundOutboundSink`).
- Production end-user surfacing — this is operator-tier tooling only.

---

## 13. Resolved decisions

All four open questions are settled (operator sign-off):

1. **Trace lifetime — both.** `ChannelAuthTrace` is wiped on `engine.end`
   (and session GC) **and** bounded by entry count like
   `PlaygroundSessionRegistry`. No trace outlives its session; a long-running
   instance can't leak the map.
2. **History depth — session-start resolution AND per-tool detail.** The panel
   shows the identity resolution that ran **when the session started** (the
   INITIAL `auth_resolver_calls` row, plus any later REFRESH rows — full session
   history, cheap from the table), **and** per-tool-call auth detail: for every
   tool call, whether the **bearer/principal token was actually included** on
   the outbound request, the forwarding mode, and the HTTP status. So §5.5's
   per-call row explicitly answers "was the token sent to *this* tool?", and
   §5.2's resolver timeline always shows the session-open resolution even when
   no tools were called yet. Per-tool detail follows `lastToolActivity` (latest
   turn); resolver calls accumulate across the whole session.
3. **SSE playground parity — on everywhere.** The section rides the shared
   snapshot, so the standard SSE playground gets it too. It's inert (the whole
   section hidden) when the session used no resolver, so there's no cost to
   leaving it on.
4. **`Turn.ToolResult.diagnostics` — approved (option a).** A single optional
   `Map<String,Object> diagnostics` field on `Turn.ToolResult`, carrying
   `authForwarding` / `authHeaderPresent` / `httpStatus`. It is transient
   diagnostic metadata, **not** part of the recorded transcript contract: the
   recorder and the LLM-history projection (`toHistoryEntries`) ignore it; only
   the engine's `ToolActivity` capture reads it. This keeps the data attached to
   the result it describes and flows naturally into the inspector.
