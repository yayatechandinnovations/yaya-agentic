# yaya-agentic runtime: network-chat banca context + brokered play

Status: DESIGN — 2026-08-15
Repo: **yaya-agentic (external)** — this doc specifies the runtime-side changes and
is meant to travel to that repo. The Smart Lottery-side counterpart (product/UX,
hub broker, org gating, client) is `docs/corillo-network-chat-play-ux-design.md`
in the smart-lottery monorepo; §3 here and §5 there define the same wire contract
and must change together.

## 1. Problem, from the runtime's point of view

The Corillo network entry point (profile `player_network_v1`) serves ONE
conversation per player that must feel org-less, yet eventually execute plays that
are strictly org-bound. Today the runtime's model is: entry point → `sender_key`
→ one pinned org from `resolve-identity`, and every capability acts in that org.
That model stays intact for banca-scoped entry points, but the network entry point
needs a second notion the runtime does not have yet: a **session-scoped banca
context** selected mid-conversation, whose *authority* comes from an
install-vended customer token obtained via the hub — not from the chat principal.

Two invariants the runtime must uphold:

- The chat principal minted for the network sender **never vends** (its sender
  sale-config stays disabled). All play authority is the brokered customer token.
- The runtime's prompt/tool selection is UX, **never the gate**: every play-path
  call is re-authorized server-side (hub broker checks + install's own play
  validation). A prompt-injected "play at banca X" without the confirm frame and
  green server-side chain must be structurally impossible, not just discouraged.

## 2. Session model

Add to the chat session state (per sessionId, alongside the existing verified
phone/device identity):

```
bancaContext: {
  installCode, orgId, displayName,
  linked: ACTIVE|PENDING|NONE,
  playCredential: { baseUrl, customerId, customerToken, expiresAt } | null,
  committedAt
} | null
```

Rules:

- `null` until an action-class tool needs it; read-class tools (results, payout,
  schedule questions about a named banca) resolve bancas ad-hoc from tool args and
  MUST NOT set or require the context.
- Set only by an explicit selection event (§3.2) or an unambiguous NL statement
  the engine resolves to exactly one banca; on ambiguity → emit `banca_picker`.
- Sticky for the session; replaced when the player names another banca (re-runs
  link/eligibility checks); cleared on session end. Never persisted across
  sessions in S3 (revisit later; a stale default banca that silently receives a
  play is worse than one extra tap).
- `playCredential` is lazy: fetched from the hub broker only when the first
  play-path tool actually runs, cached per (session, installCode), refreshed on
  expiry/401 (tokens are ~15 min by design). Never logged, never surfaced in
  frames or tool_result values.

## 3. Wire-contract additions (shared contract — keep in lockstep with app)

### 3.1 New `ui_hint` kind `banca_picker`

Emitted whenever a play-path tool is invoked without a resolvable banca context:

```json
{ "kind": "banca_picker",
  "payload": {
    "prompt": "¿En cuál banca quieres jugar?",
    "allowQuickPick": true,
    "groups": [
      { "id": "linked", "title": "Tus bancas",
        "items": [ { "code": "LAMINA01", "name": "Banca La Mina", "city": "Santiago",
                     "open": true, "juegaRemoto": true, "linked": "ACTIVE" } ] },
      { "id": "online", "title": "Disponibles en línea", "items": [ … ] }
    ] } }
```

Contract rules: clients ignore unknown ui_hint kinds, so every `banca_picker` MUST
be accompanied by an equivalent plain-text ask in the token stream (old clients
degrade to NL). Item `code` is the install code — the only value the client echoes
back; everything else is display-only.

### 3.2 Structured selection on the message POST (backward compatible)

`POST /v1/chat/sessions/{id}/messages` accepts an optional field next to `text`:

```json
{ "text": "Banca La Mina", "selection": { "kind": "banca", "code": "LAMINA01" } }
```

- `text` stays required (legible transcript; old runtimes ignore `selection`).
- On `selection.kind == "banca"`: bypass NL resolution, validate the code against
  the last emitted picker (reject codes never offered — the client cannot inject
  an arbitrary install), then run the normal link/eligibility checks.
- Reserved for the same rev, same semantics: `{ "kind": "confirm", "callId": … }`
  as a structured alternative to the current plain-"Sí" approval. Optional to
  implement now; the field shape is fixed here so no second protocol rev is
  needed.

## 4. New/changed tools for `player_network_v1`

All Smart Lottery endpoints below are hub action-plane calls authenticated with
the runtime's hub credential (`X-Agent-Auth`); none use the chat principal.

| Tool | Backs onto | Notes |
|---|---|---|
| `player_linked_bancas` | `GET /internal/agent/players/{phone}/bancas` | phone = session's verified phone, never a model-supplied arg. Returns vinculos + directory card fields; feeds picker group "linked". |
| `directory_online_bancas` | `GET /internal/agent/directory/online-bancas?openNow=…` | feeds picker group "online" and quick-pick ranking. |
| `banca_context_set` | (runtime-internal) | resolves NL/selection → sets §2 state; emits `banca_picker` when ambiguous; on `linked != ACTIVE` returns a link-offer outcome the engine must turn into a confirm frame. |
| `banca_link_confirm` | broker op `link` on `POST /internal/agent/play-session` | only after an explicit `confirm` frame. Mirrors portal `confirm-link` semantics. |
| `play_quote` | install player play surface (draft/validate) using the brokered credential | acquires `playCredential` lazily via `POST /internal/agent/play-session`; returns quote {lines, amounts, draw, cutoff, balance-after}. |
| `play_confirm` | install play execute | **hard precondition enforced by the runtime, not the prompt:** a `confirm` frame for this exact quote (`callId` match) was emitted and approved in this session, quote unchanged. Idempotency key = (sessionId, callId) end-to-end. |
| `player_overview` | `GET /internal/agent/players/{phone}/overview` | cross-banca points/cash/open-ticket/unclaimed-prize counts, per banca, + `sources[]`. Phone from session only. |
| `player_prizes` | `GET /internal/agent/players/{phone}/prizes` | cross-banca claimable-prize LIST with amounts (ticketNumber, prizeDOP, claimableDOP, claimStatus per banca) + `sources[]`. |
| `player_recent_plays` | `GET /internal/agent/players/{phone}/tickets?limit` | merged recent plays with banca labels + `sources[]`. |
| `prize_claim_network` | install claim surface using the brokered credential | wallet-only claim of one prize in the prize's banca. Requires: `claim` capability flag, committed banca context = the prize's banca (auto-committable — the prize names its banca; still announce it), and an approved `confirm` frame (`callId` match) exactly like `play_confirm`. |

Capability flags: `play_quote`/`play_confirm`/`banca_link_confirm` sit behind a
channel-binding capability `play`; `prize_claim_network` behind a separate
`claim` flag — both ship **disabled** for the network binding (enablement chain
in the platform doc §6). `player_linked_bancas`/`directory_online_bancas`/
`banca_context_set` and the personal reads (`player_overview`/`player_prizes`/
`player_recent_plays`) ship with S2 (read-safe; personal reads additionally
require a registered portal user — `NOT_ENROLLED` from the hub drives a
signup-pointer answer).

### 4.1 Correctness contract for personal reads (structural, not prompt-only)

These answers are about people's money; the runtime enforces the platform doc's
§3.4 contract mechanically:

- **Coverage disclosure is not optional.** Every aggregation tool_result carries
  `sources[]`. When any entry is not `OK`, the runtime appends a system-turn
  directive to the model context for that turn ("coverage incomplete: Banca El
  Sol TIMEOUT — you MUST tell the player which banca could not be checked") and,
  as a backstop, tags the tool_result `coverage: "partial"`. An answer that
  claims completeness over partial data is the defined worst failure of this
  feature.
- **Verbatim numbers.** Tool outputs return amounts as pre-formatted display
  strings (e.g. `"RD$450.00"`, `"1,250 pts"`) alongside raw values; the prompt
  contract requires the model to quote the display strings and forbids model
  arithmetic. No tool returns a cross-banca points total — points are org-scoped
  and never pooled; the per-banca breakdown IS the answer.
- **No stale money answers.** Aggregation freshness rides the hub's own cache
  policy (30s summaries, live tickets); the runtime never caches personal-read
  results across turns — every question re-asks the hub.
- **Greeting integration:** when the channel binding enables it, `greet_and_menu`
  may include unclaimed-prize **count + banca name only — never amounts** in
  unprompted output (platform doc §3.4/§8). Amounts require the player to ask.

Broker error mapping (engine-facing, drives copy): `NOT_ENROLLED` → point to
portal signup; `NOT_LINKED` → offer link confirm; `PLAY_DISABLED` → "esa banca no
tiene juego en línea disponible" + re-offer picker; token expiry mid-flow →
transparent refresh, re-quote if the draft is stale.

## 5. Profile / prompt contract changes

`player_network_v1` system-prompt fragment updates:

- Replace the "single-banca scope with directory exception" framing with the
  **network + banca-context** model: "you are the Corillo network assistant; you
  act for the signed-in player across the network; money actions happen only
  inside an explicitly committed banca context."
- Banca-selection etiquette: prefer the player's linked bancas; when quick-pick
  is used, always state the chosen banca before proceeding; one player sentence
  switches context; never assume a banca from history alone.
- Money rules restated for the model (belt — §4 is the suspenders): never
  execute `play_confirm` without a fresh approved quote; never discuss or accept
  payment data in chat; deposits → point to the banca's published methods.

Channel-binding changes: network binding gains the `play` capability flag
(default off) and the hub broker base URL + credential ref. Banca-scoped
bindings are untouched by this design.

## 6. Security & abuse

- **Authority separation:** chat principal (org-inert, no vend) vs. brokered
  install token (org-bound, short-lived, player-owned). Compromising the prompt
  yields no vend authority without the confirm-frame precondition and the hub
  chain (org opt-ins, ACTIVE vinculo) all green server-side.
- **Selection integrity:** structured selections validated against offered
  picker items (§3.2); phone always from the verified session, never from model
  args (§4).
- **Rate limits (app-level, in front of any provider limits):** per session —
  picker emissions, link confirms (e.g. 3/day), play_confirm attempts; per phone
  across sessions — broker calls. Exceeded → polite refusal + cool-down, logged.
- **Audit:** every broker call carries `sessionRef`; runtime logs (sessionId,
  callId, installCode, outcome) with no token material; hub keeps the
  `agente_sesion_juego` ledger (platform doc §4.2).
- **Recording strategies:** play-path tool_results must redact `customerToken`
  before any transcript/recording sink.

## 7. Rollout & compatibility

| Stage (platform doc §7) | Runtime work | Compat |
|---|---|---|
| S1 | none — current runtime serves the network entry point as-is | — |
| S2 | session model (§2), wire contract (§3), read tools + `banca_context_set` + personal reads (§4.1 contract), prompt update | old app clients: picker degrades to NL (ui_hint ignored); old runtime + new app: `selection` field ignored, NL works; personal reads need the hub agent endpoints deployed first |
| S3 | play tools + `prize_claim_network` + broker client + `play`/`claim` capability flags, rate limits, redaction | requires hub broker deployed first; flags stay off until platform chain §6 is green |

Contract-change discipline: `banca_picker` payload and the `selection` field are
versioned by this doc; additive changes only. Any breaking change requires a new
ui_hint kind, never a mutation of this one (old clients ignore unknown kinds —
that property is the upgrade path).
