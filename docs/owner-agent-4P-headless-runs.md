# 4P — platform: the headless run (2026-11-14)

Package 4P of the Smart Lottery contract `docs/admin-assistant-contracts/4A-summaries-contacts-areas.md`
(§4, §7 the `headless` flag, §11.2 row 4P). Everything is in this repo, uncommitted. `YA` =
`src/main/java/com/yayatechandinnovations/yayaagentic`, `YT` = the same under `src/test`.
The full reference is the guide, `docs/guides/admin-panel-sessions.md` **§17**; this file is what
the install-side client (`HeadlessRunClient`, package 4A) is built against. The shapes below are
what the tests assert.

## 1. What was built

| What | Where |
|---|---|
| Flyway `V91__headless_runs.sql` (table `headless_runs`, `UNIQUE (tenant_id, idem_key)`, index `(status, created_at)`) | `src/main/resources/db/migration/` |
| `POST /v1/headless/runs`, `GET /v1/headless/runs/{runId}` | `YA/api/HeadlessRunController.java` |
| Admission (service JWT, scope `headless:run`) | `YA/headless/HeadlessRunAuthenticator.java` + `outreach/ReplayGuard` |
| Submit checks, the queue (2 workers, 20 waiting), the run, deadline, outcome, start-up sweep, housekeeping | `YA/headless/HeadlessRunService.java`, `HeadlessRunStore.java`, `HeadlessRunProperties.java`, `HeadlessRunException.java`, `Ulids.java` |
| Engine: `startHeadless` / `sendHeadless` (no start flow, flagged tools only, nothing pauses, pre-taken reservation settled once) | `YA/engine/ConversationEngine.java`, `DefaultConversationEngine.java`, `YA/headless/HeadlessTurn.java`, `HeadlessStop.java` |
| Policy flag `headless` (catalogue key `headless`, default `false`, no Flyway script) and its rule | `YA/tool/ToolPolicy.java` (12th component + an 11-argument constructor for existing callers), `YA/engine/bootstrap/CatalogMapper.java`, one call in `YA/api/AdminController.java` |
| Channel `automation`; label reserved on the sessions API | `YA/core/TurnChannel.java`, `YA/api/SessionController.java` |
| Hidden from `latest`; `history` / `messages` / `actions` / `end` / `inspector` answer 404 to the principal; never resumed | `YA/api/SessionController.java`, `YA/persistence/SessionRepository.java` (`findReopenable`), `DefaultConversationEngine.resume` |
| The run token is never re-minted | `YA/tool/dispatch/HttpToolDispatcher.java` (`unattended`) |
| Console: a «Headless» switch on the tool form | `yayaagenticweb/lib/features/admin/tools/tools_screen.dart` |
| Settings `yaya.agentic.headless.*` | `src/main/resources/application.yml`, `YA/config/CoreConfiguration.java` |
| Guide | `docs/guides/admin-panel-sessions.md` §17 new; one line each in §6, §11, §14 |

Not touched: the prompt builder, the LLM clients, prompt caching, plan cards, the secret prompt,
metering of ordinary turns. `AnthropicLlmClientWireCacheTest` and `PlanCardApiTest` are unchanged.

## 2. The wire, as built

### 2.1 Headers (both endpoints)

| Header | Value |
|---|---|
| `Authorization` | `Bearer <JWT>`: **HS256**, signed with a key the tenant trusts in `tenant_jwt_secrets`; `iss` = that row's issuer; `exp` required; `aud` contains `yaya-agentic`; scope `headless:run` in `scope` (space-separated string) or `scp` (array). If the trust row pins claims (`required_claims_json`, e.g. `org`), the service token must carry them too. `nbf`, if present, is honoured with no skew. |
| `X-Nonce` | Unique per request per tenant (kept 10 minutes). |
| `X-Timestamp` | Epoch **milliseconds**, within 5 minutes of the platform clock. |
| `X-Idem-Key` | `POST` only. Required, trimmed, ≤ 120 characters. |
| `Content-Type` | `POST`: `application/json`. Body ≤ 64 KiB. |

The tenant is the one whose key verifies the service token; nothing in the path or body names it.

### 2.2 `POST /v1/headless/runs`

```json
{ "profileId": "owner_ops_v1",
  "principalToken": "<run token>",
  "principalNotAfter": "2026-11-16T12:10:00Z",
  "question": "…",
  "locale": "es", "timeZone": "America/Santo_Domingo",
  "context": { "automationId": "…", "automationName": "…", "executionId": "…",
               "scheduledFor": "2026-11-16T12:00:00Z", "test": false },
  "limits": { "maxAnswerChars": 1800, "deadlineSeconds": 240 } }
```

| Field | Rule |
|---|---|
| `profileId` | required string; latest installed version is used |
| `principalToken` | required string; verified by the tenant's ordinary authenticator chain (issuer, signature, expiry, pinned claims), identified subject, and claims `via == "automation"` and `viaReadOnly == true` (boolean, or the string `"true"`). `agentOnly` / `viaRef` are not read. |
| `principalNotAfter` | optional ISO-8601 UTC instant (`Instant.parse`): when the run token expires; not an instant → 422. The token's own `exp` claim is read too and **the earlier of the two** is used; with neither there is no check. Kept in the queued task only (no column). See §2.5 |
| `question` | required, 1–2,000 characters after trim |
| `locale` | optional, ≤ 16 characters; stored, not used |
| `timeZone` | optional IANA id; default `UTC` |
| `context` | optional JSON object, ≤ 4,000 characters serialized; stored only |
| `limits.maxAnswerChars` | optional integer 200–4,000; default 1,800 |
| `limits.deadlineSeconds` | optional integer 30–300; default 240 |

`202`, `Cache-Control: no-store`:

```json
{ "runId": "hr_01JCN8Z3V5T2K9Q4W7X6Y0ABCD", "status": "queued" }
```

`runId` = `hr_` + 26-character ULID. The same `X-Idem-Key` of the tenant within 24 hours of the
first run's creation returns exactly this body again (`status` is `"queued"` whatever the run's
state now) and starts nothing. After 24 hours the key starts a new run.

### 2.3 `GET /v1/headless/runs/{runId}`

`200`:

```json
{ "runId": "hr_…", "status": "completed",
  "answer": "…",
  "usage": { "inputTokens": 200, "outputTokens": 40, "cacheReadTokens": 0, "rounds": 2, "toolCalls": 1 },
  "tools": ["assistant_me"],
  "startedAt": "2026-11-16T12:00:02.118Z", "finishedAt": "2026-11-16T12:00:31.502Z" }
```

- `status`: `queued` | `running` | `completed` | `failed`.
- `answer`: only when `completed` and not yet erased (24 h after creation). Absent otherwise — never `null`.
- `failure`: only when `failed`: `{ "code", "message" }`.
- `usage`, `tools`: always present; zeros / `[]` until the run ends.
- `startedAt`: absent while `queued`. `finishedAt`: absent until final. ISO-8601 UTC.

`failure.code`: `needs_interaction` · `no_answer` · `tool_rounds_exhausted` · `deadline_exceeded` ·
`principal_rejected` · `quota_exceeded` · `llm_error` · `interrupted` · `token_expiring` (§2.5).

`interrupted` has two sources, the same for the caller (submit again, new key): the start-up sweep
(every `queued` / `running` row of the previous process), and — review fix R10 — the housekeeping
pass (every 15 minutes), which closes rows whose outcome was never written (for example the
database was unreachable when the worker ended), so they stop counting towards `429 busy`:

- `running` and `started_at` older than **10 minutes** = the longest deadline (300 s) + 5 minutes;
- `queued` and `created_at` older than **60 minutes** = the longest wait of a full queue
  (`ceil((queue + workers) / workers)` × 300 s = 55 minutes with the defaults 20 / 2) + 5 minutes.

One conditional `UPDATE` (`HeadlessRunStore.failAbandoned`) on the same states the worker's own
writes require (`complete`: `running`; `fail`: `queued` / `running`; `markRunning`: `queued`), so
whichever writes first stands and the other changes nothing. `failure.message` is
`the run did not finish and was closed`. Like every `interrupted`, the run keeps its message.

### 2.4 Errors

Body `{ "code": "…", "message": "…" }` (+ extra fields for `quota_exceeded`), JSON, `no-store`.
Checked in this order:

| # | Status | `code` | When |
|---|---|---|---|
| 1 | 401 | `unauthorized` | service token missing, not an HS JWT, unknown issuer, bad signature, expired, no `exp`, wrong `aud` |
| 2 | 403 | `forbidden` | valid service token without scope `headless:run` |
| 3 | 409 | `replay` | `X-Nonce` missing or reused; `X-Timestamp` missing, not a number, or > 5 min off |
| 4 | 422 | `invalid_request` | `X-Idem-Key` missing / > 120; body not a JSON object; any field rule of §2.2 |
| — | 202 | — | a repeated `X-Idem-Key` is answered here |
| 5 | 404 | `profile_not_found` | — |
| 6 | 403 | `principal_rejected` | `principalToken` fails verification or is not a read-only automation token |
| 7 | 403 | `profile_not_permitted` | `metadata.principalKinds` does not admit the principal (when `yaya.agentic.sessions.enforce-principal-kinds` is on, the default) |
| 7a | 422 | `no_headless_tools` | the profile has no tool a headless run may call (§3): no tool flagged `policy.headless`, or only flagged ones the engine would not offer. Review fix R12 — before it the run was accepted, the model answered with no tools and a message was counted. Not worth a resubmit until the catalogue changes |
| 7b | 409 | `token_expiring` | the token's end (`principalNotAfter` / `exp`, the earlier) is less than `deadlineSeconds` from now |
| 8 | 503 | `llm_unavailable` | the stub provider |
| 9 | 429 | `rate_limited` | 21st submit of the tenant that reached this point in the clock hour; `Retry-After` |
| 10 | 429 | `busy` | 2 runs of the tenant queued or running, or the worker queue full; `Retry-After: 30` |
| 11 | 409 | `quota_exceeded` | `{ "code", "message", "month", "monthlyMessages", "messages" }` |
| GET | 404 | `run_not_found` | unknown id, another tenant's run, or deleted (30 days) |

A refused submit stores nothing and takes no message.

### 2.5 The token must outlive the run (`token_expiring`, added with 4E)

The deadline counts from when a worker takes the run; the token from when the install minted it.
A run is started only while `now + deadlineSeconds ≤ notAfter` (`notAfter` = the earlier of
`principalNotAfter` and the token's `exp`):

- **at submit** (after `profile_not_permitted`, before `llm_unavailable`, the hourly limit, `busy`
  and the quota): `409 { "code": "token_expiring", "message" }` — nothing stored, no message
  taken, the hourly count not touched;
- **when a worker takes it**: the run goes `queued → failed` with `failure.code = "token_expiring"`
  (`startedAt` absent, `usage` zeros, `tools` `[]`), no session, no model call, no tool call, and
  the reserved message is given back (`settle(counted = false)`).

Nothing is widened: no refresh, no longer deadline. A run already started is not re-checked
(it started with enough life for its whole deadline).

## 3. The `headless` flag

- Catalogue: `policy.headless: true`. `POST /v1/admin/tools` answers
  `400 { "error": "headless_not_allowed", "message": "tool '<id>' cannot be headless: <why>" }`
  when the handler is not HTTP `GET` (so also any BEAN tool), or the policy is `confirmable`,
  `secretInput`, `clientInvocable`, `plannable` or `preconfirmableByAction`. Nothing is stored.
- A stored row that carries the flag against the rule is loaded without it (a warning is logged);
  the rest of its policy is kept.
- At run time only tools of the profile with the flag are in the provider request. A call to any
  other tool gets `{ "ok": false, "reason": "tool_not_available" }` as its result (status DENIED,
  nothing dispatched) and the run goes on. The engine re-checks the policy flags before offering
  and before dispatching.
- A profile none of whose tools pass that check is refused at submit: `422 no_headless_tools`
  (§2.4). The submit asks the engine (`ConversationEngine.offersHeadlessTools`), which runs the
  selection of the turn itself (`headlessTools(buildAvailableTools(profile))`), capability gates
  included.
- A platform that does not have 4P ignores the key.

## 4. Behaviour, per item of contract §4.2

1. Channel `automation`: `{session.channel}` renders `automation`; the session row has
   `channel = 'automation'`; hidden from `latest`, 404 on `history`, `messages`, `actions`, `end`,
   `inspector` for its principal; `resume` refuses it. Operators still see it in the console.
2. No start flow, greeting or menu.
3. Tools: §3.
4. Nothing pauses: `_confirmRequest`, `_secretPrompt`, step-up, elicitation, banca picker, a
   confirmable tool, an entity conflict → `failed / needs_interaction`; no `pending_*` written;
   `_uiHint` dropped.
5. `principalToken` forwarded as `Authorization: Bearer …` by `PRINCIPAL_TOKEN` tools; never
   refreshed; a tool call answered `401` ends the run `principal_rejected`.
6. The contract's fragment, verbatim, appended to the per-turn suffix; `{date}` is `yyyy-MM-dd`
   in `timeZone`.
7. `profile.effectiveMaxToolRounds()`; whole-run `Mono.timeout(deadlineSeconds)`; answer cut to
   `maxAnswerChars` ending in «…».
8. `UsageMeter.admit` at submit; `settle(counted = model was called)` at the end; the per-user
   limiter is not touched.
9. The profile's model.

The answer is the text after the model's last tool call. `no_answer` = blank, or every sentence
ends in `?`. The run's session is ended (and its working memory cleared) when the run finishes.

## 5. Departures from the contract, and why

| # | Contract | Built | Why |
|---|---|---|---|
| 1 | `headless_runs` columns as listed | plus `session_id UUID` | so an operator can open the run's conversation; not on the wire |
| 2 | 401 for the service token | 401, and **403 `forbidden`** for a valid token without the scope | the `StructuredCompletionAuthenticator` scheme the contract names does this; the install maps 401 and 403 the same way |
| 3 | error codes for a missing key / nonce not pinned | missing or long `X-Idem-Key` → 422 `invalid_request`; missing / bad nonce or timestamp → 409 `replay` | nearest pinned codes |
| 4 | `429 busy` = two runs of the tenant in flight | also when the worker queue (20) is full | contract §4.2 «queue 20, then 429 busy» |
| 5 | deadline for «the whole run» | counted from when a worker takes the run, not from submit | a queued run would otherwise expire unrun. The install's own 10-minute timeout is from submit; the run token (600 s) must cover queue wait + deadline — enforced since 4E: a run whose token no longer covers its deadline is not started (`token_expiring`, §2.5) |
| 6 | `interrupted` | the run keeps its message | after a restart the platform cannot know whether the model was called |
| 7 | files owned by 4P | also `AdminController` (one call, the 400), `SessionRepository` (one query), `HttpToolDispatcher` (no token refresh on `automation`), `CoreConfiguration`, `application.yml` | the rule needs an API refusal; `latest` needs a query; contract item 5 |
| 8 | — | `POST /v1/sessions` with `channel: "automation"` (any case) is stored as `web` | so no client can create a session that looks like a run's |
| 9 | `tool_not_available` result | its status is DENIED (an error result for the model) | an identical repeat is then dropped by the existing retry guard instead of burning rounds |
| 10 | answers erased after 24 h | 24 h from the run's **creation**; the question stays until the row goes (30 days) | one indexed sweep; the contract only names answers |
| 11 | `locale` | accepted and stored, not used | the fragment is pinned in Spanish |
| 12 | tests | one extra class, `HeadlessRunsMigrationTest` (V90 → V91 on a database with data) | the done criterion |
| 13 | baseline 1,072 tests | the baseline measured here before any change was **1,111**, green | — |

Not built: a way for operators to list runs in the console (the table and the session are there).

## 6. For the install client (4A)

- Poll with a fresh `X-Nonce` every time; a reused nonce is `409 replay`, not a run state.
- `X-Idem-Key` `summary:<executionId>:<attempt>` fits (≤ 120).
- `429` carries `Retry-After`; `busy` is the tenant's own two-in-flight ceiling most of the time.
- `quota_exceeded` arrives at submit (409). As a `failure.code` it is only a safety net.
- `needs_interaction` is not worth a resubmit; `interrupted`, `deadline_exceeded`, `llm_error` are.
- `422 no_headless_tools` at submit is a catalogue problem (no tool of the profile is flagged
  `headless`): nothing was stored or counted, and repeating it gives the same answer.
- Send `principalNotAfter` (the run token's expiry). `token_expiring` — `409` at submit or
  `failure.code` at poll — means the run never started and cost nothing: submit again with a
  **fresh token and a new `X-Idem-Key`** (the old key answers the old run for 24 h).
- The install must refuse non-reads for a `viaReadOnly` token itself: the platform filter is one
  of two locks.
- `tools` lists only tools whose call succeeded; `usage.toolCalls` counts refused calls too.

## 7. Tests

`YT/headless/`: `HeadlessRunApiTest` (14, with `no_headless_tools`), `HeadlessRunAuthenticatorTest` (7),
`HeadlessToolFilterTest` (3), `HeadlessCatalogFlagTest` (3), `HeadlessNeverPausesTest` (5),
`HeadlessRunMeteringTest` (6), `HeadlessRunLifecycleTest` (13, with the abandoned-run sweep), `HeadlessSessionHiddenTest` (3),
`HeadlessChannelVariableTest` (2), `HeadlessRunsMigrationTest` (1), `HeadlessRunTokenLifeTest` (4:
refused at submit by `principalNotAfter`, by `exp`, by the earlier; 422; unaffected run; a queued
run closed `token_expiring` with no session, model call, backend request or message — one worker,
held by a first run) — 61. All but the
authenticator test run the real stack (Testcontainers Postgres + Redis, WireMock as the tenant's
backend, the scripted model). `503 llm_unavailable` is asserted on the service built over
`StubLlmClient`, not over HTTP. The deadline test takes 30 s (the minimum deadline).
