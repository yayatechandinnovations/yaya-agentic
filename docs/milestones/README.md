# Milestones — Implementation Plan

This directory turns the design doc (`docs/design/yaya-agentic-design.md` §17) into implementation-ready milestones. Each file is the contract for one milestone: scope, deliverables, acceptance criteria, and explicit hand-offs to the next.

## Status legend

- 🟦 **Planned** — written, not started
- 🟨 **In progress**
- 🟩 **Done** — acceptance criteria all green
- ⬛ **Deferred** — moved out of v1

## Milestones index

| # | File | Weeks | Status | One-liner |
|---|---|---|---|---|
| M0 | [M0-skeleton.md](M0-skeleton.md) | 1–2 | 🟦 | Scaffold both apps, smoke chat via a single hardcoded bean tool |
| M1 | [M1-core-abstractions.md](M1-core-abstractions.md) | 3–5 | 🟦 | All SPI implementations + ConversationRecorder + admin CRUD |
| M2 | [M2-conversation-engine.md](M2-conversation-engine.md) | 6–8 | 🟦 | IntentTracker, denial UX, working memory, streaming UI hints, prompt caching |
| M2.5 | [M2.5-rag.md](M2.5-rag.md) | 8–10 | 🟦 | Knowledge sources, hybrid retrieval, citations, access-controlled sources |
| M3 | [M3-eval-observability.md](M3-eval-observability.md) | 10–11 | 🟦 | Eval harness, OTel traces, per-profile scenarios, replay |
| M4 | [M4-first-real-profile.md](M4-first-real-profile.md) | 11–13 | 🟦 | End-to-end on a real domain, ownership AuthZ, load test, security review |
| M5 | [M5-multi-tenant-admin.md](M5-multi-tenant-admin.md) | post-v1 | 🟦 | Tenant isolation, S3/warehouse sinks, OpenAPI tool importer, redaction/export/replay UX |

## Dependency graph

```
M0 ──▶ M1 ──▶ M2 ──▶ M2.5 ──▶ M3 ──▶ M4 ──▶ M5
                │       │
                └──┬────┘ (M2 and M2.5 overlap weeks 8–10; RAG depends on M2 prompt
                          assembly hooks but engine work continues in parallel)
```

M3 (eval) starts as soon as M2 prompt assembly is stable enough to record traces against.

## The two apps

This repo ships **two coordinated apps**:

1. **`./` (Spring Boot backend)** — the runtime described in the design doc. Hosts all SPIs, the conversation engine, the admin REST surface, and the SSE stream.
2. **`./yayaagenticweb` (Flutter web app)** — the **admin console + test playground**:
   - **Admin console**: manage profiles, capabilities, tools (Bean refs and HTTP specs), knowledge sources, auth bindings, recording strategies; inspect audit logs and recorder ops.
   - **Test playground**: pick a profile, impersonate a principal (with operator AuthZ), open a session, chat over SSE, see tool calls, denials, citations, and quick-replies render exactly like a production client would.

End-user / production chat surfaces (web widget, mobile, WhatsApp, …) are **out of scope for this repo** — they consume the same backend SSE API via their own clients.

## Flutter app conventions (decided in M0)

- **State management:** `flutter_riverpod`
- **HTTP client:** `dio` (interceptors for auth + tracing headers)
- **Models / JSON:** `freezed` + `json_serializable`
- **Routing:** `go_router`
- **SSE:** custom `EventSource` consumer over `dio` (web target only for v1)
- **Auth:** operator JWT in `Authorization: Bearer …` header; refresh handled by a Dio interceptor

The full F0 setup is detailed in [M0-skeleton.md](M0-skeleton.md).

## Milestone file structure

Every milestone file follows the same shape so they're skim-able and diffable:

```
# Milestone X — Title
Goal · Timeline · Status · Depends on · Design refs

## Scope (IN)
## Out of scope (NOT here)
## Backend deliverables           B1 … Bn (issue-sized chunks)
## Flutter (yayaagenticweb)       F1 … Fn
## Acceptance criteria            checkable list
## Risks & open questions
## Exit checklist                 the gate to start the next milestone
```

## Conventions

- A milestone is **done** only when every acceptance criterion is green AND the exit checklist is satisfied. Partial milestones do not unblock the next.
- Each **B**/**F** deliverable is sized to be a single PR (≤ ~600 LOC of substantive change). If a deliverable can't fit, split it before starting.
- Reference design-doc sections directly (e.g. "implements §5.5") — never paraphrase architecture in milestone files.
- Open questions from the design doc (§16) are pulled into the milestone where they need to be answered, not before.
