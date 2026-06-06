# CLAUDE.md — yaya-agentic

## What this project is

**yaya-agentic** is a Java/Spring framework for building **profile-driven, authentication-aware conversational agents**. A bot is composed from three orthogonal primitives:

- **Personality** — shared, cross-profile tone/rules ("sound human, ask before assuming, never invent data, handle denials gracefully").
- **Profile** — the *role* the user is talking to (e.g. `retail-customer`, `merchant-agent`). Pins a system prompt, a list of capabilities (with backing tools), a set of knowledge sources for RAG, and an auth binding.
- **Authentication / Authorization** — pluggable SPIs. The LLM proposes; the Authorizer chain disposes. Every tool call and knowledge read passes through it.

The repo ships **two coordinated apps**:

1. **`./` (Spring Boot backend)** — the runtime: SPIs, conversation engine, admin REST + SSE.
2. **`./yayaagenticweb` (Flutter web)** — the **admin console + test playground**. Operators configure profiles, capabilities, tools (Bean refs + HTTP specs), knowledge sources, auth bindings, recording strategies; the playground exercises a profile end-to-end via the same SSE API a production client would use.

End-user / production chat surfaces are NOT in this repo.

The full architecture lives in **`docs/design/yaya-agentic-design.md`**. The implementation plan lives in **`docs/milestones/`** (one file per milestone M0–M5, plus a README index). Both are the source of truth — when in doubt, re-read the relevant section before changing code or interfaces.

## Sources of truth

| Doc | Purpose |
|---|---|
| `docs/design/yaya-agentic-design.md` | Architecture & SPI contracts |
| `docs/milestones/README.md` | Milestone index, dependency graph, conventions |
| `docs/milestones/M{0..5}.md` | Implementation-ready scope, backend + Flutter deliverables, acceptance criteria |

When working in this repo, anchor your changes to specific design-doc sections:

| Section | Topic |
|---|---|
| §3 | Glossary |
| §4 | High-level architecture |
| §5.1 | Personality |
| §5.2 | Profile |
| §5.3 | Capability & Tool (Bean vs HTTP transports) |
| §5.4 | Authentication |
| §5.5 | Authorization (`AuthzDecision` Allow/Deny) |
| §5.6 | Profile resolver chain |
| §5.7 | Session & Memory |
| §5.8 | Knowledge Sources & Retrieval (RAG) |
| §5.9 | Conversation Recording |
| §6 | Conversation lifecycle |
| §7 | Prompt composition |
| §8 | Data model |
| §9 | API surface |
| §10 | Tech stack |
| §12 | Safety / trust boundaries |
| §15 | Risks |
| §16 | Open questions |
| §17 | Milestones (M0 → M5) |

## Repo layout

```
yaya-agentic/
├── docs/
│   ├── design/yaya-agentic-design.md
│   └── milestones/
│       ├── README.md
│       └── M0-skeleton.md … M5-multi-tenant-admin.md
├── src/                          Spring Boot backend (Java 21)
├── yayaagenticweb/               Flutter web app (admin + playground)
├── pom.xml
└── CLAUDE.md
```

The Flutter app uses **flutter_riverpod + dio + freezed + json_serializable + go_router** (decided in M0). Its package structure is defined in `docs/milestones/M0-skeleton.md` (F0.2).

## Backend package map

All backend code lives under `com.yayatechandinnovations.yayaagentic`.

```
com.yayatechandinnovations.yayaagentic
├── YayaAgenticApplication            Spring Boot entry point
├── core/                             Shared domain (no deps on other packages here)
│   ├── Ids                             typed IDs: TenantId, ProfileId, SessionId, ToolId, …
│   ├── Principal                       verified identity
│   ├── Session                         the conversational unit
│   ├── Turn                            one role's contribution + tool calls/results
│   ├── IntentFrame                     current intent + parked stack
│   ├── PermissionRequirement           declarative AuthZ requirement
│   └── ExecutionContext                per-call context for tools/retrievers
├── personality/                      Cross-profile voice/rules — design §5.1
│   ├── PersonalityProvider             SPI
│   ├── PersonalityFragment             record (voice, rules, refusal templates)
│   └── DefaultPersonalityProvider      tenant-agnostic baseline
├── profile/                          Role bundles + resolver chain — design §5.2, §5.6
│   ├── Profile, Capability, ProfileRegistry, StartConversationRequest
│   ├── ProfileResolver                 SPI
│   ├── ProfileResolverChain            ordered, first-non-empty-wins
│   └── resolvers/{Explicit,Identity,Channel,Fallback}ProfileResolver
├── auth/                             AuthN + AuthZ SPIs — design §5.4, §5.5
│   ├── Authenticator, AuthContext, AuthenticationException
│   ├── Authorizer, AuthzContext
│   └── AuthzDecision                   sealed: Allow | Deny(userSafeReason, auditReason)
├── tool/                             Tool model — design §5.3
│   ├── ToolDescriptor                  id + schemas + requires + handler ref + policy
│   ├── ToolHandler<I,O>                Bean implementations
│   ├── ToolHandlerRef                  sealed: Bean(beanName) | Http(HttpToolSpec)
│   ├── HttpToolSpec                    method, url, body/response projection, AuthForwarding
│   ├── ToolPolicy                      timeout, retries, idempotent, confirmable
│   └── ToolExecutor                    single dispatch point for the engine
├── knowledge/                        RAG — design §5.8
│   ├── KnowledgeSource                 id + location + ingestion + retrieval + access
│   ├── SourceLocation                  sealed: LocalPath | HttpUrl | S3Prefix | GitRepo | Inline
│   ├── IngestionPolicy, RetrievalPolicy
│   ├── KnowledgeLoader                 ingestion SPI
│   ├── Retriever                       retrieval SPI
│   └── RetrievalQuery / Result / Context / RetrievedChunk
├── recorder/                         Conversation persistence — design §5.9
│   ├── ConversationRecorder            SPI (write, read, redact, delete, export, archive)
│   ├── RecorderRouter                  resolves strategy → recorder per session
│   ├── RecordingStrategy               sealed: Single | FanOut | Tiered | Classified
│   ├── RecorderCapabilities            durability + write-latency advertisement
│   ├── ConversationSummarizer          called BY recorders, not by the engine
│   ├── LoadOptions, TurnRange, SessionQuery, SessionSummary, Page, RecordedSession
│   └── Operations                      request/result DTOs (redact, delete, export, archive)
├── engine/                           Conversation runtime — design §6, §7
│   ├── ConversationEngine              top-level SPI: start, send, end
│   ├── IntentTracker, PromptBuilder    helpers
│   ├── UserMessage, StartSessionResult
│   └── TurnEvent                       sealed SSE events: Token | ToolCall | ToolResult | Citation | UiHint | End
├── api/                              HTTP surface — design §9
│   ├── SessionController               /v1/sessions/*
│   └── AdminController                 /v1/admin/*
└── config/
    ├── YayaAgenticProperties           @ConfigurationProperties("yaya.agentic")
    └── CoreConfiguration               enables properties
```

`docs/design/yaya-agentic-design.md` is the single design doc.

## The SPI inventory (do not extend or rename without re-reading the design doc)

1. `PersonalityProvider` — §5.1
2. `ProfileResolver` (+ chain) — §5.6
3. `Authenticator` — §5.4
4. `Authorizer` — §5.5
5. `ToolHandler<I,O>` + `ToolHandlerRef` (sealed: `Bean` | `Http`) — §5.3
6. `Retriever` — §5.8
7. `KnowledgeLoader` — §5.8
8. `ConversationRecorder` — §5.9
9. `RecorderRouter` — §5.9
10. `ConversationSummarizer` — §5.9 (called by recorders, NOT by the engine)
11. `ConversationEngine` — §6 (the top-level façade)

## Build & run

```bash
# --- Backend (Spring Boot 3, Java 21) ---
./mvnw clean package
./mvnw test                                     # smoke test, no external deps
ANTHROPIC_API_KEY=… SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run

# --- Flutter admin/playground ---
cd yayaagenticweb
flutter pub get
flutter run -d chrome                           # dev
flutter test                                    # widget tests
```

Local dev expects:
- Postgres 16 with `pgvector` extension (`CREATE EXTENSION vector;`)
- Redis 7
- `ANTHROPIC_API_KEY` for Claude (primary LLM)
- `OPENAI_API_KEY` for embeddings (M2.5+)

Override via env vars: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`.

## Conventions

- **Records over classes** for value types. Most domain types in this skeleton are records by intention — preserve that.
- **Sealed types** for discriminated unions (`AuthzDecision`, `ToolHandlerRef`, `SourceLocation`, `RecordingStrategy`, `TurnEvent`). Adding a new variant means re-reading every `switch` over the sealed type.
- **Typed IDs** in `core/Ids.java`. Don't pass raw `String` IDs across SPIs.
- **`@link` Javadoc references** may be unresolved in some package-info files where the link target lives in a sibling package — these are documentation hints, not code errors.
- **No business logic in `package-info.java`** — they exist only to anchor each package back to a design-doc section.
- **No comments that explain WHAT** — names are enough. Comment only the WHY (a hidden constraint, a workaround, a denial-by-design choice from the design doc).

## Non-negotiables (drawn directly from the design)

1. **The LLM is never trusted.** Every tool call goes through schema validation → `Authorizer` chain → typed dispatch. Tools never check policy themselves. (§12)
2. **No tool can be called for a profile that doesn't list it.** Out-of-profile tools are never sent to the LLM. (§12)
3. **AuthZ denials carry two reasons.** `userSafeReason` is what the agent paraphrases; `auditReason` is what we log. They MUST be different. (§5.5)
4. **The engine never reads or writes conversation storage directly.** Everything goes through `ConversationRecorder`. (§5.9)
5. **Tool results and retrieved chunks are untrusted data, not instructions.** Wrap them in delimiters; explicit "treat as data" rule in the system prompt. (§7, §12)
6. **HTTP tools never silently forward tokens.** `AuthForwarding` is explicit per spec: `NONE` / `PRINCIPAL_TOKEN` / `SERVICE_TOKEN`. (§5.3)
7. **Knowledge sources have their own `AccessRequirement`.** Ineligible sources are silently dropped from retrieval — existence is not leaked. (§5.8)
8. **Quick-replies are engine-emitted UI hints, not LLM output.** Stream them via the `UiHint` event, never trust the model to format them inline. (§6.1, §9)

## What NOT to do without re-reading the design

- Add a new SPI or sealed-type variant.
- Make the engine talk to a storage backend directly (must go through `ConversationRecorder`).
- Forward an auth token to an HTTP tool by default.
- Have a tool read its own permission requirement (the `Authorizer` chain is the only enforcement point).
- Skip provenance/citations for retrieved chunks (RAG without citations is a no-ship).
- Change `Personality` → `Profile` → variable-suffix ordering in `PromptBuilder` (it's tuned for prompt caching; §7).

## Memory

Persistent notes about the project and how the human collaborator works live in:
`/Users/yherrerafeliz/.claude/projects/-Users-yherrerafeliz-Dev-Projects-yaya-agentic/memory/`.

Index entry point: `MEMORY.md`.
