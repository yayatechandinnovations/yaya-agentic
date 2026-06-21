# User Accounts & Workspaces — Backend Data Model

> **Companion to:** [PRD](PRD.md) · [TRD](TRD.md) · [UI-UX](UI-UX.md) · [App-Flow](App-Flow.md).
> **Status:** Draft v1 — implementation-ready.

This doc is the source of truth for tables, columns, indexes, constraints, FK cascade behavior, and the V20–V25 migration sequence. It also pins down the GUC plumbing (M5b) and the data-lifecycle rules.

---

## 1. ER overview

```
            ┌─────────────────────┐
            │       users         │
            │  id (UUID, PK)      │
            │  email (CITEXT, UQ) │
            │  display_name       │
            │  password_hash      │
            │  status             │
            │  is_platform_admin  │
            │  email_verified_at  │
            │  last_login_at      │
            │  deleted_at         │
            └─────────┬───────────┘
                      │ 1
                      │
                      │ N
        ┌─────────────┴─────────────┐
        │ user_workspace_memberships │      ┌──────────────────────────┐
        │  user_id (FK users.id)     │──N─►│        tenants            │
        │  workspace_id (FK tenants) │1───►│  id (UUID, PK)           │
        │  role                       │    │  code (UQ slug)          │
        │  added_by (FK users.id)     │    │  display_name            │
        │  added_at                   │    │  created_by (FK users)   │
        └─────────────────────────────┘    │  owner_user_id (FK users)│
                      │ 1                  │  archived_at             │
                      │                    └────────┬─────────────────┘
                      │                              │ 1
                      │ N                            │
        ┌─────────────┴───────────────┐              │ N
        │      user_invitations       │              │
        │  id (UUID, PK)              │              │
        │  workspace_id (FK tenants)  │──────────────┘
        │  email (CITEXT)             │
        │  role                       │  ┌──────────────────────────────┐
        │  token_hash (BYTEA UQ)      │  │ All ~20 child tables         │
        │  invited_by (FK users)      │  │ now have tenant_id UUID FK   │
        │  consumed_by (FK users)     │  │ → tenants(id)                │
        │  expires_at / consumed_at   │  │ (profiles, capabilities,     │
        └─────────────────────────────┘  │  tools_registry, sessions,   │
                                          │  knowledge_*, recording_*,  │
        ┌─────────────────────────────┐  │  audit_*, tenant_oidc_*,    │
        │  operator_sessions          │  │  tenant_jwt_secrets,        │
        │  id (TEXT PK = sid)         │  │  identity_profile_mappings, │
        │  source                     │  │  …)                          │
        │  subject                    │  └──────────────────────────────┘
        │  user_id (FK users) NEW     │
        │  csrf_token NEW             │
        │  expires_at                 │
        └─────────────────────────────┘

        ┌────────────────────────────────┐  ┌────────────────────────────────┐
        │ user_email_verification_tokens │  │   user_password_reset_tokens   │
        │  token_hash (BYTEA PK)         │  │  token_hash (BYTEA PK)         │
        │  user_id (FK users)            │  │  user_id (FK users)            │
        │  expires_at / consumed_at      │  │  expires_at / consumed_at      │
        └────────────────────────────────┘  └────────────────────────────────┘

        ┌─────────────────────────────┐    ┌─────────────────────────────────┐
        │     audit_user_events       │    │         email_outbox            │
        │  id (BIGSERIAL PK)          │    │  id (BIGSERIAL PK)              │
        │  user_id (FK users)         │    │  to_address                     │
        │  workspace_id (FK tenants)  │    │  template_id                    │
        │  kind                       │    │  subject / body_html / body_text│
        │  detail_json (JSONB)        │    │  idempotency_key (UQ)           │
        │  client_ip / user_agent     │    │  status / attempts              │
        │  occurred_at                │    │  next_attempt_at / sent_at      │
        └─────────────────────────────┘    └─────────────────────────────────┘
```

---

## 2. New tables (V20)

All new tables CASCADE on user/tenant deletion.

### 2.1 `users`

```sql
CREATE TABLE users (
    id                  UUID         PRIMARY KEY,
    email               CITEXT       NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    password_hash       TEXT         NOT NULL,    -- argon2id encoded; '' for delegate-provisioned users (never matches)
    status              VARCHAR(32)  NOT NULL,    -- PENDING_VERIFICATION | ACTIVE | SUSPENDED | DELETED
    is_platform_admin   BOOLEAN      NOT NULL DEFAULT false,
    email_verified_at   TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX users_email_uniq ON users (email);   -- CITEXT makes this case-insensitive
CREATE INDEX users_status_idx        ON users (status);
CREATE INDEX users_platform_admin_idx ON users (is_platform_admin) WHERE is_platform_admin = true;
CREATE INDEX users_deleted_at_idx    ON users (deleted_at) WHERE deleted_at IS NOT NULL;
```

Conventions:
- `CITEXT` extension required (`CREATE EXTENSION IF NOT EXISTS citext;`). Falls back to `LOWER(email)` expression index if CITEXT unavailable.
- `password_hash` is `''` for HTTP-delegate-provisioned users (no user-password login path for them). Argon2 verify against `''` is constant-time fail.
- Soft delete: `status='DELETED' AND deleted_at IS NOT NULL`. Row preserved for FK integrity; PII scrub happens 30 days later.

### 2.2 `user_workspace_memberships`

```sql
CREATE TABLE user_workspace_memberships (
    user_id        UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    workspace_id   UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    role           VARCHAR(32)  NOT NULL,    -- OWNER | ADMIN | MEMBER | VIEWER
    added_by       UUID         REFERENCES users(id) ON DELETE SET NULL,
    added_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, workspace_id),
    CONSTRAINT user_workspace_memberships_role_chk
      CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER'))
);
CREATE INDEX user_workspace_memberships_workspace_idx ON user_workspace_memberships(workspace_id);
CREATE INDEX user_workspace_memberships_role_idx      ON user_workspace_memberships(role);
```

Note: `tenants(id)` is `UUID` after V24. V20 lands before the UUID flip — the `workspace_id` column is initially `VARCHAR(64)` to match V20-era `tenants.id`. V23 sweeps it to `UUID` along with every other child FK.

> **Implementation choice:** Land V20 with `workspace_id VARCHAR(64)`. V23's table-enumeration callback will pick it up automatically. (Alternative: skip V20→V23 churn by landing V20 *after* V24; but then user-accounts code can't compile against the schema before the UUID cutover. We accept the extra V23 sweep.)

### 2.3 `user_invitations`

```sql
CREATE TABLE user_invitations (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email        CITEXT       NOT NULL,
    role         VARCHAR(32)  NOT NULL,
    token_hash   BYTEA        NOT NULL,
    invited_by   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    consumed_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT user_invitations_role_chk
      CHECK (role IN ('ADMIN','MEMBER','VIEWER'))  -- OWNER never invitable
);
CREATE UNIQUE INDEX user_invitations_token_hash_pending_uniq
  ON user_invitations (token_hash) WHERE consumed_at IS NULL;
CREATE INDEX user_invitations_email_pending_idx
  ON user_invitations (email) WHERE consumed_at IS NULL;
CREATE INDEX user_invitations_workspace_idx ON user_invitations (workspace_id);
CREATE INDEX user_invitations_expires_idx
  ON user_invitations (expires_at) WHERE consumed_at IS NULL;
```

Notes:
- The `WHERE consumed_at IS NULL` partial unique index makes "active token already exists" a DB-level constraint violation, not an app check.
- OWNER role intentionally not in CHECK — owners are minted only via signup or explicit promotion by an existing OWNER.
- `consumed_by` is `SET NULL` (not `CASCADE`) on user delete — we keep the consumption record but lose the joinable identity if the consumer is later GDPR-scrubbed.

### 2.4 Token tables

```sql
CREATE TABLE user_email_verification_tokens (
    token_hash   BYTEA        PRIMARY KEY,         -- sha256(raw token)
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX user_email_verification_tokens_user_pending_idx
  ON user_email_verification_tokens (user_id) WHERE consumed_at IS NULL;
CREATE INDEX user_email_verification_tokens_expires_idx
  ON user_email_verification_tokens (expires_at) WHERE consumed_at IS NULL;

CREATE TABLE user_password_reset_tokens (
    token_hash   BYTEA        PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX user_password_reset_tokens_user_pending_idx
  ON user_password_reset_tokens (user_id) WHERE consumed_at IS NULL;
CREATE INDEX user_password_reset_tokens_expires_idx
  ON user_password_reset_tokens (expires_at) WHERE consumed_at IS NULL;
```

A nightly cleanup job DELETEs rows where `expires_at < now() - INTERVAL '7 days'` to keep these tables small.

### 2.5 `audit_user_events`

```sql
CREATE TABLE audit_user_events (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       UUID         REFERENCES users(id) ON DELETE SET NULL,
    workspace_id  UUID         REFERENCES tenants(id) ON DELETE SET NULL,
    kind          VARCHAR(64)  NOT NULL,
    detail_json   JSONB,
    client_ip     TEXT,
    user_agent    TEXT,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX audit_user_events_kind_time_idx       ON audit_user_events (kind, occurred_at DESC);
CREATE INDEX audit_user_events_user_time_idx       ON audit_user_events (user_id, occurred_at DESC);
CREATE INDEX audit_user_events_workspace_time_idx  ON audit_user_events (workspace_id, occurred_at DESC);
CREATE INDEX audit_user_events_detail_gin          ON audit_user_events USING GIN (detail_json);
```

Canonical `kind` values (extend judiciously; add a CHECK only in M5b once stable):

| Kind | Emitted by | Detail JSON example |
|---|---|---|
| `SIGNUP` | `OperatorAccountService.register` | `{}` |
| `SIGNUP_DUPLICATE_ATTEMPT` | `OperatorAccountService.register` (path B) | `{}` |
| `VERIFY` | `OperatorAccountService.verifyEmail` | `{ "default_workspace_id": "<uuid>" }` |
| `LOGIN` | `AuthController` post-chain | `{ "strategy": "user-password" }` |
| `LOGOUT` | `AuthController.logout` | `{}` |
| `PASSWORD_RESET_REQUEST` | `OperatorAccountService.requestPasswordReset` | `{}` |
| `PASSWORD_RESET_CONFIRM` | `OperatorAccountService.confirmPasswordReset` | `{ "sessions_invalidated": 3 }` |
| `INVITE_SEND` | `InvitationService.create` | `{ "email_hash": "...", "role": "MEMBER" }` |
| `INVITE_ACCEPT` | `InvitationService.accept` | `{ "role": "MEMBER" }` |
| `INVITE_REVOKE` | `InvitationService.revoke` | `{ "invitation_id": "<uuid>" }` |
| `WORKSPACE_CREATE` | `WorkspaceService.create` | `{ "code": "acme" }` |
| `WORKSPACE_RENAME` | `WorkspaceService.patch` | `{ "old": "Acme", "new": "Acme Co" }` |
| `WORKSPACE_ARCHIVE` | `WorkspaceService.archive` | `{}` |
| `ROLE_GRANT` | `MembershipService.changeRole` | `{ "target_user_id": "<uuid>", "from": "MEMBER", "to": "ADMIN" }` |
| `ROLE_REVOKE` | `MembershipService.removeMember`, `changeRole` (demote) | `{ "target_user_id": "<uuid>", "from": "OWNER", "removed": true }` |
| `PLATFORM_ADMIN_ACTION` | `WorkspaceAccessGuard` short-circuit branch | `{ "action": "audit_user_events.list" }` |
| `ACCOUNT_DELETE_SOFT` | `OperatorAccountService.deleteAccount` (M5b) | `{}` |
| `ACCOUNT_PII_SCRUB` | `UserScrubCron` (M5b) | `{}` |
| `MAIL_DEAD` | `MailOutboxDrainer` | `{ "template": "Invitation", "to_hash": "..." }` |

**PII rule:** never store raw email, raw name, raw IP-when-PII-sensitive, raw user-agent strings beyond the dedicated columns. `detail_json` carries IDs and hashes only.

### 2.6 `email_outbox`

```sql
CREATE TABLE email_outbox (
    id                BIGSERIAL    PRIMARY KEY,
    to_address        TEXT         NOT NULL,
    template_id       VARCHAR(64)  NOT NULL,
    subject           TEXT         NOT NULL,
    body_html         TEXT,
    body_text         TEXT         NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL,
    status            VARCHAR(16)  NOT NULL,     -- PENDING | RETRYING | SENT | DEAD
    attempts          INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error        TEXT,
    sent_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX email_outbox_idempotency_uniq ON email_outbox (idempotency_key);
CREATE INDEX        email_outbox_pending_idx
  ON email_outbox (next_attempt_at)
  WHERE status IN ('PENDING','RETRYING');
CREATE INDEX        email_outbox_dead_idx        ON email_outbox (status) WHERE status = 'DEAD';
```

Notes:
- `idempotency_key = sha256(template + token_hash)` for token-bearing mails; ensures a re-enqueue doesn't double-send.
- The drainer (`MailOutboxDrainer @Scheduled`) picks rows where `status ∈ {PENDING, RETRYING} AND next_attempt_at ≤ now()`; processes ≤ 100/run.
- DEAD rows are kept for one week then purged by the same nightly cleanup as token tables.

---

## 3. `tenants` changes (V22 → V24)

The biggest mechanical change: `tenants.id` flips from `VARCHAR(64)` to `UUID`, and a new `tenants.code` carries the slug.

### 3.1 Post-V24 shape

```sql
CREATE TABLE tenants (                            -- conceptual; built incrementally V22→V24
    id                UUID         PRIMARY KEY,
    code              VARCHAR(64)  NOT NULL,      -- human-readable slug (URLs, logs, UI)
    display_name      VARCHAR(255) NOT NULL,
    description       TEXT,
    created_by        UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    owner_user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    archived_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    legacy_slug_id    VARCHAR(64),                -- retained one release post-V24; dropped in R5
    -- + every existing column from V9 (origin allowlist, default_authenticator_binding_id, etc.)
);
CREATE UNIQUE INDEX tenants_code_uniq      ON tenants (code);
CREATE INDEX        tenants_owner_idx      ON tenants (owner_user_id);
CREATE INDEX        tenants_archived_idx   ON tenants (archived_at) WHERE archived_at IS NOT NULL;
CREATE INDEX        tenants_legacy_idx     ON tenants (legacy_slug_id);   -- forensics only
```

### 3.2 Reserved `code` list

The application validates `code` against this list at create-time:

```
admin, api, auth, health, system, static, public, www, signup, login, logout,
verify, reset, accept-invitation, me, workspaces, platform-settings, audit,
metrics, status, version, root, support, billing
```

Attempting to create or rename to a reserved code returns 400 with `userSafeReason="That workspace code is reserved. Try a different one."`

### 3.3 Slug generator spec

Pseudocode:

```
function slugify(displayName):
    s = lower(displayName)
    s = remove diacritics (Unicode NFKD + strip combining marks)
    s = replace non-ASCII-alphanumeric runs with '-'
    s = trim leading/trailing '-'
    s = collapse repeated '-' to single '-'
    s = truncate to 32 chars
    if isEmpty(s) or isReserved(s):
        s = 'workspace'
    return s

function slugifyWithCollisionRetry(displayName, db):
    base = slugify(displayName)
    if not exists tenants where code = base: return base
    for i in 1..5:
        candidate = (truncate base to 28) + '-' + random3()
        if not exists tenants where code = candidate: return candidate
    throw SlugAllocationException  # ~impossible in practice
```

---

## 4. Migration sequence (V20 → V25)

### 4.1 V20 — new tables (additive, reversible)

Creates: `users`, `user_workspace_memberships` (workspace_id VARCHAR(64) initially), `user_invitations` (ditto), `user_email_verification_tokens`, `user_password_reset_tokens`, `audit_user_events`, `email_outbox`. All indexes from §2.

### 4.2 V21 — seed synthetic platform admin + OWNER memberships (additive, reversible)

```sql
-- Synthetic platform-admin user. Password hash is the empty string —
-- argon2.matches('', '') is constant-time false. The bootstrap path
-- (which uses the BOOTSTRAP_HASH from env) is the only way to authenticate
-- as this user; the user_id is what bootstrap-source sessions reference.
INSERT INTO users (id, email, display_name, password_hash, status, is_platform_admin, email_verified_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'platform-admin@local',
    'Platform Admin (synthetic)',
    '',
    'ACTIVE',
    true,
    now()
);

-- Seed OWNER memberships for every existing tenant; pre-cutover tenant_id is still VARCHAR(64).
INSERT INTO user_workspace_memberships (user_id, workspace_id, role, added_by, added_at)
SELECT '00000000-0000-0000-0000-000000000001', t.id, 'OWNER',
       '00000000-0000-0000-0000-000000000001', now()
FROM tenants t;
```

### 4.3 V22 — additive columns on `tenants` (additive, reversible)

```sql
ALTER TABLE tenants
    ADD COLUMN code           VARCHAR(64),
    ADD COLUMN id_uuid        UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN created_by     UUID,
    ADD COLUMN owner_user_id  UUID,
    ADD COLUMN archived_at    TIMESTAMPTZ;

-- Backfill code from existing id (which is the slug today).
UPDATE tenants SET code = id WHERE code IS NULL;

-- Every existing tenant becomes owned by the synthetic platform admin until
-- a real user later promotes themselves or the tenant is transferred.
UPDATE tenants
   SET created_by    = '00000000-0000-0000-0000-000000000001',
       owner_user_id = '00000000-0000-0000-0000-000000000001'
 WHERE created_by IS NULL;

ALTER TABLE tenants
    ALTER COLUMN code          SET NOT NULL,
    ALTER COLUMN created_by    SET NOT NULL,
    ALTER COLUMN owner_user_id SET NOT NULL;

ALTER TABLE tenants
    ADD CONSTRAINT tenants_code_uniq UNIQUE (code),
    ADD CONSTRAINT tenants_created_by_fk    FOREIGN KEY (created_by)    REFERENCES users(id),
    ADD CONSTRAINT tenants_owner_user_id_fk FOREIGN KEY (owner_user_id) REFERENCES users(id);

CREATE INDEX tenants_owner_idx    ON tenants (owner_user_id);
CREATE INDEX tenants_archived_idx ON tenants (archived_at) WHERE archived_at IS NOT NULL;
```

### 4.4 V23 — child FK rewrite (NON-REVERSIBLE)

A Flyway Java-callback enumerates child tables and rewrites each. The hard-coded list below avoids any reliance on `information_schema` introspection (more brittle on Postgres versions).

**Child tables to rewrite** (verified against repo as of this writing):

| # | Table | Source migration | Column(s) | FK to `tenants`? |
|---|---|---|---|---|
| 1 | `personality_fragments` | V1 | `tenant_id` | yes |
| 2 | `tools_registry` | V1 | `tenant_id` | yes |
| 3 | `capabilities` | V1 | `tenant_id` | yes |
| 4 | `knowledge_sources` | V1 | `tenant_id` | yes |
| 5 | `knowledge_documents` | V1 | `tenant_id` (via composite FK) | yes (composite) |
| 6 | `knowledge_chunks` | V1 | `tenant_id` (composite) | yes (composite) |
| 7 | `auth_bindings` | V1 | `tenant_id` | yes |
| 8 | `profiles` | V1 | `tenant_id` | yes |
| 9 | `profile_knowledge_bindings` | V1 | `tenant_id` (composite) | yes (composite) |
| 10 | `sessions` | V1 | `tenant_id` | yes |
| 11 | `turns` | V1 | `tenant_id` (composite) | yes (composite) |
| 12 | `intent_frames` | V1 | `tenant_id` (composite) | yes (composite) |
| 13 | `audit_authz` | V1 | `tenant_id` | yes |
| 14 | `recording_strategies` | V2 | `tenant_id` | yes |
| 15 | `recorder_outbox` | V2 | `tenant_id` | no (raw column) |
| 16 | `audit_recorder_ops` | V2 | `tenant_id` | no (raw column) |
| 17 | `audit_inbound_origin_denied` | V9 | `tenant_id` (nullable) | no (raw column) |
| 18 | `tenant_clone_jobs` | V10 | `source_tenant`, `destination_tenant` | no (raw columns) |
| 19 | `identity_profile_mappings` | V11 | `tenant_id` | yes |
| 20 | `tenant_oidc_issuers` | V12 | `tenant_id` | yes |
| 21 | `tenant_jwt_secrets` | V13 | `tenant_id` | yes |
| 22 | `user_workspace_memberships` | V20 | `workspace_id` | yes |
| 23 | `user_invitations` | V20 | `workspace_id` | yes |

V14/V15 add no new `tenant_id` columns — they reference `sessions(id, tenant_id)` via FK, which moves transitively when `sessions` is rewritten.

**V23 algorithm per FK table (#1–#14, #19–#23):**

```sql
-- Generic for tables with a simple tenant_id FK
ALTER TABLE <T> ADD COLUMN tenant_id_uuid UUID;
UPDATE <T> SET tenant_id_uuid = (SELECT id_uuid FROM tenants WHERE tenants.id = <T>.tenant_id);
ALTER TABLE <T> DROP CONSTRAINT <old fk name>;       -- find via information_schema in the callback
ALTER TABLE <T> DROP COLUMN tenant_id;
ALTER TABLE <T> RENAME COLUMN tenant_id_uuid TO tenant_id;
ALTER TABLE <T> ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE <T> ADD CONSTRAINT <T>_tenant_fk
  FOREIGN KEY (tenant_id) REFERENCES tenants(id_uuid) ON DELETE CASCADE;
-- Re-create any unique constraints / indexes that referenced tenant_id (e.g. profiles PK was
-- (tenant_id, id, version)). The callback enumerates these from information_schema before the
-- drop and re-applies them after the rename.
```

**Composite FK tables (#5, #6, #9, #11, #12)** require special-case logic in the callback because the FK uses `(tenant_id, …)` and the parent's PK includes `tenant_id`. Approach: the parent table's PK is rewritten first (with `id_uuid`); the child's `tenant_id` column rewrite then re-points the composite FK to the new parent PK. The callback orders rewrites topologically: parents first, then children.

**Non-FK tables (#15, #16, #17, #18)** only have a column rewrite (no FK to drop/re-add). For `tenant_clone_jobs` (#18), both `source_tenant` and `destination_tenant` are rewritten.

**`audit_inbound_origin_denied` (#17)** has a nullable `tenant_id`; the rewrite preserves nullability. Rows with `tenant_id IS NULL` (unknown tenant case) stay NULL post-rewrite.

### 4.5 V24 — swap `tenants` PK (NON-REVERSIBLE)

```sql
BEGIN;

-- Drop the old primary key.
ALTER TABLE tenants DROP CONSTRAINT tenants_pkey;

-- Rename the old slug-id (retained one release for forensics).
ALTER TABLE tenants RENAME COLUMN id TO legacy_slug_id;

-- Rename the UUID column to id and make it the PK.
ALTER TABLE tenants RENAME COLUMN id_uuid TO id;
ALTER TABLE tenants ADD PRIMARY KEY (id);

-- Re-point every child FK from id_uuid to the now-renamed id column.
-- The callback iterates the same list as V23 and re-emits each ADD CONSTRAINT
-- pointing at REFERENCES tenants(id). Because column rename is metadata-only,
-- existing FK definitions targeting tenants(id_uuid) become invalid — drop & re-add.
-- (Alternative: V24 can be skipped if V23 already targeted REFERENCES tenants(id_uuid)
--  and we keep that naming forever. We prefer the rename so the conceptual PK column
--  is named `id`.)

COMMIT;
```

Index for forensic lookups:
```sql
CREATE INDEX tenants_legacy_slug_id_idx ON tenants (legacy_slug_id) WHERE legacy_slug_id IS NOT NULL;
```

### 4.6 V25 — `operator_sessions` extension (additive, reversible)

```sql
ALTER TABLE operator_sessions
    ADD COLUMN user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    ADD COLUMN csrf_token  VARCHAR(64);   -- 32-byte random base64-url; rotated on login (M5b)

CREATE INDEX operator_sessions_user_id_idx ON operator_sessions (user_id) WHERE user_id IS NOT NULL;

-- Widen the source check (depends on V7 schema — verify before applying).
-- V7's audit_operator_login.source is the constrained column today.
-- For operator_sessions, source may not yet have a CHECK — if not, add one now:
ALTER TABLE operator_sessions
    ADD CONSTRAINT operator_sessions_source_chk
    CHECK (source IN ('BOOTSTRAP','HTTP_DELEGATE','USER_PASSWORD','USER_OIDC'));

-- Audit tables get user_id too.
ALTER TABLE audit_operator_login
    ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE audit_authz
    ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE audit_recorder_ops
    ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE SET NULL;

-- Backfill historical audit rows to the synthetic platform admin so the FK isn't
-- littered with NULLs for pre-M5a events (NULL is still allowed; this is just a tidy-up).
UPDATE audit_operator_login SET user_id = '00000000-0000-0000-0000-000000000001' WHERE user_id IS NULL;
UPDATE audit_authz          SET user_id = '00000000-0000-0000-0000-000000000001' WHERE user_id IS NULL;
UPDATE audit_recorder_ops   SET user_id = '00000000-0000-0000-0000-000000000001' WHERE user_id IS NULL;
```

### 4.7 V26+ (M5b)

Reserved for the M5b additions:
- **V26** — Postgres CHECK constraints + GUC plumbing on the five high-risk tables (§5 below).
- **V27** — Future: drop `tenants.legacy_slug_id` after a soak release.

---

## 5. GUC + CHECK belt-and-suspenders (M5b)

### 5.1 Setting the GUC per transaction

A `WorkspaceContextTransactionAdvice` `@Around` advice on every `@WorkspaceScoped` controller method opens a tx and executes:

```sql
SET LOCAL yaya.workspace_id = '<uuid string>';
```

`SET LOCAL` scopes the setting to the tx — it does not leak across pooled connections. The advice runs **after** `Guard.require` succeeds, so the value is always present and trusted.

### 5.2 CHECK constraints

```sql
-- profiles
ALTER TABLE profiles
  ADD CONSTRAINT profiles_workspace_guc_chk
  CHECK (tenant_id::text = current_setting('yaya.workspace_id', true));

-- capabilities
ALTER TABLE capabilities
  ADD CONSTRAINT capabilities_workspace_guc_chk
  CHECK (tenant_id::text = current_setting('yaya.workspace_id', true));

-- tools_registry
ALTER TABLE tools_registry
  ADD CONSTRAINT tools_registry_workspace_guc_chk
  CHECK (tenant_id::text = current_setting('yaya.workspace_id', true));

-- tenant_jwt_secrets
ALTER TABLE tenant_jwt_secrets
  ADD CONSTRAINT tenant_jwt_secrets_workspace_guc_chk
  CHECK (tenant_id::text = current_setting('yaya.workspace_id', true));

-- tenant_oidc_issuers
ALTER TABLE tenant_oidc_issuers
  ADD CONSTRAINT tenant_oidc_issuers_workspace_guc_chk
  CHECK (tenant_id::text = current_setting('yaya.workspace_id', true));
```

Notes:
- `current_setting('yaya.workspace_id', true)` returns NULL when unset (the second arg = "missing_ok"). When NULL, the CHECK passes for reads (Postgres CHECKs only fire on `INSERT`/`UPDATE`).
- The CHECK is a **write-canary**: if the Java guard ever forgets to set the GUC for a write, the INSERT/UPDATE fails loudly. Reads are uncontended.
- Reads outside a `@WorkspaceScoped` context (e.g. admin listing endpoints that need to span workspaces for a platform admin) work because they don't set the GUC and don't trigger the CHECK.

### 5.3 Verifying the canary

`GuardCheckConstraintIT` (M5b test):
1. Open a tx, `SET LOCAL yaya.workspace_id = '<uuid_A>'`.
2. Attempt `INSERT INTO profiles (id, tenant_id, …) VALUES (gen_random_uuid(), '<uuid_B>', …)`.
3. Assert Postgres raises `CheckViolation`.

---

## 6. FK cascade matrix

| Parent | Child | Cascade behavior on parent delete | Why |
|---|---|---|---|
| `users` | `user_workspace_memberships` | CASCADE | Membership rows are meaningless without the user. |
| `users` | `user_invitations` (via `invited_by`) | CASCADE | If the inviter is hard-deleted (rare; only post-scrub), drop their outstanding invitations. |
| `users` | `user_invitations` (via `consumed_by`) | SET NULL | Preserve invitation history; lose the consumer link. |
| `users` | `user_email_verification_tokens` | CASCADE | Tokens are pointless without the user. |
| `users` | `user_password_reset_tokens` | CASCADE | Same. |
| `users` | `audit_user_events` | SET NULL | Audit retention beats joinable identity. |
| `users` | `operator_sessions` | CASCADE | Sessions are pointless without the user. |
| `users` | `tenants.owner_user_id` | RESTRICT | Cannot delete a user who still owns a workspace; force transfer/archive first. |
| `users` | `tenants.created_by` | RESTRICT | Same — preserves a coherent "created by" chain. |
| `tenants` | every workspace-scoped child | CASCADE | A deleted workspace's data goes with it. (Soft archive avoids triggering this in normal ops.) |
| `tenants` | `user_workspace_memberships` | CASCADE | Memberships are pointless without the workspace. |
| `tenants` | `user_invitations` | CASCADE | Same. |

> **Decision:** `tenants` deletes CASCADE everything because in production the lifecycle is **soft-archive** (`archived_at`), not hard-delete. Hard-delete is reserved for incident response under a platform admin and intentionally takes the data with it.

---

## 7. Data lifecycle

### 7.1 Token cleanup

Nightly `@Scheduled` job `TokenCleanupCron.cleanup()`:

```sql
DELETE FROM user_email_verification_tokens WHERE expires_at < now() - INTERVAL '7 days';
DELETE FROM user_password_reset_tokens     WHERE expires_at < now() - INTERVAL '7 days';
DELETE FROM user_invitations               WHERE expires_at < now() - INTERVAL '30 days';  -- keep expired invitations 30d for audit
DELETE FROM email_outbox                   WHERE status IN ('SENT','DEAD') AND created_at < now() - INTERVAL '7 days';
```

### 7.2 GDPR PII scrub (M5b)

Nightly `UserScrubCron.scrub()`:

```sql
UPDATE users
   SET email             = 'deleted-' || id || '@scrubbed.local',
       display_name      = 'Deleted user',
       password_hash     = '',
       email_verified_at = NULL,
       last_login_at     = NULL
 WHERE status = 'DELETED' AND deleted_at < now() - INTERVAL '30 days'
   AND email NOT LIKE 'deleted-%@scrubbed.local';   -- idempotent
```

After scrub, the user row exists for FK integrity but has no PII. `audit_user_events.user_id` joins still work; the join just resolves to a tombstoned row.

### 7.3 Workspace archive vs delete

- **Archive (soft):** `UPDATE tenants SET archived_at = now()` — workspace disappears from `GET /v1/admin/workspaces`, but every child row is preserved for audit. Reads of archived workspaces return 404 to non-platform-admins.
- **Delete (hard):** never exposed via API. Reserved for SQL operator action; CASCADEs through every child table.

### 7.4 Session lifecycle

- User-session TTL = 12h.
- Bootstrap-session TTL = 1h.
- `OperatorSessionCleanup` (existing): periodic DELETE of `expires_at < now()`.
- Password reset confirm deletes all sessions for the user_id in the same tx.
- Account soft-delete deletes all sessions for the user_id in the same tx.

---

## 8. Indexes by query

Key admin-plane queries and the index they hit:

| Query | Index |
|---|---|
| `WorkspaceAccessGuard.require(userId, workspaceId)` — SELECT role from memberships | `user_workspace_memberships_pkey` (PK lookup) |
| `GET /v1/admin/workspaces` for user | `user_workspace_memberships_pkey` reverse (by user_id) — implicit since user_id is the first PK column |
| `GET /v1/admin/workspaces/{id}/members` | `user_workspace_memberships_workspace_idx` |
| `findByEmail` | `users_email_uniq` |
| Token consume (verify, reset, invite) | PK on `token_hash` |
| Pending invitation lookup by email (signup-redemption) | `user_invitations_email_pending_idx` |
| Pending invitation lookup by token (accept) | `user_invitations_token_hash_pending_uniq` |
| Audit listing by workspace | `audit_user_events_workspace_time_idx` |
| Audit listing by user | `audit_user_events_user_time_idx` |
| Audit listing by kind | `audit_user_events_kind_time_idx` |
| Audit JSONB detail search | `audit_user_events_detail_gin` |
| Outbox drain | `email_outbox_pending_idx` |
| Tenants `idOrCode` resolution | UUID parse → `tenants_pkey`; else `tenants_code_uniq` |
| Platform admin lookup | `users_platform_admin_idx` (partial) |

---

## 9. JPA entity mapping notes (for the implementer)

The TRD pins `accounts/jpa/UserEntity.java` etc. A few specifics that bite if missed:

- `UserEntity.email` annotated with `@Convert(converter = CitextConverter.class)` if your JPA driver doesn't natively support `CITEXT`. Otherwise `String` + database does the case folding.
- `UUID` columns map to `java.util.UUID` directly with `hibernate.use-streams-for-binary=false` (default in modern Spring Boot).
- `BYTEA` token columns map to `byte[]`. Hibernate handles padding cleanly.
- Multi-column primary key (`user_workspace_memberships(user_id, workspace_id)`) → `@IdClass` or `@EmbeddedId`. Prefer `@EmbeddedId(MembershipPk(UUID userId, UUID workspaceId))`.
- Token-consume queries must use `@Query("UPDATE … RETURNING …")` (native) or a follow-up SELECT inside the same tx — the JPA spec doesn't return updated rows.
- `audit_user_events.detail_json` mapped via `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6) into a `Map<String,Object>` or a typed record per kind.

---

## 10. Test data fixtures

For integration tests, seed via Flyway test migrations or `@Sql`:

```sql
-- Two users
INSERT INTO users (id, email, display_name, password_hash, status, email_verified_at)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'alice@test.io', 'Alice', '<argon2 of "Test123!@#$">', 'ACTIVE', now()),
  ('22222222-2222-2222-2222-222222222222', 'bob@test.io',   'Bob',   '<argon2 of "Test123!@#$">', 'ACTIVE', now());

-- Two workspaces per user (4 total)
INSERT INTO tenants (id, code, display_name, created_by, owner_user_id)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'alice-1', 'Alice Co', '11111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'alice-2', 'Alice Lab', '11111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', 'bob-1',   'Bob Co',   '22222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2', 'bob-2',   'Bob Lab',  '22222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222');

INSERT INTO user_workspace_memberships (user_id, workspace_id, role, added_by)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'OWNER', '11111111-1111-1111-1111-111111111111'),
  ('11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'OWNER', '11111111-1111-1111-1111-111111111111'),
  ('22222222-2222-2222-2222-222222222222', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', 'OWNER', '22222222-2222-2222-2222-222222222222'),
  ('22222222-2222-2222-2222-222222222222', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2', 'OWNER', '22222222-2222-2222-2222-222222222222');
```

`CrossUserIsolationIT` walks every admin endpoint asserting Bob cannot see Alice's workspaces and vice versa.

---

## 11. Storage estimates

For sizing the DB:

| Entity | Per-row size (rough) | Volume assumption | Steady-state size |
|---|---|---|---|
| `users` | ~400 B | 10k users | ~4 MB |
| `user_workspace_memberships` | ~80 B | 30k rows (avg 3 per user) | ~3 MB |
| `user_invitations` | ~250 B | 5k cumulative (7-day TTL → small at any time) | ~1 MB |
| `audit_user_events` | ~600 B (with JSONB) | 100/user/month × 10k users × 12 months = 12M | ~7 GB/year |
| Tokens | ~120 B | < 10k at any time (24h–7d TTL) | < 1 MB |
| `email_outbox` | ~2 kB (with body) | < 10k at any time | ~20 MB |
| `tenants` | ~500 B | < 10k | ~5 MB |

`audit_user_events` is the only table that grows linearly with use. Plan a partitioning strategy (by month, by workspace_id) when it crosses ~50 GB; not in M5b scope.

---

## 12. References

- [PRD](PRD.md), [TRD](TRD.md), [UI-UX](UI-UX.md), [App-Flow](App-Flow.md).
- `docs/design/yaya-agentic-user-accounts-design.md` §5 (data model), §10 (migration plan), §11 (threat model).
- `src/main/resources/db/migration/V1__core_schema.sql` — child tables enumerated in §4.4.
- `src/main/resources/db/migration/V9__tenants_registry.sql` — current `tenants` shape extended in §3.
- `src/main/resources/db/migration/V6__operator_sessions.sql` — `operator_sessions` shape extended in V25.
