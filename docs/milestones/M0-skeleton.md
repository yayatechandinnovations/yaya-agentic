# Milestone M0 — Skeleton

| | |
|---|---|
| **Goal** | Both apps run end-to-end. A single hardcoded profile, a single hardcoded bean tool, an in-memory recorder. One smoke conversation that proves the wiring. |
| **Timeline** | Weeks 1–2 (design §17 — M0) |
| **Status** | 🟦 Planned |
| **Depends on** | Skeleton (already in place) |
| **Design refs** | §4 architecture · §6.1 start · §9 API · §10 stack |

## Scope (IN)

- Spring Boot context boots; `SessionController` and `AdminController` respond.
- A `DefaultConversationEngine` wired with: `DefaultPersonalityProvider`, a single in-memory `Profile`, one bean `ToolHandler` (`echo` tool), an in-memory `ConversationRecorder` (no Postgres yet), Anthropic Claude as the LLM via Spring AI.
- `POST /v1/sessions` starts a session and returns a canned greeting + quick-replies.
- `POST /v1/sessions/{id}/messages` streams an SSE response with at least `token`, `tool_call`, `tool_result`, `end` events.
- Flutter web app (`yayaagenticweb`) boots, talks to the backend, can open a session, render the chat, render quick-replies, and see SSE events.

## Out of scope (NOT here)

- Real persistence (Postgres / pgvector / Redis).
- Real Authenticator / Authorizer — M0 uses a no-op stub that issues a fixed `Principal`.
- HTTP tool dispatcher (M1).
- RAG / knowledge sources (M2.5).
- Multi-tenant, admin CRUD endpoints (M1+).
- Audit, eval, replay (M3).

## Backend deliverables

### B0.1 — Hardcoded `Profile` + `Capability` + `Tool` ✅ scope
Wire one `Profile` (`hello-world@v1`) with one `Capability` ("Say hello back") backed by one bean tool (`echo`). Live in `engine.bootstrap.HelloWorldProfile`. **Not** persisted.

### B0.2 — `DefaultConversationEngine` minimal
- `start()` → resolves `hello-world@v1` via `ExplicitProfileResolver`, emits a deterministic greeting + the capability's `userFacingLabel` as a quick-reply.
- `send()` → builds the system prompt via a stub `PromptBuilder` (no caching yet), calls Anthropic via Spring AI, streams tokens. If the LLM proposes the `echo` tool, dispatch via a `BeanToolDispatcher`.
- `end()` → marks the session ended in the in-memory recorder.

### B0.3 — `InMemoryConversationRecorder`
Implements the full `ConversationRecorder` SPI against a `ConcurrentHashMap`. Lifecycle ops throw `UnsupportedOperationException` with a clear message — they're not in M0 scope but the interface is fully implemented so the engine compiles against the SPI.

### B0.4 — `BeanToolDispatcher` (Bean-only)
Resolves `ToolHandlerRef.Bean(beanName)` from the Spring context, validates args against the tool's input schema (use Jackson JsonSchema or a minimal stub), invokes, returns a `Turn.ToolResult`. HTTP variant throws `UnsupportedOperationException`.

### B0.5 — `NoopAuthenticator` + `AllowAllAuthorizer`
Both clearly named, with a class-level Javadoc that says "DEV ONLY — replaced in M1 by real implementations." Wired as the default beans behind `@ConditionalOnMissingBean`.

### B0.6 — SSE plumbing in `SessionController`
Map `TurnEvent` variants to `ServerSentEvent<Object>`. Use Reactor `Flux`. CORS open to `http://localhost:*` for the Flutter dev server.

### B0.7 — Smoke integration test
`@SpringBootTest` with WebTestClient: starts a session, sends "Hello", asserts at least one token event and an `end` event with `tokens.in > 0`. Stubs the Anthropic client with a recorded fixture (no live LLM call in CI).

## Flutter deliverables (`yayaagenticweb`)

### F0.1 — Dependencies & scaffolding
Update `pubspec.yaml`:
- `flutter_riverpod: ^2.5.1`
- `dio: ^5.7.0`
- `freezed: ^2.5.7` + `freezed_annotation`
- `json_serializable: ^6.8.0` + `json_annotation`
- `go_router: ^14.6.0`
- Build runners as dev deps.

Replace `lib/main.dart` with a `ProviderScope` + `MaterialApp.router` wiring.

### F0.2 — Project structure
```
yayaagenticweb/lib/
├── main.dart
├── app/
│   ├── router.dart            go_router config
│   └── theme.dart
├── api/
│   ├── api_client.dart        Dio + interceptors (auth header, trace id)
│   ├── api_config.dart        base URL, env
│   └── sse_client.dart        EventSource wrapper for /messages
├── features/
│   ├── playground/            chat UI (M0)
│   ├── profiles/              admin UI (M1+)
│   ├── tools/                 admin UI (M1+)
│   ├── knowledge/             admin UI (M2.5+)
│   ├── recording/             admin UI (M1+)
│   └── audit/                 admin UI (M3+)
└── models/                    freezed DTOs mirroring backend records
```

### F0.3 — Playground (chat) — M0 slice
Single route `/playground`. Inputs: profile picker (only `hello-world@v1` for now). On send:
- POST `/v1/sessions` to open one.
- Open SSE on `/v1/sessions/{id}/messages`.
- Render token stream into a chat bubble.
- Render quick-replies returned at session start as chips below the input.
- Show tool calls + results in a collapsible side panel (engineers care; end users won't).

### F0.4 — Settings → backend URL
A simple settings drawer with the backend base URL, persisted to local storage. Defaults to `http://localhost:8080`.

### F0.5 — Smoke widget test
A widget test that mocks the Dio client, asserts the playground renders streamed tokens.

## Acceptance criteria

- [ ] `./mvnw spring-boot:run` boots without external Postgres/Redis (use H2 + embedded for M0; switch in M1).
- [ ] `curl -N -X POST http://localhost:8080/v1/sessions/abc/messages -d '…'` streams SSE events to the terminal.
- [ ] `cd yayaagenticweb && flutter run -d chrome` opens the playground, lets you start a session and chat with the bot via SSE.
- [ ] Smoke integration test green (B0.7).
- [ ] Smoke widget test green (F0.5).
- [ ] No real LLM call in CI; LLM stubbed via recorded fixture.

## Risks & open questions

- **LLM stubbing in tests** — Spring AI's `ChatClient` can be wrapped behind a façade we control; do that from day one rather than mocking the SDK.
- **Embedded vs Testcontainers Postgres for M0** — defer real Postgres to M1; M0 uses H2 just to satisfy auto-config. (Notes from §17.)
- **CORS scope** — open to `localhost:*` for dev; tighten in M5.

## Exit checklist

- [ ] Both apps run with a single `make dev` (or documented command pair) per repo root.
- [ ] A short demo recording (gif or 30-second screen capture) of the playground chatting via the echo tool is checked into `docs/demos/M0.gif`.
- [ ] M1 file unblocked; team agrees the SPI surfaces in the skeleton are correct.
