# Milestone M1 — Core Abstractions

| | |
|---|---|
| **Goal** | Every SPI from the design doc has a real, persisted implementation. Profiles, capabilities, tools (Bean + HTTP), auth bindings, and recording strategies become first-class manageable entities. Admin console can CRUD all of them. |
| **Timeline** | Weeks 3–5 (design §17 — M1) |
| **Status** | 🟦 Planned |
| **Depends on** | M0 |
| **Design refs** | §5.1–§5.6 SPIs · §5.9 ConversationRecorder · §8 data model · §9 admin API · §11 extensibility · §12 safety |

## Scope (IN)

- Postgres-backed persistence for: profiles, capabilities, tools_registry, auth_bindings, recording_strategies, sessions, turns, audit_authz, audit_recorder_ops, recorder_outbox.
- Real `Authenticator` (OIDC + service token) and `Authorizer` chain (scope + ownership + OPA stub).
- Real `BeanToolDispatcher` *and* `HttpToolDispatcher` with the egress allowlist & SSRF protection from §12.
- `PostgresConversationRecorder` as the default; `OutboxFanOutRecorder` wired around it with **zero** sinks attached (open question §16 q12 — pre-decided as "yes, wire from day one").
- Admin REST endpoints from §9 backed by the new persistence.
- Flutter admin console can CRUD profiles, capabilities, tools, knowledge sources (DTO-only — wiring in M2.5), auth bindings, recording strategies.

## Out of scope (NOT here)

- IntentTracker / parameter elicitation / denial UX polish (M2).
- Working memory in Redis (M2).
- Prompt caching (M2).
- RAG implementation (M2.5).
- Eval harness, OTel traces, replay (M3).
- Multi-tenant isolation in the admin API (M5 — M1 assumes a single default tenant).

## Backend deliverables

### B1.1 — Postgres schema + Flyway migrations
Translate the data-model sketch in §8 into Flyway migrations `V1__core_schema.sql` and `V2__recorder_schema.sql`. Add the `vector` extension up-front (RAG lands in M2.5 but the migration owner is M1). Testcontainers Postgres with pgvector image for tests.

### B1.2 — JPA entities + repositories
One package `persistence.*` per table. Repositories are thin (`JpaRepository`) — no business logic. Use Hibernate's `JdbcType` mapping for `jsonb`.

### B1.3 — `PostgresConversationRecorder`
Full SPI implementation. `searchSessions` backed by SQL + `tsvector`. `redact` applies JSONPath selectors against `turns.content_json` and leaves a tombstone. `deleteSession` is hard delete + audit row. `archive` is a no-op stub (real archive lives in M5 with the S3 sink). Capabilities: `STRONG` durability, `< 50ms` typical write latency.

### B1.4 — `OutboxFanOutRecorder`
Transactional outbox: writes to primary inside the same JPA transaction as an `recorder_outbox` row, then a `@Scheduled` dispatcher publishes to each sink. **Zero sinks for now** — the dispatcher is a no-op iterator over an empty list, but the wiring is in place. Idempotency key on (session_id, turn_id, sink_id).

### B1.5 — `OidcAuthenticator` + `ServiceTokenAuthenticator` + `DelegatedHostAuthenticator`
Real JWT validation against a configured issuer (JWKS). Service token uses HMAC against a tenant-scoped secret. Delegated takes a signed identity blob from the host app. All three live behind the `Authenticator` SPI; the active one is selected by `auth_bindings` row.

### B1.6 — Authorizer chain implementations
- `ScopeAuthorizer` — required scopes vs `Principal.scopes`.
- `OwnershipAuthorizer` — resource ownership check against the principal subject (config-driven `resourceArgPath` → `principalSubjectClaim`).
- `OpaAuthorizer` — call out to an OPA sidecar via HTTP; **stubbed** for M1 (returns `Allow`) but the SPI binding is in place.

`AuthorizerChain` short-circuits on first `Deny`. Every decision (Allow or Deny) writes to `audit_authz`.

### B1.7 — `BeanToolDispatcher` (real)
Resolves bean by name from the Spring context, validates args against the JSON Schema in `ToolDescriptor.inputSchemaJson` using `networknt/json-schema-validator`. Wraps execution in a timeout per `ToolPolicy`.

### B1.8 — `HttpToolDispatcher`
Renders `HttpToolSpec.urlTemplate` and `body.template` against args; applies `headerTemplates`; chooses the `AuthForwarding` strategy explicitly:
- `NONE` — no auth header.
- `PRINCIPAL_TOKEN` — forwards the inbound bearer.
- `SERVICE_TOKEN` — resolves a backend credential from config and uses that.

Pre-flight: hostname must match the tenant's egress allowlist. Private-IP egress denied by default. Timeouts + retries from `ToolPolicy`. Response projected via JsonPath into the tool's output schema.

### B1.9 — `ProfileRegistry` Postgres-backed
Versioned: lookups always select latest non-deprecated unless a specific version is requested. Session start pins a `(profile_id, version)` for the session's lifetime (decision: sticky — design §16 q1).

### B1.10 — Admin REST: profiles, capabilities, tools, auth-bindings, recording-strategies
Implement the endpoints from §9. Validation: tools' `inputSchemaJson` must parse; `HttpToolSpec.urlTemplate` must be a valid URI template; profile must reference only known capability/knowledge/auth IDs.

### B1.11 — `RecorderRouter` + `RecordingStrategy` resolver
Reads `recording_strategies` keyed by `(scope_kind, scope_id)` (PROFILE wins over TENANT). Resolves to a concrete recorder instance at session start; cached for the session's lifetime.

### B1.12 — HTTP egress allowlist + SSRF guard
A `@Component HttpEgressPolicy` invoked by `HttpToolDispatcher` before every outbound call. Tested with WireMock + integration tests that assert private-IP / loopback / link-local denial.

## Flutter deliverables (`yayaagenticweb`)

### F1.1 — Operator auth
Login screen. Backend issues an operator JWT (admin scope). Dio interceptor attaches `Authorization: Bearer …`; refresh-on-401 handled at the interceptor.

### F1.2 — Profiles screen (list + edit + version)
- List view: all profiles for the active tenant, with version + status.
- Edit form: `displayName`, `introOneLiner`, `systemPromptFragment`, multi-select of capabilities + knowledge sources + auth binding.
- "Save as new version" button (immutable versioning).
- "Deprecate" button.

### F1.3 — Capabilities screen
List + create + edit. Form fields mirror the `Capability` record. Tool multi-select.

### F1.4 — Tools screen — Bean vs HTTP
Form switches between two modes based on a radio:
- **Bean**: select `beanName` from a dropdown populated from `GET /v1/admin/tools/available-beans` (backend exposes scanned bean names of beans implementing `ToolHandler<?,?>`).
- **HTTP**: full `HttpToolSpec` editor — method, URL template, header templates (key/value list), body template (JSON editor), response projection (JsonPath), auth forwarding (radio: NONE / PRINCIPAL_TOKEN / SERVICE_TOKEN with an explicit warning on PRINCIPAL_TOKEN).

JSON Schema editors for `inputSchemaJson` / `outputSchemaJson` use Monaco-style code editor (use `code_text_field` package).

### F1.5 — Knowledge sources screen — DTO only
Form lets you create a knowledge source descriptor (path/url/s3/git/inline). Ingestion + retrieval policy fields. **No "reindex" button yet** — that lands in M2.5. The list shows status `unindexed` for everything created here.

### F1.6 — Auth bindings screen
Form: pick an authenticator implementation + ordered list of authorizers. Validates the combination against `GET /v1/admin/auth/available`.

### F1.7 — Recording strategy screen
Form per scope (TENANT or PROFILE). Picks one of the four sealed variants:
- **Single** → pick a recorder bean.
- **FanOut** → primary + ordered list of sinks (sinks list is empty in M1; UI shows "no sinks registered yet, primary writes will go through outbox stub").
- **Tiered** → hot/cold + duration picker.
- **Classified** → classifier expression + tier map.

### F1.8 — Audit (AuthZ decisions) screen — read-only
Searchable, filterable table of `audit_authz` rows. Click a row to see `policy_trace_json`. Important for verifying AuthZ behavior during M1 testing.

### F1.9 — Playground (M0 carryover) — now resolves real profiles
Profile picker pulls live from the backend. Principal impersonation: an operator can choose claims for the test session (subject, scopes, custom claims) — backend issues a short-lived test JWT bound to that impersonation, audited as such.

## Acceptance criteria

- [ ] All §9 admin endpoints respond with proper error codes (400 on schema violation, 409 on version conflict, 200 on success).
- [ ] Creating a profile in the Flutter UI persists it, and starting a playground session against it works end-to-end.
- [ ] An `OwnershipAuthorizer` denial in the playground produces a Deny audit row with distinct `userSafeReason` / `auditReason`, and the agent's response paraphrases only the user-safe one.
- [ ] An HTTP tool against `http://127.0.0.1/anything` is rejected by the egress allowlist; one against an allowlisted host succeeds; both produce trace + audit entries.
- [ ] Every recorder write goes through the outbox; killing the dispatcher mid-write doesn't lose data — replay-on-restart picks it up.
- [ ] Smoke tests + integration tests are green; new tests cover Postgres recorder (Testcontainers), egress allowlist, and AuthZ chain composition.

## Risks & open questions

- **OPA in M1 vs M5** — design §16 q5 leans toward attribute-based for v1, OPA later. M1 ships the OPA adapter behind the SPI but stubbed; revisit in M3.
- **JSON Schema validator choice** — `networknt/json-schema-validator` is the lean pick; document the version and tolerated schema versions.
- **Bean tool discovery** — auto-scanning all `ToolHandler<?,?>` beans for the admin dropdown is convenient but leaks implementation classes; gate behind an admin scope.

## Exit checklist

- [ ] §9 admin API fully wired (minus knowledge `reindex` and session admin ops, which land in M2.5 / M3).
- [ ] Flutter admin can fully configure a profile + tool + auth binding + recording strategy and run a session against it.
- [ ] Schema migrations applied cleanly; rollback rehearsed once.
- [ ] M2 unblocked.
