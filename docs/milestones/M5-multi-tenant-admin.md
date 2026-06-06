# Milestone M5 — Multi-Tenant + Admin APIs

| | |
|---|---|
| **Goal** | The platform is multi-tenant by construction. Additional recorder sinks (S3 cold, warehouse stream) ship. The OpenAPI tool importer turns external API specs into tools without code. Redaction / export / replay UX is polished. |
| **Timeline** | Post-v1 (design §17 — M5) |
| **Status** | 🟦 Planned |
| **Depends on** | M4 |
| **Design refs** | §5.9 recorder sinks · §9 admin API · §11 extensibility · §16 q11 OpenAPI importer · §16 q1 profile versioning UX |

## Scope (IN)

- Tenant isolation: every query in every repository is tenant-scoped; integration tests assert cross-tenant denial in the recorder, the retriever, and the audit tables.
- Recorder sinks:
  - `S3ColdRecorder` — append-only JSONL bundles + manifest index.
  - `WarehouseStreamRecorder` — Kafka / Kinesis adapter for analytics ingestion.
  - `TieredRecorder` and `ClassifiedRecorder` fully wired (M1 had the SPI but the strategies were inert without sinks).
- OpenAPI tool importer: upload a spec, get a generated set of `HttpToolSpec`s with sensible defaults.
- Profile versioning UX: diff two versions, promote/demote, sticky-vs-migrate policy per profile.
- GDPR/CCPA tooling: principal export and right-to-be-forgotten across every bound recorder.
- Customer-self-serve admin (operator roles, per-tenant API keys, audit-of-audit).

## Out of scope (NOT here)

- Multi-region replication.
- Customer-facing analytics product.
- Voice / multimodal channels.

## Backend deliverables

### B5.1 — Tenant scoping audit
Every JPA query gets `WHERE tenant_id = :tenant`. Repository tests verify. A Spring `@TenantScoped` aspect intercepts every repository call and asserts the active tenant context is set; missing context is a 500, not a silent leak.

### B5.2 — `S3ColdRecorder`
Sealed session → one JSONL object per session in S3, plus a manifest index keyed by (tenant, principal, profile). Implements `loadSession` over the manifest, `searchSessions` via the manifest only (no full-text). `RecorderCapabilities.durability=EVENTUAL`, `supportsSearch=true` (limited), `supportsRedaction=true` (object rewrite).

### B5.3 — `WarehouseStreamRecorder`
Emits a structured event per turn to Kafka (configurable to Kinesis). Schema versioned in `docs/recorder/warehouse-event-schema.json`. Best-effort with backpressure; survives broker outage via the outbox.

### B5.4 — `TieredRecorder` cutover job
A `@Scheduled` job moves sessions older than `hotWindow` from the hot recorder to the cold sink. Cutover is atomic per session: append to cold, delete from hot, audit.

### B5.5 — `ClassifiedRecorder`
Classifier function wired to a `SessionClassifier` SPI. Reference impl reads a `metadata.sensitivity` tag set at session start.

### B5.6 — OpenAPI tool importer
`POST /v1/admin/tools/import-openapi` accepting a spec. Generates one `ToolDescriptor` per operation:
- Method + URL template from path + operation.
- Body template from request body schema (subset projection if marked).
- Response projection from response schema.
- `PermissionRequirement` derived from `security` scopes.
- `AuthForwarding` defaulted to `NONE`; importer flags the choice for operator review.

Operator confirms each tool before it goes live (no auto-commit).

### B5.7 — Profile versioning UX backend
`GET /v1/admin/profiles/{id}/versions` returns full version list. `GET …/diff?from=&to=` returns a structured diff (system prompt diff, capability set diff, knowledge source set diff, auth binding diff). `POST …/{id}/{version}/promote` makes a version the default for new sessions; in-flight sessions stay sticky per design §16 q1.

### B5.8 — Principal export + RTBF cascade
`POST /v1/admin/principals/{subject}/export`:
- Iterates every bound recorder.
- Streams a NDJSON export of every session the principal authored.
- Includes audit rows.

`DELETE /v1/admin/principals/{subject}`:
- For each bound recorder, calls `delete*` against every session and audit row.
- Records `audit_recorder_ops` per operation, including the operator who triggered it.
- A reconciliation job verifies every sink acked the deletion; sends an alert if any sink didn't.

### B5.9 — Per-tenant operator roles + API keys
Roles: `tenant-admin`, `profile-author`, `auditor`, `support`. Each scoped to specific admin endpoints. API keys issued per role, rotatable.

### B5.10 — Audit-of-audit
Every read of `audit_authz` / `audit_recorder_ops` itself produces a row in `audit_admin_reads`. Sounds paranoid; cheap and useful when investigating incidents.

## Flutter deliverables (`yayaagenticweb`)

### F5.1 — Tenant picker + tenant context everywhere
Top-bar tenant switcher (for operators with multi-tenant access). All admin screens become tenant-aware; cross-tenant data never displayed.

### F5.2 — Recorder sink admin
Bind sinks to a strategy: pick S3 settings, Kafka topic, etc. Live status (last-dispatched-at, outbox depth, error rate).

### F5.3 — OpenAPI importer UX
Upload spec → preview generated tools → per-tool confirmation page (set `AuthForwarding`, edit input/output schemas, set `ToolPolicy`) → commit.

### F5.4 — Profile versions screen
Version list with author, created-at, status. Diff viewer side-by-side. Promote / deprecate buttons. "Migrate in-flight sessions" toggle with a strong warning.

### F5.5 — Compliance ops screen
Per-session: redact (JSONPath selector picker), delete (with reason), archive (policy picker). Per-principal: export and forget. Every action triggers a confirmation modal and writes an audit row.

### F5.6 — Operator roles & API keys management
Standard SaaS admin UX. Per-role permission matrix view. Key rotation flow.

### F5.7 — Tenant onboarding wizard
Step-by-step: create tenant → bind authenticator → bind recording strategy → set personality (or accept default) → import OpenAPI spec → create first profile → run a playground sanity check.

## Acceptance criteria

- [ ] Two tenants in the same DB cannot read each other's sessions, audit rows, or knowledge chunks. Integration tests assert this for every recorder, retriever, and admin endpoint.
- [ ] An S3 sink survives broker outage via the outbox; sessions are eventually consistent in cold storage.
- [ ] A real OpenAPI spec (e.g. a small public API) imports into tools that an operator can confirm and a profile can attach.
- [ ] Promoting a new profile version doesn't break in-flight sessions; new sessions get the new version.
- [ ] A RTBF run against a principal with sessions across 3 sinks reports completion only when all 3 acked deletion; reconciliation alert fires if a sink fails.
- [ ] Per-role API keys can be issued, used, and rotated; a revoked key is denied within 60s of revocation.

## Risks & open questions

- **Cross-tenant test coverage cost** — needs a mini-framework so every new endpoint test gets a free cross-tenant negative case. Worth the investment.
- **OpenAPI spec quality variance** — some specs are vague on scopes / auth. Importer flags low-confidence imports for operator review.
- **Kafka coupling** — keep the recorder SPI agnostic; ship Kafka + Kinesis adapters behind a `MessagingSink` interface so adding new ones is config-only.

## Exit checklist

- [ ] All acceptance criteria green in a real multi-tenant staging environment.
- [ ] At least one external tenant onboarded end-to-end via the Flutter wizard.
- [ ] Compliance ops exercised once against synthetic data; audit trail verified.
- [ ] Post-v1 backlog seeded: A/B framework, voice channel, multi-agent handoff.
