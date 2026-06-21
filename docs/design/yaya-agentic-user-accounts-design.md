# yaya-agentic — User Accounts & Per-User Resource Scoping

> **Status:** Proposal (draft 1)
> **Companion to:** `docs/design/yaya-agentic-design.md` (the master architecture)
> **Implements / supersedes:** the concrete shape of milestone **M5 — multi-tenant-admin**
> **Scope:** the **admin / operator plane only**. The end-user `Principal`, `Authenticator`, `Authorizer`, and conversation runtime SPIs (master design §5.4 / §5.5 / §6) are unchanged.

---

## 0. TL;DR

Today, **any authenticated operator can touch any tenant**: there is no per-account ownership on the admin plane. The operator-auth layer (`operator_auth/*`, `OperatorAuthWebFilter`, `operator_sessions` table) authenticates *who is at the keyboard* but never asks *which workspaces they own*. `tenants.created_by` (V9) and the design comment in `TenantController` (line 112) call this out explicitly: "created_by lands here when M5 operator-auth wires a principal accessor in."

This design adds a **User** primitive above the existing Tenant model. Each user owns one or more **Workspaces** (= existing Tenants, renamed in UI only — the code keeps the `Tenant`/`TenantId` types unchanged). Every admin-plane resource — profiles, capabilities, tools, JWT secrets, OIDC issuers, identity-profile mappings, knowledge sources, recording strategies, sessions, audit rows — becomes reachable only through a workspace the requesting user is a member of. **User A cannot list, read, write, or probe-for-existence of User B's resources.** Unauthorized access returns **404, not 403** — existence is not leaked.

Self-serve signup (email + password) creates the user; email verification activates them and provisions a default workspace + OWNER membership in a single transaction. **Teams ship from day one**: workspaces can have multiple members across the four roles (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`), and owners can invite collaborators by email. The bootstrap operator (random-password break-glass from `OperatorBootstrap`) survives but is **off by default in prod** — users must register before the system is usable; the bootstrap path is opt-in for incident response and loudly audited.

`tenants.id` becomes a **UUID** (machine identifier); a new `tenants.code` column holds the human-readable slug (display, URLs). Routes accept either form.

The eleven SPIs in the master design (§5.1–§5.9, §6) **do not change.** We add two small admin-plane SPIs and a feature flag.

---

## 1. Goals & non-goals

### Goals
- Public self-serve registration: email + password, with verification and reset.
- **Hard isolation:** a user cannot see, list, read, write, or probe for existence of another user's resources.
- Each new user gets a default workspace on email verification.
- Reuse the existing `operator_sessions` mechanism — **one session layer**, not two.
- Preserve the bootstrap operator as a break-glass platform admin (off-by-default in prod, opt-in for incidents).
- **Teams from day one**: multiple users per workspace, role-graded access (`OWNER` / `ADMIN` / `MEMBER` / `VIEWER`), email invitations.
- Preserve the master design's eleven SPIs verbatim.

### Non-goals (this design)
- Billing, quotas, subscriptions.
- Org / department hierarchy above users.
- Federated SSO for users (Google / GitHub OIDC sign-in) — schema seam reserved, behavior deferred.
- Changes to the end-user `Principal` / `Authenticator` / conversation runtime — that's a different identity plane (master design §5.4).

---

## 2. Concepts

| Concept | Role | Lifetime |
|---|---|---|
| **User** | Registered, password-holding account that signs into the admin console. The identity the human types as. | Created at signup; soft-deletable. |
| **Workspace** | The existing `Tenant`. Owns every admin-plane resource. UI label; the code keeps `Tenant`/`TenantId`. Primary id is a UUID; human-readable `code` (slug) lives alongside. | Created on signup (default) or explicitly later. |
| **Membership** | Edge `(User × Workspace) → Role`. The single load-bearing predicate the access guard consults. | Created on signup (OWNER) or via accepted invitation (any role). |
| **Role** | `OWNER`, `ADMIN`, `MEMBER`, `VIEWER`. Strict subset semantics. **All four mintable from day one.** | Per membership. |
| **Invitation** | Pending offer to join a workspace as a specific role; redeemed via emailed token. | TTL 7 days; single-use. |
| **Operator** (existing) | The bootstrap break-glass identity. Becomes a synthetic platform admin; unchanged surface. | Bootstrapped at first boot. |
| **PlatformAdmin** | A flag on `User` (`is_platform_admin`). Bypasses workspace membership checks; loudly audited. | Granted to the bootstrap operator and to any user explicitly promoted. |

**Mental model:** *the user IS the operator*, just with a real identity, a real password they control, and a binding to a defined set of workspaces. The bootstrap operator becomes one specific kind of platform admin; it is not removed.

---

## 3. Identity & registration

### 3.1 Signup
- `POST /v1/auth/signup { email, displayName, password }`.
- Validation: RFC 5321 email; password ≥ 12 chars with class diversity. Argon2id (id, t=3, m=64MB, p=4) — reuses `OperatorBootstrap`'s primitive (no second crypto dependency).
- Email uniqueness: stored case-folded in a unique index (Postgres `CITEXT` or `LOWER(email)` expression index).
- Idempotent / enumeration-safe: response is always `202 Accepted` whether the email exists or not. If new, insert `users` row (`status = PENDING_VERIFICATION`), generate a 32-byte token, email it. If existing, send a "someone tried to sign up as you" email instead.
- **Default workspace is not created until verification** — prevents orphan workspaces from spam signups.

### 3.2 Email verification
- `GET /v1/auth/verify?token=…` activates the user (`status = ACTIVE`) and, in the same transaction:
  1. Inserts the default `tenants` row with `id = gen_random_uuid()` and `code = <slugified display name + 3-char suffix on collision>` (e.g. `acme-7f3`).
  2. Inserts an `OWNER` row into `user_workspace_memberships`.
  3. Stamps `tenants.created_by = users.id` and the denormalised `tenants.owner_user_id` (see §5.5).
- Token TTL: 24h. Resend endpoint with per-account cooldown (60s, 5/h).
- If the same email was already invited to a workspace (pending `user_invitations` row), the verification handler also redeems those invitations — the user lands logged in with both their default workspace and any pre-existing memberships.

### 3.3 Login
- Reuse `/v1/auth/login`. Body adds a discriminator alongside the existing operator strategies:
  ```json
  { "strategy": "user-password", "email": "...", "password": "..." }
  ```
- On success: mint an `operator_sessions` row with a new `source = USER_PASSWORD`, `subject = users.id`, and a new nullable column `user_id` populated (see §5.6). Cookie unchanged (`YAYA_SESSION`, HttpOnly, Secure, SameSite=Strict).
- Bootstrap operator login keeps working (`source = BOOTSTRAP`, `user_id = NULL`). Same session shape, different downstream authorization branch.

### 3.4 Password reset
- `POST /v1/auth/password-reset/request { email }` — always **204** (no enumeration). Mail only fires if the user exists & is ACTIVE.
- `POST /v1/auth/password-reset/confirm { token, newPassword }` — rotates the argon2 hash and **DELETEs all `operator_sessions` rows for that `user_id`** in the same transaction.

### 3.5 Optional: OIDC sign-in for users (deferred)
- A user may bind a Google / GitHub identity to their account in a future milestone. Schema seam: `user_oidc_identities (user_id, provider, sub)`. **Not** related to `tenant_oidc_issuers` (which is for end-user `Principal` verification — a different identity plane).

---

## 4. Authorization model

### 4.1 The single load-bearing predicate

Every admin-plane request resolves a `WorkspaceContext`:
```
WorkspaceContext { workspaceId: TenantId, requestingUser: User, role: Role }
```

Resolution rules:

1. Identify the workspace from the request: path variable (`/v1/admin/tenants/{id}/...`), body field, or query param. **The `"default"` fallback in `AdminController.requireTenant()` is removed.**
2. Look up `(user_id, workspace_id) → role` in `user_workspace_memberships` — single PK hit.
3. **No row → `404 Not Found`**, not `403 Forbidden`. The error body is identical to "tenant does not exist."
4. If `users.is_platform_admin = true`, skip the membership lookup and synthesise `role = PLATFORM_ADMIN`. The action is logged in `audit_user_events`.
5. Role is attached to the context for downstream method-level checks.

### 4.2 Roles

| Role | Read resources | Write resources | Manage members | Manage secrets (JWT/OIDC issuers) | Delete workspace |
|---|---|---|---|---|---|
| `OWNER` | ✔ | ✔ | ✔ | ✔ | ✔ |
| `ADMIN` | ✔ | ✔ | ✔ (cannot remove `OWNER`) | ✔ | ✘ |
| `MEMBER` | ✔ | ✔ | ✘ | ✘ | ✘ |
| `VIEWER` | ✔ | ✘ | ✘ | ✘ | ✘ |
| `PLATFORM_ADMIN` | ✔ all workspaces | ✔ all | ✔ all | ✔ all | ✔ all |

**All four roles are mintable from v1.** `OWNER` is auto-minted at signup; `ADMIN`, `MEMBER`, `VIEWER` are granted via the invitations flow (§8.1). `PLATFORM_ADMIN` is a separate flag on `users` — never granted via the workspace UI.

### 4.3 Enforcement — recommended (Option B) with belt-and-suspenders

| Option | Pros | Cons |
|---|---|---|
| **A. Postgres Row-Level Security** | Bulletproof; no Java path can leak. | Adds operational complexity; complicates the existing repository layer; harder to test against. |
| **B. Java aspect + repository filter (recommended)** | Fits today's repository layer; one filter handles cross-cutting `WHERE tenant_id = :ctx` injection; testable with existing `OperatorAuthWebFilter`-style tests. | Requires discipline: every admin controller method must declare its workspace binding; every repository must consult the context. |

**Recommended: B**, with a runtime safety net — set `SET LOCAL yaya.workspace_id = :ctx` at the start of every admin transaction, and add Postgres CHECK constraints on the highest-risk tables (`profiles`, `capabilities`, `tools_registry`, `tenant_jwt_secrets`, `tenant_oidc_issuers`) that fail loudly if a row's `tenant_id` doesn't match the GUC. The Java path stays primary; the DB-side check is the canary. If Option A is required by a future compliance bar, the design ports cleanly — only the `WorkspaceAccessGuard` and repository wiring change.

### 4.4 Endpoints that take a `tenant` query param
Today, `AdminController` accepts a `tenant` query/body param that defaults to `"default"`. **The default is removed.** All admin endpoints become:
- `tenant` / `workspaceId` param is **required**.
- The param must match a workspace the user is a member of, else 404.
- The cross-tenant profile clone endpoint (`POST /v1/admin/tenants/{src}/profiles/{profileId}/{version}/clone`) requires membership in **both** source and destination workspaces.

### 4.5 Listing endpoints
- `GET /v1/admin/workspaces` — returns only the requesting user's workspaces.
- Resource list endpoints (`/profiles`, `/capabilities`, `/tools`, …) require an explicit `workspaceId` and only return rows for that workspace.
- Platform admin sees all workspaces (audited).

---

## 5. Data model

All new migrations land as V20+. All new tables use `CASCADE` on user/tenant deletion.

### 5.1 `users`
```sql
CREATE TABLE users (
  id                  UUID PRIMARY KEY,
  email               CITEXT NOT NULL UNIQUE,
  display_name        VARCHAR(255) NOT NULL,
  password_hash       TEXT NOT NULL,                 -- argon2id encoded
  status              VARCHAR(32) NOT NULL,          -- PENDING_VERIFICATION, ACTIVE, SUSPENDED, DELETED
  is_platform_admin   BOOLEAN NOT NULL DEFAULT false,
  email_verified_at   TIMESTAMPTZ,
  last_login_at       TIMESTAMPTZ,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at          TIMESTAMPTZ                    -- soft delete
);
```

### 5.2 `user_workspace_memberships`
```sql
CREATE TABLE user_workspace_memberships (
  user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  workspace_id   VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  role           VARCHAR(32) NOT NULL,    -- OWNER | ADMIN | MEMBER | VIEWER
  added_by       UUID REFERENCES users(id),
  added_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, workspace_id)
);
CREATE INDEX ix_uwm_by_workspace ON user_workspace_memberships(workspace_id);
```

### 5.3 Token tables
```sql
CREATE TABLE user_email_verification_tokens (
  token_hash    BYTEA PRIMARY KEY,                   -- sha256(raw token)
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at    TIMESTAMPTZ NOT NULL,
  consumed_at   TIMESTAMPTZ
);
CREATE TABLE user_password_reset_tokens (
  token_hash    BYTEA PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at    TIMESTAMPTZ NOT NULL,
  consumed_at   TIMESTAMPTZ
);
```

### 5.4 `user_invitations` (reserved for teams v2)
```sql
CREATE TABLE user_invitations (
  id            UUID PRIMARY KEY,
  workspace_id  VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  email         CITEXT NOT NULL,
  role          VARCHAR(32) NOT NULL,
  token_hash    BYTEA NOT NULL,
  invited_by    UUID NOT NULL REFERENCES users(id),
  expires_at    TIMESTAMPTZ NOT NULL,
  consumed_at   TIMESTAMPTZ,
  consumed_by   UUID REFERENCES users(id)
);
```

### 5.5 `tenants` table changes — UUID id + human-readable code

**The change:** `tenants.id` becomes a `UUID` (machine identifier, never changes, used in all FKs). A new `tenants.code VARCHAR(64) UNIQUE NOT NULL` holds the human-readable slug (display, URLs, log lines). All ~20 tables with a `tenant_id VARCHAR(64)` FK are migrated to `tenant_id UUID`.

**New columns on `tenants`:**
- `id UUID PRIMARY KEY` (was `VARCHAR(64)`).
- `code VARCHAR(64) UNIQUE NOT NULL` — slugified display name; collision-safe (3-char random suffix on conflict).
- `owner_user_id UUID NOT NULL REFERENCES users(id)` — denormalised "primary owner" for cheap `WHERE owner_user_id = ?` listings. Kept in sync with the canonical `OWNER` membership by service code.
- `created_by UUID NOT NULL REFERENCES users(id)` — backfilled from V21, then `NOT NULL` enforced in V22.

**Migration (V21 → V24) is one-time-only; this milestone owns the cutover:**

| Step | Migration | What happens |
|---|---|---|
| V21 | seed users + memberships | Create synthetic `platform-admin@local` (password hash = never-matchable sentinel; login only via bootstrap-operator path). Insert OWNER membership rows pointing at every existing tenant. |
| V22 | add `tenants.code` + `tenants.id_uuid` | `ALTER TABLE tenants ADD COLUMN code VARCHAR(64); ADD COLUMN id_uuid UUID DEFAULT gen_random_uuid() NOT NULL;` Backfill `code = id` for every row (existing slug becomes the code). Add `UNIQUE (code)`. |
| V23 | rewrite FKs on all child tables | For every table T with `tenant_id VARCHAR(64)`: add `tenant_id_uuid UUID`; backfill via `UPDATE T SET tenant_id_uuid = (SELECT id_uuid FROM tenants WHERE tenants.id = T.tenant_id)`; drop old FK + column; rename `tenant_id_uuid` → `tenant_id`; add `REFERENCES tenants(id_uuid)`. Mechanical — driven by a single Flyway-callback that enumerates the ~20 tables. |
| V24 | swap PK | Drop `tenants_pkey`; rename `id` → `legacy_slug_id` (kept for one release for forensics), `id_uuid` → `id`; `ALTER TABLE tenants ADD PRIMARY KEY (id);` Update all FKs to reference the renamed `id` column. Drop `legacy_slug_id` in the *next* release after a soak. |

**`TenantId` type change.** `core/Ids.java`'s `TenantId` becomes a UUID wrapper. The `code` is a separate value type — `TenantCode` — used only at the edge (URL parsing, display). Internal joins, repository methods, audit rows, the `WorkspaceContext` — all use `TenantId` (UUID).

**Routes accept either form.** `/v1/admin/workspaces/{idOrCode}` — handler tries UUID parse first; on failure, looks up by `code`. This keeps human-friendly URLs in the admin console without polluting the storage layer with slug semantics.

### 5.6 `operator_sessions` changes
- Add `user_id UUID NULL REFERENCES users(id) ON DELETE CASCADE` — NULL for bootstrap-operator sessions, populated for user sessions.
- Add `source` enum values: `USER_PASSWORD`, `USER_OIDC` (future).

### 5.7 Audit tables
- `audit_authz`, `audit_recorder_ops`, `audit_operator_login`: add `user_id UUID NULL`. Backfill with the synthetic platform-admin id for historical rows.
- New table `audit_user_events`:
  ```sql
  CREATE TABLE audit_user_events (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID REFERENCES users(id),
    workspace_id VARCHAR(64) REFERENCES tenants(id),
    kind        VARCHAR(64) NOT NULL, -- SIGNUP, VERIFY, LOGIN, LOGOUT, PASSWORD_RESET_REQUEST,
                                      -- PASSWORD_RESET_CONFIRM, ROLE_GRANT, ROLE_REVOKE,
                                      -- WORKSPACE_CREATE, WORKSPACE_ARCHIVE, PLATFORM_ADMIN_ACTION
    detail_json JSONB,
    client_ip   INET,
    user_agent  TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  ```

---

## 6. SPI changes

The eleven runtime SPIs from the master design **do not change** — `Personality`, `Profile`, `Authenticator`, `Authorizer`, `ToolHandler`, `Retriever`, `KnowledgeLoader`, `ConversationRecorder`, `RecorderRouter`, `ConversationSummarizer`, `ConversationEngine`. Their contracts are about end-user conversations and stay frozen.

Two **admin-plane** SPIs are added inside `operator_auth/`:

```java
package com.yayatechandinnovations.yayaagentic.operator_auth;

public interface OperatorAccountService {
    User register(SignupRequest req);
    void verifyEmail(String rawToken);
    AuthenticationResult login(UserCredentials creds);
    void requestPasswordReset(String email);
    void confirmPasswordReset(String rawToken, String newPassword);
    Optional<User> findByEmail(String email);
    List<Membership> workspacesFor(UserId userId);
}

public interface WorkspaceAccessGuard {
    /** Returns the membership if the user can access the workspace; throws → 404 (not 403). */
    Membership require(UserId user, TenantId workspace, Role atLeast);
    /** Filters a candidate set to those visible to this user. */
    Set<TenantId> visible(UserId user, Collection<TenantId> candidates);
}
```

**Why not extend `Authorizer`?** `Authorizer` operates on `PermissionRequirement` in conversation context (a tool call, a knowledge read; master design §5.5). Admin-plane access is a different shape (URL → workspace → membership). Conflating them would force a leaky abstraction and erode the trust boundary the design takes pains to draw.

---

## 7. Request flow

1. Browser sends `POST /v1/auth/login` with `{ strategy: "user-password", email, password }`.
2. `OperatorAccountService.login()` validates argon2; mints `operator_sessions` row with `source=USER_PASSWORD`, `user_id=<users.id>`, `subject=<users.id>`. Sets `YAYA_SESSION` cookie.
3. Browser hits `GET /v1/admin/workspaces`. `OperatorAuthWebFilter` reads cookie → builds `Operator` with `attributes.user_id` populated.
4. Handler reads `OperatorContext.current(...)`, extracts `userId`, calls `OperatorAccountService.workspacesFor(userId)`, returns the list (containing only the user's workspaces).
5. Browser hits `GET /v1/admin/tenants/{tenantId}/oidc-issuers`. `WorkspaceAccessGuard.require(userId, tenantId, Role.MEMBER)` runs first — returns 404 if no membership row, otherwise the request proceeds.
6. Every repository call inside the handler runs inside a transaction prefaced by `SET LOCAL yaya.workspace_id = :tenantId` (§4.3 belt-and-suspenders).

---

## 8. API surface

### 8.1 New endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/v1/auth/signup` | none | rate-limited per IP and per email |
| `GET` | `/v1/auth/verify?token=…` | none (token-bearer) | activates account + creates default workspace |
| `POST` | `/v1/auth/login` | none | strategy: `bootstrap` \| `delegate` \| `user-password` |
| `POST` | `/v1/auth/logout` | session | exists today |
| `GET` | `/v1/auth/me` | session | now returns `{ user, memberships, platformAdmin }` |
| `POST` | `/v1/auth/password-reset/request` | none | always 204 (no enumeration) |
| `POST` | `/v1/auth/password-reset/confirm` | none (token-bearer) | invalidates all sessions for the user |
| `GET` | `/v1/admin/workspaces` | user session | lists *my* workspaces |
| `POST` | `/v1/admin/workspaces` | user session | creates a new workspace; caller becomes `OWNER` |
| `GET` | `/v1/admin/workspaces/{id}` | member | workspace detail |
| `PATCH` | `/v1/admin/workspaces/{id}` | `ADMIN`+ | rename, change settings |
| `DELETE` | `/v1/admin/workspaces/{id}` | `OWNER` | soft-archive (audit retained) |
| `GET` | `/v1/admin/workspaces/{id}/members` | member | lists members and their roles |
| `POST` | `/v1/admin/workspaces/{id}/invitations` | `OWNER`/`ADMIN` | invite by email + role; sends invitation mail |
| `GET` | `/v1/admin/workspaces/{id}/invitations` | `OWNER`/`ADMIN` | list pending invitations |
| `DELETE` | `/v1/admin/workspaces/{id}/invitations/{invId}` | `OWNER`/`ADMIN` | revoke pending invitation |
| `GET` | `/v1/auth/invitations/accept?token=…` | none (token-bearer) | if recipient is signed-in → mint membership; if not → redirect to signup with token preserved |
| `PATCH` | `/v1/admin/workspaces/{id}/members/{userId}` | `OWNER`/`ADMIN` | change role (ADMIN cannot promote to OWNER or demote OWNER) |
| `DELETE` | `/v1/admin/workspaces/{id}/members/{userId}` | `OWNER`/`ADMIN` | remove member (cannot remove the last OWNER) |

### 8.2 Existing endpoints — hardened
- `POST /v1/admin/tenants` — kept as alias for `POST /v1/admin/workspaces` so the Flutter admin paths don't break in one big-bang. The aliased handler is identical.
- Every `?tenant=…` / body `tenant` param becomes **required** and validated via `WorkspaceAccessGuard.require()`. The `"default"` fallback is removed.
- The cross-tenant profile clone endpoint requires membership in both source and destination workspaces.

### 8.3 Bootstrap-operator backwards compatibility
- A bootstrap session continues to satisfy every admin endpoint because `WorkspaceAccessGuard.require()` short-circuits when `Operator.attributes.is_platform_admin = true`.
- Every such action is logged in `audit_user_events` with `kind = PLATFORM_ADMIN_ACTION` and the target workspace id, so support actions are traceable.
- Cookie TTL for bootstrap sessions is shorter than for user sessions (e.g. 1h vs 12h).

---

## 9. Flutter admin app

- **New screens** under `lib/features/auth/`: `signup_screen.dart`, `verify_email_screen.dart`, `forgot_password_screen.dart`, `reset_password_screen.dart`, `accept_invitation_screen.dart`.
- **New screens** under `lib/features/workspaces/`: `workspace_list_screen.dart`, `workspace_settings_screen.dart`, `members_screen.dart` (member list + role chips + remove button), `invite_member_dialog.dart` (email + role selector).
- **Workspace switcher** in the admin shell header, pinned across nav. On switch, all admin feature providers (`profilesProvider`, `capabilitiesProvider`, `toolsProvider`, etc.) are invalidated and re-mounted with the new workspace id. Displays `workspace.code` (human-readable), keyed internally on `workspace.id` (UUID).
- A new Riverpod `currentWorkspaceProvider` that every feature provider depends on.
- The existing `operator_auth_screen.dart` (HTTP delegate config + bootstrap config) moves into a **Platform settings** section, only rendered when `me.platformAdmin === true`. Regular users never see it.
- API client (`dio` interceptor) attaches the `YAYA_SESSION` cookie (already does); on 401, redirects to `/login`; on 404 from a workspace-scoped resource, treats it as "no membership" and surfaces a friendly "you don't have access to this workspace" message.
- Role-aware UI: write actions are disabled and the corresponding routes hidden when the member's effective role lacks the required grant. Server-side authorization is still authoritative — the UI is a UX layer, not a trust boundary.

---

## 10. Migration plan

1. **V20** — create `users`, `user_workspace_memberships`, `user_invitations`, token tables, `audit_user_events`.
2. **V21** — seed: synthetic `platform-admin@local` user (no usable password; bootstrap path only); OWNER memberships for every existing tenant.
3. **V22** — `tenants.code` + `tenants.id_uuid` (UUID) columns; `tenants.created_by NOT NULL`; `tenants.owner_user_id` populated from V21.
4. **V23** — FK rewrite across every child table (`tenant_id VARCHAR(64)` → `tenant_id UUID`) via a Flyway callback that enumerates the ~20 tables. Old VARCHAR FKs dropped at the end.
5. **V24** — swap PK on `tenants`: old `id` (slug) renamed to `legacy_slug_id` (retained for one release for forensics); `id_uuid` renamed to `id` and made PK. All cross-table FKs re-pointed.
6. **V25** — `operator_sessions.user_id`; widen `source` enum (`USER_PASSWORD`, `USER_OIDC`).
7. **Code rollout** behind feature flag `yaya.agentic.user-accounts.enabled` (default `false`). With the flag off, the system behaves as today (operator-bootstrap is the only login path; admin endpoints accept the legacy `tenant` query param mapped through `code`). With it on, the signup/login UI ships, `AdminController.requireTenant()`'s `"default"` fallback is removed, and the bootstrap-operator login is off by default.
8. **Cutover** in a single release; rollback is "flip the flag back, leave the schema in place." The UUID migration is *not* reversible — it ships only when the flag is ready to flip on.
9. **Bootstrap operator is off by default in prod once V25 lands**, controlled by `yaya.agentic.operator-auth.bootstrap.enabled` (default `false` in prod, `true` in dev / local profiles). Users must register first; the bootstrap path is opt-in for incident response.
10. **Next release** — drop `legacy_slug_id` after the soak window confirms no rollback needed.

---

## 11. Threat model & isolation guarantees

| Threat | Mitigation |
|---|---|
| **Existence leak** (probing `GET /v1/admin/tenants/foo` to discover real tenant ids) | `WorkspaceAccessGuard` returns 404, not 403; error body is identical to "tenant does not exist." Same applies to all nested resources. |
| **Cross-workspace data leak via shared query** | Repositories accept a `WorkspaceContext` and inject `WHERE tenant_id = :ctx`. Belt-and-suspenders: `SET LOCAL yaya.workspace_id` + Postgres CHECK on high-risk tables. Repository tests assert "no query that omits the predicate compiles." |
| **Stale session after password reset** | All `operator_sessions` rows for the user are deleted in the same transaction as the password rotation. |
| **Brute force on `/login`** | Per-IP exponential backoff (Redis-backed; Redis is already a hard dependency); per-account lockout after N failures with an email notification. |
| **Email enumeration** | Signup returns 202 whether email exists or not; password-reset request returns 204 always. |
| **Bootstrap operator misuse** | Rate-limited; every action tagged in `audit_user_events`; shorter cookie TTL; bootstrap login is disabled by default in prod once at least one platform-admin user exists (configurable). |
| **Email-verification / reset token replay** | Single-use: `consumed_at` set inside the same transaction. Partial unique index `WHERE consumed_at IS NULL` makes replay a constraint violation, not an application-level check. |
| **Workspace deletion blast radius** | Soft-archive only. OWNER must confirm with a typed name match. Cascade via an outbox row processed asynchronously. |
| **Token storage** | Only `sha256(rawToken)` lives in the DB. Raw tokens appear once, in the email body. |
| **Tenant code collision / squatting** | `tenants.code` is `UNIQUE`. Signup retries with a 3-char random suffix on conflict. Reserved code list (e.g. `admin`, `api`, `auth`, `health`) blocks impersonation of system paths. Internal joins never use `code` — only `id` (UUID) — so code reuse after archival is safe. |
| **Cross-workspace invitation leak** | Invitation token is `sha256`-stored, single-use, TTL 7 days. Email content reveals only the workspace `code` (display name), never the UUID or member list. Revoking an invitation marks `consumed_at = now()` so a leaked email cannot be redeemed. |
| **Audit retention vs GDPR delete** | Soft-delete user row first (`status = DELETED`, `deleted_at` set, PII fields nulled or pseudonymised). Hard-delete after a 30-day window. Audit rows keep the `user_id` UUID but lose joinable PII. |

---

## 12. Decisions (formerly open questions)

All seven open questions are resolved. Decisions captured here so the implementing engineer doesn't re-litigate them.

1. **Email delivery → SendGrid.** Reuse the existing SendGrid account. New config namespace `yaya.agentic.email.*` with provider-pluggable interface so a future switch (SES, SMTP) is one Bean change. API key in env (`SENDGRID_API_KEY`); never in DB. Templated mails: signup-verification, password-reset, invitation, "someone tried to sign up as you" (enumeration mitigation).
2. **Tenant id → UUID; tenant code → kept.** `tenants.id` is `UUID` (machine identifier; FKs, joins, logs). `tenants.code` is the human-readable slug (display, URLs, log lines). Migration plan in §5.5 and §10. Routes accept either form via `idOrCode` discriminator.
3. **Teams from day one.** All four roles (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`) mintable in v1. Invitations flow ships in M5a (§8.1). Schema constraint: every workspace must have at least one `OWNER` at all times (enforced in the membership-remove and role-change paths).
4. **HTTP-delegate operators → auto-provision a `users` row.** On first successful delegate login, the system creates a `users` row from the IdP-asserted subject/email, marks `status = ACTIVE` (delegate IdP is trusted), and runs the same workspace bootstrap. From the second login onward, the delegate is just another sign-in strategy for an existing user. One authorization path; no legacy bypass.
5. **Rate limiting → Redis-backed, mandatory.** Per-IP and per-account counters on `/signup`, `/login`, `/password-reset/request`, `/password-reset/confirm`, `/invitations/accept`. Algorithm: sliding-window with exponential backoff on consecutive failures. Limits config under `yaya.agentic.rate-limit.*`. A 429 response surfaces `Retry-After`. Login lockout after N consecutive failures triggers an email notification to the account holder.
6. **CSRF → double-submit token from `/v1/auth/me`.** `SameSite=Strict` cookie + a server-issued CSRF token returned in the `/v1/auth/me` payload. All state-changing admin requests must echo the token in an `X-Yaya-CSRF` header. Token rotates on login and password-reset.
7. **Bootstrap operator → off by default in prod.** Config knob `yaya.agentic.operator-auth.bootstrap.enabled` defaults to `false` in the `prod` profile (and any unset profile), `true` in `local` / `dev`. Operators must register a user account first; the bootstrap path is opt-in for incident response. The random-password ritual still happens at first boot in dev, just never in prod. The platform-admin role is granted to the first registered user *only* if a one-time env var (`YAYA_FIRST_USER_IS_PLATFORM_ADMIN=true`) is set during initial bring-up.

---

## 13. Milestone breakdown

Folds into **M5 (multi-tenant-admin)** as the concrete spec. Two sub-milestones (teams are no longer deferred):

### M5a — User accounts + teams (core)
**Blocks everything else; nothing ships behind it until acceptance criteria green.**

Backend:
- V20–V25 migrations including the UUID + code split on `tenants` and child FK rewrite.
- `OperatorAccountService`, `WorkspaceAccessGuard`, `InvitationService`.
- Repository-layer workspace filter + Postgres `SET LOCAL yaya.workspace_id` plumbing.
- Endpoints: `/v1/auth/{signup,verify,login,logout,password-reset/*,invitations/accept}`, `/v1/admin/workspaces/*`, `/v1/admin/workspaces/{id}/{members,invitations}`.
- SendGrid integration (`yaya.agentic.email.*`); mail templates (verify, reset, invite, enumeration-decoy).
- HTTP-delegate operator auto-provisioning.
- `audit_user_events` writes for every state-changing path.
- Feature flag `yaya.agentic.user-accounts.enabled` wired.

Flutter:
- `signup_screen`, `verify_email_screen`, `forgot_password_screen`, `reset_password_screen`, `accept_invitation_screen`.
- `workspace_list_screen`, `workspace_settings_screen`, `members_screen`, `invite_member_dialog`.
- Workspace switcher in admin shell; `currentWorkspaceProvider`.
- Role-aware UI gating on member-management routes.

### M5b — Hardening, observability, GDPR
Backend:
- Redis-backed rate limiting (per-IP, per-account) on every public auth endpoint.
- CSRF double-submit token from `/v1/auth/me`; `X-Yaya-CSRF` enforced on mutating routes.
- `audit_user_events` UI surface (`/v1/admin/audit/user-events` with filters).
- Platform-admin gating on the existing operator-auth config screen (moves under "Platform settings").
- GDPR soft-delete pipeline (30-day window then PII scrub).
- Postgres CHECK constraints on the high-risk tables (`profiles`, `capabilities`, `tools_registry`, `tenant_jwt_secrets`, `tenant_oidc_issuers`) for the `SET LOCAL` workspace-id canary.
- Drop `tenants.legacy_slug_id` after a release of soak.

### Acceptance criteria

Inherits the master design §17 M5 entry, plus:

- **Repository scan test** (M5a): a build-failing reflection test that walks every `@Repository` and confirms every method either declares `WorkspaceContext` / `TenantId` in the signature or is annotated `@WorkspaceUnscoped` (only allowed on `users`, `user_workspace_memberships`, `user_invitations`, and session tables).
- **Cross-user isolation test** (M5a): integration test that signs up two users `A` and `B`, each with two workspaces, and asserts every read/write/list endpoint returns `404` across the boundary — including resource lookup by code, audit listing, profile clone, knowledge-source reindex.
- **Teams test** (M5a): A invites B as MEMBER; B accepts; B can write profiles but not invite; B cannot change members; A demotes B to VIEWER; B can no longer write.
- **Last-OWNER protection test** (M5a): cannot demote / remove the sole OWNER of a workspace; the API returns `409 Conflict`.
- **Migration test** (M5a): V21–V25 backfill produces exactly one OWNER membership per pre-existing tenant; every child-table row's `tenant_id` (now UUID) resolves to the right `tenants.code` post-V24.
- **Rate-limit test** (M5b): N+1 failed logins from the same IP trigger 429 with `Retry-After`; from the same account, trigger lockout + notification email.
- **CSRF test** (M5b): a state-changing request without `X-Yaya-CSRF` is `403`.
- **GDPR test** (M5b): soft-delete then cron-driven scrub clears PII from `users` and dependent tables but preserves `audit_user_events` rows with the UUID intact.

---

## 14. What does NOT change

The non-negotiables from `CLAUDE.md` and master design §12 remain in force unchanged:

1. The LLM is never trusted — every tool call still goes through schema validation → `Authorizer` → typed dispatch.
2. No tool can be called for a profile that doesn't list it.
3. AuthZ denials still carry two reasons (`userSafeReason`, `auditReason`).
4. The engine still never reads or writes conversation storage directly — `ConversationRecorder` only.
5. Tool results and retrieved chunks are still untrusted data.
6. HTTP tools still never silently forward tokens — `AuthForwarding` is explicit.
7. Knowledge sources still have their own `AccessRequirement`, silently dropped if ineligible.
8. Quick-replies are still engine-emitted `UiHint` events, never LLM output.
9. The `Personality → Profile → variable-suffix` ordering in `PromptBuilder` is untouched (it's tuned for prompt caching).

This design adds an outer ring of access control on the admin plane. The trust boundaries inside the conversation runtime are unchanged.
