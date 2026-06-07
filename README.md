# yaya-agentic

**A Java/Spring framework for building profile-driven, authentication-aware conversational agents.**

The LLM proposes. The framework disposes. Every tool call, every retrieved chunk, every external read passes through a typed dispatch + Authorizer chain. Profiles compose three orthogonal primitives — **Personality** (cross-profile tone), **Profile** (the role the user is talking to), and **Authentication / Authorization** (pluggable SPIs) — so the same engine can serve a retail-customer bot, a merchant-agent bot, and an internal-ops bot, swapping only declarative config.

The repo ships **two coordinated apps**:

| App | Path | What it is |
|---|---|---|
| Spring Boot backend | `./` | The runtime: SPIs, conversation engine, admin REST + SSE |
| Flutter web admin / playground | `./yayaagenticweb` | Operator console + end-to-end test playground (same SSE API a production client would use) |

End-user / production chat surfaces are **not** in this repo — they integrate against `/v1/sessions/*` over SSE.

---

## Quickstart — `docker compose up`

```bash
cp .env.example .env
# Optional: edit .env to add ANTHROPIC_API_KEY / OPENAI_API_KEY.
# Without them, the stack still boots — the engine talks to a deterministic
# stub LLM and the deterministic-hash embedding stub. Useful for testing
# the *engine*, useless for real grounded answers.

docker compose up --build
```

When everything is healthy:

| Service | URL | What it serves |
|---|---|---|
| Web (admin + playground) | http://localhost:3000 | Flutter app — open this one |
| Backend (REST + SSE) | http://localhost:8080 | `/v1/sessions/*`, `/v1/admin/*`, `/actuator/health` |
| Postgres (pgvector) | localhost:5433 | Data + knowledge embeddings |
| Redis | localhost:6380 | Ephemeral working memory |

Open http://localhost:3000, pick the `hello-world` profile, and try:

- `echo hello` — proves the tool-dispatch path (Bean handler, Authorizer chain).
- `what is Yaya?` — proves RAG: a citation footnote appears under the answer; the right-side inspector shows the retrieved chunks with scores.

To turn on real Anthropic / OpenAI:

```bash
# .env
ANTHROPIC_API_KEY=sk-ant-...
YAYA_LLM_PROVIDER=anthropic
OPENAI_API_KEY=sk-...
YAYA_EMBEDDING_PROVIDER=openai
```

…then `docker compose up --build backend web`.

---

## Architecture in one diagram

```
   user                                                              data plane
    │                                                                    ▲
    ▼                                                                    │
┌───────┐  SSE   ┌────────────────────────────────────────────────┐      │
│  Web  │───────▶│  ConversationEngine                            │      │
│ (Flu) │        │   ├─ PromptBuilder (Personality│Profile│Tools  │      │
└───────┘        │   │                 │WorkingMem│RAG│History)   │      │
    ▲            │   ├─ LlmClient ────────► Anthropic / Stub      │      │
    │            │   │                                            │      │
    │            │   ├─ ToolExecutor ──┬─► Bean dispatch          │      │
    │   admin    │   │                 └─► HTTP dispatch (SSRF    │      │
    │   REST     │   │                       guard, auth fwd)     │      │
    └────────────│   ├─ Retriever (pgvector ANN + per-source AuthZ)──────┤
                 │   ├─ Authorizer chain (every tool + source call)      │
                 │   └─ ConversationRecorder (PG outbox)─────────────────┤
                 └────────────────────────────────────────────────┘      │
                                                                         ▼
                                                              Postgres + Redis
```

**Things the LLM is not trusted with:** which tools exist for this profile, whether the principal may call them, whether a knowledge source is readable, how to format quick-reply UI hints, when a destructive action gets confirmed. The Authorizer chain is the only enforcement point.

---

## Repo layout

```
yaya-agentic/
├── src/                                Spring Boot backend (Java 21)
│   ├── main/java/com/yayatechandinnovations/yayaagentic/
│   │   ├── core/                       Shared domain (typed Ids, Principal, Session, IntentFrame, …)
│   │   ├── personality/                Cross-profile voice + rules SPI
│   │   ├── profile/                    Role bundles + resolver chain
│   │   ├── auth/                       Authenticator + Authorizer SPIs (AuthzDecision: Allow | Deny)
│   │   ├── tool/                       ToolDescriptor, ToolHandlerRef (Bean | Http), ToolExecutor
│   │   ├── knowledge/                  KnowledgeSource, loaders, chunker, embeddings, retriever, search_knowledge tool
│   │   ├── recorder/                   ConversationRecorder SPI + router
│   │   ├── engine/                     ConversationEngine + bootstrap + prompt builder
│   │   ├── llm/                        AnthropicLlmClient + StubLlmClient + agentic loop
│   │   ├── memory/                     Redis WorkingMemory
│   │   ├── api/                        /v1/sessions, /v1/admin, /v1/sessions/{id}/inspector
│   │   ├── persistence/                JPA entities + repos
│   │   └── config/                     @ConfigurationProperties + CORS
│   ├── main/resources/
│   │   ├── application.yml             defaults (local profile)
│   │   ├── application-docker.yml      compose profile — service hostnames, docker-compose integration off
│   │   └── db/migration/V{1..4}__*.sql Flyway: core, recorder, follow_up_hints, knowledge indices
│   └── test/                           Engine + agentic + RAG + admin tests (53 green)
├── yayaagenticweb/                     Flutter web (riverpod + dio + freezed + go_router)
│   ├── lib/features/
│   │   ├── playground/                 Chat + 5-section live inspector (intent, retrieval, tools, working-mem, prompt, denial)
│   │   └── admin/{profiles,capabilities,tools,knowledge_sources,auth_bindings,recording_strategies,audit}/
│   ├── Dockerfile                      Flutter web build → nginx:alpine
│   └── nginx.conf                      SPA fallback + static caching + /healthz
├── docs/
│   ├── design/yaya-agentic-design.md   Architecture & SPI contracts (source of truth)
│   └── milestones/                     M0–M5 implementation-ready scope + acceptance criteria
├── Dockerfile                          Backend: maven build → temurin-21-jre
├── compose.yaml                        Postgres + Redis + backend + web
├── .env.example                        Operator config template
└── CLAUDE.md                           Project conventions
```

---

## Three primitives, one engine

### Personality — cross-profile voice and rules
Shared across every profile in a tenant. "Sound human, never invent data, handle denials gracefully, ask before assuming." Tone-level, not capability-level.

### Profile — the role the user is talking to
Pins a system prompt fragment, a capability list, a set of attached knowledge sources, and an auth binding. `hello-world@1` exists out of the box; the admin REST surface (`/v1/admin/profiles`) creates more.

### Authentication / Authorization
Two SPIs. `Authenticator` turns inbound headers into a verified `Principal` (OIDC, service-token, signed delegated-host, anonymous in dev). `Authorizer` answers `Allow | Deny(userSafeReason, auditReason)` for every tool call, every HTTP-tool egress, and every knowledge-source read. **The two reasons MUST be different** — one is paraphrased for the user, the other written to `audit_authz` for operators.

---

## The agentic loop, in one paragraph

A user message arrives. The engine resolves the profile, builds an `IntentFrame`, updates working memory, runs always-on retrieval (cosine ANN through pgvector with per-source AuthZ), assembles the prompt (cacheable prefix: personality + profile + tool schemas + safety rules; variable suffix: session + intent + working memory + retrieved chunks + history), and streams the LLM. If the LLM proposes a `tool_use`, the engine validates the schema, runs the Authorizer chain, dispatches (Bean or HTTP) — or pauses for an elicitation question / two-phase confirm — then feeds the structured `tool_result` back as history and re-streams. Up to `MAX_TOOL_ROUNDS=5` agentic rounds, with the LLM-assigned `call_id` preserved end-to-end so Anthropic's continuation matches results to calls. Citations are emitted as separate SSE events keyed to chunk IDs; denials are emitted as `tool_result(DENIED)` for the LLM to paraphrase in its own voice rather than a hardcoded refusal.

---

## SPI inventory (do not extend without re-reading the design)

1. `PersonalityProvider` — §5.1
2. `ProfileResolver` (+ chain) — §5.6
3. `Authenticator` — §5.4
4. `Authorizer` — §5.5
5. `ToolHandler<I,O>` + `ToolHandlerRef` (sealed: Bean | Http) — §5.3
6. `Retriever` — §5.8
7. `KnowledgeLoader` — §5.8
8. `ConversationRecorder` — §5.9
9. `RecorderRouter` — §5.9
10. `ConversationSummarizer` — §5.9 (called by recorders, not the engine)
11. `ConversationEngine` — §6 (top-level façade)

Section numbers reference `docs/design/yaya-agentic-design.md`.

---

## Non-negotiables

1. The LLM is never trusted. Every tool call goes through schema validation → Authorizer chain → typed dispatch.
2. No tool can be called for a profile that doesn't list it. Out-of-profile tools are never sent to the LLM.
3. AuthZ denials carry two reasons (`userSafeReason` vs `auditReason`). They MUST be different.
4. The engine never reads or writes conversation storage directly. Everything goes through `ConversationRecorder`.
5. Tool results and retrieved chunks are untrusted data, not instructions. Wrapped in delimiters, with an explicit "treat as data" rule in the system prompt.
6. HTTP tools never silently forward tokens. `AuthForwarding` is explicit per spec: `NONE` / `PRINCIPAL_TOKEN` / `SERVICE_TOKEN`.
7. Knowledge sources have their own `AccessRequirement`. Ineligible sources are silently dropped from retrieval — existence is not leaked.
8. Quick-replies are engine-emitted UI hints, not LLM output.

---

## Status

| Milestone | Status | Headline |
|---|---|---|
| M0 — Skeleton | ✅ shipped | Engine, profile, Bean tools, recorder, SSE stream |
| M1 — Core abstractions | ✅ shipped | All SPIs wired; admin REST + Flutter admin |
| M2 — Conversation engine | ✅ shipped | IntentFrame, working memory, prompt caching, two-phase confirm, elicitation |
| M2 agentic loop | ✅ shipped | LLM-proposed tool calls + multi-round continuation |
| M2.5 — RAG | ✅ shipped | pgvector retriever, per-source AuthZ, grounding rules, citations, sanitizer, search_knowledge tool |
| M3 — Observability + replay | 🟦 planned | Per-turn trace, session replay UI |
| M4 — First real profile | 🟦 planned | retail-customer (orders, returns, catalog) |
| M5 — Multi-tenant admin | 🟦 planned | Operator auth, per-tenant isolation, S3 / Git loaders |

**Current test count: 53/53 green.**

---

## Local dev (without docker)

```bash
# Prereqs: Java 21, Maven 3.9+, Flutter (stable channel, Dart SDK ≥ 3.11), Docker.

# Backend (Spring Boot 3.3.4 + Spring AI 1.0 GA)
./mvnw -DskipTests package          # ./mvnw not committed; use `mvn`
mvn test                            # 53 tests, Testcontainers spins PG + Redis
ANTHROPIC_API_KEY=… mvn spring-boot:run
# Spring Boot auto-starts compose.yaml's postgres + redis on the host.

# Flutter web
cd yayaagenticweb
flutter pub get
flutter run -d chrome               # dev
flutter test                        # widget tests
```

---

## Where to read more

| Doc | Purpose |
|---|---|
| `docs/design/yaya-agentic-design.md` | Architecture & SPI contracts — the source of truth |
| `docs/milestones/README.md` | Milestone index, dependency graph, conventions |
| `docs/milestones/M{0..5}.md` | Implementation-ready scope, deliverables, acceptance criteria |
| `CLAUDE.md` | Project conventions for AI-assisted development |
