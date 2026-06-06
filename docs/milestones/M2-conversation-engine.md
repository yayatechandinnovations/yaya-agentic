# Milestone M2 — Conversation Engine

| | |
|---|---|
| **Goal** | The conversation feels human. Intent is tracked across turns, missing tool args become natural follow-up questions, denials are handled gracefully, working memory persists across turns, prompt caching is on. |
| **Timeline** | Weeks 6–8 (design §17 — M2) |
| **Status** | 🟦 Planned |
| **Depends on** | M1 |
| **Design refs** | §6.2 turn flow · §6.3 intent shifts · §6.4 parameter elicitation · §6.5 denial UX · §7 prompt composition |

## Scope (IN)

- `IntentTracker` real implementation: current + parked stack, continuation / pivot / augmentation / resume detection.
- Parameter elicitation: missing-required-arg tool calls are intercepted and converted into a single focused clarifying question.
- Denial UX: a `Deny` from the `Authorizer` becomes a synthetic tool result feeding the LLM, prompting an in-persona explanation + alternative suggestion.
- Working memory: Redis-backed per-session KV with TTL aligned to session expiry.
- Streaming UI hints: engine emits `UiHint` SSE events for quick-replies; LLM never invents them.
- Prompt caching: cacheable prefix (items 1–5 of §7) is sent with Anthropic's cache control flags; cache key includes `(tenant, profile_id, profile_version)`.
- Confirmable tools: `ToolPolicy.confirmable=true` triggers a two-phase preview → confirm pattern.

## Out of scope (NOT here)

- RAG / retrieval (M2.5).
- Eval harness (M3).
- Replay (M3).
- Long-term memory across sessions (post-v1; §16 q3).

## Backend deliverables

### B2.1 — `DefaultIntentTracker`
Hybrid heuristic + LLM-assist. Cheap classifier first; falls back to a small LLM call only on ambiguous turns. Emits `IntentFrame.parkedStack` updates. Configurable via `yaya.agentic.engine.intent.*` properties.

### B2.2 — Parameter elicitation guardrail
Post-LLM step: if a tool call is proposed with a missing required arg, **do not dispatch**. Instead synthesize an assistant turn containing exactly one focused question ("Which account did you mean — the one ending in 1234 or 5678?") and stream it. The personality rule from `DefaultPersonalityProvider` is enforced here, not trusted from the model.

### B2.3 — Denial flow
When `Authorizer` returns `Deny`:
1. Write audit row with `auditReason`.
2. Feed the LLM a synthetic tool result `{"status":"denied","reason":"<userSafeReason>","suggest_alternatives":true}`.
3. Personality's `denial-handling` rule drives the response shape.

The synthetic-result format is part of the prompt contract; documented in `engine/denial-protocol.md`.

### B2.4 — Working memory in Redis
`RedisWorkingMemory` service with operations: `get`, `put`, `merge`, `expire`. Session TTL = `yaya.agentic.session.idle-timeout` (default 30m). Slot values, partial tool args, and last tool results land here. Read into the prompt as item 8 of §7.

### B2.5 — `DefaultPromptBuilder` with split prefix/suffix
Implements §7 ordering exactly. Returns `PromptPayload(cacheablePrefix, variableSuffix)`. Cacheable prefix is rendered through a template engine with deterministic ordering. Engine wires the prefix as the cached system block, suffix as the per-turn user/system block.

### B2.6 — Prompt cache integration
For Anthropic, set `cache_control: { type: "ephemeral" }` on the prefix block via Spring AI's options. Emit metrics on cache hit ratio. Cache invalidation: bumping a profile's `version` produces a fresh cache key.

### B2.7 — Engine-emitted `UiHint` events
Quick-replies for the opening turn come from the profile's capability list (ranked by config; later by telemetry). Quick-replies inside a turn come from the active capability's declared follow-ups (a small new field `Capability.followUpHints` — additive). LLM-suggested quick-replies live behind a feature flag (design §16 q4).

### B2.8 — Confirmable tools (preview → confirm)
When a `ToolPolicy.confirmable=true` tool is proposed, the dispatcher:
1. Calls a `preview()` shape (if the tool exposes one) OR returns a structured "would do X" stub.
2. Streams a `UiHint("confirm", {summary, args})` event with the proposed action.
3. Pauses until the next user turn returns an explicit confirm / cancel (or natural-language equivalent classified by a tiny LLM step).
4. On confirm, executes the tool.

### B2.9 — End-to-end conversation tests
Scripted scenarios in `src/test/.../ConversationScenarioTest.java` cover: greeting, parameter elicitation, denial-with-alternative, intent pivot, intent resume, confirmable destructive action. LLM stubbed via the M0 façade.

## Flutter deliverables (`yayaagenticweb`)

### F2.1 — Playground: intent + working-memory inspector
Right-side debug panel (toggle):
- Active `IntentFrame` (label + slots) live-updating.
- Parked stack.
- Working memory KV table.

### F2.2 — Playground: denial visualization
When a tool is denied, surface BOTH the user-safe message (in the chat) AND the audit reason + policy trace (in the debug panel, only visible to operators). Make the distinction obvious — that's the whole point of M2's denial UX.

### F2.3 — Playground: confirmable tool UI
Render `UiHint("confirm", …)` as a clear preview card with "Confirm" / "Cancel" buttons that send the appropriate next user turn.

### F2.4 — Playground: prompt inspector
Per turn, show the rendered prompt (cacheable prefix folded by default; variable suffix expanded) and a "cache hit" badge when Anthropic reports a hit. Useful for tuning prefix stability.

### F2.5 — Capabilities admin: follow-up hints
Edit capability now exposes `followUpHints` as a sortable list of strings. Documented inline as "what the engine offers as quick-replies after this capability completes."

### F2.6 — Confirmable flag on tool admin
The tool admin form (from F1.4) now exposes `ToolPolicy.confirmable` as a toggle with an explanation banner ("Destructive actions should require explicit confirmation").

## Acceptance criteria

- [ ] In a 5-turn scripted conversation that includes a pivot and a denial, the agent stays in character, parks/resumes intent correctly, and never reveals an `auditReason` to the user.
- [ ] A tool call with a missing required arg is converted to one clarifying question; the next user reply containing the arg dispatches the tool successfully.
- [ ] Anthropic cache hit ratio ≥ 60% in repeated-profile workloads (measured via metric).
- [ ] A confirmable tool cannot execute without an explicit user confirmation turn.
- [ ] Working memory survives across turns of the same session; expired sessions auto-evict from Redis.
- [ ] Playground debug panel makes intent / memory / denials visible during M2 testing.

## Risks & open questions

- **Intent classifier cost** — a tiny LLM call per turn could double cost. Heuristic-first design and budget metric.
- **Quick-reply ranking** — initially config-driven; revisit after some telemetry in M4.
- **Confirmable detection in NL** — "yes" vs "go ahead" vs "do it" via a classifier; default to a permissive set with safe fallback to re-prompt.

## Exit checklist

- [ ] All M2 scenarios green in CI.
- [ ] Operator can demo intent pivot/resume + denial UX from the playground.
- [ ] M2.5 unblocked: `PromptBuilder` exposes a hook for retrieved chunks (item 9 of §7) that is currently a no-op.
