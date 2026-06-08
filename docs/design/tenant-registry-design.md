# Tenant Registry, Configuration & Profile Cloning — Design (v1)

> Companion to `yaya-agentic-design.md` §5.2 (Profile), §5.3 (Capability & Tool),
> §5.4 (Authentication), §5.8 (Knowledge), §5.9 (Recording), §8 (Data model),
> §9 (Admin API).
>
> **Supersedes** the absolute-URL leniency in
> `tool-url-resolution-design.md` §4 (table row "absolute URL — used verbatim").
> HTTP tools become **path-only**; the host is *always* the tenant's. The rest
> of `tool-url-resolution-design.md` (per-request allowlist, concat semantics,
> egress policy) stays intact and integrates here.

| | |
|---|---|
| **Status** | 🟡 Proposed |
| **Scope** | Promote the tenant from an implicit row to a **first-class configuration aggregate**: explicit registration, lifecycle, a single base URL that governs both outbound HTTP tools and inbound request origins, and a cross-tenant **profile-clone** operation that copies a profile plus all its transitive dependencies into a destination tenant while rewriting host-bound references. |
| **Out of scope (v1)** | Knowledge *document* corpus migration (we copy the source definition, the destination re-ingests). Tenant deletion of historical recorded sessions (handled by M5 RTBF). Operator role/permission model on the new endpoints — the existing M5 operator-auth shape applies. |

---

## 1. The problem in one paragraph

Today a tenant is a four-column row that gets `INSERT ... ON CONFLICT DO NOTHING` the first time an admin endpoint mentions its ID. That worked for the M1 skeleton; it doesn't work as a real platform feature. We need three coupled things that all live on the tenant: (1) **explicit registration** with required configuration validated up front (so an operator can't accidentally route a tool against an unregistered destination), (2) **a single host base URL** that is both the resolved base for outbound HTTP tools *and* the trust root for which inbound origins we accept for that tenant — outbound and inbound trust become symmetric and operator-controlled in one place, and (3) **a profile-clone operation** that takes a working profile in tenant A and lands it in tenant B with every transitive dependency (capabilities, tools, knowledge sources, auth bindings, recording strategies) repointed at tenant B — possible only because tools no longer carry absolute hosts.

---

## 2. The trust principle (read first)

> The tenant is the single trust root for everything the platform does on behalf of that customer: which **inbound** origins can speak to us, which **outbound** hosts our tools may dispatch to, which **authenticator** issues the principal, and which **recorder** owns the conversation. These are not four independent knobs — they're four projections of the same operator-configured tenant row.

Three corollaries flow from this:

- **HTTP tools cannot specify their own host.** A tool descriptor declares *what* operation it performs (`/v1/orders/{id}` — GET → orders); the tenant declares *where* (`https://api.acme.com`). Mixing the two reintroduces the per-tenant fan-out and the SSRF / token-leak surface called out in `tool-url-resolution-design.md` §2.
- **Inbound origin allowlist piggybacks on the same source of truth.** The host the operator told us speaks for tenant `acme` is also (by default) the only origin whose browsers may open sessions on tenant `acme`. Operators may widen this — but explicitly, on the same tenant row.
- **A profile is cloneable across tenants exactly to the extent that its dependencies don't pin a host.** Once that's true (this design's central tightening), `cloneProfile(A → B)` reduces to a deterministic id-rewrite — no operator URL-rewriting step, no manual reconciliation.

---

## 3. The tenant aggregate

```
tenants row  (after this design)
├── id                                  acme                                 [PK, slug]
├── display_name                        ACME Robotics
├── status                              ACTIVE | SUSPENDED | ARCHIVED
├── host_base_url                       https://api.acme.com                 [REQUIRED for HTTP tools]
├── host_base_url_allowlist             ["https://*.acme.com"]               [opt-in per-request override]
├── inbound_origin_allowlist            ["https://app.acme.com",             [browser Origin/Referer
│                                        "https://admin.acme.com"]            check for /v1/sessions/*]
├── require_https                       true
├── default_authenticator_binding_id    acme-oidc                            [FK → auth_bindings]
├── default_recording_strategy_id       acme-tier                            [FK → recording_strategies]
├── settings_json                       { feature flags, free-form }
├── created_at, updated_at, created_by, archived_at
└── (existing joins unchanged — auth_bindings, recording_strategies)
```

Reading the row gives the **complete trust bundle**: who can talk to us *for* this tenant (inbound), where we'll talk *on behalf of* this tenant (outbound), which `Authenticator` mints the principal, which `Recorder` owns the transcript. They can't drift from each other because they're literally one row.

### 3.1 Field rules

| Field | Required | Validation |
|---|---|---|
| `id` | yes | `^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$` (URL-safe slug, immutable) |
| `display_name` | yes | 1..255 chars, free text |
| `status` | yes | `ACTIVE` on create; transitions via dedicated endpoints (`POST .../suspend`, `POST .../archive`) |
| `host_base_url` | yes for new tenants | `http(s)://host[:port][/path]`; no query, no fragment, no control chars; `https://` if `require_https=true` |
| `host_base_url_allowlist` | no | array of glob patterns (`*` only, not regex); see `tool-url-resolution-design.md` §5 |
| `inbound_origin_allowlist` | no | array of glob patterns; empty array means "host_base_url's origin only" (see §5) |
| `require_https` | yes | default `true`; flipping to `false` requires explicit operator confirmation |
| `default_authenticator_binding_id` | yes | must reference an existing `auth_bindings(tenant_id=this.id, id=…)` row |
| `default_recording_strategy_id` | no | if set, must reference a `recording_strategies` row scoped TENANT |

Empty `host_base_url` is permitted **only on archived tenants** (so historical rows don't fail validation after a schema migration). For an `ACTIVE` tenant it is hard-required, because the alternative is silent fall-through and the failure mode is "tools dispatch to nowhere with no audit trail."

### 3.2 Schema migration (V9)

```sql
-- V9__tenants_registry.sql
ALTER TABLE tenants
    ADD COLUMN status                              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN host_base_url                       TEXT,
    ADD COLUMN host_base_url_allowlist             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN inbound_origin_allowlist            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN require_https                       BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN default_authenticator_binding_id    VARCHAR(128),
    ADD COLUMN default_recording_strategy_id       BIGINT,
    ADD COLUMN updated_at                          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ADD COLUMN created_by                          VARCHAR(255),
    ADD COLUMN archived_at                         TIMESTAMPTZ,
    ADD CONSTRAINT tenants_status_chk
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    ADD CONSTRAINT tenants_default_auth_fk
        FOREIGN KEY (id, default_authenticator_binding_id)
            REFERENCES auth_bindings(tenant_id, id);
```

Note: V9 supersedes the V8 sketch in `tool-url-resolution-design.md` §3 — same columns, broader scope, single migration.

Backfill rule for the implicit `default` tenant the M1 admin auto-created: status `ACTIVE`, `host_base_url = NULL` (will be flagged as misconfigured on first GET; operator must complete it before any HTTP tool dispatch can succeed). We intentionally do *not* fabricate a localhost default — silent defaults hide misconfiguration as failed dispatches.

---

## 4. Tenant registration & lifecycle

The implicit `ensureTenant` path in `AdminController` is **removed**. Tenants are created exclusively via the registration endpoint. Any admin write that references a non-existent tenant fails with `unknown_tenant` rather than auto-creating.

### 4.1 Endpoints (admin)

```
POST   /v1/admin/tenants
       body: { id, displayName, hostBaseUrl, requireHttps?,
               hostBaseUrlAllowlist?, inboundOriginAllowlist?,
               defaultAuthenticatorBindingId, defaultRecordingStrategyId? }
       201 → tenant; 409 if id taken; 400 on validation

GET    /v1/admin/tenants
GET    /v1/admin/tenants/{id}

PUT    /v1/admin/tenants/{id}                     full replace (excluding id, status)
PATCH  /v1/admin/tenants/{id}                     partial update (per-field)

POST   /v1/admin/tenants/{id}/suspend             → status=SUSPENDED
                                                    sessions in-flight finish;
                                                    new /v1/sessions denied
POST   /v1/admin/tenants/{id}/resume              → status=ACTIVE

POST   /v1/admin/tenants/{id}/archive             → status=ARCHIVED;
                                                    all reads still permitted for audit;
                                                    no writes accepted; tool dispatch refuses

GET    /v1/admin/tenants/{id}/health              { hostBaseUrlSet, authBindingResolves,
                                                    recordingStrategyResolves, dependencyCount }
```

`PUT /v1/admin/tenants/{id}/host-routing` from `tool-url-resolution-design.md` §10 is collapsed into the full `PATCH /v1/admin/tenants/{id}` here — operators only need to learn one URL.

### 4.2 Validation pipeline at save time

1. Slug shape (regex above).
2. URL grammar — `http(s)://host[:port][/path]`, no `?`, no `#`, no control chars. (Same as the existing v2 rule.)
3. `require_https=true` ⇒ all URLs in `host_base_url`, `host_base_url_allowlist`, and `inbound_origin_allowlist` must be `https://`.
4. Referenced `defaultAuthenticatorBindingId` exists.
5. Referenced `defaultRecordingStrategyId` exists (when set) and is scoped TENANT.
6. The tenant cannot be its own dependency (no self-loops in `default_*` fields after a future "linked tenants" feature lands).

Errors are reported with stable codes (`bad_host_base_url`, `unknown_auth_binding`, …) — the Flutter form binds field-level errors against those codes.

---

## 5. Inbound origin enforcement

Today there is no per-tenant CORS / origin enforcement on `SessionController` — `/v1/sessions/*` accepts any origin. With the tenant now owning a host base, the symmetric inbound check becomes cheap.

```
On every /v1/sessions/* and SSE connect:
  1. Resolve tenant from the authenticated principal.
  2. Read tenant.inbound_origin_allowlist.
  3. If empty           → permit Origin == origin-of(tenant.host_base_url).
  4. If non-empty       → permit Origin matching any glob in the list.
  5. Mismatch           → 403  origin_not_permitted   (audit row carries
                            { tenant, principal, origin, expected })
```

This is **not** a substitute for the existing `Authenticator` / `Authorizer` chain — it's a coarse first-line filter that closes the "a third-party hosted page tries to drive an authenticated session in another tab" attack class. It also makes the operator's mental model coherent: "I told yaya-agentic my customer lives at `app.acme.com`, and that's both who can talk to it *and* the only thing it'll dispatch tools at by default."

`require_https=true` also forces the inbound channel to be HTTPS (via the existing Spring TLS terminator config — this design doesn't introduce a new mechanism, just a per-tenant assertion that breaks the build if a tenant is configured otherwise).

---

## 6. HTTP tools are path-only

This is the user-requested tightening of `tool-url-resolution-design.md` §4.

### 6.1 The new rule

```
HttpToolSpec.urlTemplate (post-substitution):

  /v1/orders/123                ← ACCEPTED  (path, resolved against tenant.host_base_url)
  https://api.acme.com/orders   ← REJECTED  (absolute URL not permitted on HTTP tools)
  //example.com/x               ← REJECTED  (protocol-relative)
  file:///etc/passwd            ← REJECTED  (non-http scheme)
  orders/123                    ← REJECTED  (ambiguous — must start with /)
  <empty after substitution>    ← REJECTED  (caller passed empty path var)
```

Save-time validation in `AdminController` rejects any non-path `urlTemplate` for a new or updated tool with status code `400 absolute_url_not_permitted`. Existing tools whose URLs are absolute are flagged on the **tenant health endpoint** as `dependency_misconfigured` and refuse to dispatch (failing with a clear "tool migrated to path-only — please re-save against tenant host" message) — they are NOT silently rewritten.

A one-shot migration helper is provided (§9.2) that detects absolute URLs whose origin matches the tenant's `host_base_url` and rewrites them to path form in place, bumping the tool's version. Operator-confirmed, never automatic.

### 6.2 Why path-only and not "path or absolute"

`tool-url-resolution-design.md` v2 was deliberately lenient — let absolute URLs work to ease incremental adoption. The shape we want for v1-real is stricter because:

1. **A tool descriptor with `https://api.acme.com/...` baked in is a tenant-shaped piece of config wearing a tool-shaped costume.** It can't be cloned to tenant `beta` without an edit. Path-only erases the bug.
2. **Operators reading the registry can't tell at a glance whether `tools[37]` is reusable.** "Is this descriptor portable, or is it secretly hard-wired to ACME?" path-only makes portability the default.
3. **Audit interpretation is simpler.** Every dispatched URL is a deterministic function of `(tool.urlTemplate, tenant.host_base_url, optional header)`. With absolute URLs mixed in, you have a "where did this host come from?" question per audit row.
4. **The cost of the tightening is bounded** — the only existing fixture using absolute URLs is the M0 retail demo. Migration is a couple of rows.

### 6.3 What stays unchanged from `tool-url-resolution-design.md`

- Per-tenant `host_base_url_allowlist` for opt-in per-request overrides via `X-Yaya-Host-Base-Url` (§5).
- Concat semantics (`trimTrailing(base, '/') + '/' + trimLeading(path, '/')`) (§7).
- `HttpEgressPolicy` check on the resolved absolute URL (§11).
- `pinBaseToTenantDefault` flag on `HttpToolSpec` for privileged tools that should ignore the override even when allowlisted (§10).
- Audit columns `resolved_base` + `base_source ∈ {tenant-default, header}` on tool-call rows (§15 B7.8).

The `BaseUrlResolver` from §10 of that doc still exists; it now has **one fewer branch** (the "URL is already absolute → return as-is" early-return is removed, because we've forbidden that input shape).

#### 6.3.1 Multi-environment as a first-class supported flow

The header + allowlist mechanism is *the* answer for environments. A tenant with prod + staging + EU looks like:

```
tenants[acme]:
  host_base_url:           https://api.acme.com           ← prod, the default
  host_base_url_allowlist: [
    "https://staging.api.acme.com",
    "https://eu.api.acme.com"
  ]
```

Clients (the testing playground, regional gateways, a staging deploy of the host app) pass `X-Yaya-Host-Base-Url: https://staging.api.acme.com` and the dispatcher routes there. Auth + AuthZ stay pinned to the tenant row — the *header* never introduces a new trust root, it only chooses among the bases the operator already trusted by listing them. This is decision §11.2: we don't fragment a tenant into N rows per environment; one tenant row owns N approved bases.

---

## 7. Cross-tenant profile cloning

The headline new operation. One operator action: *"Take profile `support-agent@v3` from tenant `acme` and land it in tenant `acme-eu`."* The platform handles the transitive dependency walk and the id-rewrite.

### 7.1 The dependency closure

Given a source profile `P @ tenant=A`:

```
P
├── capabilities[]                              [each capability is its own version-pinned row]
│   └── for each capability C
│       └── tool_ids[]                          [each tool resolved to its current ACTIVE version]
│           └── tool T (BEAN handler → leaf;
│                       HTTP handler → no host to rewrite by construction)
│
├── knowledge_bindings                          [profile_knowledge_bindings]
│   └── for each binding → knowledge_source K
│       └── ingestion config + location         [location MAY embed tenant-specific identifiers]
│
├── auth_binding_id                             [auth_bindings(tenant=A, id=…)]
│   └── authenticator_ref + authorizer_chain
│
├── recording_strategy                          [recording_strategies(scope=PROFILE, scope_id=P.id)]
│   └── may inherit from tenant strategy
│
└── personality                                 [personality_fragments scoped to tenant A]
                                                  cloned alongside the profile when the
                                                  destination doesn't already have fragments
                                                  for that locale (§7.8)
```

Closure semantics: we walk *referenced* rows transitively, **collect a plan**, and apply it as a single transaction at the destination.

### 7.2 The clone endpoint

```
POST /v1/admin/tenants/{src}/profiles/{profileId}@{version}/clone
body:
  {
    "destinationTenant": "acme-eu",
    "destinationProfileId": "support-agent",    // optional; default = same id
    "conflictPolicy": "FAIL",                   // FAIL | SKIP | NEW_VERSION  (per-resource)
    "knowledgeLocationStrategy": "RETAIN",      // RETAIN | TEMPLATE | OMIT
    "personalityPolicy": "AUTO",                // AUTO | ALWAYS | NEVER       (§7.8)
    "dryRun": true                              // 200 with plan; nothing written
  }
```

#### Response (dryRun)

```json
{
  "destinationTenant": "acme-eu",
  "plan": {
    "profile":        { "action": "CREATE_NEW_VERSION", "fromVersion": 3, "toVersion": 1 },
    "capabilities":   [
      { "id": "answer-faq",       "action": "CREATE_NEW_VERSION", "toVersion": 1 },
      { "id": "lookup-order",     "action": "CREATE_NEW_VERSION", "toVersion": 1 }
    ],
    "tools":          [
      { "id": "list-orders",      "action": "CREATE_NEW_VERSION", "toVersion": 1,
        "urlTemplate": "/v1/orders?customerId={customerId}",     // unchanged — path-only
        "notes": [] },
      { "id": "fetch-faq",        "action": "CREATE_NEW_VERSION", "toVersion": 1 }
    ],
    "knowledgeSources": [
      { "id": "support-kb",       "action": "CREATE_NEW_VERSION",
        "location": { "kind": "S3Prefix", "uri": "s3://acme-kb/tenant=acme/..." },
        "notes": ["LOCATION_REFERENCES_SOURCE_TENANT — operator must edit before ingestion"] }
    ],
    "authBindings":   [
      { "id": "oidc-default",     "action": "REUSE_EXISTING",
        "notes": ["destination tenant already has 'oidc-default'; profile will reference it as-is"] }
    ],
    "recordingStrategies": [
      { "scopeKind": "PROFILE", "action": "CREATE",
        "notes": ["strategyJson referenced an S3 bucket via {tenant} template — substituted to 'acme-eu'"] }
    ],
    "personality":    { "action": "SKIP_DEFAULT_INHERITS" }
  },
  "warnings": [
    "knowledgeSource support-kb retains a location string scoped to tenant acme — re-ingest after editing"
  ]
}
```

#### Apply (`dryRun: false`)

The same plan is executed in a single DB transaction. Failure of any resource rolls back the entire clone. Atomicity matters because partial clones leave a destination tenant in a state the operator didn't ask for and didn't see in the dry-run.

### 7.3 Rewriting rules

| Resource | Rewrite | Why |
|---|---|---|
| Profile (`tenant_id`, `version`) | `tenant_id = dest`, `version = nextVersion(dest, profileId)` | Versioning is per-tenant per-id; the destination's version stream is independent. |
| Capability | same — versioned anew under the destination | Capability rows are tenant-scoped today (`capabilities.tenant_id`). |
| Tool — BEAN handler | re-saved verbatim; `handler_bean_name` is a code reference, tenant-independent | The Spring bean is platform-level, not per-tenant. |
| Tool — HTTP handler | re-saved verbatim — including `urlTemplate` (a **path**) | Path-only rule means no host appears in the descriptor; the destination tenant's `host_base_url` resolves automatically at dispatch time. **This is the whole point of §6.** |
| Knowledge source | `tenant_id = dest`; `location` handled per `knowledgeLocationStrategy` (§7.5) | Locations often embed tenant identifiers. |
| Auth binding | per `conflictPolicy`: REUSE if destination already has a binding with the same id; CREATE_NEW if not | A clone shouldn't silently replace destination's `Authenticator`. |
| Recording strategy (scope=PROFILE) | `tenant_id = dest`, `scope_id = destinationProfileId`, `strategyJson` templated for `{tenant}` if found | Strategies often carry tenant-specific S3 prefixes. |
| Personality fragment | `personalityPolicy` (see §7.8) | Tenant-scoped voice/rules; safe to clone but never silently overwrite the destination's existing voice. |

### 7.4 The `conflictPolicy`

What happens when a resource with the same id already exists at the destination:

| Value | Behavior |
|---|---|
| `FAIL` (default) | Abort the entire clone with `409 destination_resource_exists` + the offending id. Operator must resolve manually. |
| `SKIP` | Reuse the destination's existing version (do not write); record `action: REUSE_EXISTING` in the plan. |
| `NEW_VERSION` | Treat the source row as the new version; destination keeps its old versions, gets a new top version. |

`FAIL` is the default because cloning is a write that operators rarely intend to be destructive. The Flutter UI's wizard forces an explicit per-resource decision on collisions detected in the dry-run.

### 7.5 The `knowledgeLocationStrategy`

Locations are the one place tenant-specific strings legitimately hide in non-tenant fields. Three strategies:

- `RETAIN` (default) — copy the location string verbatim. Most accurate, almost certainly needs an operator edit before re-ingestion. Plan emits a warning.
- `TEMPLATE` — best-effort substitute the source tenant id with the destination's in any `S3Prefix.uri`, `HttpUrl`, `GitRepo.path`, `LocalPath`. Helpful when operators have established the convention; safe to over-emit since the operator confirms the plan.
- `OMIT` — clone the knowledge source row but null out the location, forcing the operator to fill it in before re-ingestion. Best for high-sensitivity sources where automatic templating would be footgun-y.

The destination tenant **re-ingests** the source. We do not copy `knowledge_documents` or `knowledge_chunks` rows — they're derived data tied to embeddings produced under the source tenant's egress / API keys. Re-ingest under destination keeps the chunks' provenance honest.

### 7.6 Versioning at the destination

Every cloned resource lands as a **new version** under the destination tenant. Specifically: `version = max(existingDestinationVersions) + 1`, regardless of the source version. This makes the destination's version stream a record of its own promotions, not a leaked artifact of the source's history. The plan's `fromVersion`/`toVersion` columns make the lineage auditable.

### 7.7 Audit

```
INSERT INTO tenant_clone_jobs
    (id, source_tenant, destination_tenant, source_profile_id, source_version,
     plan_json, applied_at, applied_by, status, error_json)
```

Every clone — dryRun or applied — gets a `tenant_clone_jobs` row. `plan_json` captures the *resolved* dry-run plan. Dry-runs are kept for 30 days; applied jobs are kept for the life of the tenant (they're cheap and load-bearing for forensics). Each individual write inside an applied clone produces its existing `audit_admin_writes` row, with a `clone_job_id` field pointing back to the parent.

### 7.8 Personality cloning

Personality fragments live at `personality_fragments(tenant_id, locale, version)` — they're tenant-scoped, not profile-scoped, so cloning is conceptually "make sure the destination has the voice this profile was authored against."

`personalityPolicy` controls the behavior:

| Value | Behavior |
|---|---|
| `AUTO` (default) | For each locale the source tenant has fragments in: if the destination already has fragments for that locale, **REUSE** them (don't overwrite — destination's brand voice wins). If the destination has none, **CREATE** by cloning the source's latest active version. |
| `ALWAYS` | Always create a new version at the destination from the source, regardless of whether destination already has personality. The destination's prior fragments stay (versioning) but the cloned one becomes the latest. Operator opts into this only when "make destination identical to source" is the goal. |
| `NEVER` | Skip personality entirely; destination must already have a usable fragment (validated at clone time — `400 destination_missing_personality` if not). |

The dry-run plan surfaces the per-locale action so operators see exactly which voice the destination will speak with after the clone. The default (`AUTO`) is conservative: it never overwrites an existing voice, only fills in gaps.

---

## 8. Security ledger

| Concern | Mitigation |
|---|---|
| **Operator clones a profile from a tenant they shouldn't see.** | The clone endpoint requires `tenant-admin` on **both** source and destination. M5 operator-role check; the existing per-endpoint authorization decorator runs twice. |
| **Clone leaks tenant-specific credentials via cloned auth bindings.** | Auth bindings carry references (`authenticator_ref`, JWKS URL, etc.), not secrets. Secret material lives in a separate `tenant_secrets` table keyed by `(tenant, ref)` and is **never** copied. The cloned binding at the destination references the *destination's* secret entry; if missing, dispatch fails with `unknown_secret` until the operator provisions it. |
| **Knowledge source location secretly references source tenant's S3 prefix.** | Handled explicitly by `knowledgeLocationStrategy`. The default `RETAIN` emits a plan-level warning; the wizard surfaces every warning before applying. |
| **Cloning bypasses the destination tenant's authorizer chain.** | Clone writes profile/capability/tool rows. Those still pass through the existing `Authorizer` chain at *dispatch* time inside the destination tenant. The clone doesn't grant any runtime authority. |
| **Path-only tightening breaks an existing operator workflow they had grandfathered in.** | Health-endpoint surface for "absolute URL still present" with the migration helper (§9.2). No silent dispatch failure — clear operator-facing error message naming the offending tool. |
| **Inbound origin allowlist defaults too loose.** | Default is **`host_base_url`'s origin only**. Widening requires an explicit operator action. The classic mistake (allowlist defaults to `*` for first-five-minutes ergonomics) is avoided. |
| **A clone with `conflictPolicy=NEW_VERSION` overwrites the destination's currently-promoted profile by accident.** | New versions are promoted explicitly via M5's `POST /v1/admin/profiles/{id}/{version}/promote`. A new version from a clone arrives as `status=ACTIVE` but not promoted — in-flight sessions stay on the prior version per design §16 q1. |
| **Tenant `id` collision between a deleted-then-recreated tenant.** | Tenants are never hard-deleted by this design — `archive` is final and the row stays for audit. Recreation under the same id is rejected with `409 tenant_archived`. |

---

## 9. Code touch points

### 9.1 Backend

| File | Change |
|---|---|
| `persistence/TenantEntity.java` | Add the new columns; expose typed `status` enum and `Origin` value type for the allowlists. |
| `persistence/TenantRepository.java` | Add `findActiveById`, `findAllByStatus`. |
| `api/AdminController.java` | Remove `ensureTenant`; add `/v1/admin/tenants/*` endpoints; reject writes against unknown tenants with `unknown_tenant`. |
| `api/TenantController.java` (NEW) | If `AdminController` is getting unwieldy, split tenant endpoints out — same conventions as the rest. |
| `api/SessionController.java` | Hook the origin-allowlist check between Authenticator and engine dispatch. |
| `tool/HttpToolSpec.java` | No schema change; Javadoc updated — "absolute URLs no longer accepted." |
| `tool/dispatch/HttpToolDispatcher.java` | Removes the "URL is already absolute → pass through" branch. Save-time validation now also rejects absolute on writes (re-uses `BaseUrlValidator`). |
| `tool/dispatch/BaseUrlResolver.java` | Unchanged from `tool-url-resolution-design.md`. |
| `tool/migration/AbsoluteToPathMigrator.java` (NEW) | One-shot helper invoked by `/v1/admin/tools/migrate-to-path?tenant=…&dryRun=true`. Detects absolute URLs whose origin matches `tenant.host_base_url`, emits a plan, applies on confirm. |
| `tenant/clone/CloneService.java` (NEW) | Orchestrator: walks dependencies, resolves conflicts, builds plan, applies as a single transaction. |
| `tenant/clone/CloneRequest.java`, `ClonePlan.java`, `CloneResult.java` (NEW) | DTOs. |
| `persistence/TenantCloneJobEntity.java` (NEW) + V10 migration | Audit table for clone runs. |
| `engine/bootstrap/M0Catalog.java` | Drop the implicit `default` tenant assumption — read from `tenants` like everything else. |

### 9.2 Migration helper

```
POST /v1/admin/tools/migrate-to-path?tenant=acme&dryRun=true
→ {
    "candidates": [
      { "toolId": "list-orders-acme", "version": 4,
        "current": "https://api.acme.com/v1/orders",
        "rewritten": "/v1/orders",
        "matchedTenantHost": true }
    ],
    "unsafe": [
      { "toolId": "list-orders-cross", "version": 2,
        "current": "https://api.beta.io/v1/orders",
        "reason": "ORIGIN_NOT_TENANT_HOST — refusing to rewrite" }
    ]
  }
```

`dryRun=false` writes new versions for everything in `candidates`; `unsafe` entries are never touched and must be resolved manually (typically by re-platforming them onto the *correct* tenant, or — rarely — by adding the host to that tenant's `host_base_url_allowlist`).

### 9.3 Flutter (`yayaagenticweb`)

| Screen | Change |
|---|---|
| **Tenants list** | Becomes the platform's home screen for operators with multi-tenant access. Status column, host base URL, health badge. |
| **Tenant detail / create wizard** | Two-step: identity (id, name) → routing (`host_base_url`, allowlists, default bindings). Live validation against the API. |
| **Tools form** | URL field: helper text *"Path only (`/...`). The host resolves from this tool's tenant."* Inline lint when an operator pastes an absolute URL — single-click "extract path" if origin matches tenant host. |
| **Profile detail** | New action: **"Clone to another tenant…"** → wizard that calls the dry-run endpoint, presents the plan + warnings, lets operator resolve conflicts per resource, then applies. |
| **Tenant health card** | Pulls `/v1/admin/tenants/{id}/health` — green dot per dependency, red dot with copy-rewrite link to the migration helper for absolute-URL tools. |

---

## 10. End-to-end: clone `support-agent` from `acme` to `acme-eu`

### Pre-state

```
tenant acme:                                    tenant acme-eu:
  host_base_url: https://api.acme.com             host_base_url: https://eu.api.acme.com
  profiles:                                       profiles:
    support-agent @ v3                              (none)
  capabilities:                                   capabilities:
    answer-faq @ v2, lookup-order @ v5              (none)
  tools:                                          tools:
    list-orders @ v4   path: /v1/orders/{id}        (none)
    fetch-faq    @ v2  path: /v1/faq?q={q}        auth bindings:
  auth bindings:                                    oidc-default
    oidc-default
```

### Operator action

```
POST /v1/admin/tenants/acme/profiles/support-agent@3/clone
{
  "destinationTenant": "acme-eu",
  "conflictPolicy": "FAIL",
  "knowledgeLocationStrategy": "TEMPLATE",
  "dryRun": false
}
```

### Post-state

```
tenant acme-eu:
  profiles:
    support-agent @ v1            (cloned from acme/support-agent@v3)
  capabilities:
    answer-faq @ v1, lookup-order @ v1
  tools:
    list-orders @ v1   path: /v1/orders/{id}            ← VERBATIM — no host to rewrite
    fetch-faq    @ v1  path: /v1/faq?q={q}              ← VERBATIM — no host to rewrite
  auth bindings:
    oidc-default                                        ← REUSE_EXISTING (was already there)
```

A user opening a session on `acme-eu` invokes `list-orders` → dispatcher reads `acme-eu.host_base_url = https://eu.api.acme.com` → final URL `https://eu.api.acme.com/v1/orders/{id}`. Same descriptor as `acme`, dispatched against a different host *automatically* because the host is a tenant concern, not a tool concern.

If `acme-eu` did **not** already have `oidc-default`, the dry-run would have surfaced `action: CREATE` for it — and the operator would be asked to confirm before secrets get re-provisioned at the destination.

---

## 11. Resolved design decisions

The questions raised during review are resolved as follows. Kept here as a decision log; they're load-bearing for the implementation.

1. **Knowledge corpus (`knowledge_documents` / `knowledge_chunks`) is NOT copied.** Cloning copies the source *definition* only; the destination re-ingests under its own egress / API keys. Preserves embedding provenance and avoids surfacing source-tenant chunks under the destination's auth. (§7.5)
2. **Multiple environments per tenant are first-class via `host_base_url_allowlist` + `X-Yaya-Host-Base-Url`** — not via multiple `host_base_url` values. The single `host_base_url` is the tenant's default; the allowlist + header is the *supported* mechanism for prod / staging / regional environments. This is explicitly the flexibility surface, not a "we'd revisit later" placeholder. (§6.3, `tool-url-resolution-design.md` §5)
3. **Cross-tenant *linking* is rejected; cloning is the only mechanism.** Two tenants never share a row in `capabilities` / `tools` / `knowledge_sources`. Every cross-tenant transfer goes through `CloneService`. Keeps tenant isolation an invariant of the data model rather than a runtime check.
4. **Personality is cloneable as a first-class resource** via `personalityPolicy` (§7.8). Default `AUTO` fills gaps in the destination without overwriting an existing voice; `ALWAYS` and `NEVER` cover the explicit-overwrite and skip-entirely paths.
5. **Tenant lifecycle and in-flight session drain semantics** are out of scope of *this* design and tracked separately. The `SUSPENDED` status here means "no new sessions, in-flight finish"; if a `DRAINING` intermediate is needed later, it slots in without schema changes (status check expands to include `DRAINING`). This is unrelated to cloning — every cloned resource at the destination is rooted in the destination's tenant row, never references the source.

---

## 12. Sequencing

Slot as **M2.8 — Tenant Registry & Profile Clone**, immediately after M2.7 (`tool-url-resolution-design.md`) — which is itself gated on §13.1 (verified tenant on `Principal`). Estimated effort: ~3–4 days of backend + 2 days of Flutter.

### Backend deliverables

- **B8.1** V9 migration: tenant columns + constraints + default values.
- **B8.2** `TenantEntity` + repository update; remove implicit `ensureTenant`.
- **B8.3** Tenant CRUD endpoints + lifecycle transitions + health endpoint.
- **B8.4** Path-only validation in `AdminController` for `HttpToolSpec` writes; reject absolute URLs.
- **B8.5** `AbsoluteToPathMigrator` + endpoint.
- **B8.6** Inbound origin allowlist check on `SessionController`.
- **B8.7** `CloneService` + plan / apply / dryRun.
- **B8.8** V10 migration for `tenant_clone_jobs`.
- **B8.9** Cross-tenant denial integration tests against every cloned resource type.

### Flutter deliverables

- **F8.1** Tenants list + detail + create wizard.
- **F8.2** Tenant health card (per-tenant misconfiguration surface).
- **F8.3** Profile clone wizard (dryRun → plan UI → conflict resolution → apply).
- **F8.4** Tools form: path-only helper + "extract path" affordance for legacy absolute URLs.

### Acceptance criteria

- [ ] Creating a tenant with a missing or malformed `host_base_url` is rejected at save time.
- [ ] Saving an HTTP tool with an absolute `urlTemplate` is rejected at save time with `absolute_url_not_permitted`.
- [ ] Existing tools with absolute URLs surface on the tenant's health endpoint and refuse to dispatch with a clear error.
- [ ] `AbsoluteToPathMigrator` rewrites only tools whose absolute origin matches the tenant host; leaves the rest with a `ORIGIN_NOT_TENANT_HOST` note.
- [ ] An inbound request whose `Origin` doesn't match `inbound_origin_allowlist` (or the host_base_url's origin when the list is empty) gets a `403 origin_not_permitted` with an audit row.
- [ ] Cloning `support-agent @ v3` from `acme` to a fresh `acme-eu` produces working profile + capabilities + tools at version 1 with no manual URL editing; a session on `acme-eu` dispatches `list-orders` against `https://eu.api.acme.com/...`.
- [ ] A clone with `conflictPolicy=FAIL` against a destination that already has one of the resources is rejected with `409 destination_resource_exists` and **no** rows written.
- [ ] A clone with `knowledgeLocationStrategy=TEMPLATE` substitutes the source tenant id in `S3Prefix.uri`; the source location is preserved untouched.
- [ ] `tenant_clone_jobs` carries the full plan and an `applied_by` value for every applied clone; dry-runs are recorded with `status=DRY_RUN` and auto-purged after 30 days.
- [ ] Two cloned profiles in destinations `acme-eu` and `acme-apac` resolve to their respective tenant `host_base_url`s; cross-tenant principal cannot dispatch the wrong host.

### Risks

| Risk | Mitigation |
|---|---|
| **The path-only tightening breaks demos / fixtures during the M2.7 → M2.8 transition.** | The two milestones ship sequentially with the migration helper in between; fixtures get rewritten in the same PR. |
| **Operators are surprised by the explicit-registration requirement (no more auto-create).** | Migration note in release docs; the M2.8 admin home screen prompts to complete the `default` tenant's `host_base_url` on first load. |
| **`CloneService` becomes a hot spot for "small additions" — copying personality, copying historical sessions, copying audit.** | Single clone surface; new opt-ins added as explicit flags on `CloneRequest`, never silent. Personality clone, if added, is a separate endpoint per §11 q4. |
| **Inbound origin enforcement breaks integrations that legitimately speak from a different origin.** | The allowlist is operator-controlled and supports globs; rollout note recommends populating it before flipping enforcement on; first release ships it in *report-only* mode for one full release cycle. |

---

## 13. Summary

- The tenant becomes a first-class registered configuration aggregate — explicit lifecycle, single source of truth for host base URL, inbound origins, default auth, default recorder.
- HTTP tools are **path-only**. The host is *always* the tenant's. This kills the per-customer fan-out, makes cloning safe by construction, and tightens the leniency that `tool-url-resolution-design.md` v2 left open.
- Inbound origin enforcement closes the symmetric trust gap — "who can speak to me for tenant X" lives on the same row as "who do I speak to on behalf of tenant X."
- **Cross-tenant profile clone** walks the transitive dependency graph (profile → capabilities → tools → knowledge → auth → recording), produces a dry-run plan, and applies atomically — the operator confirms warnings (knowledge locations, conflict policies) before any write. Auth secrets are never copied; the destination provisions its own.
- Ships as **M2.8**, right after M2.7. Backend ~3–4d, Flutter ~2d.
