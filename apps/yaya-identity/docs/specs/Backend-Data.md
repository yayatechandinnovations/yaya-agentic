# yaya-identity — Backend Data Model

> **Product:** yaya-identity — the shared multi-tenant identity provider (IdP) + authorization service for the `apps/` ecosystem.
> **Companion to:** [PRD](PRD.md) · [TRD](TRD.md) · [UI-UX](UI-UX.md) · [App-Flow](App-Flow.md).
> **Implements:** [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) — **§14 Data Model** is the primary source; §5 (domain records), §7 (PDP), §11 (security/PII), §13 (milestones I0–I6).
> **Status:** Draft v1 — implementation-ready.
> **Anchors (yaya-agentic):** `CLAUDE.md` non-negotiables **#3** (denials carry two distinct reasons → `audit_log` stores both), **#6** (tokens never silently forwarded → token-exchange is a modeled, audited event). Postgres 16, Flyway, self-hosted (no paid IdP).

This doc is the source of truth for tables, columns, types, indexes, constraints, encryption/hashing rules, the migration sequence (Flyway, `yaya_identity` schema), and the data-integrity invariants. It expands design §14's one-line sketch into full DDL.

---

## 1. Schema-isolation principle (read first)

**Everything yaya-identity owns lives in one Postgres schema: `yaya_identity`.** There are **zero cross-schema foreign keys or joins** to any application schema (`yaya_sales`, the agent's `public`/core tables, any future app). This is the hard guarantee from milestone **I0** (design §13; PRD **FR-7.2**, **AC-41**, success metric **M6**) that lets yaya-identity lift onto a separate Postgres instance with no migration rewrite — mirroring the yaya-sales **S0** schema guard.

Concretely:

- Every migration runs with `SET search_path = yaya_identity` (or fully qualifies every object as `yaya_identity.<table>`).
- **No DDL in a yaya-identity migration may reference an object outside `yaya_identity`** — no `REFERENCES other_schema.x`, no `JOIN other_schema.y`, no `other_schema.` qualifier anywhere.
- Integration to apps is **token + API + events only**. A yaya-identity user does not FK to a yaya-sales `store`; a `store:st_123` scope is stored as opaque `(scope_type, scope_id)` **strings**, never a foreign key. yaya-identity asserts *who a subject is and what role/scope they carry*; the app owns its own domain rows and resolves the scope string against them.

### 1.1 Build-time migration lint (AC-41 / FR-7.2)

A build-failing test — `SchemaIsolationLintTest` (PRD §7.6) — parses every file under `src/main/resources/db/migration/` and **fails the build** if any migration:

1. Names a schema other than `yaya_identity` (regex over `<ident>\.<ident>` where the leading identifier is a known foreign schema, plus a blocklist of `public.`, `yaya_sales.`, `pg_catalog.` writes).
2. Emits a `REFERENCES` clause whose target is schema-qualified to anything but `yaya_identity`.
3. Creates an object without the `yaya_identity` schema being the active `search_path` at that point in the file.

This is the identity analog of the yaya-sales S0 cross-schema guard. It runs in CI on every push; a red lint blocks merge. See §11 for how it slots into the migration convention.

---

## 2. Entity-relationship overview

ASCII sketch of the core aggregates. `─▶` = FK (all **within** `yaya_identity`); `···▶` = logical reference by opaque string (**no FK**, crosses no schema).

```
                          ┌──────────────────────┐
                          │   organizations      │
                          │  id (UUID, PK)       │◀── parent_id (self, nullable; flat in v1)
                          │  slug (UQ) status    │
                          └───────┬──────────────┘
              org_id ─┬──────┬────┴────────┬───────────┬───────────┬─────────────┐
                      ▼      ▼             ▼           ▼           ▼             ▼
              ┌───────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐
              │memberships│ │ groups │ │ roles  │ │policies│ │ clients  │ │audit_log │
              │(user,org) │ │        │ │(org=∅  │ │        │ │          │ │(append-  │
              └─────┬─────┘ └───┬────┘ │=system)│ └────────┘ └────┬─────┘ │  only)   │
                    │           │      └───┬────┘                 │       └──────────┘
       ┌────────────┘           │          │                     │
       ▼                        ▼          ▼                      ▼
 ┌───────────┐          ┌──────────────┐ ┌──────────────────┐ ┌───────────────┐
 │  users    │          │group_members │ │ role_permissions │ │ refresh_tokens│
 │ id (PK)   │◀─────────┤(group,user)  │ │(role,perm_key)   │ │ sessions      │
 │ kind      │          └──────────────┘ └────────┬─────────┘ └───────────────┘
 │ status    │                                    ▼
 └─────┬─────┘                          ┌──────────────────┐   ┌────────────────┐
       │ user_id                        │   permissions    │   │ app_permissions│
       ▼                                │  key (PK)        │◀··│ (app manifest, │
 ┌───────────┐                          │  <app>.<res>:act │   │  declared cat.)│
 │credentials│                          └──────────────────┘   └────────────────┘
 │ secret_ref│
 │(hash/enc) │              ┌─────────────────────────────────────────────┐
 └───────────┘              │            role_assignments                  │
                            │  subject_kind USER|GROUP  + subject_id       │
                            │  role_id ─▶ roles                            │
                            │  scope_kind ORG|RESOURCE                     │
                            │  scope_type, scope_id  ···▶ app resource     │  (opaque strings,
                            └─────────────────────────────────────────────┘   NO cross-schema FK)

   Platform / infra (org-agnostic or infra):
   ┌────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌──────────┐
   │signing_keys│ │idempotency_  │ │      outbox      │ │ (audit_  │
   │(kid, alg,  │ │   keys       │ │(aggregate,event, │ │  log,    │
   │ rotation)  │ │(scope,key)   │ │ payload, status) │ │  above)  │
   └────────────┘ └──────────────┘ └──────────────────┘ └──────────┘
```

**Reading it:** `organizations` is the tenant root; almost everything is org-scoped by an `org_id` column (FR-7.1). `users` are *global* identities joined to orgs via `memberships` (a contractor is one user, many memberships). RBAC is `roles → role_permissions → permissions`, granted through the polymorphic, scoped `role_assignments`. ABAC lives in `policies`. Machine identity and token/session state hang off `clients`, `refresh_tokens`, `sessions`. `signing_keys`, `audit_log`, `outbox`, `idempotency_keys` are the platform substrate laid in I0.

---

## 3. Conventions

- **Schema:** every object is created in `yaya_identity`.
- **PK type:** `UUID` (`gen_random_uuid()` via `pgcrypto`) for entity tables; natural keys where the value *is* the identity (`permissions.key`, `signing_keys.kid`, composite membership PKs). `audit_log`/`outbox` use `BIGSERIAL` (append-only, monotonic).
- **Timestamps:** `TIMESTAMPTZ`, `DEFAULT now()`.
- **Enums:** stored as `VARCHAR` + a `CHECK (col IN (...))` (portable, greppable, no `ALTER TYPE` migrations) — matching house style.
- **JSON:** `JSONB` columns are suffixed `_json` per design §14.
- **Email uniqueness:** `CITEXT` (require `CREATE EXTENSION IF NOT EXISTS citext;`), falling back to a `LOWER()` expression index — same rule as the user-accounts spec.
- **Secrets:** see §9. Passwords → Argon2id hash; TOTP seeds / signing private keys / client secrets → either a hash (irreversible verification) or an AES-256-GCM envelope (reversible where the value must be *used*, e.g. a signing key). **Plaintext secret material is never stored.**

---

## 4. Platform & audit tables (milestone I0)

These land first — the app must boot on its own schema, serve JWKS, and audit before any org exists.

### 4.1 `signing_keys`

```sql
CREATE TABLE yaya_identity.signing_keys (
    kid          VARCHAR(64)  PRIMARY KEY,           -- key id surfaced in JWT header + JWKS
    alg          VARCHAR(16)  NOT NULL DEFAULT 'RS256',
    public_pem   TEXT         NOT NULL,              -- public key, served in JWKS (not secret)
    private_ref  BYTEA        NOT NULL,              -- AES-256-GCM envelope of the private key (§9)
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at   TIMESTAMPTZ,                        -- when it left ACTIVE
    CONSTRAINT signing_keys_alg_chk    CHECK (alg IN ('RS256','RS384','RS512','ES256')),
    CONSTRAINT signing_keys_status_chk CHECK (status IN ('ACTIVE','RETIRING','RETIRED'))
);
CREATE UNIQUE INDEX signing_keys_one_active_uniq
    ON yaya_identity.signing_keys (status) WHERE status = 'ACTIVE';   -- exactly one active signer
CREATE INDEX signing_keys_jwks_idx
    ON yaya_identity.signing_keys (status) WHERE status IN ('ACTIVE','RETIRING');
```

**Rationale.** RS256 is the token signing algorithm (FR-1.3); the `kid` lets a relying party pick the right key from JWKS. **Rotation retention (FR-1.4 / NFR-Avail-3 make-before-break):** a rotated key moves `ACTIVE → RETIRING`, stays in JWKS (the `signing_keys_jwks_idx` partial index feeds the JWKS endpoint) for at least the max access-token TTL, then `→ RETIRED` and drops from JWKS. The partial unique index guarantees **exactly one** signer at a time. `public_pem` is not secret and is served verbatim; `private_ref` is an encrypted envelope, never a raw key.

### 4.2 `audit_log` (append-only)

```sql
CREATE TABLE yaya_identity.audit_log (
    id            BIGSERIAL    PRIMARY KEY,
    org_id        UUID,                              -- nullable: platform-level events have no org
    actor         VARCHAR(255),                      -- who performed it (sub / client_id / 'platform')
    event_type    VARCHAR(64)  NOT NULL,             -- see §4.2.1
    subject       VARCHAR(255),                      -- decision subject (sub) for PDP rows
    action        VARCHAR(128),                      -- permission key for PDP rows, e.g. sales.order:refund
    resource      VARCHAR(255),                      -- <type>:<id> for PDP rows (opaque; no FK)
    decision      VARCHAR(16),                        -- PERMIT | DENY (PDP rows only; NULL for auth events)
    user_reason   TEXT,                              -- #3: user-safe reason (paraphrasable)
    audit_reason  TEXT,                              -- #3: internal truth (matched rule, amounts…)
    context_json  JSONB,                             -- full request context + matched rules
    at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT audit_log_decision_chk
        CHECK (decision IS NULL OR decision IN ('PERMIT','DENY')),
    -- #3 non-negotiable, enforced at the row: a denial's two reasons MUST differ.
    CONSTRAINT audit_log_two_reasons_distinct_chk
        CHECK (decision <> 'DENY' OR user_reason IS DISTINCT FROM audit_reason)
);
CREATE INDEX audit_log_org_time_idx     ON yaya_identity.audit_log (org_id, at DESC);
CREATE INDEX audit_log_subject_time_idx ON yaya_identity.audit_log (subject, at DESC);
CREATE INDEX audit_log_action_time_idx  ON yaya_identity.audit_log (action, at DESC);
CREATE INDEX audit_log_event_time_idx   ON yaya_identity.audit_log (event_type, at DESC);
CREATE INDEX audit_log_context_gin      ON yaya_identity.audit_log USING GIN (context_json);
```

**Rationale.** This is the identity analog of yaya-sales' stock/payment ledgers and satisfies **FR-6.1 / AC-40** (100% of auth events + PDP decisions logged). The design's single `reasons_json` is expanded into **two dedicated columns** so non-negotiable **#3** can be enforced *in the database*: `audit_log_two_reasons_distinct_chk` rejects any `DENY` row whose `user_reason` equals `audit_reason`. This is the persistence-layer belt to the build-failing unit test (**M5 / AC-26**) that already guards every `Deny` construction site — the DB will not accept a violating row even if a code path slips.

**Token-exchange is audited, not silent (#6).** An RFC 8693 exchange writes a `token.exchanged` row recording the source `sub`, source `aud`, and new `aud` — so every downstream identity forward is provable (AC-31, success metric M7). There is no schema path that forwards an identity without producing this row.

**Immutability.** The audit module's DB role is granted `INSERT, SELECT` only — **no `UPDATE`, no `DELETE`** on `audit_log`. Append-only is enforced by grant, and hardened by a trigger:

```sql
CREATE OR REPLACE FUNCTION yaya_identity.audit_log_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (%). Rows may not be % after insert.',
        TG_TABLE_NAME, TG_OP;
END $$;
CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON yaya_identity.audit_log
    FOR EACH ROW EXECUTE FUNCTION yaya_identity.audit_log_immutable();
CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON yaya_identity.audit_log
    FOR EACH ROW EXECUTE FUNCTION yaya_identity.audit_log_immutable();
```

Retention/export handled in §10.

#### 4.2.1 Canonical `event_type` values

| `event_type` | Emitted when | `decision` set? |
|---|---|---|
| `auth.login.success` / `auth.login.failure` | primary-factor auth | no |
| `auth.mfa.challenged` / `auth.mfa.passed` | TOTP step-up | no |
| `token.issued` | any grant issues an access token | no |
| `token.refreshed` / `token.refresh.reuse_detected` | refresh rotation / replay | no |
| `token.exchanged` | RFC 8693 exchange (records src/new `aud`) — **#6** | no |
| `token.revoked` / `auth.logout` | revoke / logout | no |
| `pdp.decision` | every `/pdp/decide[-batch]` call | **yes** |
| `client.secret_rotated` | client-secret rotation | no |
| `admin.*` | any admin-API mutation (org/user/role/policy/client) | (as applicable) |
| `pii.scrubbed` | PII deletion path (AC-44) | no |

### 4.3 `idempotency_keys`

```sql
CREATE TABLE yaya_identity.idempotency_keys (
    scope          VARCHAR(64)  NOT NULL,           -- endpoint/operation family
    idem_key       VARCHAR(255) NOT NULL,           -- the client-supplied Idempotency-Key
    request_hash   BYTEA        NOT NULL,           -- sha256(canonical request) — reject key reuse w/ different body
    response_json  JSONB        NOT NULL,           -- the stored response to replay
    status_code    INT          NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, idem_key)
);
CREATE INDEX idempotency_keys_created_idx ON yaya_identity.idempotency_keys (created_at);
```

**Rationale.** **FR-7.3:** mutating admin/token calls carry an `Idempotency-Key`; a repeated `(scope, key)` returns the stored `response_json`/`status_code`. `request_hash` lets us 422 a key reused with a *different* body (a client bug, not a safe retry). A nightly job (§10) purges rows older than the retry window.

### 4.4 `outbox`

```sql
CREATE TABLE yaya_identity.outbox (
    id            BIGSERIAL    PRIMARY KEY,
    aggregate     VARCHAR(64)  NOT NULL,            -- e.g. 'user', 'membership', 'role', 'policy', 'client'
    aggregate_id  VARCHAR(255) NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,            -- see below
    payload_json  JSONB        NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    TEXT,
    CONSTRAINT outbox_status_chk CHECK (status IN ('PENDING','PUBLISHED','DEAD'))
);
CREATE INDEX outbox_pending_idx ON yaya_identity.outbox (created_at)
    WHERE status = 'PENDING';
```

**Rationale.** Transactional outbox for the published events (design §10.3 #5, PRD US-71): `user.created`, `membership.added`, `role.assigned`, `policy.changed`, `client.secret_rotated`. Written in the same transaction as the mutation; a relay drains `status='PENDING'` (the partial index is the drain query) and marks `PUBLISHED`. These feed relying-party cache invalidation (PRD US-72 / NFR-Perf-5: a client's ≤30s decision cache is invalidated by `policy.changed`/`role.assigned`).

| `event_type` | Aggregate | Payload gist |
|---|---|---|
| `user.created` | user | `{ user_id, kind }` (no PII) |
| `membership.added` | membership | `{ user_id, org_id, status }` |
| `role.assigned` | role_assignment | `{ assignment_id, subject_kind, subject_id, role_id, scope }` |
| `policy.changed` | policy | `{ policy_id, org_id, effect }` |
| `client.secret_rotated` | client | `{ client_id, org_id }` |

---

## 5. Organizations, users, credentials, memberships (milestone I1)

### 5.1 `organizations`

```sql
CREATE TABLE yaya_identity.organizations (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255) NOT NULL,
    slug           VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    parent_id      UUID         REFERENCES yaya_identity.organizations(id) ON DELETE RESTRICT,
    metadata_json  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT organizations_status_chk CHECK (status IN ('ACTIVE','SUSPENDED'))
);
CREATE UNIQUE INDEX organizations_slug_uniq ON yaya_identity.organizations (slug);
CREATE INDEX organizations_parent_idx ON yaya_identity.organizations (parent_id)
    WHERE parent_id IS NOT NULL;
```

**Rationale.** The tenant boundary (**FR-2.1**); maps 1:1 to a yaya-agentic tenant, may own many yaya-sales stores (the store is a *scope string*, never an FK). `slug` is the human-readable, URL-safe handle (unique). `parent_id` is **reserved-but-flat** (design §16 Q6, PRD §6.2): the self-FK exists so nesting is a non-breaking future change, but v1 semantics are flat — no inheritance walks the tree. `ON DELETE RESTRICT` prevents orphaning a child under a deleted parent. `metadata_json` carries free-form org attributes.

### 5.2 `users`

```sql
CREATE TABLE yaya_identity.users (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    kind           VARCHAR(16)  NOT NULL DEFAULT 'PERSON',
    status         VARCHAR(16)  NOT NULL DEFAULT 'INVITED',
    primary_email  CITEXT,                            -- nullable: SERVICE users / phone-only shoppers
    phone          VARCHAR(32),
    display_name   VARCHAR(255),
    service_client_id UUID      REFERENCES yaya_identity.clients(id) ON DELETE CASCADE,  -- set iff kind=SERVICE
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_kind_chk   CHECK (kind IN ('PERSON','SERVICE')),
    CONSTRAINT users_status_chk CHECK (status IN ('INVITED','ACTIVE','LOCKED','DISABLED')),
    -- A SERVICE user is the identity face of a service-account client; a PERSON never carries one.
    CONSTRAINT users_service_client_chk
        CHECK ((kind = 'SERVICE') = (service_client_id IS NOT NULL)),
    -- A PERSON must be reachable by at least one PII channel; a SERVICE user need not.
    CONSTRAINT users_person_contact_chk
        CHECK (kind = 'SERVICE' OR primary_email IS NOT NULL OR phone IS NOT NULL)
);
CREATE UNIQUE INDEX users_primary_email_uniq ON yaya_identity.users (primary_email)
    WHERE primary_email IS NOT NULL;                  -- CITEXT → case-insensitive
CREATE UNIQUE INDEX users_phone_uniq ON yaya_identity.users (phone)
    WHERE phone IS NOT NULL;
CREATE INDEX users_status_idx ON yaya_identity.users (status);
```

**Rationale.** A **global** identity (design §5.2, **FR-2.2**): one user, many memberships, so a contractor across orgs is not duplicated (US-25). `kind` splits `PERSON` (human) from `SERVICE` (machine identity). **A `SERVICE` user is the identity face of a service-account `client`** — the `service_client_id` FK ties them, and `users_service_client_chk` makes the correspondence total-and-exclusive (SERVICE ⇔ has a client). `primary_email` is `CITEXT` for case-insensitive uniqueness; both email and phone are nullable and independently unique (a shopper may be phone-only, US-03). `status` drives the auth gate: `LOCKED`/`DISABLED` block token issuance (FR-2.4). PII (`primary_email`, `phone`, `display_name`) is exactly what the PII-scrub path nulls (§10.3, AC-44).

### 5.3 `memberships`

```sql
CREATE TABLE yaya_identity.memberships (
    user_id    UUID         NOT NULL REFERENCES yaya_identity.users(id) ON DELETE CASCADE,
    org_id     UUID         NOT NULL REFERENCES yaya_identity.organizations(id) ON DELETE CASCADE,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    joined_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, org_id),
    CONSTRAINT memberships_status_chk CHECK (status IN ('INVITED','ACTIVE','SUSPENDED'))
);
CREATE INDEX memberships_org_idx ON yaya_identity.memberships (org_id);
```

**Rationale.** Ties a global user to an org (design §5.2). Composite PK `(user_id, org_id)` — a user joins each org at most once. `memberships_org_idx` powers "list the org's members." Invitation acceptance flips `status → ACTIVE` in the same transaction that sets the user's credential (**AC-10**).

### 5.4 `credentials`

```sql
CREATE TABLE yaya_identity.credentials (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES yaya_identity.users(id) ON DELETE CASCADE,
    kind        VARCHAR(24)  NOT NULL,
    secret_ref  TEXT         NOT NULL,               -- Argon2id hash | AES-256-GCM envelope | phone marker (§9)
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT credentials_kind_chk
        CHECK (kind IN ('PASSWORD','PHONE_OTP','TOTP')),   -- WEBAUTHN, OIDC_FEDERATION reserved (vNext)
    CONSTRAINT credentials_status_chk CHECK (status IN ('ACTIVE','DISABLED'))
);
-- at most one active credential of a kind per user
CREATE UNIQUE INDEX credentials_user_kind_active_uniq
    ON yaya_identity.credentials (user_id, kind) WHERE status = 'ACTIVE';
```

**Rationale.** Backs the `CredentialProvider` SPI (design §6.2, **FR-1.6**). `secret_ref` is **never plaintext** and its encoding is `kind`-dependent (§9): `PASSWORD` → an Argon2id encoded hash (verify-only, irreversible); `TOTP` → an AES-256-GCM envelope of the shared seed (must be *decrypted* to compute the expected code, so it is encrypted, not hashed); `PHONE_OTP` → a marker only, since live OTP codes are single-use, TTL-bound, and held in Redis (the ecosystem OTP machinery), never persisted here as a reusable secret. The partial unique index enforces one active credential per `(user, kind)`. A password reset **rotates** this row and, in the same transaction, revokes the user's sessions/refresh families (**FR-1.9**).

---

## 6. Groups, permissions, roles, scoped assignments (milestone I2)

### 6.1 `groups` & `group_members`

```sql
CREATE TABLE yaya_identity.groups (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID         NOT NULL REFERENCES yaya_identity.organizations(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX groups_org_name_uniq ON yaya_identity.groups (org_id, name);

CREATE TABLE yaya_identity.group_members (
    group_id  UUID NOT NULL REFERENCES yaya_identity.groups(id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES yaya_identity.users(id)  ON DELETE CASCADE,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX group_members_user_idx ON yaya_identity.group_members (user_id);
```

**Rationale.** Groups aggregate users within one org for bulk role targeting (design §5.3, **FR-2.5**). A role assigned to a group applies to all current members; `group_members_user_idx` lets effective-permission resolution find every group a subject belongs to (needed by §6.4 assignment resolution). `groups_org_name_uniq` keeps group names unique per org.

### 6.2 `permissions` & `app_permissions`

```sql
CREATE TABLE yaya_identity.permissions (
    key          VARCHAR(160) PRIMARY KEY,           -- "<app>.<resource>:<action>"
    description  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- structural guard on the "<app>.<resource>:<action>" grammar
    CONSTRAINT permissions_key_format_chk
        CHECK (key ~ '^[a-z][a-z0-9_-]*\.[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*$')
);

CREATE TABLE yaya_identity.app_permissions (
    app            VARCHAR(64)  NOT NULL,             -- declaring app, e.g. 'sales', 'identity'
    permission_key VARCHAR(160) NOT NULL REFERENCES yaya_identity.permissions(key) ON DELETE CASCADE,
    declared_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (app, permission_key)
);
CREATE INDEX app_permissions_app_idx ON yaya_identity.app_permissions (app);
```

**Rationale.** A permission is the atomic right keyed `<app>.<resource>:<action>` (design §5.4, **FR-3.1**) — e.g. `sales.order:refund`, `identity.user:invite`. The `permissions_key_format_chk` regex enforces the grammar and the app-namespacing that prevents cross-app collisions. `app_permissions` is the **declared catalog / manifest** (design §9.4, US-30): an app registers the permissions it recognizes; the admin console composes roles **only** from declared permissions. The `app` prefix in the key and the manifest's `app` column agree by convention (the registration endpoint validates it).

### 6.3 `roles` & `role_permissions`

```sql
CREATE TABLE yaya_identity.roles (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID         REFERENCES yaya_identity.organizations(id) ON DELETE CASCADE, -- NULL = system role
    name        VARCHAR(255) NOT NULL,
    system      BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- a system role is org-agnostic; a custom role belongs to exactly one org
    CONSTRAINT roles_system_org_chk CHECK (system = (org_id IS NULL))
);
-- unique per org; system roles (org_id NULL) unique globally by name
CREATE UNIQUE INDEX roles_org_name_uniq
    ON yaya_identity.roles (org_id, name) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX roles_system_name_uniq
    ON yaya_identity.roles (name) WHERE org_id IS NULL;

CREATE TABLE yaya_identity.role_permissions (
    role_id        UUID         NOT NULL REFERENCES yaya_identity.roles(id) ON DELETE CASCADE,
    permission_key VARCHAR(160) NOT NULL REFERENCES yaya_identity.permissions(key) ON DELETE RESTRICT,
    PRIMARY KEY (role_id, permission_key)
);
CREATE INDEX role_permissions_perm_idx ON yaya_identity.role_permissions (permission_key);
```

**Rationale.** A role bundles permissions (design §5.4, **FR-3.2**). `org_id NULL` marks a **system role** (`owner`, `admin`, `member`) available to every org; the `roles_system_org_chk` invariant makes `system` and `org_id IS NULL` agree so a role can't claim to be system while owning an org. `role_permissions` is the join; `ON DELETE RESTRICT` on the permission side stops deleting a permission that roles still reference (protects the catalog). `role_permissions_perm_idx` answers "which roles grant permission X" for the console's impact view.

### 6.4 `role_assignments` — the scoped-assignment table

```sql
CREATE TABLE yaya_identity.role_assignments (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID         NOT NULL REFERENCES yaya_identity.organizations(id) ON DELETE CASCADE,
    subject_kind VARCHAR(8)   NOT NULL,               -- USER | GROUP  (polymorphic subject)
    subject_id   UUID         NOT NULL,               -- users.id or groups.id (kind-dependent; no single FK)
    role_id      UUID         NOT NULL REFERENCES yaya_identity.roles(id) ON DELETE CASCADE,
    scope_kind   VARCHAR(8)   NOT NULL,               -- ORG | RESOURCE
    scope_type   VARCHAR(64),                         -- e.g. 'store'  (NULL when scope_kind=ORG)
    scope_id     VARCHAR(255),                        -- e.g. 'st_123' (NULL when scope_kind=ORG) — OPAQUE, no FK
    granted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    granted_by   VARCHAR(255),                        -- audit trail: who granted it
    CONSTRAINT role_assignments_subject_kind_chk CHECK (subject_kind IN ('USER','GROUP')),
    CONSTRAINT role_assignments_scope_kind_chk   CHECK (scope_kind   IN ('ORG','RESOURCE')),
    -- ORG scope carries no resource coordinates; RESOURCE scope must carry both.
    CONSTRAINT role_assignments_scope_shape_chk CHECK (
        (scope_kind = 'ORG'      AND scope_type IS NULL     AND scope_id IS NULL) OR
        (scope_kind = 'RESOURCE' AND scope_type IS NOT NULL AND scope_id IS NOT NULL)
    )
);
-- de-dup identical grants (NULLs compared as equal via COALESCE surrogate)
CREATE UNIQUE INDEX role_assignments_uniq ON yaya_identity.role_assignments
    (subject_kind, subject_id, role_id, scope_kind,
     COALESCE(scope_type,''), COALESCE(scope_id,''));
-- HOT PATH: resolve a subject's assignments (see §8)
CREATE INDEX role_assignments_subject_idx
    ON yaya_identity.role_assignments (subject_kind, subject_id);
-- resolve everyone assigned on a given resource
CREATE INDEX role_assignments_scope_idx
    ON yaya_identity.role_assignments (scope_kind, scope_type, scope_id);
CREATE INDEX role_assignments_org_idx ON yaya_identity.role_assignments (org_id);
```

**Rationale.** This is the table that makes *"manager of store A but not store B"* native (design §5.5, **FR-3.3/3.4**). Two polymorphic axes:

- **Subject** — `(subject_kind, subject_id)` points at either a `users` row or a `groups` row. It is deliberately **not** a single FK (a column can't FK two tables); referential integrity is enforced in the service layer and by the `subject_kind` CHECK. A group assignment cascades to every member at evaluation time.
- **Scope** — `Scope.Org(org)` is `scope_kind='ORG'` (covers all resources in the org); `Scope.Resource(type,id)` is `scope_kind='RESOURCE'` with `(scope_type, scope_id)` — e.g. `('store','st_123')`. **`scope_id` is an opaque string, never a cross-schema FK** (§1): yaya-identity never joins to the yaya-sales `store` row.

`role_assignments_scope_shape_chk` enforces the sealed `Scope` variants at the row level. **Effective-permission resolution** (§8) is: given a subject and a target resource `(type,id)`, gather all assignments where `(subject_kind,subject_id)` is the user *or any of their groups*, and whose scope is `ORG` **or** matches `(type,id)` exactly; union their roles' permissions. `role_assignments_subject_idx` is the index that drives token minting and every PDP call; `role_assignments_scope_idx` answers the inverse ("who can act on this store"). Revoking is a `DELETE`; it takes effect immediately at the PDP and on the next token (**FR-3.6 / AC-13**).

---

## 7. Policies (milestone I3)

### 7.1 `policies`

```sql
CREATE TABLE yaya_identity.policies (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID         NOT NULL REFERENCES yaya_identity.organizations(id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    effect         VARCHAR(8)   NOT NULL,             -- PERMIT | DENY
    target_json    JSONB        NOT NULL,             -- which actions/resource-types this applies to
    condition_json JSONB        NOT NULL DEFAULT 'true'::jsonb,  -- JSON-logic over subject/resource/env
    priority       INT          NOT NULL DEFAULT 100,
    active          BOOLEAN     NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT policies_effect_chk CHECK (effect IN ('PERMIT','DENY'))
);
CREATE UNIQUE INDEX policies_org_name_uniq ON yaya_identity.policies (org_id, name);
-- PDP lookup: active policies for an org, ordered by priority (deny-overrides eval order)
CREATE INDEX policies_pdp_lookup_idx
    ON yaya_identity.policies (org_id, priority DESC) WHERE active = true;
-- coarse target prefilter (actions/resource-types the policy targets)
CREATE INDEX policies_target_gin ON yaya_identity.policies USING GIN (target_json);
```

**Rationale.** ABAC layered on RBAC (design §5.7 / §7, **FR-4.4/4.5**). `effect` is `PERMIT|DENY`; `target_json` names the actions/resource-types the policy applies to (used to prefilter matching policies via the GIN index); `condition_json` is the **sandboxed JSON-logic** expression over the three attribute bags (subject/resource/environment) — no I/O, no arbitrary code (**FR-4.5/4.7**, design §16 Q4). `priority` orders evaluation; `active` gates a policy without deleting it. The PDP's evaluation order (FR-4.2) — resolve RBAC, then policies highest-priority-first, **DENY short-circuits (deny-overrides)**, else default-deny — reads `policies_pdp_lookup_idx` (org + priority, active only). `simulate` (FR-4.7 / AC-24) runs the same evaluation against a hypothetical request **without persisting** — it touches no table beyond reads, so there is no simulate-specific storage.

Every `decide` writes a `pdp.decision` row to `audit_log` (§4.2) with the matched policy in `context_json` and, on `DENY`, the two distinct reasons (**#3**).

---

## 8. Index & query strategy (hot paths)

The four latency-sensitive paths and the exact indexes they hit:

| Hot path | Query shape | Index |
|---|---|---|
| **Effective permissions for subject+resource** (token minting + every PDP call, NFR-Perf-2) | assignments for the user *and their groups*, scope `ORG` or matching `(type,id)` → union role permissions | `role_assignments_subject_idx` (subject → assignments), `group_members_user_idx` (user → groups), `role_permissions` PK + `role_permissions_perm_idx` |
| **PDP policy lookup** | active policies for org, priority DESC, target-matching action/resource-type | `policies_pdp_lookup_idx` (org, priority, `active`), `policies_target_gin` prefilter |
| **Token verification / refresh** | find live refresh token, detect reuse in a family | `refresh_tokens` PK + `refresh_tokens_family_idx` + `refresh_tokens_user_idx` (§9.2) |
| **Audit filtering** (`GET /orgs/{id}/audit?from&to&subject&action`, US-66) | org+time, subject+time, action+time, JSONB detail | `audit_log_org_time_idx`, `audit_log_subject_time_idx`, `audit_log_action_time_idx`, `audit_log_context_gin` |
| **Login by email/phone** | credential lookup by identifier | `users_primary_email_uniq`, `users_phone_uniq` |
| **"Who can act on store X"** (inverse resolution / admin UI) | assignments by scope | `role_assignments_scope_idx` |
| **JWKS serve** | active + retiring keys | `signing_keys_jwks_idx` |
| **Outbox drain / idempotency replay** | pending events / stored response | `outbox_pending_idx`, `idempotency_keys` PK |

**Effective-permission resolution sketch** (the query the whole authorization model turns on):

```sql
-- Given :user_id, :org_id, target resource (:res_type, :res_id):
WITH subject_ids AS (
    SELECT 'USER'::text AS kind, :user_id AS id
    UNION ALL
    SELECT 'GROUP', gm.group_id
      FROM yaya_identity.group_members gm WHERE gm.user_id = :user_id
)
SELECT DISTINCT rp.permission_key
  FROM yaya_identity.role_assignments ra
  JOIN subject_ids s
       ON s.kind = ra.subject_kind AND s.id = ra.subject_id
  JOIN yaya_identity.role_permissions rp
       ON rp.role_id = ra.role_id
 WHERE ra.org_id = :org_id
   AND ( ra.scope_kind = 'ORG'
      OR (ra.scope_kind = 'RESOURCE'
          AND ra.scope_type = :res_type AND ra.scope_id = :res_id) );
```

This runs both at **token minting** (to embed the scoped `roles` claim for coarse checks, FR-3.5) and inside **PDP step 1** before policy evaluation. It stays within `yaya_identity` — the resource coordinates are compared as strings, honoring §1.

---

## 9. Encryption & secret handling

The one rule: **plaintext secret material is never stored.** What is *hashed* (irreversible; you only ever verify) vs *encrypted* (reversible envelope; you must use the value) vs *never persisted*:

| Secret | Table.column | Treatment | Why |
|---|---|---|---|
| User password | `credentials.secret_ref` (kind=PASSWORD) | **Argon2id** encoded hash | Verify-only; never recovered (NFR-Sec-1, FR-6.3). |
| TOTP seed | `credentials.secret_ref` (kind=TOTP) | **AES-256-GCM envelope** | Must be decrypted to compute the expected code → encrypt, don't hash. |
| Phone OTP code | — (not persisted) | Redis, single-use, TTL ≤ 10 min | A live OTP is ephemeral; only a `PHONE_OTP` marker row exists in `credentials`. |
| Signing private key | `signing_keys.private_ref` | **AES-256-GCM envelope** | Must be decrypted to sign; public half in `public_pem` is not secret. |
| Client secret | `clients.secret_hash` | **hash** (e.g. bcrypt/Argon2id) | Verify-only at token endpoint; never displayed after creation (FR-5.4). |
| Refresh token | `refresh_tokens.token_hash` | **hash** (sha256) | Opaque bearer; store the hash, compare on presentation. |

**Envelope cipher.** The AES-256-GCM envelope mirrors the agent's `JwtSecretCipher` (design §11, FR-6.3): a per-secret data key encrypts the payload; the master key comes from configuration (`yaya.identity.secrets.encryption-key`, self-hosted, no KMS dependency). `private_ref` and the TOTP `secret_ref` store `nonce ‖ ciphertext ‖ tag`. Rotating the master key re-wraps envelopes without touching the underlying secrets.

**Hashing.** Argon2id parameters target NFR-Perf-3 (p50 ≤ 100ms / p99 ≤ 500ms on a 4-core box). Client secrets and refresh tokens are hashed because the endpoint only ever *checks* a presented value.

### 9.1 `clients`

```sql
CREATE TABLE yaya_identity.clients (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID         NOT NULL REFERENCES yaya_identity.organizations(id) ON DELETE CASCADE,
    name               VARCHAR(255) NOT NULL,
    type               VARCHAR(16)  NOT NULL,          -- PUBLIC | CONFIDENTIAL | SERVICE
    secret_hash        TEXT,                           -- NULL for PUBLIC (PKCE, no secret)
    secret_prev_hash   TEXT,                           -- overlap window during rotation (FR-5.4)
    secret_prev_expires_at TIMESTAMPTZ,                -- when the previous secret retires
    grants_json        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    redirect_uris_json JSONB        NOT NULL DEFAULT '[]'::jsonb,
    audiences_json     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    allowed_scopes_json JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT clients_type_chk CHECK (type IN ('PUBLIC','CONFIDENTIAL','SERVICE')),
    -- PUBLIC clients hold no secret; CONFIDENTIAL/SERVICE must.
    CONSTRAINT clients_secret_presence_chk
        CHECK ((type = 'PUBLIC') = (secret_hash IS NULL)),
    -- #6 / FR-5.2: a token audience must be non-empty so tokens are always audience-bound.
    CONSTRAINT clients_audiences_nonempty_chk
        CHECK (jsonb_array_length(audiences_json) >= 1)
);
CREATE INDEX clients_org_idx ON yaya_identity.clients (org_id);
```

**Rationale.** A relying-party app or service account (design §5.6, **FR-5.1**). `type` = `PUBLIC` (PKCE, no secret) / `CONFIDENTIAL` (has secret) / `SERVICE` (client-credentials → the `SERVICE_TOKEN`). `clients_secret_presence_chk` binds secret presence to type. **Secret rotation with overlap** (FR-5.4 / AC-32): `rotate-secret` writes the new hash to `secret_hash`, moves the old to `secret_prev_hash` with `secret_prev_expires_at`; both verify during the window, giving zero-downtime rotation, and a `client.secret_rotated` outbox/audit event fires. `clients_audiences_nonempty_chk` enforces the **audience-binding invariant (#6 / FR-5.2)**: no client can mint an audience-less token, so tokens can never be replayed across apps.

### 9.2 `refresh_tokens`

```sql
CREATE TABLE yaya_identity.refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         REFERENCES yaya_identity.users(id) ON DELETE CASCADE,   -- NULL for client-cred
    client_id   UUID         NOT NULL REFERENCES yaya_identity.clients(id) ON DELETE CASCADE,
    family_id   UUID         NOT NULL,                -- ties a rotation lineage together
    token_hash  BYTEA        NOT NULL,                -- sha256(opaque token) — never plaintext
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    audience    VARCHAR(255) NOT NULL,                -- the aud this refresh mints for (non-empty)
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT refresh_tokens_status_chk CHECK (status IN ('ACTIVE','CONSUMED','REVOKED')),
    CONSTRAINT refresh_tokens_aud_nonempty_chk CHECK (length(audience) > 0)
);
CREATE UNIQUE INDEX refresh_tokens_hash_uniq ON yaya_identity.refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_family_idx ON yaya_identity.refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx   ON yaya_identity.refresh_tokens (user_id)
    WHERE status = 'ACTIVE';
```

**Rationale.** Rotating, revocable refresh with reuse-detection (design §6.3, **FR-1.5 / AC-4**). Each rotation issues a new row in the same `family_id` and marks the old `CONSUMED`. **Reuse-detection:** presenting a token whose row is already `CONSUMED` triggers revocation of the **entire family** (`UPDATE … SET status='REVOKED' WHERE family_id = ?`) and a `token.refresh.reuse_detected` audit row — `refresh_tokens_family_idx` makes this cheap. The token itself is stored only as `token_hash` (opaque bearer, never plaintext). `audience` (non-empty) records what the refresh mints for; there is **no** column that would let a refresh silently retarget another audience — an audience change is an explicit token-exchange (#6), audited separately.

### 9.3 `sessions`

```sql
CREATE TABLE yaya_identity.sessions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES yaya_identity.users(id) ON DELETE CASCADE,
    client_id     UUID         REFERENCES yaya_identity.clients(id) ON DELETE SET NULL,
    device_json   JSONB,                              -- UA / device fingerprint (non-PII where possible)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ,                        -- back-channel logout / lock stamps this
    expires_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX sessions_user_active_idx ON yaya_identity.sessions (user_id)
    WHERE revoked_at IS NULL;
CREATE INDEX sessions_expiry_idx ON yaya_identity.sessions (expires_at)
    WHERE revoked_at IS NULL;
```

**Rationale.** Login sessions with device context and back-channel logout (design §5/§6, **FR-1.9 / FR-2.4 / AC-14**). `revoked_at` supports logout, back-channel logout to clients, and the "lock/disable a user revokes their sessions" path. `sessions_user_active_idx` powers the bulk revoke ("end all of this user's sessions") that fires on lock, credential reset, and PII deletion.

---

## 10. Audit immutability, retention & PII deletion

### 10.1 Immutability
Enforced two ways (§4.2): the audit module's DB role holds **`INSERT, SELECT` only** on `audit_log`, and `BEFORE UPDATE`/`BEFORE DELETE` triggers raise. Append-only is thus guaranteed even against a bug in the audit module itself.

### 10.2 Export & retention
`GET /orgs/{id}/audit` (US-66) reads via the org/subject/action/time indexes and can stream an export. Retention is a **windowed archive**, not a mutate-in-place: rows past the retention horizon are copied to cold storage (or an archive partition) and only then removed by a privileged, separately-audited maintenance role — the append-only grant on the live table is never relaxed for the application. (`audit_log` is the one table that grows linearly; partition by month when it crosses the size threshold — out of v1 scope, noted for ops.)

### 10.3 PII deletion (AC-44 / FR-6.5)
A deletion request scrubs **PII in `users` and `credentials`** while `audit_log` keeps only **non-PII UUIDs**:

```sql
-- scrub identity PII; the row survives for FK integrity and audit joins
UPDATE yaya_identity.users
   SET primary_email = NULL,
       phone         = NULL,
       display_name  = 'Deleted user',
       status        = 'DISABLED'
 WHERE id = :user_id;

DELETE FROM yaya_identity.credentials WHERE user_id = :user_id;   -- secrets gone entirely
UPDATE yaya_identity.sessions SET revoked_at = now() WHERE user_id = :user_id AND revoked_at IS NULL;
UPDATE yaya_identity.refresh_tokens SET status = 'REVOKED' WHERE user_id = :user_id AND status = 'ACTIVE';
```

`audit_log` rows are **not** rewritten: they reference the subject by `sub`/UUID (a non-PII identifier), never by email/name (which live only in dedicated `users` columns, never in `context_json` — the payload rule: IDs and hashes only). A `pii.scrubbed` audit row records the deletion. This mirrors the yaya-agentic recorder redact/delete discipline (§5.9) and the user-accounts scrub.

---

## 11. Migration strategy (Flyway)

- **Tool:** Flyway, versioned `V<n>__<slug>.sql` under `src/main/resources/db/migration/`.
- **Schema:** Flyway is configured with `flyway.schemas=yaya_identity` and `flyway.defaultSchema=yaya_identity`; migration files begin by ensuring `CREATE SCHEMA IF NOT EXISTS yaya_identity;` and setting the search path. Every object is created in `yaya_identity`.
- **Extensions:** `CREATE EXTENSION IF NOT EXISTS pgcrypto;` (UUIDs) and `citext;` (case-insensitive email) in the first migration.
- **Isolation lint (AC-41):** `SchemaIsolationLintTest` runs in CI over the migration directory and fails on any cross-schema reference (§1.1). This is the gate that keeps the lift-to-own-instance guarantee true over time.
- **Ordering aligned to milestones (design §13):**

| Migration | Milestone | Lands |
|---|---|---|
| `V1__schema_and_extensions.sql` | **I0** | `CREATE SCHEMA yaya_identity`, extensions, search-path |
| `V2__platform_signing_keys.sql` | **I0** | `signing_keys` |
| `V3__audit_log.sql` | **I0** | `audit_log` + immutability triggers + two-reason CHECK |
| `V4__idempotency_outbox.sql` | **I0** | `idempotency_keys`, `outbox` |
| `V5__organizations.sql` | **I1** | `organizations` |
| `V6__users_memberships.sql` | **I1** | `users`, `memberships` |
| `V7__credentials.sql` | **I1** | `credentials` |
| `V8__permissions_catalog.sql` | **I2** | `permissions`, `app_permissions` |
| `V9__roles.sql` | **I2** | `roles`, `role_permissions` |
| `V10__groups.sql` | **I2** | `groups`, `group_members` |
| `V11__role_assignments.sql` | **I2** | `role_assignments` (scoped grants) |
| `V12__policies.sql` | **I3** | `policies` |
| `V13__clients.sql` | **I4** | `clients` |
| `V14__refresh_tokens_sessions.sql` | **I4** | `refresh_tokens`, `sessions` |
| `V15__seed_system_roles.sql` | **I2/I4** | seed `owner`/`admin`/`member` system roles + `identity.*` permission manifest |

> **Note on `users.service_client_id` (V6) vs `clients` (V13).** `users` declares the FK to `clients`, which lands later (I4). Land V6 with the `service_client_id` column but **add the FK constraint in V13** once `clients` exists (a one-line `ALTER TABLE … ADD CONSTRAINT` in V13), so no migration forward-references a table that doesn't yet exist. `SERVICE` users only appear at I4 anyway (machine identity), so nothing is stranded.

---

## 12. Data-integrity invariants

| Invariant | Enforced by | Traces to |
|---|---|---|
| **Two denial reasons differ** — `user_reason ≠ audit_reason` on any `DENY` | `audit_log_two_reasons_distinct_chk` (DB) + build-failing unit test | #3, FR-4.6, AC-26 |
| **No plaintext secret** — passwords hashed, TOTP/keys enveloped, refresh/client-secret hashed | column encoding rules (§9); no plaintext column exists | NFR-Sec-1, FR-6.3 |
| **Audience non-empty for tokens** — every client and refresh binds an `aud` | `clients_audiences_nonempty_chk`, `refresh_tokens_aud_nonempty_chk` | #6, FR-5.2, AC-30 |
| **No silent token forwarding** — audience change is an explicit, audited exchange | no retarget column; `token.exchanged` audit row required | #6, FR-5.3, AC-31, M7 |
| **Scope validity** — `ORG` carries no resource coords; `RESOURCE` carries both | `role_assignments_scope_shape_chk` | FR-3.3/3.4 |
| **System role ⇔ no org** — a system role is org-agnostic | `roles_system_org_chk` | FR-3.2 |
| **SERVICE user ⇔ has a client** | `users_service_client_chk` | FR-5.1 |
| **Permission key grammar** — `<app>.<resource>:<action>` | `permissions_key_format_chk` | FR-3.1 |
| **Append-only audit** — no UPDATE/DELETE | grant + triggers | FR-6.1, AC-40 |
| **One active signer** | `signing_keys_one_active_uniq` | FR-1.4, AC-3 |
| **Last-owner protection** — an org always keeps ≥1 `owner` assignment | **service-layer invariant** (checked in the same tx as any revoke/demote; not expressible as a single-row CHECK) | persona §2.1/2.2, FR-2.x |
| **Schema isolation** — zero cross-schema FK/join | migration lint (§1.1) | FR-7.2, AC-41, M6 |

> **Last-owner protection** cannot be a table CHECK (it's a cross-row cardinality rule). It is enforced transactionally: any operation that would revoke or demote the final `owner` role assignment for an org is rejected before commit, with a two-reason denial. The `role_assignments_subject_idx`/`role_assignments_scope_idx` make the "count remaining owners" query cheap.

---

## 13. Traceability

| Table / constraint | FR / AC |
|---|---|
| `organizations` (status, parent_id, slug) | FR-2.1, FR-7.1, §16 Q6 |
| `users` (kind, status, email/phone uniqueness, service_client_id) | FR-2.2, US-25, US-03, FR-5.1 |
| `memberships` | FR-2.2, AC-10, US-25 |
| `credentials` (secret_ref treatment) | FR-1.6, FR-1.9, NFR-Sec-1 |
| `groups`, `group_members` | FR-2.5, US-24, AC-12 |
| `permissions`, `app_permissions` | FR-3.1, US-30 |
| `roles`, `role_permissions` | FR-3.2, US-31 |
| `role_assignments` (scoped, polymorphic) | FR-3.3/3.4/3.5/3.6, AC-11, AC-12, AC-13 |
| `policies` (effect, target/condition, priority, active) | FR-4.2/4.4/4.5, AC-20…AC-25 |
| `audit_log` two reasons + append-only | #3, FR-4.6, FR-6.1, AC-26, AC-40 |
| `clients` (type, audiences non-empty, secret rotation) | FR-5.1/5.2/5.4, AC-30, AC-32 |
| `refresh_tokens` (family, reuse-detection, aud) | FR-1.5, AC-4; #6 (no silent retarget) |
| `sessions` (revoked_at, back-channel logout) | FR-1.9, FR-2.4, AC-14 |
| `signing_keys` (rotation retention) | FR-1.4, AC-3, NFR-Avail-3 |
| `idempotency_keys` | FR-7.3 |
| `outbox` (event list) | US-71, US-72, NFR-Perf-5 |
| token-exchange audited (no silent forward) | #6, FR-5.3, AC-31, M7 |
| PII scrub keeps non-PII UUIDs | FR-6.5, AC-44 |
| schema isolation + migration lint | FR-7.2, AC-41, M6 |

---

## 14. References

- [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) — master design; **§14 Data Model** (primary source), §5 (domain records), §6 (auth/tokens), §7 (PDP), §9 (API), §11 (security/PII), §13 (milestones), §16 (open questions).
- [PRD](PRD.md) — FR-2.x/3.x/4.x/5.x/6.x/7.x, AC-1…AC-44, success metrics M1–M7, §7.6 integration tests (incl. `SchemaIsolationLintTest`).
- `apps/yaya-sales/docs/design/yaya-sales-design.md` — first relying party; the **S0** schema-isolation guard this doc mirrors (§1).
- `docs/design/yaya-agentic-design.md` — §5.4 AuthN, §5.5 AuthZ (two-reason `AuthzDecision`), §5.9 recorder redact/delete, §12 trust boundaries.
- `docs/specs/user-accounts/Backend-Data.md` — house-style template for this doc (table-by-table DDL, index-by-query, migration sequence, PII scrub).
- `CLAUDE.md` — non-negotiables #3 (two reasons) and #6 (no silent token forwarding); Postgres 16, Flyway, self-hosted constraint, `JwtSecretCipher` (AES-256-GCM envelope).
