# Yaya Agentic — Design Document

**Status:** Draft v0.1
**Owner:** Yenfri Herrera
**Audience:** Engineering team building Yaya Agentic
**Last updated:** 2026-06-05

---

## 1. Summary

Yaya Agentic is a framework for building **profile-driven, authentication-aware conversational agents**. It lets product teams stand up a ChatBot whose capabilities, tone, and authority are configured by composing three orthogonal concerns:

1. **Personality** — a shared, cross-profile prompt that makes every bot feel like the same product (human, warm, concise, honest about limits).
2. **Profile** — the *role* the user is interacting with (e.g. `retail-customer`, `merchant-agent`, `internal-support`). A profile pins a system prompt, a set of capabilities, the tools that implement them, and authorization policy.
3. **Authentication / Authorization (AuthN/AuthZ)** — a pluggable layer that decides *who* the user is and *whether* a given tool invocation is allowed. Tools never trust the LLM; they always go through AuthZ.

The result is a conversation that:
- Greets the user, introduces itself, and surfaces the actions it can perform.
- Recognizes intent, asks for missing parameters in natural language, and stays in character.
- Calls tools through a typed, auditable execution path.
- Handles authorization rejections gracefully ("I can't pull that account's transactions because it isn't linked to your profile") without leaking implementation detail.
- Tracks intent shifts and gracefully resumes or pivots.

This document defines the architecture, core abstractions, conversation lifecycle, data model, extension points, and open questions for v1.

---

## 2. Goals & Non-Goals

### 2.1 Goals (v1)

- **G1.** A clean, composable model where a new product surface = personality + 1 profile + N capabilities + an AuthN/AuthZ binding.
- **G2.** Tool calls are deterministic, typed, auditable, and policy-checked — independent of the LLM's "intent."
- **G3.** Conversations feel human: consistent persona, clarifying questions, graceful error handling, intent tracking.
- **G4.** Streaming-first responses with structured UI hints (suggested actions / quick replies).
- **G5.** Provider-pluggable LLMs (start with Anthropic Claude via Spring AI; OpenAI as secondary).
- **G6.** Multi-tenant by construction (a tenant can override personality fragments, register profiles, and bind auth providers).
- **G7.** First-class observability: every turn, tool call, and AuthZ decision is traceable.

### 2.2 Non-Goals (v1)

- No visual flow builder. Profiles are defined in code/config; a UI can come later.
- No voice / multimodal input. Text first.
- No agent-to-agent (A2A) protocol. Single-agent-per-conversation for v1.
- No fine-tuning. We compose prompts and tools; we don't train models.
- No on-device / offline execution.

---

## 3. Glossary

| Term | Definition |
|---|---|
| **Personality** | Cross-profile prompt fragment defining tone, style, and conversational rules. |
| **Profile** | A bundle of `(systemPrompt, capabilities[], authBinding, metadata)` describing a role. |
| **Capability** | A user-meaningful action ("List my transactions"). Backed by one or more tools. |
| **Tool** | A typed, executable function with a JSON schema, a handler, and a required permission. |
| **Authenticator** | Verifies the caller's identity; produces a `Principal`. |
| **Authorizer** | Given `(Principal, Tool, args)`, returns ALLOW / DENY (+ reason). |
| **Principal** | Verified caller identity + claims (tenant, scopes, attributes). |
| **Session** | A persistent conversation, including history, working memory, and active intent. |
| **Turn** | One user message + the agent's response (which may include tool calls). |
| **Intent Frame** | Structured representation of what the user is currently trying to accomplish. |
| **Resolver** | Strategy that maps an incoming session-start request to a `Profile`. |
| **Knowledge Source** | A registered document corpus (paths, URLs, S3 prefixes, …) attached to a Profile for retrieval. |
| **Retriever** | Component that, given a query + session context, returns ranked chunks from the profile's knowledge sources. |
| **Tool Handler** | The thing that actually runs a tool call. Two flavors: an **in-process Spring bean** or a **remote HTTP endpoint**. |
| **Conversation Recorder** | SPI for writing, reading, redacting, exporting, archiving, and replaying conversations. Owns storage choice (Postgres, S3, warehouse, …). |
| **Recording Strategy** | Per-tenant or per-profile config that resolves to a recorder (or composition of recorders) at session start. |

---

## 4. High-Level Architecture

```
                   ┌──────────────────────────────────────────────────────┐
                   │                  Client / Host App                    │
                   │   (web, mobile, WhatsApp, internal console, …)        │
                   └────────────────────────┬─────────────────────────────┘
                                            │ HTTP / WS (SSE streaming)
                                            ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         Yaya Agentic Service (Spring Boot)                    │
│                                                                              │
│   ┌──────────────┐   ┌──────────────────┐   ┌──────────────────────────┐    │
│   │ Conversation │──▶│  Profile Resolver│──▶│  Profile Registry         │    │
│   │  Controller  │   │      Chain       │   │  (in-mem + persistence)   │    │
│   └──────┬───────┘   └──────────────────┘   └──────────────────────────┘    │
│          │                                                                   │
│          ▼                                                                   │
│   ┌──────────────────────────────────────────────────────────────────────┐  │
│   │                       Conversation Engine                            │  │
│   │  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────┐    │  │
│   │  │ PromptBuilder│  │ IntentTracker│  │ ToolCallPlanner         │    │  │
│   │  └──────┬───────┘  └──────────────┘  └────────────┬────────────┘    │  │
│   │         │                                          │                 │  │
│   │         ▼                                          ▼                 │  │
│   │  ┌──────────────────────┐               ┌──────────────────────┐    │  │
│   │  │   LLM Client (SPI)   │◀─────tool─────│   Tool Executor      │    │  │
│   │  │   (Spring AI)        │     results   │  ├─ Bean dispatch    │    │  │
│   │  │                      │               │  └─ HTTP dispatch    │    │  │
│   │  └──────────┬───────────┘               └──────────┬───────────┘    │  │
│   │             │ retrieved context                                       │  │
│   │             ▼                                                         │  │
│   │  ┌──────────────────────────────────────────────────────────┐       │  │
│   │  │ Retrieval Pipeline (per-profile knowledge sources)        │       │  │
│   │  │  query → embed → vector + keyword → rerank → top-K        │       │  │
│   │  └──────────────────────┬───────────────────────────────────┘       │  │
│   └─────────────────────────┼────────────────────────┼──────────────────┘  │
│                             │                        │                     │
│                             ▼                        ▼                     │
│      ┌──────────────────────────────────────────────────────────────┐     │
│      │              Authentication & Authorization                  │     │
│      │   Authenticator SPI    │    Authorizer SPI (policy chain)    │     │
│      └────────────────┬───────┴───────────────┬──────────────────────┘     │
│                       │                       │                            │
│      ┌────────────────▼─────┐  ┌──────────────▼──────────────┐             │
│      │  Identity Providers  │  │  Policy Stores              │             │
│      │  (OIDC, internal,    │  │  (OPA/Rego, attribute, RBAC)│             │
│      │   service tokens)    │  │                             │             │
│      └──────────────────────┘  └─────────────────────────────┘             │
│                                                                              │
│      ┌──────────────────────────────────────────────────────────────────┐ │
│      │ ConversationRecorder SPI  ◀── RecorderRouter (per session) ──▶    │ │
│      │   Postgres │ S3 cold │ Warehouse │ Null │ Redact/Encrypt decos    │ │
│      │   FanOut via transactional outbox                                  │ │
│      └──────────────────────┬──────────────────────┬─────────────────────┘ │
│                             │                      │                       │
│      ┌──────────────────────▼───┐  ┌───────────────▼──┐  ┌───────────────┐│
│      │ Conversation Store(s)    │  │ Memory Service   │  │ Tracing/Audit ││
│      │ (Postgres / S3 / …)      │  │ (vector + KV)    │  │ (OTel + audit)││
│      └──────────────────────────┘  └──────────────────┘  └───────────────┘│
│                                                                              │
│      ┌──────────────────────┐  ┌──────────────────────────────────────────┐ │
│      │ Knowledge Ingestion  │  │ Knowledge Store                          │ │
│      │ (loaders + chunkers  │─▶│ (pgvector + BM25 index + provenance      │ │
│      │  + embedders, sched.)│  │  metadata, per-profile namespaces)        │ │
│      └──────────────────────┘  └──────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
                            │                                  ▲
                            ▼                                  │ tool dispatch
              ┌─────────────────────────────────┐    ┌─────────┴──────────────┐
              │     Downstream Domain APIs       │    │  In-process Tool Beans │
              │  (Accounts, Payments, Tickets…) │    │  (Spring @Component)   │
              └─────────────────────────────────┘    └────────────────────────┘
```

---

## 5. Core Abstractions

### 5.1 Personality (`PersonalityProvider`)

The persona layer is **the same across all profiles in a tenant**. It defines:
- Voice and tone (e.g. "warm, brief, professional, never robotic").
- Universal conversational rules: greet, introduce capabilities, ask before assuming, never invent data, admit limits clearly.
- Refusal templates and authorization-denial language.

```java
public interface PersonalityProvider {
    PersonalityFragment forTenant(TenantId tenantId, Locale locale);
}

public record PersonalityFragment(
    String voiceAndTone,
    List<ConversationalRule> rules,
    RefusalTemplates refusals
) {}
```

The fragment is rendered into the system prompt **above** the profile's prompt so personality rules always win on conflict.

### 5.2 Profile (`Profile`)

A profile is a typed, versioned bundle:

```java
public record Profile(
    ProfileId id,                              // e.g. "retail-customer@v3"
    String displayName,                        // e.g. "Yaya Assistant"
    String introOneLiner,                      // "I'm Yaya, here to help with your account."
    String systemPromptFragment,               // role-specific guidance
    List<CapabilityRef> capabilities,          // what this profile can do
    List<KnowledgeSourceRef> knowledgeSources, // documents available to retrieval
    AuthBindingRef authBinding,                // which Authenticator + Authorizer apply
    Map<String, Object> metadata               // tenant tags, locale defaults, etc.
) {}
```

A profile **never embeds tool implementations or document content** — only references. This keeps profiles declarative and snapshottable, and lets capability/tool catalogs and knowledge corpora evolve independently. See §5.8 for how `knowledgeSources` participate in retrieval.

### 5.3 Capability & Tool

A **capability** is the user-meaningful unit ("Send money", "List transactions"). It is what the bot advertises and what the user picks. A capability is implemented by one or more **tools** the LLM can call.

```java
public record Capability(
    CapabilityId id,
    String userFacingLabel,        // shown as quick-reply
    String userFacingDescription,  // 1-line explanation
    String llmGuidance,            // how the LLM should think about this
    List<ToolId> tools,            // backing tools
    PermissionRequirement requires // declared permission needs
) {}

public record ToolDescriptor(
    ToolId id,
    JsonSchema inputSchema,
    JsonSchema outputSchema,
    PermissionRequirement requires,   // checked by Authorizer before exec
    ToolHandlerRef handler,           // how to actually run it
    ToolPolicy policy                 // timeout, retries, idempotency, confirmable
) {}

// A tool can be implemented two ways. The engine picks the dispatcher
// at runtime based on the descriptor; the LLM doesn't know or care.
public sealed interface ToolHandlerRef
        permits ToolHandlerRef.Bean, ToolHandlerRef.Http {

    /** In-process Spring bean that implements ToolHandler<I,O>.
     *  Resolved from the Spring context by name (or type) at startup. */
    record Bean(String beanName) implements ToolHandlerRef {}

    /** Remote HTTP resource. Args → request via a declarative mapping;
     *  response → tool result via a JsonPath/JMESPath projection. */
    record Http(HttpToolSpec spec) implements ToolHandlerRef {}
}

public record HttpToolSpec(
    HttpMethod method,
    UriTemplate url,                    // supports {arg.field} placeholders
    Map<String,String> headerTemplates,
    BodyTemplate body,                  // builds JSON body from args
    ResponseProjection response,        // maps response → tool output schema
    AuthForwarding authForwarding       // none | principal-token | service-token
) {}

// The handler contract that Bean tools implement directly.
// HTTP tools get an adapter implementation generated from HttpToolSpec.
public interface ToolHandler<I, O> {
    O execute(I input, ExecutionContext ctx) throws ToolException;
}
```

**Why two transports?**
- **Bean** is for things that live inside this service: validation, formatting, lightweight orchestration, anything that benefits from direct access to Spring beans (repos, mappers, internal services). Fast, no network, fully type-safe.
- **HTTP** is for everything else: existing domain APIs, third-party SaaS, internal microservices behind their own auth. Declarative — onboarding a remote action does not require writing or deploying Java code, only registering a `ToolDescriptor` with an `Http` handler.

Both share the same `ToolDescriptor`/`PermissionRequirement`/AuthZ pipeline, so from the LLM's and the Authorizer's perspective they are indistinguishable.

Capabilities → tools is intentionally many-to-one *or* one-to-many. A "Transfer money" capability may need three tools (`lookup_account`, `quote_transfer`, `execute_transfer`); a single `query_transactions` tool may back several capabilities. The split is what enables the bot to surface high-level actions while still letting the LLM compose finer steps.

**AuthForwarding** on HTTP tools is explicit on purpose: the engine never silently forwards the user's bearer token to a downstream. Each tool declares whether it travels with `none`, `principal-token` (the inbound user JWT), or `service-token` (a backend-only credential resolved at dispatch time), and the choice is part of the audit record.

### 5.4 Authentication SPI

```java
public interface Authenticator {
    Principal authenticate(AuthContext ctx) throws AuthenticationException;
}

public record Principal(
    String subject,
    TenantId tenant,
    Set<String> scopes,
    Map<String, Object> claims,
    Instant verifiedAt
) {}
```

Reference implementations to ship:
- `OidcAuthenticator` (Bearer/JWT)
- `ServiceTokenAuthenticator` (machine-to-machine)
- `AnonymousAuthenticator` (guest profiles, lobby flows)
- `DelegatedHostAuthenticator` (host app passes signed identity)

### 5.5 Authorization SPI

```java
public interface Authorizer {
    AuthzDecision authorize(Principal p, Tool<?,?> tool, Object args, AuthzContext ctx);
}

public sealed interface AuthzDecision {
    record Allow(Map<String,Object> obligations) implements AuthzDecision {}
    record Deny(String userSafeReason, String auditReason) implements AuthzDecision {}
}
```

`userSafeReason` is what we let the agent paraphrase to the user. `auditReason` is what we log. **The two must be different.** Authorizers can be chained (e.g. scope check → policy engine → resource-ownership check) and short-circuit on first DENY.

Reference implementations:
- `ScopeAuthorizer` (token scopes vs `requires`)
- `OpaAuthorizer` (OPA/Rego policy)
- `OwnershipAuthorizer` (resource-attribute check — "is this account owned by `Principal.subject`?")

### 5.6 Profile Resolver Chain

Resolvers are tried in order; first non-empty wins.

```java
public interface ProfileResolver {
    Optional<ProfileId> resolve(StartConversationRequest req, AuthContext auth);
}
```

Shipped resolvers:
- `ExplicitProfileResolver` — host app passes `profileId`.
- `IdentityProfileResolver` — maps `Principal.claims` → profile (e.g. `role=merchant` ⇒ `merchant-agent`).
- `ChannelProfileResolver` — maps origin (`whatsapp`, `web-public`) → profile.
- `FallbackProfileResolver` — config-driven default per tenant.

### 5.7 Session & Memory

```java
public record Session(
    SessionId id,
    TenantId tenant,
    Principal principal,
    ProfileId profile,
    List<Turn> history,
    IntentFrame activeIntent,
    Map<String, Object> workingMemory   // slot values, last tool results, etc.
) {}
```

Memory layers:
- **Working memory** (per session) — slot values, partial tool args, last results. KV in Postgres.
- **Episodic history** — message log, truncated/summarized for prompt budget. Postgres.
- **Long-term memory** (optional, post-v1) — vector store keyed by `(tenant, principal)`.

### 5.8 Knowledge Sources & Retrieval (RAG)

Each profile can attach one or more **knowledge sources** — corpora that the engine retrieves from at conversation time and folds into the prompt as grounded context. This is what lets a profile answer policy / how-to / product / SOP questions accurately without baking them into the system prompt.

```java
public record KnowledgeSource(
    KnowledgeSourceId id,           // e.g. "merchant-handbook"
    TenantId tenant,
    String name,
    SourceLocation location,        // see below
    IngestionPolicy ingestion,      // chunking, embedding, refresh cadence
    RetrievalPolicy retrieval,      // topK, score threshold, hybrid weights
    AccessRequirement access,       // who can retrieve from it (PermissionRequirement)
    int version
) {}

public sealed interface SourceLocation {
    record LocalPath(Path root, Glob include, Glob exclude) implements SourceLocation {}
    record HttpUrl(URI base, List<URI> seeds) implements SourceLocation {}
    record S3Prefix(String bucket, String prefix) implements SourceLocation {}
    record GitRepo(URI repo, String ref, Glob include) implements SourceLocation {}
    record Inline(List<DocumentBlob> docs) implements SourceLocation {}
}

public interface Retriever {
    RetrievalResult retrieve(RetrievalQuery query, RetrievalContext ctx);
}

public record RetrievalResult(
    List<RetrievedChunk> chunks,    // ranked
    RetrievalTrace trace            // which sources, scores, query rewrites
) {}

public record RetrievedChunk(
    KnowledgeSourceId source,
    String documentId,
    String text,
    Map<String,Object> metadata,    // title, url, page, section, last_modified…
    double score
) {}
```

**Ingestion** is a separate, scheduled pipeline (loader → chunker → embedder → indexer) writing into a per-profile namespace of the knowledge store. v1 ships:
- A `LocalPathLoader` (the "path to documents" case — drop files in a configured root and they're picked up).
- An `HttpLoader` for URL lists with a refresh interval.
- Markdown / PDF / HTML / plain-text parsers.
- A default chunker (recursive structural split with overlap) and embedding model configurable per source.

**Retrieval** is invoked by the conversation engine when the active turn warrants it (see §6.2). Strategy per profile:
- Always-on: every turn retrieves and grounds the response.
- LLM-gated: a cheap classifier (or a `should_retrieve` tool call) decides per turn — avoids retrieval on pure transactional turns ("yes, transfer it") where it adds noise.
- Tool-driven: retrieval is exposed as a tool (`search_knowledge`) the LLM can call when it needs to look something up.

v1 default is **LLM-gated** with `search_knowledge` also exposed as a tool for explicit lookups.

**Provenance is mandatory.** Every retrieved chunk carries source + document + metadata, and the engine surfaces citations to the client through a `citation` SSE event so the UI can render "see source" links. The personality includes a rule: *do not state a fact derived from retrieved context without grounding it in a returned chunk; if no chunk supports the answer, say so.*

**Access control on knowledge.** Knowledge sources have their own `AccessRequirement`, evaluated by the same `Authorizer` chain used for tools. A profile may *reference* a knowledge source the current principal isn't allowed to read; in that case the source is silently dropped from the retrieval set (and a counter is incremented) — it doesn't leak existence. This makes it safe for one profile to span multiple sensitivity tiers when the principal's claims decide what they actually see.

### 5.9 Conversation Recording

Where conversations live, how long they live, who can read them, and what gets exported or forgotten are deployment-specific concerns that the engine should not bake in. The `ConversationRecorder` SPI is the single abstraction the engine uses to read and write conversation state; implementations decide the storage backend and policy.

```java
public interface ConversationRecorder {

    // ---- Write path (engine → recorder) -----------------------------
    void onSessionStarted(Session session, RecorderContext ctx);
    void onTurnRecorded(SessionId id, Turn turn, RecorderContext ctx);
    void onSessionEnded(SessionId id, SessionEndContext ctx);

    // ---- Read path (engine + admin → recorder) ----------------------
    Optional<RecordedSession> loadSession(SessionId id, LoadOptions opts);
    List<Turn>                loadTurns(SessionId id, TurnRange range);
    Page<SessionSummary>      searchSessions(SessionQuery query);

    // ---- Lifecycle / compliance -------------------------------------
    RedactionResult redact(SessionId id, RedactionRequest req);
    void            deleteSession(SessionId id, DeletionRequest req);
    Stream<SessionExport> exportForPrincipal(Principal p, ExportRequest req);
    void            archive(SessionId id, ArchivePolicy policy);

    // ---- Capabilities (so the engine can adapt) ---------------------
    RecorderCapabilities capabilities();
}

public record RecorderCapabilities(
    boolean supportsSearch,
    boolean supportsRedaction,
    boolean supportsExport,
    boolean supportsArchive,
    Durability durability,            // STRONG | EVENTUAL
    Latency writeLatency              // typical write ack budget
) {}
```

A `Turn` (defined in §5.7) is the durable unit — role, content, tool calls, tool results, model + token metadata, intent frame snapshot, retrieved chunk IDs (not full chunks — those are re-derivable from the knowledge store at replay time). The recorder never sees raw embeddings or knowledge chunk bodies; it sees IDs and metadata.

**Recorder routing.** A profile (or tenant) binds to a `RecordingStrategy`, evaluated per session at start time:

```java
public sealed interface RecordingStrategy {

    /** Single backend. */
    record Single(ConversationRecorder primary) implements RecordingStrategy {}

    /** Primary handles reads; writes fan out to all sinks (async, outbox-backed). */
    record FanOut(ConversationRecorder primary,
                  List<ConversationRecorder> sinks) implements RecordingStrategy {}

    /** Recent in fast store, old moved to cold store after `hotWindow`. */
    record Tiered(ConversationRecorder hot,
                  ConversationRecorder cold,
                  Duration hotWindow) implements RecordingStrategy {}

    /** Route per session by a classifier (e.g. data-sensitivity tier). */
    record Classified(SessionClassifier classifier,
                      Map<String, ConversationRecorder> byTier,
                      ConversationRecorder fallback) implements RecordingStrategy {}
}
```

The engine talks to a `RecorderRouter` that resolves the strategy into a concrete recorder per call; from the engine's perspective there is always exactly one recorder behind the SPI.

**Shipped implementations (v1).**
- `PostgresConversationRecorder` — default; queryable; strong durability; backs `searchSessions` via SQL + `tsvector` on turn content.
- `S3ColdRecorder` — append-only JSONL bundles, one object per ended session, with a manifest index; read-only for older sessions; cheap retention.
- `OutboxFanOutRecorder` — transactional outbox pattern: writes land in the primary recorder inside the engine's transaction, an outbox row is emitted in the same transaction, and a background dispatcher publishes to other sinks (warehouse, audit log, customer's bring-your-own sink). Solves the dual-write problem cleanly.
- `RedactingRecorder` (decorator) — applies the tenant's PII redaction policy before delegating. Wraps any recorder.
- `EncryptingRecorder` (decorator) — envelopes turn payloads under a tenant-scoped KMS key before delegating. Wraps any recorder.
- `NullRecorder` — drops everything; for tests and ephemeral / privacy-mode profiles where conversations must not be persisted.

Decorators compose, so a typical production binding looks like `Encrypting(Redacting(OutboxFanOut(Postgres, [Warehouse, ColdS3])))`.

**Write semantics.** `onTurnRecorded` is called on the engine's hot path and must complete before the turn is acknowledged to the client. Implementations that can't meet that latency must either (a) acknowledge fast and stage the durable write through an outbox, or (b) declare `Durability.EVENTUAL` so the engine can warn at config time. The default Postgres recorder is synchronous + strongly durable; fan-out sinks are always async via the outbox.

**Read semantics.** History loading goes through the recorder — including the engine's own prompt-building history fetch. The recorder may return summarized turns (for prompt budget) when `LoadOptions.summarized=true`; summarization itself is a recorder concern, not an engine concern, because what gets summarized vs. preserved verbatim depends on the storage/policy tier.

**Replay.** Given a `SessionId`, `loadSession` + the recorded `Turn` log are sufficient to fully rehydrate state for: audit ("show me what happened"), eval ("re-run this conversation against a new model/profile version"), and debugging ("re-execute from turn N with a patched tool"). Replay never re-issues real side-effecting tool calls — the engine has a `ReplayMode` that intercepts tool dispatch and returns the recorded result instead, or surfaces it to the operator for review.

**Compliance ops** are first-class:
- `redact(sessionId, RedactionRequest)` — masks or removes fields per JSONPath selectors, leaves a tombstone with reason + operator identity.
- `deleteSession(sessionId, DeletionRequest)` — hard delete; the deletion itself is audited.
- `exportForPrincipal(principal, …)` — streams every session belonging to a subject for GDPR/CCPA-style data export, across all bound recorders.
- All three are gated by an admin `Authorizer` binding and produce structured audit records in `audit_recorder_ops`.

**What the engine never does.** Direct SQL against turns. Direct S3 access. Custom serialization. The recorder owns the on-disk format; the engine owns the in-memory `Turn` shape and depends on the SPI only.

---

## 6. Conversation Lifecycle

### 6.1 Start

```
Client ──POST /sessions── {channel, hints?, authToken}
                       │
                       ▼
              Authenticator(s)  ──▶ Principal
                       │
                       ▼
            ProfileResolverChain ──▶ ProfileId
                       │
                       ▼
              Profile + Personality loaded
                       │
                       ▼
        Greeting turn synthesized (no LLM call needed if cached
        intro template; LLM call optional for personalized hello)
                       │
                       ▼
              Session persisted, returned to client
```

The opening response always includes:
- A short, in-character greeting (personality-driven).
- The profile's `introOneLiner`.
- A **quick-reply list** of top-N capabilities the profile exposes.

These quick-replies are produced from the `Capability.userFacingLabel`s ranked by the profile config (and later by usage telemetry). They are sent as a structured side-channel in the response payload — the LLM does not have to "invent" them, ensuring consistent UI.

### 6.2 Turn

```
User message
    │
    ▼
IntentTracker.update(history, message) ─▶ IntentFrame
    │
    ▼
Retrieval gate (per profile strategy)
    ├─ if always-on or classifier says "yes":
    │     Retriever.retrieve(query, ctx)  ─▶ ranked chunks + provenance
    │     (AccessRequirement-filtered against Principal)
    └─ if tool-driven only: skip (LLM may call search_knowledge later)
    │
    ▼
PromptBuilder.build(personality, profile, history, intent,
                    workingMemory, retrievedChunks)
    │
    ▼
LLM call (with tool schema = profile's tools, filtered by AuthZ pre-check)
    │
    ├── plain reply ─────────────────────────────────────────────▶ stream to user
    │
    └── tool_call(toolId, args)
              │
              ▼
        ToolExecutor:
            1. Validate args vs schema
            2. Authorizer.authorize(Principal, tool, args)
                 ├─ DENY ─▶ inject system message:
                 │           "Tool denied: <userSafeReason>"
                 │           Agent paraphrases politely, suggests alternative.
                 └─ ALLOW ─▶ tool.execute(...)
                                ├─ success ─▶ result back to LLM
                                └─ failure ─▶ structured error back to LLM
              │
              ▼
        LLM continues; may emit more tool calls or finalize reply
```

### 6.3 Intent shifts

The `IntentTracker` keeps a small stack: `current` + `parked`. Heuristic + LLM-assisted detection:

- **Continuation** — message fills a slot for the current intent. Stay.
- **Pivot** — user clearly switched topic. Park current intent (with summary), pop or create new.
- **Augmentation** — user added a constraint to the active intent. Update slots.
- **Resume** — user comes back to a parked intent (LLM detects "as I was saying about X").

The active `IntentFrame` is rendered into the prompt as a compact structured note so the LLM does not have to re-derive context every turn.

### 6.4 Parameter elicitation

When the LLM needs to call a tool but is missing required args, it does NOT hallucinate. We enforce this with:

1. Tool input schemas marked `required`.
2. A system rule in the personality fragment: *"If a required parameter is missing, ask the user one focused question. Do not assume."*
3. A guardrail step (post-LLM) that catches tool calls with missing required fields and converts them into a natural-language clarifying question.

### 6.5 Authorization denial UX

When `Authorizer.authorize` returns `Deny`, the engine:

1. Logs the `auditReason` with the full request context.
2. Feeds the LLM a synthetic tool result of shape:
   `{"status":"denied","reason":"<userSafeReason>","suggest_alternatives":true}`
3. The personality includes denial-handling rules (acknowledge → explain in user terms → offer what *is* possible).

Example: user asks for someone else's transactions →
> "I can only pull transactions for accounts linked to your profile. The account you mentioned isn't one of them. If you'd like, I can show **your** recent activity, or help you add an account."

---

## 7. Prompt Composition

The system prompt is assembled per turn, in a fixed order, so caching can be maximally effective:

```
[CACHEABLE PREFIX — same per (tenant, profile, profile_version)]
1. PersonalityFragment (tenant-level)
2. Profile.systemPromptFragment
3. Capability catalog (compact list w/ labels + descriptions)
4. Tool schemas (filtered to authorized-or-could-be-authorized)
5. Static safety / refusal rules

[VARIABLE SUFFIX — per session/turn]
6. Session metadata (principal display name only, no PII)
7. Active IntentFrame (compact JSON)
8. Working memory snapshot (slot values)
9. Retrieved knowledge chunks (delimited, with provenance IDs)
10. Truncated/summarized history
11. Current user message
```

Items 1–5 are emitted as a stable prefix to maximize **prompt caching** (Anthropic + OpenAI both support it). Profile version is part of the cache key so prompt edits invalidate cleanly.

Retrieved chunks (item 9) are wrapped in untrusted delimiters (`<retrieved id="…" source="…">…</retrieved>`) and the system prompt instructs the model to treat them as data, not instructions — the same hardening applied to tool results (§12). Citations are returned out-of-band via SSE `citation` events keyed to the chunk IDs, so the UI can render footnotes without the model having to format them inline.

---

## 8. Data Model (initial sketch, Postgres)

> Sessions/turns tables below are the schema for the default **`PostgresConversationRecorder`**. Other recorders own their own storage layout behind the SPI; only the in-memory `Turn` shape is shared. Recorder routing config and recorder-ops audit live in the control plane regardless of which recorder is bound.


```
tenants(id, name, settings_json)

personality_fragments(tenant_id, locale, voice_tone, rules_json, refusals_json, version)

profiles(id, tenant_id, version, display_name, intro, system_prompt,
         capabilities_json, auth_binding_json, metadata_json,
         status, created_at, deprecated_at)
        UNIQUE(tenant_id, id, version)

capabilities(id, tenant_id, label, description, llm_guidance,
             tool_ids_json, requires_json, version)

tools_registry(id, tenant_id, version, input_schema_json,
               output_schema_json, requires_json,
               handler_kind,            -- 'BEAN' | 'HTTP'
               handler_bean_name,       -- when BEAN
               handler_http_spec_json,  -- when HTTP (method/url/body/proj/auth)
               policy_json,             -- timeout, retries, idempotency, confirmable
               status)

knowledge_sources(id, tenant_id, version, name,
                  location_kind, location_json,
                  ingestion_policy_json, retrieval_policy_json,
                  access_requirement_json, status,
                  last_indexed_at, doc_count, chunk_count)

knowledge_documents(id, source_id, tenant_id, uri, title,
                    content_hash, metadata_json, last_modified, indexed_at)

knowledge_chunks(id, source_id, document_id, tenant_id,
                 chunk_index, text, embedding VECTOR(N),
                 metadata_json, tsv TSVECTOR)
                 -- pgvector for ANN, tsv for BM25-style keyword

profile_knowledge_bindings(profile_id, profile_version, source_id, source_version)

recording_strategies(id, tenant_id, scope_kind,        -- 'TENANT' | 'PROFILE'
                     scope_id, strategy_json, version)

recorder_outbox(id, tenant_id, session_id, turn_id, sink_id,
                payload_json, attempts, next_attempt_at,
                created_at, dispatched_at, status)
                -- transactional outbox for FanOut/Tiered recorders

audit_recorder_ops(id, tenant_id, op,                 -- READ | REDACT | DELETE | EXPORT | ARCHIVE
                   session_id, principal_subject,     -- subject of the data (may be null)
                   actor_subject,                     -- operator who triggered the op
                   request_json, result_json, created_at)

auth_bindings(id, tenant_id, authenticator_ref, authorizer_chain_json)

sessions(id, tenant_id, principal_subject, profile_id, profile_version,
         created_at, ended_at, channel)

turns(id, session_id, idx, role, content_json, tool_calls_json,
      tool_results_json, model, tokens_in, tokens_out, created_at)

audit_authz(id, tenant_id, session_id, turn_id, principal_subject,
            tool_id, args_json, decision, user_reason, audit_reason,
            policy_trace_json, created_at)

intent_frames(session_id, frame_json, parked_stack_json, updated_at)
```

PII handling: `turns.content_json` is encrypted at rest with a tenant-scoped key. `audit_authz.args_json` redacts fields tagged `pii: true` in the tool schema.

---

## 9. API Surface (v1)

All endpoints are tenant-scoped via header / path.

```
POST   /v1/sessions                       start a conversation
GET    /v1/sessions/{id}                  fetch state
POST   /v1/sessions/{id}/messages         send a user message (SSE response)
POST   /v1/sessions/{id}/end              terminate

GET    /v1/profiles                       list profiles visible to caller
GET    /v1/profiles/{id}                  fetch profile (with capabilities)

POST   /v1/admin/profiles                 create/update profile (versioned)
POST   /v1/admin/capabilities             register capability
POST   /v1/admin/tools                    register tool (schema + bean OR http handler)
POST   /v1/admin/auth-bindings            register auth binding

POST   /v1/admin/knowledge-sources        register a knowledge source (path/url/s3/git/inline)
POST   /v1/admin/knowledge-sources/{id}/reindex   trigger ingestion run
GET    /v1/admin/knowledge-sources/{id}/status    last index time, doc/chunk counts

POST   /v1/admin/recording-strategies     bind a recorder strategy to tenant/profile
GET    /v1/admin/recording-strategies/{scope}     fetch effective strategy

GET    /v1/admin/sessions?…               search sessions (delegates to recorder)
GET    /v1/admin/sessions/{id}            load a recorded session
POST   /v1/admin/sessions/{id}/redact     apply RedactionRequest
DELETE /v1/admin/sessions/{id}            hard delete (audited)
POST   /v1/admin/sessions/{id}/archive    apply ArchivePolicy
POST   /v1/admin/sessions/{id}/replay     start a replay run (no real side-effects)

POST   /v1/admin/principals/{subject}/export   GDPR-style export across all recorders
DELETE /v1/admin/principals/{subject}          right-to-be-forgotten (cascades to recorders)

GET    /v1/admin/audit/authz?…            query AuthZ decisions
GET    /v1/admin/audit/recorder-ops?…     query recorder-op audit trail
```

`POST /v1/sessions/{id}/messages` streams Server-Sent Events:

```
event: token      data: {"text":"Hello"}
event: token      data: {"text":", I'm Yaya."}
event: tool_call  data: {"id":"…","tool":"list_transactions","args":{…}}
event: tool_result data: {"id":"…","status":"ok","summary":"3 txns"}
event: citation   data: {"chunk_id":"…","source":"merchant-handbook","title":"Refunds policy","url":"…"}
event: ui_hint    data: {"quick_replies":["Last 7 days","Last month"]}
event: end        data: {"turn_id":"…","tokens":{"in":1234,"out":456}}
```

`ui_hint` is the structured channel for quick-replies, cards, etc. — produced by the engine, never invented by the LLM.

---

## 10. Tech Stack

- **Java 21**, **Spring Boot 3.x**.
- **Spring AI** for LLM client abstraction, chat memory, function-calling glue, prompt templating, document loaders, and vector store abstraction. Provider modules: Anthropic (primary), OpenAI (secondary).
- **PostgreSQL** for sessions, turns, profiles, audit.
- **pgvector** extension on the same Postgres for knowledge chunk embeddings + ANN search (keeps the v1 stack to one stateful store; swap behind the `Retriever` SPI later if needed).
- Postgres full-text (`tsvector`) for the keyword leg of hybrid retrieval; reranker is a Spring AI `RerankerModel` (cross-encoder), optional in v1.
- **Redis** for ephemeral working memory + rate limiting.
- **OpenTelemetry** (traces + metrics) + structured JSON logs for audit.
- **Optional:** OPA sidecar for declarative authorization policies (kept behind the `Authorizer` SPI so it's not load-bearing).
- **Test:** JUnit 5, Testcontainers (Postgres+pgvector, Redis), WireMock for downstream APIs and HTTP tool handlers, plus an **agent eval harness** (see §13).

---

## 11. Extensibility — adding a new profile

End-to-end, the steps to onboard a new role:

1. **Register tools** (or reuse): `POST /v1/admin/tools` with input/output schemas + a handler:
   - `handler: { kind: "BEAN", beanName: "listTransactionsTool" }` — references a `@Component` implementing `ToolHandler<I,O>` already in the deployed service.
   - `handler: { kind: "HTTP", spec: { method, url, body, response, authForwarding } }` — declarative, no code change required to wire a remote endpoint as a tool.
2. **Register knowledge sources** (optional): `POST /v1/admin/knowledge-sources` with a location (path, URL, S3, git, inline), ingestion + retrieval policy, and an access requirement. Trigger initial indexing.
3. **Register capabilities**: bind user-facing label/description to tool IDs and declared permissions.
4. **Register auth binding**: pick an Authenticator + Authorizer chain.
5. **Create profile**: assemble `(intro, systemPromptFragment, capabilities, knowledgeSources, authBinding)`. Versioned.
6. **(Optional) Customize personality** at tenant level.
7. **Wire a resolver** if the profile shouldn't be passed explicitly by the host (e.g. identity-claim mapping).
8. **Add evals** — a small set of scripted conversations for this profile (see §13), including at least one knowledge-grounded question per source.

No service code change is required for steps 1–7 as long as the underlying **bean** tool handlers already exist. **HTTP** tool handlers and **knowledge sources** can be added live without redeploying — this is the primary configuration-only extension surface. Bean tool handlers do require code + deploy (they're trusted, in-process execution).

---

## 12. Safety, Guardrails, and Trust Boundaries

- **The LLM is not trusted.** It proposes; the executor disposes. Every tool invocation passes schema validation + `Authorizer`.
- **No tool can be called for a profile that doesn't list it.** The LLM never even sees out-of-profile tools.
- **HTTP tool handlers are sandboxed at the egress layer.** A registered allowlist of host patterns per tenant; outbound calls go through a configured client with explicit timeouts, SSRF protection (no private IPs unless explicitly allowed), and TLS pinning options.
- **Output filtering.** A post-LLM scrubber strips obvious leaked PII patterns and tool-internal IDs that shouldn't reach the user.
- **Prompt-injection hardening.** Tool results *and* retrieved knowledge chunks returned to the LLM are wrapped in untrusted delimiters and labeled as data; the system prompt instructs the model to ignore any instructions found inside them. This matters especially for RAG: a malicious document is otherwise a direct injection vector.
- **Knowledge access control.** Sources carry `AccessRequirement` evaluated per turn against the `Principal`; ineligible sources are silently dropped from the retrieval set.
- **Rate limits** per principal and per tenant; per-tool and per-knowledge-source budgets for expensive calls.
- **Kill switch** per profile, per tool, and per knowledge source — flip a flag and the engine stops using it with a graceful message / silently for sources.
- **PII tagging in tool and knowledge-source schemas** drives redaction in audit logs, traces, and memory.
- **Recorder reads are AuthZ'd.** No code path in the engine reads conversation content from storage directly — it goes through the recorder, which checks the operator/principal against an admin AuthZ binding for admin-side reads. Engine-side reads (history for the next prompt) carry the session's own `Principal` and are scoped to that session.

---

## 13. Observability & Evaluation

### 13.1 Tracing

Each turn is a trace; spans include: `intent.update`, `prompt.build`, `llm.call`, `tool.authorize`, `tool.execute`, `response.stream`. Tool call args are attributes (with PII redaction).

### 13.2 Metrics

- Turns/session, tool calls/turn, AuthZ deny rate, denial-to-resolution rate, time-to-first-token, tokens in/out, cache hit ratio, per-tool latency.

### 13.3 Eval harness

A scripted, replayable test runner that:
- Loads a "scenario" (profile + seeded principal + scripted user turns + expected behaviors).
- Runs the full engine against recorded or live LLMs.
- Asserts: which tools were called, which were denied, whether required slots were collected, whether the agent stayed in persona, whether intent shifts were handled, **whether knowledge-grounded answers cited the expected source(s) and refused to answer when no chunk supported the claim**.

This becomes the regression suite for prompt and profile changes. v1 ships with eval scenarios per shipped profile.

---

## 14. Worked Example — "List my transactions"

**Profile:** `retail-customer@v1` — capabilities include `view_account_summary`, `list_transactions`, `dispute_charge`, `update_contact_info`.

```
USER: Hello
BOT:  Hi! I'm Yaya, your account assistant. I can help you check balances,
      review transactions, dispute a charge, or update your contact info.
      What would you like to do?
      [quick replies: Balance · Transactions · Dispute · Update profile]

USER: Show me transactions for account 998877
      (LLM proposes: list_transactions(account_id=998877, range=default))
      (Authorizer: OwnershipAuthorizer DENIES — 998877 not in principal.accounts)
      (audit_reason: "principal=u_42 not owner of account 998877")
      (user_safe_reason: "that account isn't linked to your profile")

BOT:  I can only show transactions for accounts linked to your profile, and
      I don't see 998877 there. I can pull up activity for your linked
      account ending in 1234 — would that work? Or, if 998877 should be
      yours, I can help you link it.
      [quick replies: Show 1234 · Link an account]

USER: Yes show 1234, last 30 days
      (LLM proposes: list_transactions(account_id=…1234, range=30d))
      (Authorizer: ALLOW)
      (Tool result: 14 transactions, summary)
BOT:  Here are your last 30 days on account ending 1234 — 14 transactions,
      totaling $… Want a summary by category, or to see them as a list?
      [quick replies: By category · Show list · Export]
```

This example exercises: greeting + capabilities, ownership-based AuthZ denial, user-safe denial language, alternative suggestion, slot filling (range), continuation intent, and structured UI hints.

---

## 15. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| LLM hallucinates tool args / fakes results | Strict schema validation; no client-visible result unless tool actually executed; denial-aware prompts. |
| Prompt injection via tool output | Wrap tool results in untrusted delimiters; instruct model to treat as data; strip control phrases in scrubber. |
| Profile sprawl across tenants | Versioned profiles; required eval scenarios per profile before deploy; admin UI later. |
| AuthZ logic drifts from tool implementation | `requires` declared on Tool *and* enforced by Authorizer; eval scenarios assert deny paths. |
| Cost runaway | Per-tenant token budgets, prompt caching, history summarization, per-tool call budgets. |
| Latency from chained tool calls | Parallelize independent tool calls; cache idempotent reads in working memory. |
| HTTP tool endpoint flakiness / SSRF | Per-tenant egress allowlist, timeouts, retries with backoff, circuit breaker, no private-IP egress by default. |
| Stale or wrong knowledge | Source-level `last_indexed_at` surfaced in citations; scheduled re-ingestion; freshness eval scenarios. |
| Document prompt injection ("ignore previous…") via RAG | Untrusted-delimiter wrapping; explicit "treat as data" rule; sanitization of obvious instruction phrases at ingest time. |
| Cross-tenant leakage in vector store | Per-tenant namespace + tenant_id filter enforced at query layer; integration tests assert isolation. |
| Dual-write inconsistency across recorders | Transactional outbox: primary write + outbox row in one transaction; background dispatcher publishes to other sinks with retries. |
| Recorder write becomes hot-path bottleneck | Recorder capabilities declare `writeLatency`; SLO budget enforced; offending sinks moved to fan-out (eventual) so only the primary stays on the critical path. |
| Right-to-be-forgotten misses a sink | `deletePrincipal` iterates every bound recorder; recorder ops are audited; reconciliation job verifies all sinks acked deletion. |

---

## 16. Open Questions

1. **Profile versioning UX** — when a profile is bumped, do in-flight sessions stay on the old version (sticky) or migrate? Current lean: sticky for session lifetime.
2. **Multi-agent / handoff** — out of scope for v1, but should the `Session` model already carry an `agentId` so we can add it later without migration?
3. **Memory privacy** — long-term memory across sessions is powerful but risky; gate behind explicit per-profile opt-in?
4. **Quick-reply generation** — static (from profile config) vs. dynamic (LLM-suggested, engine-vetted). v1 ships static; LLM-suggested behind a flag.
5. **Policy language** — ship with attribute-based + OPA, or just attribute-based for v1? Lean: attribute-based for v1, OPA adapter behind SPI but not GA.
6. **Locale / i18n** — personality fragment is per-locale; do profiles carry localized intros, or do we translate at render time?
7. **Tool side-effect semantics** — do we need a two-phase "preview → confirm" pattern as a first-class engine concept for destructive actions? Recommended: yes, via a `confirmable=true` flag on tools.
8. **Embedding model choice** — `text-embedding-3-small` (OpenAI) vs Voyage vs a local model via Spring AI? Lean: start with OpenAI small for cost/quality, model ID stored on each chunk so we can re-index without rebuilding the world.
9. **Chunking strategy** — single recursive structural chunker for v1 vs per-source strategies (e.g. table-aware for PDFs). Lean: one default, with the chunker resolvable per-source by name when we need overrides.
10. **Retrieval gating** — classifier vs always-on vs tool-only. Lean: LLM-gated default, configurable per profile, `search_knowledge` always available as an explicit tool.
11. **HTTP tool spec format** — invent a small DSL vs adopt OpenAPI-derived descriptors. Lean: support both — hand-written `HttpToolSpec` for v1, OpenAPI importer behind it later.
12. **Default recorder strategy** — ship with `Single(Postgres)` as the out-of-box default, or with `OutboxFanOut(Postgres, [])` (empty sinks) so adding a warehouse later is a config-only change. Lean: the latter — wire the outbox from day one, no sinks attached, so we don't repaint the hot path later.
13. **Summarization ownership** — `LoadOptions.summarized=true` puts summarization inside the recorder; do we instead expose a `ConversationSummarizer` SPI consumed by the recorder so summarization strategy is independent of storage? Lean: yes, the recorder calls a `ConversationSummarizer`; defaults to a built-in implementation.
14. **Replay semantics for non-deterministic tools** — replays return recorded results, but should the engine surface a "diff" when the LLM's *plan* diverges (different tool sequence) for the same recorded user turns? Lean: yes, structured replay diff is part of the eval harness.

---

## 17. Milestones

- **M0 — Skeleton (week 1–2):** project scaffold, Spring AI wired to Anthropic, hello-world profile, in-memory profile registry, REST + SSE endpoints, single hardcoded bean tool.
- **M1 — Core abstractions (week 3–5):** Personality, Profile, Capability, Tool (Bean + HTTP handlers), Authenticator, Authorizer, **ConversationRecorder** SPIs; Postgres recorder + outbox scaffolding (no sinks yet); profile resolver chain; audit logging; HTTP egress allowlist.
- **M2 — Conversation engine (week 6–8):** IntentTracker, parameter elicitation, denial-handling, working memory, streaming UI hints, prompt caching.
- **M2.5 — RAG (week 8–10):** KnowledgeSource SPI, ingestion pipeline (local path + URL loaders, default chunker, OpenAI embeddings), pgvector + hybrid retrieval, citations over SSE, retrieval gating, access-controlled sources.
- **M3 — Eval & observability (week 10–11):** eval harness with knowledge-grounded scenarios, OTel traces, per-profile eval scenarios, dashboards.
- **M4 — First real profile (week 11–13):** end-to-end with a real domain (TBD), ownership AuthZ, denial UX polished, ≥1 knowledge source attached, load test, security review.
- **M5 — Multi-tenant + admin APIs (post-v1):** tenant isolation, admin endpoints, profile versioning UX, OpenAPI-driven HTTP tool importer, additional recorder sinks (S3 cold, warehouse stream), redaction/export/replay UX.

---

## 18. Appendix — Minimal Interfaces Recap

```java
public interface PersonalityProvider {
    PersonalityFragment forTenant(TenantId t, Locale l);
}

public interface ProfileResolver {
    Optional<ProfileId> resolve(StartConversationRequest req, AuthContext auth);
}

public interface Authenticator {
    Principal authenticate(AuthContext ctx);
}

public interface Authorizer {
    AuthzDecision authorize(Principal p, Tool<?,?> tool, Object args, AuthzContext ctx);
}

public interface ToolHandler<I, O> {        // BEAN tools implement this directly
    O execute(I input, ExecutionContext ctx);
}

public sealed interface ToolHandlerRef
        permits ToolHandlerRef.Bean, ToolHandlerRef.Http {
    record Bean(String beanName) implements ToolHandlerRef {}
    record Http(HttpToolSpec spec) implements ToolHandlerRef {}
}

public interface Retriever {                 // RAG entry point
    RetrievalResult retrieve(RetrievalQuery query, RetrievalContext ctx);
}

public interface KnowledgeLoader {           // ingestion plug-in
    Stream<RawDocument> load(SourceLocation location, IngestionContext ctx);
}

public interface ConversationRecorder {      // where conversations live, plus lifecycle ops
    void onSessionStarted(Session s, RecorderContext ctx);
    void onTurnRecorded(SessionId id, Turn turn, RecorderContext ctx);
    void onSessionEnded(SessionId id, SessionEndContext ctx);

    Optional<RecordedSession> loadSession(SessionId id, LoadOptions opts);
    List<Turn>                loadTurns(SessionId id, TurnRange range);
    Page<SessionSummary>      searchSessions(SessionQuery query);

    RedactionResult redact(SessionId id, RedactionRequest req);
    void            deleteSession(SessionId id, DeletionRequest req);
    Stream<SessionExport> exportForPrincipal(Principal p, ExportRequest req);
    void            archive(SessionId id, ArchivePolicy policy);

    RecorderCapabilities capabilities();
}

public interface RecorderRouter {            // resolves strategy → recorder per session
    ConversationRecorder recorderFor(Session session, RecordingDecisionContext ctx);
}

public interface ConversationSummarizer {    // called by recorders, not by the engine
    SummarizedTurn summarize(List<Turn> window, SummarizationContext ctx);
}

public interface ConversationEngine {
    StartSessionResult start(StartConversationRequest req);
    Flux<TurnEvent>   send(SessionId id, UserMessage msg);
    void              end(SessionId id);
}
```

These SPIs are the contract that everything else — UI, domain code, policies, providers, knowledge sources, remote tools — plugs into. Adding a new remote action means a `ToolDescriptor` + `Http` handler; adding new in-process logic means a `ToolHandler` bean + descriptor; adding new grounding material means a `KnowledgeSource` + (optional) custom `KnowledgeLoader`.
