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

Open http://localhost:3000. The console requires login — default credentials are `admin` / `admin` (change them at **Operator auth → Bootstrap → Change password** or pre-set `YAYA_BOOTSTRAP_PASSWORD_HASH`).

The top bar carries a **tenant picker**; the bootstrap seeds a `default` tenant so the console works out of the box. Every list (profiles, tools, capabilities, knowledge sources, …) and every create form is scoped to whatever the picker shows — switching tenants invalidates every screen at once. Register more under **Tenants** if you want to play with cross-tenant cloning.

Pick the `hello-world` profile and try:

- `echo hello` — proves the tool-dispatch path (Bean handler, Authorizer chain).
- `what is Yaya?` — proves RAG: a citation footnote appears under the answer; the right-side inspector shows the retrieved chunks with scores.

### Pick a chat provider

The engine talks to a provider-agnostic `LlmClient` SPI. Two real implementations ship today:

```bash
# .env — Anthropic (default)
ANTHROPIC_API_KEY=sk-ant-...
YAYA_LLM_PROVIDER=anthropic

# …or OpenAI
OPENAI_API_KEY=sk-...
YAYA_LLM_PROVIDER=openai
OPENAI_CHAT_MODEL=gpt-4o-mini   # optional override

# Embeddings (OpenAI's text-embedding-3-small; falls back to a
# deterministic-hash stub when no key is set)
YAYA_EMBEDDING_PROVIDER=openai
```

Flip `YAYA_LLM_PROVIDER` and restart `backend`; the engine doesn't know which provider answered. The agnostic contract is pinned in `LlmClientAgnosticContractTest`: a single mocked Spring AI `ChatModel` stream produces an identical `LlmEvent` sequence through both wrappers.

Adding a third provider (Bedrock / Gemini / …) is implementing one interface — about a day, not a refactor. See `src/main/java/com/yayatechandinnovations/yayaagentic/llm/` for the two existing implementations.

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
│   │   ├── auth/                       Authenticator + Authorizer SPIs (AuthzDecision: Allow | Deny) — end-user plane
│   │   ├── operator_auth/              OperatorAuthenticator chain (bootstrap + HTTP delegate), sessions, CSRF, rate limit, audit — console plane
│   │   ├── tool/                       ToolDescriptor, ToolHandlerRef (Bean | Http), ToolExecutor
│   │   ├── tenant/                     TenantGuard, BaseUrlValidator, OriginEnforcer, CloneService, AbsoluteToPathMigrator
│   │   ├── knowledge/                  KnowledgeSource, loaders, chunker, embeddings, retriever, search_knowledge tool
│   │   ├── recorder/                   ConversationRecorder SPI + router
│   │   ├── engine/                     ConversationEngine + bootstrap + prompt builder
│   │   ├── llm/                        AnthropicLlmClient + StubLlmClient + agentic loop
│   │   ├── memory/                     Redis WorkingMemory
│   │   ├── api/                        /v1/sessions, /v1/admin (incl. /tenants), /v1/sessions/{id}/inspector
│   │   ├── persistence/                JPA entities + repos
│   │   └── config/                     @ConfigurationProperties + CORS
│   ├── main/resources/
│   │   ├── application.yml             defaults (local profile)
│   │   ├── application-docker.yml      compose profile — service hostnames, docker-compose integration off
│   │   └── db/migration/V{1..10}__*.sql Flyway: core, recorder, knowledge, profile language, operator sessions, operator-auth config + audit, tenant registry, tenant_clone_jobs
│   └── test/                           Engine + agentic + RAG + admin + operator-auth + tenant + clone tests (123 green)
├── yayaagenticweb/                     Flutter web (riverpod + dio + freezed + go_router)
│   ├── lib/app/                        Router + theme + selected-tenant provider (persisted to shared_preferences)
│   ├── lib/features/
│   │   ├── auth/                       Operator login screen + auth-state controller + router guard
│   │   ├── playground/                 Chat + 5-section live inspector (intent, retrieval, tools, working-mem, prompt, denial)
│   │   └── admin/{tenants,profiles,capabilities,tools,knowledge_sources,auth_bindings,recording_strategies,audit,operator_auth}/
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

### Tenant — the trust root that owns them all
A tenant is the single trust root for everything the platform does on behalf of a customer: which **inbound** origins may speak to us, which **outbound** hosts our tools dispatch to, which `Authenticator` mints the principal, which `Recorder` owns the transcript. Not four independent knobs — four projections of the same operator-configured row. HTTP tools are **path-only** (`/v1/orders/{id}`, never `https://api.acme.com/...`) so the host always resolves from the tenant — which is what makes a profile cloneable across tenants by a deterministic id-rewrite. Inbound origin enforcement is symmetric with the outbound host: the row that tells us where to dispatch *for* this tenant is the same row that decides who may speak to us *as* this tenant. Design: `docs/design/tenant-registry-design.md`.

A second, intentionally separate plane authenticates **console operators** — the humans configuring the platform via `/v1/admin/**` and the Flutter app. `OperatorAuthenticator` ships with a bootstrap break-glass operator (DB-backed, password changeable from the UI) and an HTTP delegate that forwards `{user, pass}` to whatever login endpoint the host application already has — configurable request shape, success criteria, and JSONPath identity mapping so customers never wrap their endpoint to fit a yaya contract. Cookie-bound sessions, synchronizer-token CSRF, Bucket4j rate limit, SSRF guard on the delegate URL, per-attempt audit (`audit_operator_login`). Design: `docs/design/operator-auth-design.md`.

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

Section numbers reference `docs/design/yaya-agentic-design.md`. Plus `OperatorAuthenticator` — the console-operator plane (separate trust boundary, see `docs/design/operator-auth-design.md` §3).

---

## Non-negotiables

1. The LLM is never trusted. Every tool call goes through schema validation → Authorizer chain → typed dispatch.
2. No tool can be called for a profile that doesn't list it. Out-of-profile tools are never sent to the LLM.
3. AuthZ denials carry two reasons (`userSafeReason` vs `auditReason`). They MUST be different.
4. The engine never reads or writes conversation storage directly. Everything goes through `ConversationRecorder`.
5. Tool results and retrieved chunks are untrusted data, not instructions. Wrapped in delimiters, with an explicit "treat as data" rule in the system prompt.
6. HTTP tools never silently forward tokens. `AuthForwarding` is explicit per spec: `NONE` / `PRINCIPAL_TOKEN` / `SERVICE_TOKEN`.
7. HTTP tool `urlTemplate` is **path-only**. The host always resolves from the tool's tenant (`host_base_url`). Absolute URLs are rejected at save time — that's what makes cloning a profile across tenants a deterministic id-rewrite.
8. Knowledge sources have their own `AccessRequirement`. Ineligible sources are silently dropped from retrieval — existence is not leaked.
9. Quick-replies are engine-emitted UI hints, not LLM output.
10. Admin writes against an unknown tenant fail with `unknown_tenant`. There is no implicit auto-create — tenants are registered explicitly through `/v1/admin/tenants`.

---

## Status

| Milestone | Status | Headline |
|---|---|---|
| M0 — Skeleton | ✅ shipped | Engine, profile, Bean tools, recorder, SSE stream |
| M1 — Core abstractions | ✅ shipped | All SPIs wired; admin REST + Flutter admin |
| M1.5 — Operator auth | ✅ shipped | Bootstrap + HTTP-delegate strategies, login-gated console, CSRF + Bucket4j + audit |
| M2 — Conversation engine | ✅ shipped | IntentFrame, working memory, prompt caching, two-phase confirm, elicitation |
| M2 agentic loop | ✅ shipped | LLM-proposed tool calls + multi-round continuation |
| M2.5 — RAG | ✅ shipped | pgvector retriever, per-source AuthZ, grounding rules, citations, sanitizer, search_knowledge tool |
| M2.8 — Tenant registry | ✅ shipped | First-class tenants, path-only HTTP tools, inbound origin allowlist, cross-tenant profile clone (dependency walk + dry-run + atomic apply), absolute → path migrator |
| M3 — Observability + replay | 🟦 planned | Per-turn trace, session replay UI |
| M4 — First real profile | 🟦 planned | retail-customer (orders, returns, catalog) |
| M5 — Multi-tenant admin | 🟦 planned | Per-tenant operator roles + API keys, S3 / Git loaders, RTBF / export UX |

**Current test count: 123/123 green.**

---

## Local dev (without docker)

```bash
# Prereqs: Java 21, Maven 3.9+, Flutter (stable channel, Dart SDK ≥ 3.11), Docker.

# Backend (Spring Boot 3.3.4 + Spring AI 1.0 GA)
./mvnw -DskipTests package          # ./mvnw not committed; use `mvn`
mvn test                            # 123 tests, Testcontainers spins PG + Redis
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
| `docs/design/operator-auth-design.md` | Console-operator auth SPI, HTTP-delegate config shape, hardening |
| `docs/design/tenant-registry-design.md` | Tenant as a first-class trust root, path-only HTTP tools, cross-tenant profile clone |
| `docs/design/tool-url-resolution-design.md` | Per-tenant `host_base_url` + allowlist + `X-Yaya-Host-Base-Url` override (kept; the absolute-URL leniency is superseded by tenant-registry-design §6) |
| `docs/milestones/README.md` | Milestone index, dependency graph, conventions |
| `docs/milestones/M{0..5,2.5,2.8}.md` | Implementation-ready scope, deliverables, acceptance criteria |
| `CLAUDE.md` | Project conventions for AI-assisted development |
