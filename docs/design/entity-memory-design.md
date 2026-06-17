# Entity Memory & Tool Output Binding — Design (v1)

> Companion to `yaya-agentic-design.md` §5.3 (Capability & Tool), §5.5
> (Authorization), §5.7 (Session & Memory), §6 (Conversation lifecycle),
> §7 (Prompt composition), §12 (Safety / trust boundaries). Touches
> `ToolDescriptor`, `ToolExecutor`, `DefaultConversationEngine`,
> `DefaultPromptBuilder`, `ConversationRecorder`. Introduces a new SPI,
> `EntityMemory`, distinct from `WorkingMemory`.

| | |
|---|---|
| **Status** | 🟡 Proposed (v1) |
| **Scope** | Multi-step tool workflows where tool outputs (IDs, references, opaque tokens) must be carried forward as inputs to subsequent tools — without the LLM having to remember, transcribe, or echo them. Engine-owned, server-side, session-scoped. Operator-declared on each tool. Conflict resolution surfaces to the user via the agentic loop, not silently. |
| **Out of scope (v1)** | Cross-session memory (account-level state surviving session end). Persistence to Postgres beyond what the recorder already writes per turn. `INTENT` / `TURN` scopes (only `SESSION` ships in v1). Declarative tool chaining (engine auto-invoking prerequisites). LLM-side memory editing tools. |

---

## 1. The problem in one paragraph

Real conversational workflows are multi-step: a tool produces an ID, a subsequent tool needs that ID, the user adds another item that needs the same ID again. Today the only way for the LLM to carry an ID forward is to read it back out of the conversation history — which is fragile under summarization, prone to transcription errors, and forces operators to either expose internal IDs in the chat surface or hide them and hope the model keeps them in working set. Sensitive identifiers (auth tokens, session refs, principal subject IDs derived from upstream calls) are worse: we should not be relying on the LLM to even *see* them, let alone repeat them correctly. We need a typed, scoped, engine-owned memory that captures structured tool outputs at dispatch time and fills them back into subsequent tool calls without the LLM having to hold them.

---

## 2. The trust principle (read first)

**The LLM proposes structure. The engine fills values.** This is the same invariant the `Authorizer` chain already enforces for permissions (§12, "the LLM is never trusted") extended to identifiers and references. The model is told a name exists; the engine knows the value.

Three consequences shape every decision in this design:

- **Bindings run BEFORE the `Authorizer` chain.** The chain validates the *expanded* args — the real `ticketId`, the real `principalSubject` — not a placeholder. AuthZ semantics do not change; the chain just sees a fuller args map than the LLM sent.
- **Captures run AFTER successful dispatch.** Only `OK` tool results contribute to memory; `DENIED` and `FAILED` are not captured. A denied call cannot poison memory.
- **Sensitive entities are never visible to the LLM.** A per-capture `visibleToLlm` flag governs whether the name appears in the prompt at all. When `false`, the entity is bound silently — the model neither sees the name nor the value, and cannot leak it in chat output. Auth tokens, upstream session refs, and similar must use `visibleToLlm: false` by construction.

The framework stays **consumer-agnostic**. Lottery, retail, healthcare — they all use the same SPI; the operator declares per-tool which entities are produced and which are consumed.

---

## 3. Core types

```java
public record EntityRef(
    String name,              // logical key, e.g. "ticketId", "draftRef"
    Object value,             // the actual id or struct
    EntityScope scope,
    EntityKind kind,
    Ids.ToolId producedBy,    // provenance — which tool captured this
    Instant capturedAt,
    Map<String, Object> metadata
) {}

public enum EntityScope {
    SESSION,    // v1: persists for the conversation lifetime
    INTENT,     // future: cleared on intent transition
    TURN        // future: single-turn only
}

public enum EntityKind {
    SINGLETON,  // one value at a time; conflict policy applies
    COLLECTION  // many values under the same name; APPEND-style semantics
}
```

Three scopes are designed up-front but only `SESSION` ships in v1. The other two are real lifecycle needs and the type system should not have to break later to add them.

`EntityKind.COLLECTION` matters because some workflows accumulate (line items on a ticket, attachments on a draft). A collection-kind capture appends; a singleton triggers conflict resolution.

---

## 4. The `EntityMemory` SPI

```java
public interface EntityMemory {

    /** Add or merge an entity. Conflict handling is the caller's job. */
    void capture(Ids.SessionId session, EntityRef entity);

    Optional<EntityRef> resolve(Ids.SessionId session, String name);

    /** Snapshot — used by PromptBuilder and admin observability. */
    List<EntityRef> all(Ids.SessionId session);

    void clear(Ids.SessionId session, String name);

    /** Used at intent transitions (INTENT scope) and session end. */
    void clearScope(Ids.SessionId session, EntityScope scope);
}
```

A **distinct SPI from `WorkingMemory`**, not a sub-namespace. The behaviors are different enough to deserve separation:

| | `WorkingMemory` | `EntityMemory` |
|---|---|---|
| **Source** | Engine internals + arbitrary K/V | Tool outputs only |
| **Typed** | `Map<String, Object>` | `EntityRef` with provenance + scope |
| **Visibility** | Always projected to prompt | Per-capture `visibleToLlm` |
| **Lifecycle** | Cleared on `session.end` | Scoped (`SESSION` / `INTENT` / `TURN`) |
| **Auditability** | Best-effort | Each entry has `producedBy` + `capturedAt` |
| **Recovery** | Lost on restart | Rehydrated from recorder on resume (phase 2) |

Default implementation lives next to `DefaultWorkingMemory` — `InMemoryEntityMemory`, `ConcurrentHashMap<Ids.SessionId, Map<String, EntityRef>>`. Phase 2 adds a Postgres-backed impl rehydrated by the recorder on session resume.

---

## 5. `ToolDescriptor` extensions

Two new optional fields, both defaulting to empty list (so existing tools are unaffected).

```java
public record EntityCapture(
    String name,                   // EntityMemory key this capture targets
    String jsonPointer,            // RFC-6901 pointer into the tool's output JSON
    EntityScope scope,
    EntityKind kind,
    OnConflict onConflict,
    boolean visibleToLlm,          // false = name NOT projected in prompt
    Optional<Ids.ToolId> rollbackTool,  // ASK_USER + REPLACE: clean up the old one
    Optional<Ids.ToolId> describeTool   // ASK_USER + VIEW: read-only descriptor for "show me"
) {}

public record EntityBinding(
    String argName,                // tool input schema field
    String entityName,             // EntityMemory key
    BindingMode mode               // FILL_IF_MISSING | OVERRIDE | REQUIRED
) {}

public record ToolDescriptor(
    Ids.ToolId id,
    String inputSchemaJson,
    String outputSchemaJson,
    PermissionRequirement requires,
    ToolHandlerRef handler,
    ToolPolicy policy,
    List<EntityCapture> captures,   // NEW — default List.of()
    List<EntityBinding> bindings    // NEW — default List.of()
) {}
```

`OnConflict` and `BindingMode` are described in §7 and §6.

**Why JSON Pointer (RFC-6901) and not JSONPath?** JSON Pointer is a tiny, deterministic grammar — `/orders/0/id`. JSONPath's filter expressions (`$.orders[?(@.status=='OK')].id`) are power that we don't need at the capture layer and that would invite operators to express business logic inside the descriptor. If a tool's response shape doesn't naturally expose the right field, the right fix is to project it in the tool's `response` mapping, not to encode a filter in the capture.

---

## 6. Lifecycle: bind, dispatch, capture

The agentic dispatch path (`runToolDispatch` in `DefaultConversationEngine`) gains two new stages, bracketing the existing pipeline:

```
LLM proposes tool call (toolId, args)
        │
        ▼
1. Apply EntityBindings
        args' = applyBindings(args, tool.bindings, EntityMemory(session))
        ── FILL_IF_MISSING: fill only when args has no value for argName
        ── OVERRIDE:        memory wins regardless of what the LLM sent
        ── REQUIRED:        if memory has no value AND LLM didn't supply,
                            trigger elicitation (existing flow)
        │
        ▼
2. Authorizer.authorize(principal, tool.requires, args', ctx)
        ── Sees the EXPANDED args. AuthZ is enforced on real values.
        │
        ▼
3. Schema validation (existing)
        │
        ▼
4. Confirmable gate (existing, with §consumed_confirm rules)
        │
        ▼
5. ToolExecutor.execute → Dispatched(result)
        │
        ▼
6. Apply EntityCaptures (only when result.status == OK)
        for each capture in tool.captures:
            value = JsonPointer.extract(result.value, capture.jsonPointer)
            applyCapture(session, capture, value)
        ── DENIED / FAILED never capture. A poisoned upstream
            response cannot pollute memory through a denied path.
```

`BindingMode.OVERRIDE` matters when the operator wants to enforce "the LLM never gets to override the captured `ticketId`" — common for security-sensitive entities. `FILL_IF_MISSING` is the default and is the right answer when the LLM might legitimately propose a new entity by name. `REQUIRED` routes missing-and-not-in-memory through the existing elicitation flow.

---

## 7. Conflict resolution — `ASK_USER` as a first-class policy

Silent overwrite is the wrong default for entities that anchor real-world operations (a draft ticket, an open order). Silent ignore is also wrong (the user is confused why their second attempt did nothing). The right default in v1 is **ask the user**, with the conflict surfaced through the agentic loop the same way confirmable tools work today.

```java
public enum OnConflict {
    ASK_USER,    // v1 default for SINGLETON captures
    OVERWRITE,   // operator-opt-in for genuinely transient entities
    IGNORE,      // operator-opt-in (idempotency)
    ERROR,       // raise; engine emits a typed failure
    APPEND       // valid only on EntityKind.COLLECTION
}
```

### The ASK_USER state machine

When a capture with `ASK_USER` fires and `EntityMemory` already has a `SINGLETON` entry under the same name:

```
1. Engine writes pending_entity_conflict into WorkingMemory:
       { entityName, incoming, existing, fromCallId,
         rollbackTool, describeTool }

2. Engine emits structured events:
       TurnEvent.UiHint("entity_conflict", {
           name: "<entityName>",
           existing: { producedBy, capturedAt },
           options: ["continue_existing", "replace", "view_existing"]
       })

3. The LLM continuation round sees the conflict in working memory
   and is instructed (system prompt rule) to paraphrase a question
   in the conversation language, offering the three options. The
   framework supplies the SHAPE, not the COPY — the LLM owns the
   wording so it stays in tone and language.

4. User replies. A new EntityConflictDetector parses the response
   into {CONTINUE_EXISTING, REPLACE, VIEW_EXISTING, UNCLEAR},
   analogous to ConfirmDetector.

5. Routing:
   ── CONTINUE_EXISTING → discard incoming; the original tool
       outcome is reported to the LLM as success without
       updating memory. The user keeps using the existing
       entity going forward.

   ── REPLACE → if rollbackTool is present, dispatch it against
       the existing entity (e.g., cancel-old-draft). On rollback
       success, OVERWRITE memory with incoming. On rollback
       failure, surface the failure; do NOT silently swap memory.

   ── VIEW_EXISTING → if describeTool is present, dispatch it
       against the existing entity. Memory is unchanged. The
       conflict prompt re-asks after the view.

   ── UNCLEAR → drop pending_entity_conflict and let the rest of
       the turn handle whatever the user said (mirrors the
       confirm-pivot rule).
```

The engine knows nothing about lottery tickets or shipping drafts. It knows the *names* and *options*; the LLM owns the copy. Adding a new domain is `INSERT INTO tool_descriptor.captures` with the right pointers and `rollbackTool` / `describeTool` references — no engine change.

### Why this is the right default

- The user keeps agency over destructive transitions (replacing a draft, switching contexts).
- The LLM gets a typed signal it cannot fabricate or skip; the framework's safety frame holds.
- Operators who *want* silent overwrite (e.g., for transient session refs that always cycle) opt into `OVERWRITE` explicitly.

---

## 8. Visibility & the security model

`EntityCapture.visibleToLlm` is the single knob that decides whether the LLM is told a name exists.

```
visibleToLlm = true   → Name listed in prompt; LLM can reference it
                         by being asked to call a tool that binds it.
                         Value is NEVER in the prompt.

visibleToLlm = false  → Name not in prompt. LLM doesn't know it
                         exists. Bindings still apply silently.
                         Used for: auth tokens, principal claims
                         derived from upstream calls, opaque
                         session refs, anything sensitive.
```

This is the structural answer to "we should not expose IDs in the conversation": the operator marks the capture invisible, the engine still threads the value to the right tool args, the LLM cannot accidentally leak what it cannot see.

The `Authorizer` chain receives the **fully expanded** args (visible + invisible). Authorization is unaffected — it sees the real principal subject, the real account ref. Audit rows capture which entities were bound to a call (by name) so a replay can re-attempt with the same memory state without exposing values in operator-readable logs (the values are stored in the entity table, joined on call audit row).

---

## 9. Prompt projection

`DefaultPromptBuilder` gains a new variable-suffix block, between working memory (8) and retrieved chunks (9):

```
Captured context (already known to the executor — DO NOT echo these
names' values to the user, and DO NOT include them in your tool args;
the executor will fill them in automatically):
- ticketId   (captured by start_ticket at 14:32 UTC)
- drawDate   (captured by resolve_draw at 14:32 UTC)
```

Rules in the SAFETY block extend by one line:

> "If a tool's input schema lists a field that appears in *Captured context*, do not include that field in your tool_call arguments. The executor will bind it. If you echo the value to the user in chat, you are violating instructions."

Three things this block deliberately does NOT include:

1. **Values.** Never. Visible-to-LLM means "you may reference by name", not "you may see the value."
2. **Invisible entries.** Sensitive captures don't appear here at all.
3. **A conversational gloss.** No "you have an active ticket draft" — the LLM will compose that from intent context, and adding a second source of truth invites the model to restate it (see the `answer-only-latest` rule already in personality).

---

## 10. End-to-end walk-through

Two tools, both registered once, none of them aware of any specific business domain:

```
ToolDescriptor: start_ticket
  inputSchema  : { channel: string }
  outputSchema : { ticketId: string, reference: string }
  captures     : [
    { name: "ticketId",  jsonPointer: "/ticketId",
      kind: SINGLETON,   scope: SESSION,
      onConflict: ASK_USER, visibleToLlm: true,
      rollbackTool: cancel_ticket, describeTool: show_ticket },
    { name: "ticketRef", jsonPointer: "/reference",
      kind: SINGLETON,   scope: SESSION,
      onConflict: ASK_USER, visibleToLlm: false /* opaque ref */ }
  ]

ToolDescriptor: add_line
  inputSchema  : { ticketId: string, numbers: string, lotteryId: number }
  outputSchema : { lineId: string }
  bindings     : [
    { argName: "ticketId", entityName: "ticketId",
      mode: OVERRIDE /* never let the LLM forge this */ }
  ]
```

A session walk-through:

```
User    : "add 24-36 to today's quiniela"
LLM     : proposes start_ticket(channel="web")
Engine  : authorize → dispatch → result { ticketId:"T-9", reference:"R-7" }
          captures: ticketId="T-9" (visible), ticketRef="R-7" (hidden)

LLM     : proposes resolve_lottery(name="quiniela")
Engine  : … result { lotteryId: 17 }
          captures: lotteryId=17 (visible)

LLM     : proposes add_line(numbers="24-36", lotteryId=17)
          ── LLM did NOT include ticketId in args; it knows from
              the prompt block that the executor will bind it.
Engine  : bindings: { ticketId: "T-9" } ← from EntityMemory
          args'   : { ticketId:"T-9", numbers:"24-36", lotteryId:17 }
Authoriz: sees the real ticketId; permission checks pass.
Engine  : dispatches, captures lineId in a COLLECTION/APPEND.

User    : "add 12 too"
LLM     : proposes add_line(numbers="12", lotteryId=17)
Engine  : bindings again fill ticketId="T-9" silently.

User    : "actually start a new ticket — 7 in la primera"
LLM     : proposes start_ticket(channel="web")
Engine  : dispatch → result { ticketId:"T-12", reference:"R-9" }
          SINGLETON capture on ticketId conflicts with existing "T-9".
          → onConflict: ASK_USER
          → writes pending_entity_conflict, emits UiHint, LLM
             paraphrases: "You have an active draft from earlier.
             Want me to cancel it and start fresh, keep the
             existing one, or show you what's in it?"

User    : "cancel the previous"
Engine  : EntityConflictDetector → REPLACE
          → dispatches rollbackTool (cancel_ticket) against "T-9"
          → on success: OVERWRITE memory with ticketId="T-12"
          → continuation: LLM paraphrases "Done — started a new
             one. Want to add 7 in la primera?"
```

The framework saw lottery tickets exactly never. The operator declared captures + bindings + rollback once and got durable behavior.

---

## 11. Code touch points

| File | Change |
|---|---|
| `core/entity/EntityRef.java` | NEW. Record per §3. |
| `core/entity/EntityScope.java` | NEW. Enum: `SESSION`, `INTENT`, `TURN`. |
| `core/entity/EntityKind.java` | NEW. Enum: `SINGLETON`, `COLLECTION`. |
| `core/entity/EntityMemory.java` | NEW. SPI per §4. |
| `core/entity/InMemoryEntityMemory.java` | NEW. Default `ConcurrentHashMap`-backed bean. Phase 1 only — replaced by Postgres-backed impl in phase 2. |
| `tool/EntityCapture.java`, `tool/EntityBinding.java`, `tool/OnConflict.java`, `tool/BindingMode.java` | NEW. Records / enums per §5. |
| `tool/ToolDescriptor.java` | Add `captures` and `bindings` fields (default empty list). Existing constructors get a compat overload that fills empties. |
| `tool/dispatch/EntityBinder.java` | NEW. Pure function: `(args, bindings, memorySnapshot) → expandedArgs`. Lives in tool package because it's tool-side enforcement. |
| `tool/dispatch/JsonPointerExtractor.java` | NEW. Thin wrapper over Jackson's `JsonPointer`. Defensive — null-on-missing rather than throw. |
| `engine/DefaultConversationEngine.java` | Hook the binder before authorize; on `OK` Dispatched, run captures; handle `ASK_USER` conflict by writing `pending_entity_conflict` and short-circuiting the LLM continuation through the same shape as `pending_confirm`. |
| `engine/confirm/EntityConflictDetector.java` | NEW. Mirrors `ConfirmDetector` but yields `{CONTINUE_EXISTING, REPLACE, VIEW_EXISTING, UNCLEAR}`. |
| `engine/prompt/DefaultPromptBuilder.java` | New variable-suffix block per §9. Only lists `visibleToLlm` entities. Add the one-line safety rule. |
| `engine/TurnEvent.java` | New `UiHint` payload shape documented (no new event type — `entity_conflict` is a `UiHint.kind`). |
| `recorder/ConversationRecorder.java` | `Turn.ToolResult` gains a `Map<String, EntityRef> capturedEntities` field (or sibling). Recorder writes per-turn; load rehydrates `EntityMemory` (phase 2 will add the dedicated table; phase 1 reads back from turn results on session resume). |
| `api/AdminController` (Flutter side, M5) | Tool form: captures + bindings sub-editors. Minimal in v1 — just enough to declare them. |

`HttpToolDispatcher`, `BeanToolDispatcher` are **unchanged**. Capture + binding are engine-side, ahead of dispatch.

---

## 12. Security ledger

| Concern | Mitigation |
|---|---|
| **LLM transcribes a wrong ID.** Model misremembers `T-9` as `T-7` from history. | Bindings remove the LLM from the value path entirely. The model proposes the call shape; the engine fills the value from memory. |
| **LLM hallucinates an ID that doesn't exist.** | `BindingMode.OVERRIDE` forces memory to win even when the LLM passes a value. `FILL_IF_MISSING` plus elicitation also catches this (a hallucinated value is just an argument the engine will replace or challenge). |
| **Captured value leaks into chat.** Model echoes `ticketId` in its reply. | (a) `visibleToLlm: false` removes the name from the prompt entirely. (b) The safety rule explicitly tells the model not to echo `Captured context` values. (c) The personality rule `answer-only-latest` reduces the surface for restating. |
| **Cross-session bleed.** Entity from session A surfaces in session B. | `EntityMemory` is keyed on `(Ids.SessionId)`. The in-memory impl uses a `ConcurrentHashMap` per session; Postgres-backed impl scopes the table by `(tenant_id, session_id)`. No global keyspace. |
| **Cross-tenant bleed.** | Session is tenant-scoped at start. Tenant isolation is enforced at session creation and rides through every `Ids.SessionId` operation. No tenant joinable from another tenant's session. |
| **Capture from a DENIED call poisons memory.** Tool dispatch returns a value but authorization denied. | Captures are gated on `result.status == OK`. `DENIED` and `FAILED` never capture. |
| **A malicious upstream returns an attacker-chosen ID for the user's draft.** | The Authorizer chain has already vetted the tool call; the resolved base URL is operator-controlled (per tool-url-resolution-design.md §6). The upstream response is treated as DATA, not instructions (§7 safety rule, unchanged). The captured value is just a token used for subsequent calls back to the same operator-trusted upstream — the attacker would have to control the upstream itself, in which case the entire tool surface is compromised regardless. |
| **Authorizer is bypassed because args are post-binding.** | Binder runs strictly BEFORE the Authorizer chain. Chain sees expanded args. AuthZ is enforced on real values, every time. |
| **Operator declares `visibleToLlm: true` on a sensitive entity by mistake.** | Save-time validation in the admin UI: any entity whose `name` matches a configured pattern (`*token*`, `*secret*`, `*credential*`) defaults to `false` and warns when toggled to `true`. Operators can override but the default fails closed. |
| **Conflict resolution UI hint leaks the existing entity's value.** | The `entity_conflict` UiHint payload carries `producedBy` and `capturedAt` only — never the value. The LLM paraphrases the question without ever being told what the existing value is. |
| **Audit obscurity.** Operator cannot tell which entities were bound to a given call. | Tool-call audit row gains a `bound_entities` field (names only). The values live in the entity table joined by `(session_id, name, captured_at_or_before(call_time))`. Replay can reconstruct the bind set. |
| **Replay attack: bind an old captured value to a new call.** | Captures carry `capturedAt`; bindings can declare `maxAgeSeconds` (phase 2). v1 ships without TTL because session scope already bounds the lifetime, and short sessions are the common case. Flagged in §14 open question 3. |

---

## 13. Admin UI

- **Tool form — Captures sub-section** (table with columns):
  - Name, JSON Pointer, Scope (SESSION only in v1), Kind, On-Conflict, Visible to LLM, Rollback tool (ref), Describe tool (ref).
  - Inline lint: pointer validates against the tool's `outputSchema`. Save fails if the pointer doesn't address a leaf.
  - Save-time guard: name matches a "sensitive" pattern → `visibleToLlm` defaults `false`, requires explicit operator toggle with a confirmation prompt.
- **Tool form — Bindings sub-section** (table):
  - Arg name (validated against `inputSchema`), Entity name (suggests from any tool's captures in this profile), Mode.
- **Profile form** — read-only entity graph: shows which tools produce which entities, and which tools consume them. Renders cycles in red (a real config bug if it happens; flagged for review).
- **Playground inspector** — adds an "Entity memory" tab to the running session view: shows the current `EntityMemory.all(session)` snapshot, with sensitive values masked. Operator-facing only.

---

## 14. Open questions

1. **Should `OnConflict.ASK_USER` invoke the rollback tool through the existing confirmable-tool pipeline?** I.e., if the rollback tool is itself confirmable, does that trigger a second user prompt? Recommendation: **yes** — chains compose, the user gets two prompts ("cancel old?" then "are you sure?"). Hand-waving the second one would be a §12 violation.
2. **Should `EntityRef.value` be Jackson-serializable always, or do we allow opaque `Object`?** Recommendation: serializable only. Forcing JSON-serializable values means recorder rehydration and admin observability work without per-type adapters. Tools that produce non-JSON outputs project to JSON in their response mapping anyway.
3. **TTL / freshness on captured entities.** Not in v1; session scope is the bound. But a tool that returns a 5-minute-valid token should not be re-bindable at minute 6. Add `EntityCapture.ttlSeconds` (optional) in phase 2; on `resolve`, expired entries return empty and trigger `REQUIRED` elicitation.
4. **Collision between an EntityBinding and a `pending_elicit`.** If the LLM previously paused for elicitation on `ticketId` and the user has since produced one via a separate flow, the binder should fill from memory at the resume step. Recommendation: at `pending_elicit` resume, run bindings first; if memory satisfies the missing field, skip the elicitation prompt.
5. **Should the Authorizer be able to introspect EntityRef provenance?** Useful for rules like "only allow `add_line` if the bound `ticketId` was produced by `start_ticket` in this session, not some other path." Recommendation: phase 2. Adds an `AuthzContext.entities()` view returning the bound `EntityRef`s for the current call.

---

## 15. Future

- **Phase 2 — Persistence + INTENT/TURN scopes.** Dedicated `session_entities` table joined on the recorder's session id; rehydration on resume. INTENT scope wired into `IntentTracker` transitions. TURN scope wired into engine turn boundaries. TTL per §14.3.
- **Phase 3 — Declarative tool chaining.** If a binding is `REQUIRED` and the memory has no value but some tool in the profile *produces* that entity, the engine MAY auto-invoke that tool. Real feature, real complexity (loop detection, AuthZ on the auto-invoked tool, user transparency). Deferred until the simpler shape proves out.
- **Out of scope, possibly forever — cross-session memory.** "Remember this user's drafts across sessions" is account-level state, which belongs in the *consumer's* persistence layer (their order-drafts table), not in yaya-agentic. The framework's memory ends at session end by design.

---

## 16. Sequencing

Slot as **M2.10 — Entity Memory & Tool Output Binding**. Sequenced after the recorder + summarizer work already in main, after M2.7 (tool URL resolution), independent of M3.

### Backend deliverables

- **B10.1** `core/entity/{EntityRef,EntityScope,EntityKind,EntityMemory,InMemoryEntityMemory}.java`. SPI + default bean.
- **B10.2** `tool/{EntityCapture,EntityBinding,OnConflict,BindingMode}.java`. Records + enums.
- **B10.3** `ToolDescriptor` field additions + compat overload.
- **B10.4** `tool/dispatch/EntityBinder.java` + `tool/dispatch/JsonPointerExtractor.java`. Pure functions, unit-tested.
- **B10.5** `runToolDispatch` integration: binder before authorize; capture after OK Dispatched. No changes to `HttpToolDispatcher` or `BeanToolDispatcher`.
- **B10.6** `engine/confirm/EntityConflictDetector.java` + state machine in `DefaultConversationEngine.send` mirroring `pending_confirm`.
- **B10.7** Prompt projection block in `DefaultPromptBuilder` + safety rule line.
- **B10.8** `Turn.ToolResult.capturedEntities` field + recorder write path; phase-1 rehydration reads back from turn results.
- **B10.9** Tool-call audit row carries `bound_entities` (names only).

### Flutter deliverables

- **F10.1** Tool form: Captures + Bindings sub-editors (minimal, table-only).
- **F10.2** Sensitive-name lint + default `visibleToLlm: false` for matching names.
- **F10.3** Playground inspector: "Entity memory" tab with masked sensitive values.

### Acceptance criteria

- [ ] A tool with no captures or bindings behaves identically to today (no regression).
- [ ] A tool with a SINGLETON capture stores the captured value; a subsequent tool with a binding for that name dispatches against the captured value, even when the LLM omits the field from its proposal.
- [ ] Authorizer chain receives expanded args (test asserts the bound value is what the chain sees, not a placeholder).
- [ ] `DENIED` dispatch does not capture; `FAILED` dispatch does not capture; only `OK` captures.
- [ ] `visibleToLlm: false` entity name does not appear in any rendered prompt payload (snapshot test on `PromptBuilder.PromptPayload`).
- [ ] `OnConflict.ASK_USER`: a second SINGLETON capture under the same name pauses the turn, emits a `UiHint("entity_conflict")` with the three options, and writes `pending_entity_conflict` to working memory.
- [ ] `EntityConflictDetector` classifies `"cancela el anterior"` / `"keep the existing"` / `"show me what I have"` correctly (multilingual test set; document the matching as locale-aware in v2 if needed).
- [ ] `REPLACE` dispatches the rollback tool against the existing entity before overwriting memory. Rollback failure does NOT overwrite memory and surfaces the failure to the LLM continuation.
- [ ] `CONTINUE_EXISTING` discards the incoming entity; subsequent bindings still resolve to the original.
- [ ] Cross-session isolation: two parallel sessions, same tenant, both producing `ticketId` — neither sees the other's value.
- [ ] Cross-tenant isolation: enforced at session-creation boundary, asserted in a multi-tenant integration test.
- [ ] Replay of a recorded session reconstructs the same `bound_entities` set per call.

### Risks

| Risk | Mitigation |
|---|---|
| **Binder + Authorizer ordering bug** lets an unbinded placeholder reach auth. | Unit + integration test asserts the chain receives expanded args under every binding mode. Wire the binder as a single call-site in `runToolDispatch`, never duplicated. |
| **Operator declares `visibleToLlm: true` on a secret.** | Sensitive-name lint + default-to-false. Phase 2 adds an organization-level policy that can FORCE `false` for matching patterns. |
| **`ASK_USER` adds a turn of friction operators didn't expect.** | Per-tool opt-out is implicit: declare `OnConflict.OVERWRITE` and silence is back. Document the tradeoff in the admin UI on the conflict-mode selector. |
| **Recorder rehydration drift.** Phase 1 reads back captures from per-turn results; if a turn was redacted, captures vanish. | Phase 2's dedicated `session_entities` table fixes this. Phase 1 acceptance criterion: a redacted turn's captures are treated as cleared (memory state matches what's queryable). Document this in §14 and on the redaction admin flow. |

---

## 17. Summary

- **The LLM proposes structure; the engine fills values.** Same invariant as confirmable tools and the Authorizer chain, extended to identifiers.
- **A new SPI, `EntityMemory`,** distinct from `WorkingMemory`, holds tool-output state with provenance, scope, and visibility metadata.
- **Tools declare what they produce and what they consume** via `EntityCapture` and `EntityBinding` on `ToolDescriptor`. The framework stays consumer-agnostic; operators add new domains by declaring captures and bindings, not by changing engine code.
- **Conflict on a singleton entity defaults to ASK_USER** through the agentic loop — the framework supplies the SHAPE and OPTIONS; the LLM paraphrases the question in the conversation language; the user keeps agency.
- **Sensitive entities are invisible to the LLM by construction** via `visibleToLlm: false`. The model cannot leak what it cannot see. The Authorizer chain still sees real values.
- **Session-scoped only in v1.** Cross-session state is a consumer concern, not a framework concern.
- **Sequences as M2.10**, after the recorder/summarizer work and tool URL resolution, independent of M3.
