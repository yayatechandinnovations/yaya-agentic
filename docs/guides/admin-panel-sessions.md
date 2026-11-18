# Sessions from a tenant's own app, as the signed-in user

How an app that belongs to a tenant (for example an admin panel served from the
tenant's own domain, on web, desktop or mobile) talks to an agent profile **as
its logged-in user**, with no second login. This is the contract the app codes
against. Everything here is covered by `AppSessionsApiTest`,
`SessionStopSemanticsTest`, `SessionInspectorAccessTest`, `UsageMeterTest`,
`PrincipalKindsTest`, `PostToolTextHistoryTest`, `HttpToolTemplateSafetyTest`,
`TurnChannelTest`, for §15 `SecretPromptApiTest`, `HttpToolSecretHeaderTest`,
`SecretPromptsTest` and `SecretInputCatalogRoundTripTest`, for §17 the
`Headless*Test` classes named there, and for §6 `elsewhere` / §18
`SessionHandoffApiTest`, `HandoffBlockTest`, `HandoffTapTest` and
`WhatsAppChannelHandoffE2ETest`.

Base URL: the platform's public URL (`yaya.agentic.public-base-url`). All paths
below are relative to it.

## 1. Rules that hold on every call

| Rule | Detail |
|---|---|
| Identity | `Authorization: Bearer <the user's current session token>` on **every** request. No cookies are used or accepted cross-origin. |
| Tenant | The tenant id is sent once, at start (and on the two tenant-level GETs). A token is only accepted for a tenant whose trusted issuer is pinned to it (§10), so a token of another business is rejected even when both share a signing key. |
| Ownership | A session belongs to the token subject (`sub`) that started it. Every later call must present a valid token with the same `sub`; anything else answers `404 session_not_found`. |
| Token used by tools | The token on **each** `messages` / `actions` call is the one forwarded to the tenant's backend for that turn. Refresh the token in the app as usual and just send the new one. |
| Body | `Content-Type: application/json`. |
| Errors | Non-2xx responses are always JSON (§8), also on the streaming endpoints. |

## 2. Start a session

`POST /v1/sessions`

```json
{ "tenant": "bancadh", "profileId": "owner_ops_v1", "channel": "admin-app" }
```

| Field | Notes |
|---|---|
| `tenant` | Required for an app. The tenant id the install reports. |
| `profileId` | The assistant to open. It is a request, not a choice: the server accepts it only if the profile is installed for the tenant **and** its `metadata.principalKinds` admit the caller (§10). Omit it to let the tenant's identity→profile mappings decide. |
| `profileVersion` | Optional. Omit to get the latest version. |
| `channel` | Free label stored with the session (default `web`). It is only a label: tools always see these sessions as channel `app` (§14). |

`200`:

```json
{
  "sessionId": "5b0e…",
  "profileId": "owner_ops_v1",
  "profileVersion": 6,
  "greeting": "Soy tu asistente de operaciones…",
  "quickReplies": ["…"],
  "traits": { "menu": { "…": "…" } }
}
```

`greeting` is what to show first; it can take a few seconds when the profile
runs a start flow (it may call tools with the user's token, and may have the
model write the greeting). `traits` carries the profile's client traits (its
`menu`, when it has one); `{}` otherwise.

Errors: `401 auth_required | token_expired | token_invalid`,
`403 profile_not_permitted`, `404 profile_not_available`,
`403 origin_not_permitted`, `429 rate_limited`.

- **Limit.** One user may start 10 sessions per minute
  (`yaya.agentic.sessions.session-starts-per-minute`,
  `YAYA_SESSIONS_STARTS_PER_MINUTE`). Past it: `429 rate_limited` with
  `Retry-After: <seconds>`. This limit is separate from the message limit.
  Do not start a session on every page load: call `latest` (§6) first and
  start one only when it answers `NONE` or `EXPIRED`, or when the user asks
  for a new conversation.
- **Allowance.** Starting a session never uses a message of the monthly
  allowance, and it is never refused because the allowance is used up: the
  session opens so the app can show that state (read `usage`, §7). In that
  state a metered profile's greeting is not written by the model: it is the
  start flow's fixed text, or the profile's own greeting. The first `messages`
  call then answers `quota_exceeded` (§8).
- When the model does write the greeting, its tokens are added to the
  tenant's and the profile's token totals (§11); `messages` is unchanged.

## 3. Send a message (SSE)

`POST /v1/sessions/{sessionId}/messages` — response `text/event-stream`.
Send `Accept: text/event-stream`.

```json
{
  "text": "súbele a 80",
  "clientContext": {
    "screenId": "sales_controls.detail",
    "recordRef": "control:42",
    "label": "Control de ventas — Colmado Lina"
  }
}
```

| Field | Notes |
|---|---|
| `text` | The user's words. May be `""` when `selection` is sent. |
| `selection` | Answer to a frame (§5, §15, §16), the tap on a bound action (`{kind:"action", actionId}`) or on a quick reply (`{kind:"option", id, send}`: the engine takes `send` — else `text`, else `id` — as the user's words and keeps the tap on the recorded message). Optional. |
| `clientContext` | What the user has open. Optional; see below. |
| `attachmentIds` | Ids returned by §3a, at most 4, each usable once. Optional. |

### 3a. Attachments (photos, PDFs, voice notes)

Upload first, then name the ids in the message.

`POST /v1/sessions/{sessionId}/attachments` — `multipart/form-data`, part
`file` (filename matters for the name shown; the type is sniffed from the
bytes), optional text part `durationSeconds` for a voice note the app
measured. Same bearer token as the session: only the session's owner may
upload. → `201`:

```json
{ "id": "chatatt_…", "kind": "IMAGE|AUDIO|DOCUMENT", "filename": "ticket.jpg",
  "contentType": "image/jpeg", "sizeBytes": 183020,
  "transcript": "…", "durationSeconds": 14,
  "codes": [ { "format": "QR_CODE", "text": "4130552218-7781902345" } ],
  "extractedTextChars": 0, "url": "/v1/chat/media/<token>" }
```

- Kinds: images JPEG/PNG/WebP (≤ 10 MB; JPEG/PNG are re-encoded, EXIF
  removed, long side ≤ 1,600 px), audio OGG/Opus, WebM, M4A/AAC, MP3, WAV
  (≤ 10 MB and ≤ 120 s), PDF (≤ 15 MB; text extracted). HEIC/HEIF is refused
  — convert on the device first.
- `codes`: QR / barcodes read from an image, if any. `transcript`: the voice
  note's text (Spanish by default, from the profile's language).
- `url`: the file, served by `GET /v1/chat/media/<token>` with no
  `Authorization` header — the signed token is the authorization and lasts
  15 minutes. History (§6) returns a fresh one per render; it is `null` once
  the bytes were removed (30 days after the message was sent).
- Limits: 4 per message, 20 uploads per session per hour, 150 MB per user
  per day. An uploaded file that is never sent is deleted after 24 hours.

| HTTP | code | Client |
|---|---|---|
| 413 | `too_large` | Over the size cap, or a voice note over 2 minutes (`hint` says so). |
| 415 | `unsupported_type` | Not a kind listed above; for HEIC the body carries `hint: "Mándala como JPG o PNG"`. |
| 429 | `too_many_uploads` | 20 uploads in an hour on this session (`Retry-After`). |
| 429 | `quota_exceeded` | 150 MB uploaded today by this user. |
| 429 | `voice_quota_exceeded` | The business's voice minutes of the month are used up; type instead. |
| 422 | `transcription_failed` | The voice note was kept (`id`, `url`, `durationSeconds` present; `transcript` null); offer «Reintentar» or let the user type. |
| 403 | `owner_required` | A playground session without a signed-in owner. |
| 404 | `session_not_found` | Not this user's session. |

In the message, what the model gets: the images as vision input (first two),
the codes as data lines, the PDF text as data, the voice note as
`[nota de voz, 0:14] <transcript>`. A 6-digit number after «código» in a PDF
or a code is masked before the model sees it. A message naming an id that is
not this session's, or already sent, answers `404 attachment_not_found`; more
than four, `400 too_many`.

### `clientContext`

Given to the model **for this turn only**, marked as unverified UI context. It
is never stored in the conversation the model re-reads, and it is never proof
of anything — the backend still decides what the user may see or do. Send it
on every message while a screen is open; leave it out when there is none.

| Field | Limit |
|---|---|
| `screenId` | ≤ 96 chars, only `A–Z a–z 0–9 . _ : / -` |
| `recordRef` | ≤ 160 chars; letters, digits, space and `. _ : / \| = , # @ + -` |
| `label` | ≤ 200 chars; line breaks and control characters become spaces |

All three are optional strings. Any other key, a non-string value, or a value
over its limit → `400 invalid_client_context` (nothing runs).

### Events

Each event is `event:<name>` + `data:<json>`. A turn is a sequence of these,
ending with exactly one `end` — or one `error` (§8).

| Event | `data` |
|---|---|
| `token` | `{"text":"…"}` — append to the assistant bubble. |
| `tool_call` | `{"callId":"…","tool":{"value":"report_sales_summary"},"args":{…}}` |
| `tool_result` | `{"callId":"…","status":"OK"\|"DENIED"\|"FAILED","value":{…},"error":null,"diagnostics":{…}}` |
| `ui_hint` | `{"kind":"…","payload":{…}}` — §4. |
| `citation` | `{"chunkId":"…","source":{"value":"…"},"title":"…","url":"…"}` |
| `end` | `{"turnId":{"value":"…"},"tokensIn":1234,"tokensOut":210}` — tokens the model used in this turn (0/0 when the turn did not need the model). |
| `error` | `{"code":"quota_exceeded"\|"turn_failed","message":"…",…}` — the turn ended; no `end` follows. |

`tool_call` / `tool_result` are informational (a "working…" indicator). Render
from `token` and `ui_hint`. Ignore event names and hint kinds you do not know.

Example (a read that also opens a screen):

```
event:tool_call
data:{"callId":"toolu_01","tool":{"value":"open_screen"},"args":{"screenId":"sales.controls"}}

event:tool_result
data:{"callId":"toolu_01","status":"OK","value":{"ok":true},"error":null,"diagnostics":{}}

event:ui_hint
data:{"kind":"open_screen","payload":{"screenId":"sales.controls","route":"/controls","args":{},"label":"Controles de venta"}}

event:token
data:{"text":"Abrí los controles de venta."}

event:end
data:{"turnId":{"value":"0c5b…"},"tokensIn":8120,"tokensOut":64}
```

## 4. `ui_hint` kinds

Same engine and same payloads as the chat API.

| `kind` | `payload` | Meaning |
|---|---|---|
| `confirm` | `{toolId, callId, args, summary, confirmLabel, cancelLabel, items?}` | A change is waiting for the user's decision (§5). `summary` is the question; `items` (`[{id,label,checked}]`) appears when several things are confirmed together and the user may untick some. |
| `secret_prompt` | `{callId, title, message, format, length?, expiresInSeconds, attemptsLeft?, canResend}` | A tool needs a code from the user (§15). |
| `plan` | `{callId, title, steps:[{id, toolId, summary}], confirmLabel, cancelLabel, canDrop, text}` | Several changes are waiting for one decision (§16). |
| `plan_step` | `{callId, stepId, state, message?}` | One step of a plan the user agreed to changed state (§16). |
| `plan_result` | `{callId, steps:[{id, toolId, summary, state, message?}]}` | The plan finished; the final state of every step (§16). |
| `data_table` | `{title?, columns, rows, totals?}` | A table built from a tool result. |
| `data_card` | `{title?, rows:[{label,value,type}]}` | A key/value card. |
| `attachment` | `{assetId, filename, contentType, sizeBytes, url}` | A document. `url` is **relative to the platform base URL** (`/v1/media/<token>`), signed and short-lived; open it by navigation / download, no `Authorization` header needed. |
| `quick_replies` | `{options:[{id, label, send, style?}], source}` | Suggested next messages (one shape for every producer, `docs/design/chat-experience-design.md` and the assistant-channels design §2.2). Tapping one sends `send` as `text` together with `selection {kind:"option", id, send}`. `send` defaults to `label`, `id` to a slug of the label; `style` is `primary`, `danger` or `neutral` when set. `source` says who produced them: `rule` (a tool's suggestion rules), `start` (a capability's follow-ups or a start flow), `ask` (a tool's `_askUser`, see below), `suggester` (the reply suggester: a model call the engine validated — never over a frame, only when the profile sets `metadata.policy.replySuggestions: true` and the answer ended with a question), `frame`. Older servers sent `{items:[…], actions?:[{label,send}]}`; a client may keep reading those shapes. Transient: not kept in `uiHints`. |
| `menu` | the profile menu with fresh badges | Same shape as `traits.menu`. |
| anything a tool emits | — | A tool result field `_uiHint {kind, payload}` is passed through unchanged as a `ui_hint` (and removed from what the model sees). `open_screen` arrives this way: `{screenId, route, args, label, webUrl}`. |

`actions` is not a hint kind on this API; action buttons arrive inside
`quick_replies.options`.

A tool result may carry a reserved `_askUser` key — `{kind: "choice|yes_no",
question, options:[{id,label,send}]}` — handled like `_uiHint`: removed before
the model sees the result, then emitted as `quick_replies {source:"ask"}` after
the assistant's final text of that turn. `yes_no` without options is the
localised «Sí» / «No» pair. The last tool of the turn to ask wins, and the ask
takes the place of any `rule` or `start` chips of the same turn (a ticket the
model identified from a photo is confirmed before «Anular» is offered); rule
and start chips also leave after the final text. A pending confirm, plan or
secret prompt in the same turn suppresses the ask (the frame's buttons are the
only question).

## 5. Confirm or cancel

When a turn ends with a `confirm` hint, nothing has been done yet. The same
turn also streams `payload.summary` as a `token` (for text-only channels):
when you draw the card, do not show that text a second time. The arguments
are held on the server; the app only sends the decision:

```json
{ "text": "", "selection": { "kind": "confirm", "callId": "<payload.callId>" } }
{ "text": "", "selection": { "kind": "cancel",  "callId": "<payload.callId>" } }
{ "text": "", "selection": { "kind": "confirm_items", "callId": "…", "itemIds": ["id1","id3"] } }
```

- `confirm` runs the held call and streams `tool_call`, `tool_result`, hints,
  then the model's short account of what happened, then `end`.
- `cancel` streams one `token` ("Listo, no hice nada.") and `end`. It does not
  use the model and does not count against the allowance.
- `confirm_items` confirms only the ticked `itemIds` (it can remove items,
  never add).
- A `callId` that is no longer pending (already answered, or replaced) streams
  one `token` saying the confirmation is no longer valid, then `end`. Nothing
  runs. Disable the card's buttons once one is tapped.
- Only one confirmation is pending per session. Typing a different request
  instead of answering drops it.
- Two answers to the same card sent at the same moment (a double tap, a
  retried request) run the change once: the second gets the "no longer
  valid" answer.
- One frame waits per session — a confirmation, a plan (§16) or a code prompt
  (§15). A new one replaces the one that was waiting.
- When the model asks for several changes in one answer and they cannot go on
  one plan card (§16), the first gets its `confirm` card and each of the
  others arrives in the same turn as a `tool_call` + `tool_result` whose
  `value` is `{"ok": false, "reason": "deferred", "userSafeMessage": "…"}`.
  Nothing ran for those; the model asks for them again, one card at a time,
  once the first is answered. A deferred call is not listed in
  `lastToolResults`.
- A turn that raised a card ends there, also when other tool calls of the
  same answer returned results: the model is asked again only after the
  card is answered.

## 6. Reopen where the user was

`GET /v1/sessions/latest?tenant=<tenant>&profileId=<profileId>&history=true&channel=admin-app`

`profileId` is optional (any profile). `history=false` skips the turns.
`channel` is optional: when given, only a session started with that
`channel` label (§2) is a `RESUMABLE` / `EXPIRED` candidate, so a panel never
reopens the user's WhatsApp or chat-app thread — those are reported as
`elsewhere` instead (below, and §18). Without `channel` the newest open
session is returned whatever its label, as before.
The conversation of a headless run (§17) is never returned here, also while
the run is still going: it belongs to no screen.

```json
{
  "state": "RESUMABLE",
  "sessionId": "5b0e…",
  "profileId": "owner_ops_v1",
  "profileVersion": 6,
  "createdAt": "2026-11-13T13:02:11Z",
  "lastActivityAt": "2026-11-13T13:40:02Z",
  "turns": [
    { "role": "user", "text": "abre los controles", "createdAt": "…", "uiHints": [], "channel": "admin-app" },
    { "role": "assistant", "text": "", "createdAt": "…",
      "uiHints": [ { "kind": "open_screen", "payload": { "…": "…" } } ], "channel": "admin-app" },
    { "role": "assistant", "text": "Abrí los controles.", "createdAt": "…", "uiHints": [], "channel": "admin-app" }
  ],
  "pendingConfirm": null,
  "pendingSecret": null,
  "pendingPlan": null,
  "lastToolResults": [
    { "callId": "toolu_01", "tool": "open_screen", "status": "OK", "createdAt": "…" }
  ],
  "elsewhere": {
    "sessionId": "9c41…", "channel": "whatsapp",
    "lastActivityAt": "2026-11-18T14:02:11Z", "turns": 9,
    "preview": { "user": "Qué pasó con el ticket 4411…", "assistant": "El ticket 4411 está confirmado…" },
    "pending": "confirm"
  }
}
```

`elsewhere` (only computed when `channel` was sent; `null` when there is
none) is the user's other open conversation on this profile, born on a
different channel, with a turn in the last 24 hours — the newest if several.
A headless run never counts. `preview` is the last user and assistant text,
each cut to 160 characters; `pending` is `confirm`, `plan` or `secret` while
that conversation still waits for an answer there, else `null`. The panel
shows it as a one-line suggestion («Conversación en WhatsApp hace 12 min —
[Continuar aquí] [Ignorar]») and never opens it by itself: «Continuar aquí»
is the hand-off of §18.

`lastToolResults` lists the tool calls that **finished** after the user's last
stored message (`status`: `OK`, `DENIED` or `FAILED`), `[]` when there are
none. It is how a client that stopped a turn learns what became of a call that
was running (§12). A confirmed change appears under the `callId` of its
`confirm` card.

| `state` | What the app does |
|---|---|
| `RESUMABLE` | Show `turns`, keep sending to `sessionId`. If `pendingConfirm` is not null it is the `confirm` payload still waiting: draw the card again. If `pendingSecret` is not null it is the secret prompt still waiting (§15): draw it again. If `pendingPlan` is not null it is the `plan` payload still waiting (§16): draw it again. At most one of the three is not null. |
| `EXPIRED` | The session is older than the profile's `metadata.policy.sessionMaxAgeHours`. `turns` may be shown for reference; start a new session before sending. |
| `NONE` | No open session for this user. `sessionId` is null, `turns` is `[]`. Start one. |

Notes on `turns`:

- `channel` is the label of the surface each turn came in on (`admin-app`,
  `whatsapp`, `chat`, `sms`, `console`, …): the session's own label (§2) for
  a turn on this API, the channel's name elsewhere. It is `null` on turns
  stored before it was recorded. The same field is on `history` and on the
  `carried` turns of a hand-off (§18).
- One user message can be followed by several assistant entries (a step that
  showed hints, then the closing text). Entries with neither text nor hints
  are omitted.
- `uiHints` are the frames shown in that step, except `quick_replies` and
  `menu` (only meaningful live), `confirm` (use `pendingConfirm`),
  `secret_prompt` (use `pendingSecret`) and `plan` / `plan_step` /
  `plan_result` (use `pendingPlan`; a finished plan is not replayed — the
  steps that ran are in `lastToolResults` and the model's account is a turn).
  Attachment `url`s are re-signed on every read.
- A user turn sent with files (§3a) carries `attachments`:
  `[{ "id", "kind", "filename", "contentType", "sizeBytes", "transcript"?,
  "durationSeconds"?, "codes"?, "url" }]` — the same shape as the upload
  response, `url` freshly signed per read, `null` once the bytes were removed
  by retention (show the name only). Absent or `[]` on text-only turns.
- Do **not** replay `open_screen` hints from history as navigation.

`GET /v1/sessions/{sessionId}/history` returns `{ sessionId, turns,
pendingConfirm, pendingSecret, pendingPlan, lastToolResults }` for one session of the caller (also after
it ended).

A session survives a platform restart: the next `messages` call restores it.
The app does not need to do anything.

## 7. End, actions, usage

- `POST /v1/sessions/{sessionId}/end` → `200`, empty body. The session stops
  being offered by `latest`; later `messages` answer `409 session_ended`.
  Call it for "new conversation".
- `POST /v1/sessions/{sessionId}/actions` `{ "tool": "<toolId>", "args": {…} }`
  → SSE, same events. Runs one tool the profile marks as client-invocable
  (e.g. "load more"), without the model. A tool that is not allowed answers
  `403 {"error":"FORBIDDEN",…}` with no further detail. Not counted against the allowance.
- `GET /v1/sessions/usage?tenant=<tenant>` →

```json
{ "month": "2026-11", "messages": 412, "monthlyMessages": 1500,
  "voiceSecondsUsed": 840, "voiceSecondsLimit": 3600, "imagesIn": 57 }
```

  `monthlyMessages: null` = unlimited. What is left = `monthlyMessages -
  messages` (never below 0). Any signed-in user of the tenant may read it.
  Refresh it after each `end` event, or when a `quota_exceeded` error arrives.
  `voiceSecondsUsed` / `voiceSecondsLimit` (null = unlimited) are the month's
  voice-note seconds; `imagesIn` the images given to the model. Hide the mic
  when `voiceSecondsUsed >= voiceSecondsLimit`.

## 8. Errors and what the client does

HTTP errors (before the stream starts) are JSON. Two shapes exist; read
`code`, falling back to `error`:

```json
{ "error": "TOO_MANY_REQUESTS", "message": "too many requests, try again shortly", "code": "rate_limited" }
{ "error": "origin_not_permitted", "message": "origin '…' is not permitted for tenant '…'" }
```

| HTTP | code | Client |
|---|---|---|
| 401 | `token_expired` | Refresh the user's token, repeat the same request once. If refresh fails, send the user to login. |
| 401 | `token_invalid` | The token is not accepted for this tenant (wrong business, bad signature). Do not retry; treat as signed out / misconfigured. |
| 401 | `auth_required` | No bearer token was sent. |
| 403 | `profile_not_permitted` | This account cannot open that assistant. Hide the panel. |
| 404 | `profile_not_available` | The assistant is not installed for this tenant. Hide the panel. |
| 404 | `session_not_found` | Unknown session, or not this user's. Call `latest`, or start a new session. |
| 409 | `session_ended` / `session_expired` | Start a new session and resend. |
| 400 | `invalid_client_context` | A bug in the app: fix the `clientContext` it sends. |
| 429 | `rate_limited` | More than 20 messages in a minute by this user (actions have their own equal limit), or more than 10 session starts in a minute by this user (§2). Header `Retry-After: <seconds>`. Wait, then let the user resend; do not retry a start in a loop. |
| 403 | `origin_not_permitted` (in `error`) | The page's origin is not this tenant's (§9). |
| 403 | `FORBIDDEN` (in `error`) | An `actions` call for a tool that is not allowed. |

Errors after the stream started arrive as one SSE `error` event and the stream
ends (HTTP status is `200`):

```
event:error
data:{"code":"quota_exceeded","message":"Se agotaron los mensajes del asistente incluidos este mes. …","month":"2026-11","messages":1500,"monthlyMessages":1500}
```

| code | Meaning | Client |
|---|---|---|
| `quota_exceeded` | The tenant's monthly allowance is used up. Refused before anything ran: no tool was called, the model was not asked, nothing was counted. | Show `message` (already in the profile's language) as a notice, not as an assistant answer. Keep the user's text in the input. |
| `turn_failed` | The turn broke (model or tool infrastructure). Parts may already have been streamed. It is not counted when it broke before the model was called, or when the model call failed without producing anything. | Show a retry. If a `tool_result` with `status: OK` for a change was already received, the change **did** happen — say so instead of offering a blind retry. |

A dropped connection without `end` or `error` is a network failure. The
server treats it exactly like a stop (§12): follow the same steps to learn
what happened before offering a retry.

## 9. Browser access (CORS)

- Allowed origins on `/v1/sessions/**`: the tenant's own origin — the origin of
  `tenants.host_base_url`, or, when the tenant has an inbound origin allowlist,
  exactly that list — plus `yaya.agentic.sessions.extra-origins`
  (`YAYA_SESSIONS_EXTRA_ORIGINS`, comma-separated, `*` wildcard) for shared
  admin domains and local development.
- A preflight is answered for any known origin; the request itself is then
  checked against the tenant it addresses and refused with
  `403 origin_not_permitted` when the origin is another tenant's.
- Allowed request headers: `Authorization`, `Content-Type`, `Accept`,
  `Cache-Control`, `Last-Event-ID`. Methods: `GET`, `POST`. `Retry-After` is
  exposed. Credentials are **not** allowed: use `fetch` with the default
  `credentials` (no cookies) — in Flutter web, do not set `withCredentials`.
- SSE is a `POST` with a body, so use a streaming `fetch` / HTTP client, not
  `EventSource`.
- A newly registered tenant origin is accepted within one minute.
- Desktop and mobile builds send no `Origin`; CORS does not apply to them.

## 10. Per-tenant setup (done by provisioning, not by the app)

1. **Trust the install's user tokens, pinned to this tenant.**
   `POST|PUT /v1/admin/tenants/{tenantId}/jwt-secrets`

   ```json
   {
     "iss": "smart-lottery-identity",
     "secret": "<the install's signing secret>",
     "audiences": ["smart-lottery-api"],
     "principalKind": "SmartLotteryJwtAuthenticator/staff",
     "requiredClaims": { "org": "<this business's organization id, as in the token>" }
   }
   ```

   - `requiredClaims`: every token must carry these claim values or it is
     rejected for this tenant. This is what makes the tenant unambiguous when
     several businesses share one signing secret.
   - `principalKind`: the label matched against a profile's
     `metadata.principalKinds`. It is refused without `requiredClaims`.
   - On `PUT`, leaving either field out keeps its value (a secret rotation
     does not unbind the tenant); `""` / `{}` clears it.
   - Without this step the owner profile answers `403 profile_not_permitted`.
     With `requiredClaims` set, tokens of the same issuer that lack the claim
     stop being accepted for the tenant on every channel.

2. **Install the profile** with `metadata.principalKinds` naming that kind,
   and, as wanted, the new optional profile fields on
   `POST /v1/admin/profiles`:

   | Field | Meaning |
   |---|---|
   | `model` | Model for this profile's turns. Must be listed in `yaya.agentic.llm.allowed-models` (`YAYA_LLM_ALLOWED_MODELS`), else `400 model_not_allowed`. `""` = the deployment's model. |
   | `maxToolRounds` | Tool rounds per turn, 1–16, else `400 max_tool_rounds_out_of_range`. The default is 5. |
   | `metered` | `true` = the profile's turns count against, and are refused by, the monthly allowance. `false` = never refused. |

   Every `POST` saves a complete new version, with one exception — these
   three fields **when they are left out or `null`**:

   | The profile id… | A field left out or `null` means |
   |---|---|
   | does not exist yet for the tenant | The default: the deployment's model, 5 rounds, not metered. |
   | already has a version | The value of its latest version. The new version keeps it. |

   So a save that does not mention `metered` never switches metering off, and
   one that does not mention `model` never moves the profile to another model.
   A value that is sent always wins. To change one back on an existing
   profile, send it: `"metered": false`, `"model": ""` (the deployment's
   model), `"maxToolRounds": 5`. An installer that wants a fixed result on
   every run sends all three every time.

3. **Origin**: `tenants.host_base_url` (or the tenant's inbound origin
   allowlist) must be the origin the app is served from.

4. **Allowance** (pushed from the tenant's plan):

   `PUT /v1/admin/tenants/{tenantId}/usage-quota` `{ "monthlyMessages": 1500 }`
   — `null` = unlimited; the key must be present. → `{ "monthlyMessages": 1500 }`

   `GET /v1/admin/tenants/{tenantId}/usage?month=YYYY-MM` (month optional =
   current) →

   ```json
   {
     "month": "2026-11", "messages": 412, "inputTokens": 3310442, "outputTokens": 88120,
     "cacheReadTokens": 2876300, "cacheCreationTokens": 214800,
     "monthlyMessages": 1500,
     "profiles": [
       { "profileId": "owner_ops_v1", "metered": true, "messages": 412, "inputTokens": 3310442, "outputTokens": 88120,
         "cacheReadTokens": 2876300, "cacheCreationTokens": 214800 },
       { "profileId": "player_vend_v1", "metered": false, "messages": 9051, "inputTokens": 41200300, "outputTokens": 702114,
         "cacheReadTokens": 36911000, "cacheCreationTokens": 1502000 }
     ]
   }
   ```

   Both need a workspace ADMIN or a platform-scope API key, like the other
   tenant admin endpoints.

5. Optional: `settings.timeZone` on the tenant (an IANA id such as
   `America/Santo_Domingo`) sets where the month turns over.

## 11. How the allowance is counted

- Only **metered** profiles draw on it. The top-level `messages`,
  `inputTokens`, `outputTokens` are the metered totals; `profiles` lists every
  profile for cost reporting. Customer-facing profiles are unmetered and are
  never refused.
- One message = one user turn for which the model was called. It counts from
  the moment the call starts: closing the stream (§12) or losing the
  connection afterwards does not give the message back.
- Not counted: a refused turn (`quota_exceeded`, `rate_limited`), a turn that
  failed before the model was called, a turn whose model call failed without
  producing anything, a cancel, a stale confirmation, an `actions` tap,
  starting a session. A plan (§16) the user agreed to is one message however
  many steps it has; cancelling one is none. Confirming a change counts once its call has finished
  and the model is asked to report the result; a confirm stopped while the
  call is still running does not.
- Tokens are the provider's figures summed over the model calls; input
  includes cached prompt tokens. `cacheReadTokens` and `cacheCreationTokens`
  say how much of `inputTokens` was read from, and written to, the provider's
  prompt cache: a read costs about a tenth of the input price and a write
  about 1.25× (2× with the one-hour cache), so the input cost of a month is
  `(inputTokens − cacheReadTokens − cacheCreationTokens) + 0.1 × cacheReadTokens
  + 1.25 × cacheCreationTokens` input-priced tokens. A month whose
  `cacheReadTokens` stays near zero is being billed in full on every call.
  They are recorded whenever the provider
  reported them, also for a turn that did not count as a message, and for a
  greeting the model wrote at session start (tokens only, no message). A model
  call that is cut off before it finished reports no figures, so its tokens
  are not in the totals. Not included: conversation summarisation.
- The check happens before the turn does anything, with one atomic counter per
  tenant and month, so parallel turns cannot exceed the allowance.
- The month is the calendar month in the tenant's `settings.timeZone`, or UTC
  when the tenant has none (or an invalid one).
- On WhatsApp, SMS and the chat app the same refusal is spoken as one fixed
  sentence instead of an `error` event.
- A headless run (§17) is one message, taken when the run is accepted and
  given back when the model was never reached.

## 12. Stopping a turn

There is no stop endpoint. The client stops a turn by closing the event
stream of that `messages` (or `actions`) call. A connection that drops on its
own is handled the same way. What the server does depends on how far the turn
had got:

| The stream is closed… | Server | Stored in the conversation |
|---|---|---|
| while the model is writing, before any tool call | The model call is cancelled. No tool is called afterwards. | Nothing: neither the user's message nor the partial answer. The next turn's model does not know the message was sent. |
| while a tool call is running (a read, or a change that needs no confirmation) | The call is **not** interrupted: it runs to its end and its result is stored. Then the turn ends: the model is not asked again and no further tool is called. | The user's message and the call with its result. No closing text. |
| after the user confirmed a change, while that call is running | Same: the call runs to its end, exactly once, and its result is stored under the `callId` of the `confirm` card. The confirmation is already used up (`pendingConfirm` is null). | The call with its result. The model's account of it is not written. |
| after a `tool_result` arrived, while the model is writing its account | The model call is cancelled. | The call and its result (stored before the account started). Not the partial account. |
| after a `confirm` card arrived, before the user answered | Nothing is running. | The card stays pending (`pendingConfirm`). |

Rules that follow:

- A tool call that started is never cut short and never lost: its result is
  stored whether or not anyone is listening, and the next turn's model reads
  it. A call that had not started when the stream closed never starts.
- A change is never run twice because of a stop. Sending the same `confirm`
  again answers "no longer valid" and runs nothing (§5). The identifiers the
  backend receives for the call (`callId`, and any idempotency key derived
  from it) are the ones of the original card.
- A stopped turn counts against the allowance if the model had been called
  for it — also when nothing of it was stored (first row of the table). It
  does not count when the stop came before any model call: a confirmed change
  still running, or a card waiting for an answer.

What the client does after a stop (or a dropped connection):

1. Keep what was already streamed on screen, marked as stopped. A
   `tool_result` with `status: OK` that was received means that call happened.
2. `GET /v1/sessions/{sessionId}/history` and read it:
   - `pendingConfirm` not null → draw that card again; nothing ran.
   - The stop followed a **confirm**: look for the card's `callId` in
     `lastToolResults`. `OK` → show that the change was applied. `FAILED` /
     `DENIED` → show that it was not. Absent → the call is still running, or
     the connection dropped before the server started it: read `history`
     again every 2 s, for up to 30 s. Still absent after that → the change
     was not made; the user can ask for it again. Keep the input disabled
     while waiting, so a second request for the same change is not sent
     while the first is still running.
   - The stop followed a **typed message**: if the last `user` entry of
     `turns` is that message, the turn reached a tool call and
     `lastToolResults` are its calls — tell the user what was done. If it is
     not there, nothing ran and the message is not part of the conversation:
     leave the text in the input so it can be sent again. (A call that was
     still running at the moment of the read appears a few seconds later;
     read once more before resending a request that changes something.)
3. Continue in the same session. No call is needed to "close" the stopped
   turn.

## 13. The inspector is not for apps

`GET /v1/sessions/{sessionId}/inspector` (prompt, working memory, tool
results) is an operator view. It is never readable with a session id alone:

| Session | Who may read it |
|---|---|
| Started by a signed-in user (this guide) | That user, with a valid token of the same `sub` — or a console operator who is ADMIN of the tenant's workspace, or an API key that reaches that workspace at ADMIN (a platform-scope key reaches all). |
| Playground session (started without a user identity, or with the console's `actAs`) | A signed-in console operator, or API key, with at least MEMBER on the tenant's workspace. |

Anything else answers `401 auth_required` (no credentials) or
`404 session_not_found`. An app has no reason to call it.

## 14. What a tool's request is built from (for whoever writes the tools)

The profile's tools are HTTP tools: each one declares a URL, headers and a body
as templates, and the platform fills the `{…}` placeholders when the tool runs.
The backend that receives the request can rely on the following.

### Variables

| Placeholder | Value | Set by |
|---|---|---|
| `{name}`, `{name.field}` | An argument of the tool call, as validated against the tool's input schema. | The model (and the user's confirmation, for a confirmable tool). |
| `{principal.subject}` | The verified user (`sub`). | The platform, from the token. |
| `{principal.tenant}` | The tenant id. | The platform. |
| `{principal.claims.<claim>}` | A verified token claim, e.g. `{principal.claims.org}` (`organizationId`, `organization_id`, `org_id`, `orgId` are also read as `org`). A blank one fails the call; nothing is sent. | The platform, from the token. |
| `{session.id}` | The session id. | The platform. |
| `{session.channel}` | Where this turn came in: `app`, `whatsapp`, `sms` or `chat`. See below. | The platform. |
| `{turn.id}` | The id of the current turn. | The platform. |
| `{call.id}` | The id of this tool call. `{session.id}-{turn.id}-{call.id}` is the usual `Idempotency-Key`. | The platform. |
| `{secret.value}` | What the user typed into a secret prompt, for the one call that follows it (§15). Only in a header template; empty everywhere else and on every other call. | The platform, from the user's answer. |

`principal`, `session`, `turn`, `call`, `secret` and `principalToken` are reserved: an
argument with one of those names is dropped, so the model can never supply a
value a template reads as the platform's. The user's token is not a variable;
a tool with `authForwarding: PRINCIPAL_TOKEN` gets it as `Authorization`. A
header written as `Bearer {principalToken}` is therefore not needed: it is
skipped, and the forwarding sets the header (or no header, when the tool does
not forward the token).

### `{session.channel}`

The channel of the **current turn**, always one of five values:

| Value | The turn came from |
|---|---|
| `app` | The token-authenticated sessions API of this guide (`/v1/sessions…`): messages, confirm answers, `actions`. |
| `whatsapp` | A WhatsApp message or button tap. |
| `sms` | An SMS. |
| `chat` | The public chat app (`/v1/chat/e/{handle}/…`), including replies to a message the system sent the user there. |
| `automation` | A headless run (§17): nobody is present. No client can produce it — a session started with the label `automation` is stored as `web` and its turns are `app`. |

It is decided by the endpoint that received the message, tap or reply — never
by the request body, and not by the `channel` label of §2. A one-tap action
button reports the channel it was tapped on, which may differ from the one the
conversation started on. Use it for audit (`"channel":"{session.channel}"` in a
body, or a header such as `X-Agent-Channel: {session.channel}`) or to let the
backend decide per surface. Anything that is not WhatsApp, SMS, the chat app or
a headless run is `app`. An endpoint that words its answer by channel treats
`automation` as a text channel.

### How values are placed

A substituted value is data. Whatever an argument, a claim or a session value
contains, the request has exactly the fields, address and header lines the
template declares.

| Where the placeholder is | What happens to the value |
|---|---|
| JSON body, inside a string — `"reason":"{reason}"` | JSON-string-escaped: quotes, backslashes, line breaks and control characters arrive as that text. Write the quotes in the template. |
| JSON body, unquoted, as a whole value — `"amountDOP":{amountDOP}`, `"enabled":{enabled}`, `"lines":{linesJson}` | Written as one JSON value. A number or boolean argument is written as is; an object or array argument is serialised; an absent one is `null`. A **string** here is JSON text prepared by the caller and must parse as exactly one value: for an argument the schema declares `string`, an object or an array (or the type in its `contentSchema.type`, when you want to pin one: `{"type":"string","contentSchema":{"type":"array"}}`); for one declared `number`, `integer`, `boolean`, `object` or `array`, that type. Malformed JSON, anything after the value, a repeated key or the wrong type fails the call with a tool error the model can read, and nothing is sent. The text is otherwise sent unchanged. |
| JSON body, unquoted, part of a token — `"amount":-{n}` | Only letters, digits and `_ . + -`; anything else fails the call. |
| URL path or query | Percent-encoded, so a value cannot add a path segment, a query parameter or a fragment. A path value of `.` or `..` fails the call. |
| Header | Sent as is. A value with a line break or another control character fails the call; this covers every header, including `Idempotency-Key` and `Authorization`. |
| Body with content type `application/x-www-form-urlencoded` | Percent-encoded. |
| Body with any other non-JSON content type | Substituted as text, unescaped. Do not put untrusted values in such a body. |

A body is treated as JSON when its `contentType` is absent or contains `json`.

Two rules for tool authors follow from the table:

1. Quote every string argument in a JSON body. Leave only numbers, booleans and
   whole JSON arguments unquoted.
2. To pass a list or an object, either declare the argument `array`/`object`
   and write `"lines":{lines}`, or keep the pre-serialised form: declare it
   `string`, name what it holds in the description, and write
   `"lines":{linesJson}`. Both are validated before the request leaves.

## 15. Asking the user for a code (the secret prompt)

Some changes need a code the user holds: a verification code sent by SMS, a
payment code printed on a ticket. The code is never a tool argument. The tool
asks for it, the platform collects it through a masked prompt and hands it to
the tool's backend in a header, for one request.

### For whoever writes the tool

1. Mark the tool in the catalogue: `"policy": { "secretInput": true }`
   (default `false`). The flag is kept by the admin API (`POST` / `GET
   /v1/admin/tools`), by a profile clone and by the console's tool form.
2. Read the code from a header whose template is `{secret.value}`:

   ```json
   "headers": { "X-Agent-Secret": "{secret.value}" }
   ```

   `{secret.value}` is filled only on the call that follows the user's
   answer. On every other call it is empty, and a header whose template uses
   it is **not sent** (no empty header, no log line). It is available to
   header templates only: in a URL or a body it always renders empty. `secret`
   is a reserved name, so an argument called `secret` is dropped.
3. When the code is missing or wrong, answer normally (HTTP 200) with the
   field `_secretPrompt` at the top level of the result:

   ```json
   { "ok": false, "reason": "verification_code_required",
     "_secretPrompt": { "title": "Código de verificación",
                        "message": "Envié un código por SMS al ***1234. Escríbelo para aplicar: Mover la estación 07 al control de pago «Especial 80».",
                        "format": "digits", "length": 6,
                        "expiresInSeconds": 300, "attemptsLeft": 3, "canResend": true } }
   ```

   | Field | Notes |
   |---|---|
   | `title`, `message` | Shown to the user as given. `message` is what WhatsApp, SMS and the chat app send. Defaults: «Código» / «Escribe el código.» |
   | `format` | `digits` (default) or `alphanumeric`. Any other value is passed to the client unchanged; on a text channel it then matches only one unbroken printable word of exactly `length`. |
   | `length` | Number of characters. An app's prompt works without it. **WhatsApp, SMS and the chat app need it**: a prompt without a length is never answered by a typed message there (see below), so a tool meant for those channels always states it. |
   | `expiresInSeconds` | How long the prompt waits. Default 300, at most 3600. |
   | `attemptsLeft` | Optional, shown to the user. |
   | `canResend` | Default `false`. `true` lets the client ask for a new code. The platform allows three such requests along one chain of prompts; the prompt that follows the third is sent with `canResend: false` whatever the tool answered. |

What the platform does with such a result:

- `_secretPrompt` is removed before anything else reads the result: the model,
  the stored conversation and the client's `tool_result` get the result without
  it. It is removed from the result of **any** tool; only a `secretInput` tool
  raises a prompt (from another tool it is dropped, with a warning in the log).
- The call that just ran is held for the session as `pending_secret {toolId,
  args, callId, format, length, expiresAt}` — the arguments exactly as they
  were sent, never a code.
- The turn ends there, like a confirmation: the model is not asked to comment.
  The stream is `tool_call`, `tool_result`, `ui_hint` (`secret_prompt`), `end`.
- When the answer arrives, the **same tool runs once more with the held
  arguments** and `{secret.value}` set. The prompt is used up before that call
  starts, and by one answer only: of two answers that arrive together (a double
  tap, a message delivered twice) one runs the call and the other is told the
  prompt is no longer valid. The call has a new id: the prompt's `callId` followed by `-s`
  (`{call.id}`, and so the usual `Idempotency-Key`, differ from the first
  call's). Authorization runs again; the confirmation is not asked again.
- A result that carries `_secretPrompt` again (wrong code) raises a new prompt,
  with a new `callId`. A result without it ends the exchange: the model is
  then asked to report the result.
- "Send a new code" is the same call with no secret: the backend sees the
  request without the header and decides whether to send another code. After
  three of them along one chain of prompts the platform runs no more: the
  prompt stays, and the user enters a code or cancels.
- The request that carries the code is sent **once**. The tool's `maxRetries`
  does not apply to it: after a timeout or a 5xx the call fails and the user
  is asked again, instead of the code being spent twice. (A 409 that asks for
  a fresh user token is still answered with one new request: the backend
  refused that one before reading it.)

A confirmable tool may be `secretInput` too: the user confirms first, the call
runs, and the prompt follows. A tool run through `actions` (§7) can raise a
prompt the same way.

### For the app

The hint, as streamed and as `pendingSecret` on `latest` / `history`:

```json
{ "kind": "secret_prompt",
  "payload": { "callId": "5f0c…", "title": "Código de verificación",
               "message": "Envié un código por SMS al ***1234. …",
               "format": "digits", "length": 6,
               "expiresInSeconds": 300, "attemptsLeft": 3, "canResend": true } }
```

`length` and `attemptsLeft` are absent when the tool did not give them.
`pendingSecret` is that payload with `expiresInSeconds` counted from the
moment of the read, plus `expiresAt` (ISO-8601 UTC); it is `null` when there
is no prompt or it has lapsed. The prompt is not listed among a stored turn's
`uiHints`.

Answers, on `POST /v1/sessions/{sessionId}/messages`:

```json
{ "text": "", "selection": { "kind": "secret", "callId": "<payload.callId>", "value": "123456" } }
{ "text": "", "selection": { "kind": "secret", "callId": "<payload.callId>", "value": "" } }
{ "text": "", "selection": { "kind": "secret_cancel", "callId": "<payload.callId>" } }
```

| Answer | Stream | Counts as a message |
|---|---|---|
| `secret` with a value, accepted by the backend | `tool_call`, `tool_result`, hints, the model's account, `end` | Yes, like a confirm (§11). |
| `secret` with a value, refused with a new prompt | `tool_call`, `tool_result`, `ui_hint` (`secret_prompt`, new `callId`, new `message`), `end` | No. |
| `secret` with an empty value («reenviar») | The same as a refused one: the call runs without a code and normally answers with a new prompt. | No. |
| `secret` with an empty value after three resends on this chain of prompts (the prompt said `canResend: false`) | One `token` saying a new code was already requested several times, `end`. Nothing runs. The prompt stays. | No. |
| `secret_cancel` | One `token` («Listo, no hice nada.»), `end`. Nothing runs. | No. |
| A `callId` that is not the pending one (answered, replaced, lapsed), or the second of two answers to the same prompt sent at once | One `token` saying the code prompt is no longer valid, `end`. Nothing runs. | No. |
| A value longer than 64 characters or with control characters | One `token` asking to type it again, `end`. The prompt stays. | No. |

- The `tool_call` event of the re-run shows the held arguments. The value is
  in no event.
- The `value` is trimmed. In the app, `text` sent **without** a `secret`
  selection while a prompt is pending is a new request: the prompt is dropped
  and the text is handled normally (it is never read as the code).
- One prompt is pending per session. A new confirmation replaces it; it lapses
  at `expiresAt`; ending the session drops it. So does a turn that is
  answered before the code is looked at: a banca pick, a tap on an action
  button, a reply to a session start flow.
- A prompt survives a platform restart, like a confirmation.
- If the stream is closed while the re-run is in flight, §12 applies with the
  re-run's id (`<callId>-s`) in `lastToolResults`; `pendingSecret` not null
  afterwards means the backend asked again.

### WhatsApp, SMS and the chat app

The turn that raises the prompt sends `message` as its text. From then on the
rule is: **a message that could hold the code is never an ordinary message.**
It is not shown to the model, not stored in the conversation and not kept in
the inbound row. While the prompt is pending, the next typed message is read
like this (a tap on a button is not typed text and is never read as a code):

| The typed message | What happens |
|---|---|
| Exactly one part of it has the prompt's `format` and `length`: the code alone, with spaces or hyphens («481 516», «481-516»), with a full stop, or inside a sentence («mi código es 481516») | It is the code: the held call runs with it. The model is not shown the message. For `alphanumeric`, a code inside a sentence is recognised only when it has a digit in it; alone, any code is. |
| A cancel word («no», «cancelar», …) | The prompt is dropped; «Listo, no hice nada.» |
| It may hold a code but not one that can be read: for `digits`, any other message with a digit in it (wrong length, two numbers); for other formats, any other message | Nothing runs and the **prompt stays**. The answer is a fixed sentence («Sigo esperando el código de 6 dígitos. Envíalo solo, sin más texto, o escribe «cancelar» para dejarlo.»). The user's turn is stored as «(código ingresado)». Does not count as a message. |
| `digits` only: a message with no digit in it | It cannot hold the code. The prompt is dropped and the message is handled as a normal message, by the model. |

So with a numeric code the user can still change the subject in words; to send
a request that has a number in it («mueve la estación 07») they cancel first.
With a code made of letters and digits every message is the code, a cancel or
the fixed sentence, until the prompt is answered, cancelled or lapses.

A prompt **without a `length`** is never answered by a typed message: the
platform cannot tell a code from a word. The same table applies with its first
row removed, and the fixed sentence says the code cannot be read from a
message. The chat app's `secret` selection still works. The log gets a warning
when a tool raises such a prompt.

A code typed **up to five minutes after the prompt lapsed** is still a code:
the answer is the fixed sentence «Ese código ya no está vigente. …», nothing
runs, and the turn is stored as the marker. In those minutes only a message
that reads as the code is held back; other messages, numbers included, are
normal messages again.

The chat app may also send the `secret` / `secret_cancel` selection, as an app
does. There is no "resend" on a text channel: the user asks again.

### Where the value is, and is not

The value exists in the inbound request, in the platform's memory for the
duration of that turn, and in the one outgoing header. It is not written to:
any event of the stream, the stored conversation (the user's turn is stored as
«(código ingresado)», or «(reenviar código)» for an empty value), `latest`,
`history`, the inspector, working memory, the recorded tool call, diagnostics,
logs, or any request to the model.

On WhatsApp and SMS the provider delivers the message before the platform can
know it is a code, so it sits in the inbound queue row until the worker picks
it up. The first thing the worker does with a message — after the carrier's
STOP / START keywords, before anything that can return or fail — is to look
for a code prompt in that customer's open conversation. If the message is the
code or may hold it (the table above, the five minutes after a lapse
included), the text in that row — the provider payload's `Body`, the stored
display body and any voice-note transcript — is replaced with «(código
ingresado)» and saved at once, on its own. That holds whichever way the
message then goes: a normal turn, a conversation a person has taken over (the
desk reads the marker), a customer who opted out, no route, a refused
identity, an error. A row that fails outside the worker (the queue was full)
is checked again when it is marked failed. If the conversation the prompt
belonged to has idled out and the message opens a new one, that one receives
the marker, not the text. The code has still crossed the messaging provider,
and it is in the row for the moments between the webhook and the worker.

## 16. Several changes on one card (plans)

When the user asks for several changes at once («para la venta en la 12 y
avísale a la 07»), the model calls each tool in the same answer. The platform
puts the ones that may share a card on **one plan**: the steps in order, one
agreement, steps can be left out, each step runs as its own tool call with the
user's token, and the run stops at the first step that fails.

### For whoever writes the catalogue

Mark a tool with `"policy": { "confirmable": true, "plannable": true,
"confirmPrompt": "¿…?" }` (default `plannable: false`). A step's line on the
card is the tool's `confirmPrompt` filled with the call's own arguments, with
one leading «¿» and one trailing «?» removed («¿Detener la venta en la
estación {stationCode}?» → «Detener la venta en la estación 12»); a tool
without a prompt shows its id. Do not mark a `secretInput` tool: a code prompt
cannot be raised in the middle of a plan (a step that answers `_secretPrompt`
fails and the prompt is dropped).

What the platform does at the end of one model answer, with `K` = the
answer's confirmable calls that passed authorization, in the model's order,
and `P` = those of `K` whose tool is `plannable`:

| Case | Result |
|---|---|
| `K` empty | Nothing changes. |
| one call in `K` | The single `confirm` card (§5), plannable or not. |
| two or more in `P` | One `plan` card with the calls of `P`, at most 6. Every other call of `K` (not plannable, or past the sixth) is **deferred** (§5). |
| two or more in `K`, fewer than two in `P` | The first call of `K` gets the `confirm` card; the others are deferred. |

Calls that need no confirmation run at once, as always. If the same answer
already raised a card another way (a code prompt, a tool-requested
confirmation), every call of `K` is deferred. Profiles without a `plannable`
tool never see a plan.

A step is an ordinary call of the tool: same request, same headers, same
authorization as when it is confirmed alone. The call id of a step is the id
the model gave that call, so anything derived from `{call.id}` (an
`Idempotency-Key`) differs per step, and is the same if the step were
replayed. A failed step is one whose call did not return status `OK`, or whose
result is an object with top-level `"ok": false`. There is no rollback: what
ran, ran.

### For the app

The turn that raises a plan streams the model's own words (if any), then:

```json
{ "kind": "plan",
  "payload": { "callId": "7d0e6c0a-…", "title": "Voy a hacer esto, en orden:",
               "steps": [ { "id": "1", "toolId": "station_sales_toggle", "summary": "Detener la venta en la estación 12" },
                          { "id": "2", "toolId": "station_notify", "summary": "Enviar el aviso «Cierre» a la estación 07" } ],
               "confirmLabel": "Hacer todo", "cancelLabel": "Cancelar", "canDrop": true,
               "text": "Voy a hacer esto, en orden:\n1. Detener la venta en la estación 12\n2. Enviar el aviso «Cierre» a la estación 07\n¿Lo hago?" } }
```

- `callId` is the plan's id (not a tool call's). `steps[].id` is `"1"`, `"2"`,
  … in the order they will run. There are 2 to 6 steps. No arguments are in
  the payload: they are held on the server.
- The same turn also streams `payload.text` as a `token`, for channels without
  cards (preceded by a blank line when the model wrote something before it).
  When you draw the card, do not show that text a second time: remove
  `payload.text` from the end of the bubble, as with `summary` in §5.
- The turn has no `tool_call` for the steps. It may have `tool_call` /
  `tool_result` pairs before the hint: calls that needed no confirmation, and
  deferred ones (§5).
- `pendingPlan` on `latest` / `history` is exactly this `payload` while the
  plan waits for an answer, and `null` otherwise — also while it runs and
  after it finished.

Answers, on `POST /v1/sessions/{sessionId}/messages`:

```json
{ "text": "", "selection": { "kind": "confirm", "callId": "<payload.callId>" } }
{ "text": "", "selection": { "kind": "confirm_items", "callId": "<payload.callId>", "itemIds": ["1","3"] } }
{ "text": "", "selection": { "kind": "cancel", "callId": "<payload.callId>" } }
```

| Answer | What happens | Counts as a message |
|---|---|---|
| `confirm` | Every step runs, in order. | Yes, once (§11). |
| `confirm_items` | Only the steps in `itemIds` run, **in the plan's order** whatever the order of the list. An id the card did not offer is ignored. An empty list, or one with no id of the card, is a cancel. | Yes, once. |
| `confirm` **with** `itemIds` | Read as `confirm_items`: those steps, never all of them. Send `confirm` without `itemIds` for «Hacer todo». | Yes, once. |
| `cancel` | Nothing runs. One `token` («Listo, no hice nada.»), `end`. The model is not called. | No. |
| `cancel` at the same moment as a `confirm` | One of the two wins. If the cancel wins: as `cancel`, and the confirm gets the «no longer valid» answer. If the confirm wins, the plan runs and the cancel is answered with one `token`, «Ya estaba en marcha, no alcancé a cancelarlo.», `end`. | No. |
| A `callId` that is not the pending plan (answered, replaced, cancelled), **no `callId` at all**, or the second of two answers sent at once | One `token` saying the confirmation is no longer valid, `end`. Nothing runs; a pending plan stays pending. | No. |
| Typed text | See «Typed answers» below. | |

#### Typed answers

A plan is a numbered list, so a typed reply is not read like the reply to a
single confirmation (§5), where «1» or «sí, …» is a yes. On every channel:

| The typed text is… | Examples | What happens | Counts |
|---|---|---|---|
| nothing but a yes, the card's confirm label, or «todo» | «sí», «Sí.», «dale», «sí, dale», «ok perfecto», «Hacer todo», «TODO», «todos», 👍 | Every step runs. | Like `confirm`. |
| nothing but step numbers (with «solo», «la», «paso», «y», an opening yes) | «solo la 1», «sí, solo la 1», «1 y 3», «pasos 2 y 3» | Those steps run, in the plan's order — as `confirm_items`. | Like `confirm_items`. |
| everything except step numbers | «todo menos la 2», «sí, menos la 1 y la 2» | The other steps run. | Like `confirm_items`. |
| nothing but a no, the card's cancel label, or «ninguno» / «nada» | «no», «CANCELAR», «mejor no», «no, déjalo», 👎 | Nothing runs («Listo, no hice nada.»). | No. |
| about the plan, but not one of the above | «1», «2» (a number alone), «sí, solo el aviso», «dale, menos el aviso», «ok pero la 15 no», «solo la 9», «1-3» | **Nothing runs and the plan stays pending.** The turn is `ui_hint` `plan` (the same payload as before, same `callId`), one `token`, `end`. The token is, in an app, «No quiero hacer de más. Toca «Hacer todo», o dime cuáles pasos quieres (por ejemplo «solo la 1»).»; elsewhere «No quiero hacer de más. Responde TODO para hacerlos todos, CANCELAR para no hacer nada, o dime cuáles pasos quieres (por ejemplo «solo la 1»).» The model is not called. | No. |
| the same, a second time for the same plan | | The plan is dropped and the text is handled as a new request (the model usually proposes again). After the question a number alone («2») is that step. | Yes. |
| anything else | «mejor dime las ventas» | The plan is dropped and the text is handled as a new request. | Yes. |

No reading runs a step the reply did not name: a yes followed by any other
word, a step named by a word instead of its number, a number the plan does
not have and a range («1-3») all ask instead of running. Because the frame is
sent again with the question, an app that removed the card when the user
typed shows it again; `pendingPlan` never stopped being that frame.

The stream of a plan that runs, for a plan of three where the user left
step 2 ticked and it failed:

```
event:ui_hint      {"kind":"plan_step","payload":{"callId":"7d0e…","stepId":"1","state":"running"}}
event:tool_call    {"callId":"toolu_01A","tool":{"value":"station_sales_toggle"},"args":{…}}
event:tool_result  {"callId":"toolu_01A","status":"OK","value":{…},…}
event:ui_hint      {"kind":"plan_step","payload":{"callId":"7d0e…","stepId":"1","state":"done","message":"Venta detenida en 12 — Colmado Lina"}}
event:ui_hint      {"kind":"plan_step","payload":{"callId":"7d0e…","stepId":"2","state":"running"}}
event:tool_call    {"callId":"toolu_01B",…}
event:tool_result  {"callId":"toolu_01B","status":"OK","value":{"ok":false,"userSafeMessage":"No pude enviarlo ahora."},…}
event:ui_hint      {"kind":"plan_step","payload":{"callId":"7d0e…","stepId":"2","state":"failed","message":"No pude enviarlo ahora."}}
event:ui_hint      {"kind":"plan_step","payload":{"callId":"7d0e…","stepId":"3","state":"skipped"}}
event:ui_hint      {"kind":"plan_result","payload":{"callId":"7d0e…","steps":[
                     {"id":"1","toolId":"station_sales_toggle","summary":"Detener la venta en la estación 12","state":"done","message":"Venta detenida en 12 — Colmado Lina"},
                     {"id":"2","toolId":"station_notify","summary":"Enviar el aviso «Cuadre» a la estación 07","state":"failed","message":"No pude enviarlo ahora."},
                     {"id":"3","toolId":"station_sales_toggle","summary":"Detener la venta en la estación 15","state":"skipped"}]}}
event:token        {"text":"Detuve la 12. El aviso a la 07 no salió…"}
event:end          {…}
```

`plan_step.state`:

| `state` | Meaning | Followed by |
|---|---|---|
| `running` | The step's call is starting. | its `tool_call`, `tool_result`, the tool's own hints, then `done` or `failed` |
| `done` | The call returned `OK` and its result does not say `"ok": false`. | the next step |
| `failed` | The call was denied or failed, answered `"ok": false`, asked for a code, or is no longer available. | `skipped` for every step after it |
| `skipped` | The step did not run: the user left it out, or a step before it failed. No `running` precedes it and it has no `tool_call`. | — |

- `message` is the result's `userSafeMessage`, else its `summaryLine`, else
  (for a denied call) the refusal's reason; absent when the result has none.
  It is never present on `running` or `skipped`.
- `plan_step` events arrive in step order; every step of the plan gets a
  final state (`done`, `failed` or `skipped`) before `plan_result`.
- `plan_result.steps` lists **every** step of the card, in order, with its
  final state — the same information as the `plan_step` events, for a client
  that prefers to draw once.
- After `plan_result` the model is asked once to say what was done and what
  was not; its text and `end` follow. The tools' own hints (`data_card`, an
  `open_screen`, …) arrive between a step's `tool_result` and its final
  `plan_step`, as they would for a single call.
- Disable the card's buttons once one is tapped. Two answers at once run the
  plan once (table above).
- If the stream is closed after the answer was sent, the plan still runs to
  its last step and every result is stored (§12, third row): read `history`
  — `pendingPlan` is `null`, and `lastToolResults` has one entry per step
  that ran, under the step's own call id (`toolu_…`, not the plan's
  `callId`). Steps that did not run have no entry. The model's account is
  not written in that case.
- While a plan is running, another message on the same session is answered
  with one `token` («Todavía estoy haciendo esos cambios. Dame un momento.»)
  and `end`; nothing else happens and it does not count. `pendingPlan` is
  already `null` then.
- If the platform restarted in the middle of a run, the next message of the
  session is answered — whatever it says — with one `token` built from what
  was stored («El plan se interrumpió. Hecho: 1. Sin confirmar: 2. Sin hacer:
  3.»), then `end`. The groups, each present only when it has steps: «Hecho»,
  «Falló», «Sin confirmar» (the step's call had started; whether it was
  applied is not known) and «Sin hacer». Nothing more runs, the plan is gone,
  and the message is not otherwise handled: the user sends it again. Does not
  count.

### WhatsApp, SMS and the chat app

- The turn's text is the model's words followed by `text`:

  ```
  Voy a hacer esto, en orden:
  1. Detener la venta en la estación 12
  2. Enviar el aviso «Cierre» a la estación 07
  ¿Lo hago?
  ```

  (English profiles: «I will do this, in order:» / «Shall I?», buttons «Do
  all» / «Cancel».)
- WhatsApp shows two buttons, «Hacer todo» and «Cancelar», whose payloads are
  `confirm:<callId>` and `cancel:<callId>`, exactly like a confirmation.
- When the buttons cannot be sent (the text is longer than 1,024 characters,
  or the button message failed) the plan goes out as text followed by a line
  of **words**, never «1. Hacer todo / 2. Cancelar» — the steps are the only
  numbered list:

  ```
  Responde TODO para hacerlos todos, CANCELAR para no hacer nada, o dime cuáles pasos quieres (por ejemplo «solo la 1»).
  ```

  (English: «Reply ALL to do them all, CANCEL to do nothing, or tell me which
  steps you want (for example "only 1").») SMS always appends that line. A
  single confirmation keeps its numbered options and «Responde SÍ o NO.».
- Those words, and the buttons' labels, are what a typed reply is read by
  («Typed answers» above). A text channel can leave steps out by their
  numbers («solo la 1», «todo menos la 2»); to leave a step out by what it
  does («menos el aviso») the user is asked once how to choose, and after
  that the model proposes again.
- `plan_step` and `plan_result` are not sent: the model's account is the
  report.

### Where a plan is kept

In the session's working memory (`pending_plan`: the frame and each step's
tool, arguments and call id) until it is answered, replaced, or its run ends
— then it is removed. Two small keys sit next to it: `plan_asked` (the id of
the plan whose user was already told how to answer by typing) and
`plan_fate` (what the answer that took the plan did with it — `RUN`,
`CANCELLED` or `DROPPED` — which is how an answer that arrived at the same
moment tells the truth). An answer takes the plan with a single-flight claim
(`plan:<callId>`): a confirm, a cancel and a typed new request all take the
same one, and a confirm that wins it reads the plan again before running, so
a plan replaced in between does not run. It idles out with the session (30 minutes). It is shown
to the model in the per-turn part of the prompt while it waits, never in the
cached part.

## 17. Headless runs

A tenant's backend has a profile's agent answer **one stored question with
nobody present** — «every Monday, tell me which stations sold less and why» —
and collects the answer as text. The run acts as one person, with a
short-lived read-only token the backend mints for that run; it can only read,
it never waits for anyone, and it costs one message. Covered by
`HeadlessRunApiTest`, `HeadlessRunAuthenticatorTest`, `HeadlessToolFilterTest`,
`HeadlessCatalogFlagTest`, `HeadlessNeverPausesTest`, `HeadlessRunMeteringTest`,
`HeadlessRunLifecycleTest`, `HeadlessSessionHiddenTest`,
`HeadlessChannelVariableTest`, `HeadlessRunsMigrationTest` and `HeadlessRunTokenLifeTest`.

It is two calls: submit (the answer is `202`, at once) and poll. Nothing is
pushed back.

### Who may call

Both endpoints take the same three headers, and nothing else admits a request:

| Header | Value |
|---|---|
| `Authorization` | `Bearer <service JWT>` — HS256, signed with a key the tenant trusts (`tenant_jwt_secrets`, the business's sender credential), with `iss` = that trusted issuer, `exp`, `aud` containing `yaya-agentic`, and `scope` containing `headless:run`. The scheme of `POST /v1/structured-completions`. |
| `X-Nonce` | A value never sent before by this tenant (a UUID). |
| `X-Timestamp` | Now, in epoch milliseconds; at most 5 minutes off the platform's clock. |

The tenant is the one whose key verifies the token. It is never read from the
path or the body, and there is no anonymous fallback: an API key, a console
cookie, a platform service token or a user's own token is `401`.

### Submit

`POST /v1/headless/runs`, plus `X-Idem-Key` (required, at most 120
characters) and `Content-Type: application/json`.

```json
{ "profileId": "owner_ops_v1",
  "principalToken": "<the run token>",
  "principalNotAfter": "2026-11-16T12:10:00Z",
  "question": "¿Qué estaciones vendieron menos esta semana que la anterior y por qué?",
  "locale": "es",
  "timeZone": "America/Santo_Domingo",
  "context": { "automationId": "…", "automationName": "…", "executionId": "…",
               "scheduledFor": "2026-11-16T12:00:00Z", "test": false },
  "limits": { "maxAnswerChars": 1800, "deadlineSeconds": 240 } }
```

| Field | Rule |
|---|---|
| `profileId` | Required. The latest version installed for the tenant runs, and is the one recorded. |
| `principalToken` | Required. A token of the person the run answers for, **as the tenant's trusted issuer signs every staff token** (same issuer, key and pinned claims, §10) and carrying `via: "automation"` and `viaReadOnly: true`. It is forwarded as `Authorization: Bearer …` on each tool call (`authForwarding: PRINCIPAL_TOKEN`) and never renewed, so it must outlive the run: `deadlineSeconds` plus the time the run may wait for a worker — see `principalNotAfter`. |
| `principalNotAfter` | Optional. When `principalToken` stops being accepted, as an ISO-8601 UTC instant; anything else is `422`. The platform also reads the token's own `exp` and uses the earlier of the two (with neither, nothing is checked). A run is only **started** while `now + deadlineSeconds` is not past it: at submit a token that is already too short is `409 token_expiring`; a run whose wait for a worker used the margin up is closed `failed / token_expiring` when the worker takes it, before a conversation, a model call or a tool call exists, and its message is given back. Nothing is extended or renewed. |
| `question` | Required, 1–2,000 characters after trimming. |
| `locale` | Optional, at most 16 characters. Recorded; the instruction of «What the model is told» is in Spanish whatever it says. |
| `timeZone` | Optional IANA id, default `UTC`. The date the model is told is today's in this zone. |
| `context` | Optional object, at most 4,000 characters as JSON. Kept with the run for the operator; never shown to the model, never returned. |
| `limits.maxAnswerChars` | Optional, 200–4,000, default 1,800. |
| `limits.deadlineSeconds` | Optional, 30–300, default 240. |

Answer `202`:

```json
{ "runId": "hr_01JCN8Z3V5T2K9Q4W7X6Y0ABCD", "status": "queued" }
```

`runId` is `hr_` and a 26-character ULID. Sending the same `X-Idem-Key` again
within 24 hours answers **that same body** — whatever the request now holds
and whatever state the run has reached — and starts nothing; nothing is
checked or counted again. After 24 hours the key names a new run.

### Poll

`GET /v1/headless/runs/{runId}` — the three headers (a fresh nonce each time).

```json
{ "runId": "hr_01JCN8Z3V5T2K9Q4W7X6Y0ABCD",
  "status": "completed",
  "answer": "Esta semana bajaron la 12 (−18 %) y la 07 (−9 %)…",
  "usage": { "inputTokens": 18240, "outputTokens": 412, "cacheReadTokens": 15900,
             "rounds": 4, "toolCalls": 5 },
  "tools": ["assistant_me", "report_sales_summary_station", "insight_station"],
  "startedAt": "2026-11-16T12:00:02.118Z",
  "finishedAt": "2026-11-16T12:00:31.502Z" }
```

| Field | When | Meaning |
|---|---|---|
| `status` | always | `queued` → `running` → `completed` or `failed`. The last two are final. |
| `answer` | `completed`, for 24 hours | Plain text, trimmed, at most `maxAnswerChars` characters; a longer one is cut and ends in «…». Absent once erased. |
| `failure` | `failed` | `{ "code": "…", "message": "…" }`. The message is for a log, not for a person. |
| `usage` | always | Zeros until the run ends. `rounds` = model calls; `toolCalls` = calls the model made that got a result, refused ones included; tokens as in §11. |
| `tools` | always | The tools whose call succeeded, each once, in the order first used. `[]` until the run ends. |
| `startedAt` | once running | When a worker took it. |
| `finishedAt` | once final | — |

`failure.code`:

| Code | Meaning | A new submit can help |
|---|---|---|
| `needs_interaction` | Something came up that only a person could answer: a tool result asked for a confirmation or a code, a tool needed a verification step or an argument the model left out. Nothing was asked and nothing is left pending. | no |
| `no_answer` | The model wrote nothing, or only questions. | maybe |
| `tool_rounds_exhausted` | The profile's tool-round ceiling was reached before an answer. | maybe |
| `deadline_exceeded` | Not finished `deadlineSeconds` after a worker took it. The run is stopped. | yes |
| `principal_rejected` | The tenant's backend answered `401` to a tool call: the run token expired or was refused. The run stops at that call. | with a new token |
| `token_expiring` | The run waited for a worker until its token could no longer cover `deadlineSeconds` (`principalNotAfter`). It was never started: no model call, no tool call, `usage` all zeros, no message counted. | yes, with a new token and a new key |
| `quota_exceeded` | The allowance ran out. In practice this is answered at submit (`409`). | next month |
| `llm_error` | The model call failed. | yes |
| `interrupted` | The platform restarted before the run finished, or the run's outcome was never written and housekeeping closed it (still `running` 10 minutes after it started, or still `queued` 60 minutes after it was created — past any deadline a run can have). The token went with the process, so the run cannot be picked up: submit again, with a new key. | yes |

### Errors

Always JSON, `Cache-Control: no-store`: `{ "code": "…", "message": "…" }` (not
the `error` / `message` pair of §8 — this is a service API).

| Status | `code` | When |
|---|---|---|
| 401 | `unauthorized` | No service token, or one that does not verify, has no `exp`, or is not for audience `yaya-agentic`. |
| 403 | `forbidden` | A valid service token without scope `headless:run`. |
| 409 | `replay` | `X-Nonce` missing or already used, `X-Timestamp` missing, not a number, or more than 5 minutes off. |
| 422 | `invalid_request` | The body is not a JSON object, a field breaks a rule of the table above, or `X-Idem-Key` is missing or too long. |
| 404 | `profile_not_found` | The tenant has no such profile. |
| 403 | `principal_rejected` | `principalToken` does not pass the tenant's ordinary verification (issuer, signature, expiry, pinned claims), or lacks `via = "automation"` or `viaReadOnly = true`. An ordinary staff token is refused: a run never holds a credential that could write. |
| 403 | `profile_not_permitted` | The profile's `metadata.principalKinds` does not admit the principal (§10). |
| 422 | `no_headless_tools` | The profile has no tool a headless run may call (none flagged `policy.headless`). Nothing to answer from, so the run is not accepted. |
| 409 | `token_expiring` | The token ends (`principalNotAfter`, or its `exp`) less than `deadlineSeconds` from now. Submit again with a fresh token. |
| 503 | `llm_unavailable` | The deployment has no model configured. |
| 429 | `rate_limited` | More than 20 submits of the tenant passed the checks above in the current clock hour. `Retry-After` in seconds. |
| 429 | `busy` | The tenant already has 2 runs queued or running, or the platform's queue (20) is full. `Retry-After: 30`. |
| 409 | `quota_exceeded` | The month's allowance is used up: `{ "code", "message", "month", "monthlyMessages", "messages" }` (§8). Nothing is queued. |
| 404 | `run_not_found` | `GET`: no such run, or it is another tenant's, or it is older than 30 days. |

They are checked in that order. A repeated `X-Idem-Key` is answered after the
`422` checks and before every later one. A refused submit takes no message and
stores nothing.

### What a run may do

- **Tools.** The model is offered the profile's tools that carry
  `policy.headless: true`, and no other. A call to any other tool — one of the
  profile that is not flagged, one of another profile, one that does not
  exist — never leaves the platform: its result is
  `{ "ok": false, "reason": "tool_not_available" }` and the run goes on.
- **The flag.** `policy.headless` is for reads meant for unattended use. The
  catalogue API (`POST /v1/admin/tools`) refuses it with
  `400 { "error": "headless_not_allowed", … }` on a tool whose handler is not
  an HTTP `GET`, or whose policy is `confirmable`, `secretInput`,
  `clientInvocable`, `plannable` or `preconfirmableByAction`. A stored row
  that carries it against the rule is loaded without it. The engine checks
  the policy once more before offering or dispatching. Being a `GET` is not
  enough: a read that is not meant to run unattended is simply not flagged.
- **Nothing waits.** No confirmation card, plan, code prompt, verification
  step, picker or question is ever framed: where a conversation would pause,
  the run ends `failed / needs_interaction`, and no `pending_confirm`,
  `pending_plan`, `pending_secret`, `pending_elicit` or
  `pending_entity_conflict` is written. A `_uiHint` in a tool result is
  dropped.
- **No start flow.** The profile's start flow, greeting and menu are not run:
  the first thing that happens is the question.
- **No memory of other conversations.** A run begins empty and its working
  memory is cleared when it ends.
- **The token.** Only `principalToken` is forwarded. It is not re-minted on a
  `409 token_refresh_required`, and a `401` ends the run.
- **`{session.channel}`** is `automation` (§14).
- **Limits.** The profile's tool-round ceiling; `deadlineSeconds` for the whole
  run; `maxAnswerChars` for the answer.
- **The model** is the profile's.

The tenant's backend must hold the other half of the read-only rule: it
refuses every request that is not a read when the token says `viaReadOnly`.

### What the model is told

The tools and the cached part of the prompt are the profile's, unchanged. One
paragraph is added to the per-turn part (never the cached one):

> Esta es una ejecución automática: nadie está presente. Responde la pregunta
> con los datos de las herramientas, en español, en texto simple (sin tablas
> ni markdown), en {maxAnswerChars} caracteres como máximo. No preguntes nada,
> no propongas cambios y no ofrezcas hacer nada. Si un dato no está
> disponible, dilo en una línea. Hoy es {yyyy-MM-dd} ({timeZone}). Empieza por
> assistant_me.

The list of tools differs from a conversation's, so a run has a prompt-cache
prefix of its own, shared by the runs of the same profile.

The answer is the text the model wrote after its last tool call. What it wrote
before a call is working text and is not part of it.

### What it costs

One message of the tenant's month (§11) per run, whatever its rounds — taken
when the run is accepted, so an allowance that is used up answers `409` and
nothing runs. The message is given back when the run never reached the model
(refused for a full queue, closed before a worker took it, or its only model
call failed with nothing produced). A run that stops later —
`needs_interaction`, `deadline_exceeded`, `no_answer` — stays counted. Tokens
are recorded as for a turn. The per-user limits of §8 are not consulted: a run
has the tenant-wide ceilings of «Errors».

A run that is `interrupted` by a restart keeps its message: the platform
cannot tell whether the model had been called.

### Where a run is kept, and where its token is not

- `headless_runs` (V91): the run, its question, `context`, the subject of the
  person, its outcome. The **answer is erased 24 hours** after the run was
  created and the **row 30 days** after (every 15 minutes).
- The conversation is recorded like any other (the question, each tool call
  and result, the answer) under channel `automation`, closed when the run
  ends, and readable in the operator console. It is **not** returned by
  `GET /v1/sessions/latest`, and `history`, `messages`, `actions`, `end` and
  `inspector` answer `404 session_not_found` for it — to its own principal
  too. It is never reopened.
- `principalToken` is held by the queued task and the one turn's request
  context. It is in no table, no log line, no working memory and not in the
  inspector — which is why a restart ends a run as `interrupted` instead of
  resuming it.

### Settings

`yaya.agentic.headless.*`: `workers` (2), `queue` (20), `max-per-tenant` (2),
`per-hour` (20), `answer-ttl` (`PT24H`), `row-ttl` (`P30D`). Live sessions are
in the process's memory, so runs execute on the instance that accepted them
(single instance).

## 18. Continuing a conversation from another channel (hand-off)

Sessions stay **one per channel**: what the user says on WhatsApp is in a
`whatsapp` session, what they say in the panel is in an `admin-app` one, and
neither is ever reopened from the other side (`latest` with `channel`, §6).
When the user wants to go on here with what they were doing there, the panel
asks for a **hand-off by copy**:

`POST /v1/sessions/{sessionId}/handoff` `{ "fromSessionId": "<the elsewhere session>" }`
→ `200`

```json
{
  "fromSessionId": "9c41…", "fromChannel": "whatsapp",
  "carried": [
    { "role": "user", "text": "Qué pasó con el ticket 4411", "createdAt": "…", "uiHints": [], "channel": "whatsapp" },
    { "role": "assistant", "text": "El ticket 4411 está confirmado.", "createdAt": "…", "uiHints": [], "channel": "whatsapp" }
  ]
}
```

What it does:

- Both sessions must belong to the caller (same tenant, same subject, same
  profile); the target must be live (the usual `409 session_ended` /
  `session_expired` otherwise) and have had **no hand-off yet**; the source
  must have had activity within 7 days — an `EXPIRED` source is fine, its
  summary is stored.
- The platform builds a block from the source: its rolling summary, if any,
  plus the last 12 user/assistant turns rendered one per line as
  `[hh:mm canal] Usuario|Asistente: texto` (hours in UTC; rich frames and
  attachments as one-line notes), cut to about 2,000 tokens with the newest
  kept. It is appended to the target as **one `SYSTEM` turn** with
  `metadata.origin = "CHANNEL_HANDOFF"` and
  `metadata.handoff = { fromSessionId, fromChannel, throughTurnIdx }`, framed
  to the model as data, followed by a fixed instruction: the person continues
  here what they were doing on the other channel; if a confirmation, plan or
  code was pending there, say so in one line and propose it again here if
  they want — nothing from the other channel counts as confirmed.
- The source session is not touched: it keeps its own thread, its own policy
  and its own pending frame (which the target never answers).
- `carried` are the copied turns as `HistoryTurn`s with `channel`, for the
  panel to draw as a collapsed block («Desde WhatsApp · 9 mensajes») above
  the new conversation. They are **not** returned by `history` / `latest`
  (those keep user and assistant turns only), so a panel that reopens the
  target later shows the conversation without the copied block.

| Status | `code` | When |
|---|---|---|
| 400 | `from_session_required` | No `fromSessionId`. |
| 403 | `handoff_not_allowed` | The source is another user's, on another profile, the target itself, or a headless run. |
| 404 | `session_not_found` | Unknown source (or one in another tenant); unknown target, or not this user's. |
| 409 | `handoff_exists` | The target already received a hand-off. |
| 409 | `session_ended` / `session_expired` | The target is not live. |
| 410 | `source_too_old` | The source had no activity in the last 7 days. |

The other direction works the same way, bounded: when WhatsApp opens a new
conversation and the same person had an `admin-app` session with a turn in
the last 2 hours, the greeting carries two buttons, «Seguir con la app»
(payload `handoff:<sessionId>`) and «Empezar de cero» (`fresh`), emitted as
`quick_replies { options: [...], source: "start" }`. The tap runs this same
hand-off into the WhatsApp session and is answered with a fixed line, without
the model. Closing a WhatsApp conversation (idle timeout, `#new` / `restart`)
now ends its engine session (`ended_at` set), so a dead thread never counts
as open anywhere.

### What a panel can rely on

`GET /v1/sessions/features?tenant=<tenant>` (bearer, any signed-in user) →

```json
{ "handoff": true, "attachments": true, "voice": false, "listPicker": true, "replySuggestions": true }
```

`voice` is true only when the platform has a transcriber configured. A panel
hides the paperclip and the microphone when the flag is false or the endpoint
answers 404 (an older platform).

Covered by `SessionHandoffApiTest`, `HandoffBlockTest`, `HandoffTapTest` and
`WhatsAppChannelHandoffE2ETest`.
