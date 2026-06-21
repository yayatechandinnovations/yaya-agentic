# User Accounts & Workspaces — Product Requirements (PRD)

> **Feature:** Self-serve user accounts, teams, and per-workspace resource isolation for the yaya-agentic admin console.
> **Implements:** `docs/design/yaya-agentic-user-accounts-design.md` (master design for this feature).
> **Maps to milestone:** M5 (split into M5a *core* + M5b *hardening*).
> **Status:** Draft v1 — implementation-ready.
> **Companion docs:** [TRD](TRD.md) · [UI-UX](UI-UX.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).

---

## 1. Problem & Vision

### 1.1 Problem statement

Today, the yaya-agentic admin console authenticates *who is at the keyboard* (via `operator_auth/*`) but never asks *which workspaces they own*. Any authenticated operator can read, modify, or delete any tenant's profiles, capabilities, tools, JWT secrets, OIDC issuers, sessions, and audit rows. There is also no public path to sign up — every operator depends on the bootstrap break-glass identity baked into `OperatorBootstrap`.

The platform cannot ship to external customers in this state. Two customers cannot share an installation without leaking each other's configuration; an internal team cannot onboard a colleague without sharing the platform-wide bootstrap password.

### 1.2 Vision

The admin plane becomes a **multi-tenant SaaS surface** with hard per-user isolation:

- Anyone can self-serve a workspace via email + password.
- Each workspace is owned by one user and can be shared with teammates across four roles.
- A user can see, list, read, or write **only** resources inside workspaces they belong to. Existence of out-of-workspace resources is never leaked (404, not 403).
- The bootstrap break-glass identity survives but is off-by-default in production.
- The eleven runtime SPIs from the master design (`Personality`, `Profile`, `Authenticator`, `Authorizer`, `ToolHandler`, `Retriever`, `KnowledgeLoader`, `ConversationRecorder`, `RecorderRouter`, `ConversationSummarizer`, `ConversationEngine`) do not change. This is an outer ring of access control on the admin plane only.

### 1.3 Success metrics

| # | Metric | Target | How measured |
|---|---|---|---|
| M1 | Self-serve signup completion rate | ≥ 60% of started signups verify email within 24h | `audit_user_events` (`SIGNUP` → `VERIFY`) funnel |
| M2 | Cross-user isolation defects | **0** confirmed leaks in any release | Integration test suite (§7.3); production audit log diff |
| M3 | Time-to-first-conversation for a new tenant | ≤ 10 minutes from signup to first playground turn | Wall-clock from `SIGNUP` event to first session start for that user's workspace |
| M4 | Bootstrap-operator use in prod | < 1% of admin requests | Ratio of `source = BOOTSTRAP` to total authenticated admin requests |
| M5 | Team invitation acceptance | ≥ 70% of sent invitations accepted within 7-day TTL | `user_invitations` (`consumed_at` non-null / total sent) |
| M6 | UUID migration safety | Zero data-loss incidents; zero referential-integrity errors | `V21–V24` migration tests; production runbook dry-run |

---

## 2. Personas

### 2.1 Workspace Owner (`OWNER`)
A founder, ops lead, or solo developer who signed up themselves. Wants to spin up a profile, plug in tools, and get a playground conversation working in under 10 minutes. Pays for the workspace, controls who else gets in, owns secrets (JWT, OIDC).

**Top jobs:** sign up; create a workspace; invite teammates; configure profiles/capabilities/tools; rotate secrets; delete workspace.

### 2.2 Workspace Admin (`ADMIN`)
A senior engineer the owner trusts with everything except deleting the workspace and demoting the owner. Configures auth bindings, manages members (except owners), edits profiles.

**Top jobs:** invite/remove members below the OWNER tier; manage secrets; configure auth bindings.

### 2.3 Workspace Member (`MEMBER`)
A teammate who builds profiles and tools but doesn't manage people or rotate JWT/OIDC secrets.

**Top jobs:** create/edit profiles, capabilities, tools, knowledge sources; run playground tests.

### 2.4 Workspace Viewer (`VIEWER`)
A stakeholder — PM, support engineer, security reviewer — who needs read-only access to inspect what was configured. Cannot mutate anything.

**Top jobs:** browse profiles, read sessions, audit recorder ops.

### 2.5 Platform Admin (`PLATFORM_ADMIN`)
A staff identity flagged on `users.is_platform_admin = true`. Bypasses workspace membership checks; every action is audited. The bootstrap operator is one specific platform admin.

**Top jobs:** incident response; cross-tenant debugging; granting another user the platform-admin flag.

### 2.6 Bootstrap Operator (existing, hardened)
The legacy break-glass identity from `BootstrapOperatorAuthenticator`. Now off-by-default in prod; surfaces as a synthetic platform admin when enabled. Used only for incident response with shorter session TTL and louder audit.

---

## 3. User stories

Stories use the format **As a `<role>`, I want `<capability>` so that `<value>`.** Each carries an ID for traceability in the TRD and acceptance tests.

### 3.1 Identity (M5a)

| ID | Story | Acceptance gist |
|---|---|---|
| US-01 | As an anonymous visitor, I want to sign up with email + password so I can get a workspace. | `POST /v1/auth/signup` → 202 (enumeration-safe) → verification email. |
| US-02 | As a new user, I want to verify my email so my account activates and I get a default workspace. | `GET /v1/auth/verify?token=…` → ACTIVE + default workspace + OWNER membership, all in one tx. |
| US-03 | As a returning user, I want to sign in with email + password. | `POST /v1/auth/login` with `strategy=user-password` → `YAYA_SESSION` cookie. |
| US-04 | As a user who forgot my password, I want to reset it via email without anyone learning my email is registered. | `POST /v1/auth/password-reset/request` always returns 204. |
| US-05 | As a user who used the reset link, I want my old sessions invalidated. | Confirm step deletes every `operator_sessions` row for that `user_id` in the same tx. |
| US-06 | As a user, I want to see my profile (email, display name, memberships, CSRF token). | `GET /v1/auth/me`. |
| US-07 | As a user, I want to log out, ending my session immediately. | `POST /v1/auth/logout` deletes the session row server-side. |

### 3.2 Workspaces & isolation (M5a)

| ID | Story | Acceptance gist |
|---|---|---|
| US-10 | As an OWNER, I want to create additional workspaces beyond my default. | `POST /v1/admin/workspaces` mints workspace + OWNER membership. |
| US-11 | As any member, I want to list workspaces I belong to. | `GET /v1/admin/workspaces` returns only `WHERE user_id = me`. |
| US-12 | As any member, I want to switch the active workspace in the admin shell. | Workspace switcher (UI); every feature provider reactively re-mounts. |
| US-13 | As any user, I want to be unable to even *probe for existence* of another user's workspace. | Out-of-workspace access returns **404 with identical body to "tenant does not exist."** |
| US-14 | As an ADMIN, I want to rename my workspace and edit its settings. | `PATCH /v1/admin/workspaces/{id}`. |
| US-15 | As an OWNER, I want to soft-archive a workspace I no longer need. | `DELETE /v1/admin/workspaces/{id}` flips a flag; data retained for audit. |
| US-16 | As a human, I want workspace URLs to use a slug (`code`) instead of a UUID. | Routes accept `{idOrCode}`; UI displays `code`. |

### 3.3 Teams (M5a)

| ID | Story | Acceptance gist |
|---|---|---|
| US-20 | As an OWNER/ADMIN, I want to invite a teammate by email + role. | `POST /v1/admin/workspaces/{id}/invitations`; SendGrid mail with token. |
| US-21 | As an OWNER/ADMIN, I want to list pending invitations. | `GET …/invitations`. |
| US-22 | As an OWNER/ADMIN, I want to revoke a pending invitation. | `DELETE …/invitations/{invId}` sets `consumed_at`. |
| US-23 | As an invited user, I want to accept by clicking a link in my email. | `GET /v1/auth/invitations/accept?token=…` mints membership; redirects to signup if not registered. |
| US-24 | As an OWNER, I want to change a member's role. | `PATCH …/members/{userId}`. ADMINs cannot promote to OWNER or demote OWNER. |
| US-25 | As an OWNER, I want to remove a member. | `DELETE …/members/{userId}`. Cannot remove the **last** OWNER (returns 409). |
| US-26 | As a workspace, I have at least one OWNER at all times. | Last-OWNER protection enforced in member-remove and role-change paths. |

### 3.4 Platform admin & bootstrap (M5a)

| ID | Story | Acceptance gist |
|---|---|---|
| US-30 | As a platform admin, I want to access any workspace without joining it (audited). | `is_platform_admin = true` short-circuits the guard; every action emits `PLATFORM_ADMIN_ACTION` to `audit_user_events`. |
| US-31 | As the first user during bring-up, I want to optionally be the platform admin. | `YAYA_FIRST_USER_IS_PLATFORM_ADMIN=true` env var grants the flag on first signup only. |
| US-32 | As an SRE in an incident, I want the bootstrap operator path I can opt into in prod. | `yaya.agentic.operator-auth.bootstrap.enabled` (default `false` in prod, `true` in dev). |
| US-33 | As an HTTP-delegate operator, I want my first login to auto-provision a `users` row. | Successful delegate auth creates `users` (status=ACTIVE) and default workspace; subsequent logins reuse the row. |

### 3.5 Hardening, observability, GDPR (M5b)

| ID | Story | Acceptance gist |
|---|---|---|
| US-40 | As the platform, I want to rate-limit signup/login/reset/invite-accept. | Redis sliding-window per IP and per account; 429 with `Retry-After`. |
| US-41 | As an account holder, I want to be notified by email when my account is locked after N failed logins. | Lockout triggers SendGrid notification template. |
| US-42 | As the platform, I want CSRF protection on every mutating admin request. | Double-submit token from `/v1/auth/me`; `X-Yaya-CSRF` enforced. |
| US-43 | As a platform admin, I want to read `audit_user_events` with filters. | `/v1/admin/audit/user-events?kind=&user_id=&workspace_id=&from=&to=` (paged). |
| US-44 | As a user, I want to delete my account and have my PII scrubbed after a grace window. | Soft-delete (immediate) + 30-day scrub cron (PII null, UUID retained for audit). |
| US-45 | As the platform, I want belt-and-suspenders on workspace isolation. | `SET LOCAL yaya.workspace_id = :ctx` + Postgres CHECK constraints on `profiles`, `capabilities`, `tools_registry`, `tenant_jwt_secrets`, `tenant_oidc_issuers`. |

---

## 4. Functional requirements

Numbered for traceability. Each FR maps to one or more user stories.

### 4.1 Identity & registration

- **FR-1.1** Signup accepts `{ email, displayName, password }`. Email is RFC 5321 valid; password ≥ 12 chars with ≥ 3 of {lowercase, uppercase, digit, symbol}.
- **FR-1.2** Email uniqueness is enforced case-insensitively (`CITEXT` or `LOWER(email)` expression index).
- **FR-1.3** Signup is **enumeration-safe**: response is 202 regardless of whether the email already exists. If new, insert + send verification mail. If existing, send a "someone tried to sign up as you" mail.
- **FR-1.4** Email verification activates the user, creates the default workspace, inserts OWNER membership, and (if any) redeems pending invitations to that email — all in a single transaction. Token TTL = 24h.
- **FR-1.5** Verification resend endpoint: per-account cooldown 60s, 5/h cap.
- **FR-1.6** Login accepts three strategies (`bootstrap`, `delegate`, `user-password`) on `POST /v1/auth/login` discriminated by `strategy` field.
- **FR-1.7** `user-password` strategy verifies the argon2id hash and mints an `operator_sessions` row with `source=USER_PASSWORD`, `user_id=<users.id>`, and `subject=<users.id>`.
- **FR-1.8** Password reset request always returns 204. Mail fires only if the user exists and is ACTIVE. Token TTL = 1h, single-use.
- **FR-1.9** Password reset confirm rotates the hash AND deletes every `operator_sessions` row for that `user_id` in the same transaction.
- **FR-1.10** Sessions use `YAYA_SESSION` cookie (HttpOnly, Secure, SameSite=Strict). User-session TTL = 12h; bootstrap-session TTL = 1h.

### 4.2 Workspaces

- **FR-2.1** Every workspace has a UUID `id` and a unique human-readable `code` (slug). Routes accept either; UI displays `code`.
- **FR-2.2** Workspace `code` is generated by slugifying `displayName`; collisions append a 3-char random suffix (e.g. `acme-7f3`). A reserved-code list blocks `admin`, `api`, `auth`, `health`, `system`, `static`.
- **FR-2.3** `GET /v1/admin/workspaces` returns only workspaces where the requesting user has a membership row.
- **FR-2.4** `POST /v1/admin/workspaces` creates a workspace and atomically inserts an OWNER membership for the caller.
- **FR-2.5** `PATCH /v1/admin/workspaces/{idOrCode}` requires role `ADMIN+`. Renaming changes `displayName` (and re-slugs `code` only on explicit operator request — codes are otherwise immutable to preserve URL stability).
- **FR-2.6** `DELETE /v1/admin/workspaces/{idOrCode}` requires role `OWNER`. Performs **soft archive** (sets `archived_at`); does not cascade-delete child rows. Reverts via a separate restore endpoint reserved for platform admins.
- **FR-2.7** The `"default"` fallback in `AdminController.requireTenant()` is removed. Every admin request must resolve a workspace from a path/body/query param.

### 4.3 Isolation guard

- **FR-3.1** Every workspace-scoped admin endpoint runs `WorkspaceAccessGuard.require(userId, workspaceId, Role.atLeast)` **before** any business logic.
- **FR-3.2** No membership row → `404 Not Found` with the same error body as "workspace does not exist."
- **FR-3.3** `is_platform_admin = true` short-circuits the guard, attaches synthetic role `PLATFORM_ADMIN`, and emits an `audit_user_events` row.
- **FR-3.4** Every admin-plane repository method must accept a `WorkspaceContext` (or `TenantId`) and inject `WHERE tenant_id = :ctx` into its query.
- **FR-3.5** A reflection-based build-time test fails the build if any `@Repository` method takes neither `WorkspaceContext` nor `TenantId` AND is not annotated `@WorkspaceUnscoped` (allowed only on `users`, `user_workspace_memberships`, `user_invitations`, and session/token tables).

### 4.4 Teams

- **FR-4.1** Roles are `OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, plus the implicit `PLATFORM_ADMIN`. Strict subset semantics per [PRD §4.2 of the design doc / TRD §3].
- **FR-4.2** Invitations are created with `(workspace_id, email, role, token_hash, invited_by, expires_at)`. TTL = 7 days, single-use.
- **FR-4.3** Accepting an invitation while signed in mints the membership immediately. Accepting while unauthenticated redirects to signup with the token preserved across the flow.
- **FR-4.4** ADMINs cannot promote any member to OWNER or demote an OWNER. UI hides; server enforces (403 with `userSafeReason="Only an owner can change owner roles."`).
- **FR-4.5** Removing the last OWNER returns `409 Conflict` with `userSafeReason="A workspace must have at least one owner. Promote another member to owner before removing yourself."`
- **FR-4.6** Revoked invitations cannot be redeemed (DB-level: `consumed_at IS NOT NULL` excludes from accept lookup).

### 4.5 Auditing

- **FR-5.1** Every state-changing identity action emits an `audit_user_events` row (`SIGNUP`, `VERIFY`, `LOGIN`, `LOGOUT`, `PASSWORD_RESET_REQUEST`, `PASSWORD_RESET_CONFIRM`, `INVITE_SEND`, `INVITE_ACCEPT`, `INVITE_REVOKE`, `ROLE_GRANT`, `ROLE_REVOKE`, `WORKSPACE_CREATE`, `WORKSPACE_ARCHIVE`, `PLATFORM_ADMIN_ACTION`).
- **FR-5.2** Every existing audit table (`audit_authz`, `audit_recorder_ops`, `audit_operator_login`) gains a nullable `user_id` column. Historical rows backfilled to the synthetic platform-admin user id.
- **FR-5.3** (M5b) `GET /v1/admin/audit/user-events` lists with filters `kind`, `user_id`, `workspace_id`, `from`, `to`; paged, default 50.
- **FR-5.4** PII is never written to audit rows in plain text; `detail_json` may contain ids/UUIDs/role names only.

### 4.6 Hardening (M5b)

- **FR-6.1** Redis-backed sliding-window rate limit on `/v1/auth/signup`, `/v1/auth/login`, `/v1/auth/password-reset/request`, `/v1/auth/password-reset/confirm`, `/v1/auth/invitations/accept`. Per-IP and per-identity (email or user_id).
- **FR-6.2** Exponential backoff on consecutive failures; lockout after N (configurable, default 10) failed logins triggers a notification email.
- **FR-6.3** All 429 responses include `Retry-After`.
- **FR-6.4** CSRF: server-issued double-submit token returned in `/v1/auth/me`. All `POST`/`PATCH`/`DELETE` on `/v1/admin/**` and `/v1/auth/logout` require matching `X-Yaya-CSRF` header. Token rotates on login and password reset. (Reuses existing `CsrfWebFilter`.)
- **FR-6.5** Postgres CHECK constraints on `profiles`, `capabilities`, `tools_registry`, `tenant_jwt_secrets`, `tenant_oidc_issuers`: row's `tenant_id` must equal current GUC `yaya.workspace_id` (set via `SET LOCAL` at transaction start).

### 4.7 GDPR (M5b)

- **FR-7.1** Account self-delete is soft (status=DELETED, deleted_at set, PII fields nulled-or-pseudonymised).
- **FR-7.2** A scheduled job runs nightly and hard-scrubs PII from `users` rows where `deleted_at < now() - 30 days`. Audit rows retain the `user_id` UUID; joinable PII is lost.
- **FR-7.3** OWNER deleting their own account must either transfer ownership or archive the workspace first. UI surfaces both paths.

---

## 5. Non-functional requirements

### 5.1 Security

- **NFR-Sec-1** Passwords stored as argon2id (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` — already wired in `CoreConfiguration`). No second crypto dependency.
- **NFR-Sec-2** Tokens (email verification, password reset, invitation) stored as `sha256(rawToken)`; raw token appears once in the outbound email.
- **NFR-Sec-3** Cookies HttpOnly, Secure (when not local), SameSite=Strict.
- **NFR-Sec-4** All identity endpoints behind rate limiting (M5b). Per-IP limits absolute; per-account limits trigger lockout + notification.
- **NFR-Sec-5** Existence non-leak: 404 is identical body to "workspace does not exist" for every cross-user probe.
- **NFR-Sec-6** Email enumeration non-leak: signup always 202; password reset request always 204.
- **NFR-Sec-7** CSRF enforced on every state-changing admin request (M5b).
- **NFR-Sec-8** PostgreSQL `SET LOCAL yaya.workspace_id` GUC + CHECK constraints on high-risk tables as belt-and-suspenders (M5b).

### 5.2 Performance

- **NFR-Perf-1** `WorkspaceAccessGuard.require()` is a single primary-key lookup on `(user_id, workspace_id)`. Target p99 ≤ 5ms in-process (excluding DB rtt).
- **NFR-Perf-2** Argon2id verify p50 ≤ 100ms / p99 ≤ 500ms on a 4-core dev box.
- **NFR-Perf-3** `GET /v1/auth/me` (including memberships fetch) p99 ≤ 50ms in-process.
- **NFR-Perf-4** Workspace switcher in UI: switching invalidates feature providers and re-fetches within 500ms p95.
- **NFR-Perf-5** UUID migration `V21–V24` completes in ≤ 60s on a database with ≤ 10 tenants and ≤ 100k child rows total. (Larger production datasets require the dry-run runbook in TRD §10.)

### 5.3 Availability

- **NFR-Avail-1** SendGrid outage degrades signup/invite/reset to **queued retry** (outbox-pattern, max 3 retries with exponential backoff), not user-visible failure. Cron job drains the outbox.
- **NFR-Avail-2** Redis outage degrades rate limiting to **fail-open with a loud audit warn** (M5b). System functions; rate-limit canary fires for SRE.
- **NFR-Avail-3** No new external runtime dependency beyond SendGrid (which is opt-in via config; missing key → log warn + drop mail at boot, not a hard failure).

### 5.4 Compatibility

- **NFR-Compat-1** The eleven runtime SPIs from the master design do not change.
- **NFR-Compat-2** Existing `POST /v1/admin/tenants` keeps working as an alias of `POST /v1/admin/workspaces` for one release. Flutter paths don't break in a big-bang.
- **NFR-Compat-3** The bootstrap operator login keeps working in dev/local. In prod, off by default; controllable via `yaya.agentic.operator-auth.bootstrap.enabled`.
- **NFR-Compat-4** Existing `?tenant=…` query param continues to work but is now **required** and validated through the guard.

### 5.5 Observability

- **NFR-Obs-1** Every identity action emits a structured `audit_user_events` row (FR-5.1).
- **NFR-Obs-2** Existing audit tables gain `user_id` so a per-user audit query is one join.
- **NFR-Obs-3** SLO dashboards (Grafana, future): signup completion funnel, login failure rate, lockout rate, invite acceptance rate, platform-admin action count.

### 5.6 Accessibility (Flutter)

- **NFR-A11y-1** All form fields have visible labels; placeholder is not the only label.
- **NFR-A11y-2** Error messages are screen-reader-announced via Flutter `Semantics`.
- **NFR-A11y-3** Color is never the only signal for state (role chips include text labels; error icons + colored text).
- **NFR-A11y-4** Keyboard navigation for every flow (signup, invite-accept, member-management). Logical tab order.

---

## 6. Scope

### 6.1 In scope (M5a)

- Self-serve signup, email verification, login (3 strategies), password reset, logout, `/me`.
- Workspaces (CRUD), workspace switcher in UI.
- Memberships, four roles, invitations (send/list/revoke/accept).
- Last-OWNER protection.
- UUID migration of `tenants.id` + child FKs across all ~20 tables.
- `tenants.code` slug + `idOrCode` route discriminator.
- `OperatorAccountService` + `WorkspaceAccessGuard` SPIs.
- Repository-layer workspace filter (Java-side primary).
- Flutter screens: signup, verify, login (updated), forgot/reset password, accept-invite, workspace list, workspace settings, members, invite dialog, workspace switcher header.
- SendGrid integration + 4 mail templates (verify, reset, invite, enumeration-decoy).
- HTTP-delegate operator auto-provision.
- `audit_user_events` writes everywhere.
- Feature flag `yaya.agentic.user-accounts.enabled`.

### 6.2 In scope (M5b)

- Redis-backed rate limiting on all public auth endpoints (per-IP + per-account).
- Account lockout + notification email after N failed logins.
- CSRF double-submit token (extending existing `CsrfWebFilter`).
- `GET /v1/admin/audit/user-events` API + Flutter audit screen.
- Platform-admin gate on existing operator-auth config screen.
- GDPR soft-delete + 30-day scrub cron.
- Postgres CHECK constraints + `SET LOCAL` GUC plumbing on high-risk tables.
- Drop `tenants.legacy_slug_id` after soak.

### 6.3 Out of scope

- Billing, quotas, subscriptions.
- Org / department hierarchy above users.
- Federated SSO for users (Google / GitHub OIDC sign-in). Schema seam reserved (`user_oidc_identities`); behavior deferred.
- Changes to the end-user `Principal`, `Authenticator`, `Authorizer`, or conversation runtime SPIs.
- Multi-region replication.
- API keys per user (the existing per-tenant API key system is unchanged; per-user API tokens are deferred).
- 2FA / WebAuthn. Reserved for a future security pass.

### 6.4 Non-goals (firm)

- Conflating `OperatorAccountService` with `Authenticator`. They live on different identity planes (admin operator vs end-user principal); see TRD §3.2 for rationale.
- Adding tenant access checks inside tools or recorders. The trust boundary stays at the admin-plane guard; runtime AuthZ inside the conversation is unchanged.

---

## 7. Acceptance criteria

### 7.1 M5a — core acceptance

- [ ] **AC-1** Two users `A` and `B` who each created their own workspace cannot list, read, update, delete, or **probe for existence** of the other's workspaces, profiles, capabilities, tools, knowledge sources, JWT secrets, OIDC issuers, identity-profile mappings, sessions, or audit rows. Every probe returns 404 with an identical body.
- [ ] **AC-2** Signup → verify → default workspace works end-to-end with one email round-trip. The verified user lands on the workspace list screen with one workspace.
- [ ] **AC-3** Login with each of the three strategies (`bootstrap`, `delegate`, `user-password`) yields a valid `YAYA_SESSION`. `/v1/auth/me` correctly reflects `platformAdmin` for bootstrap.
- [ ] **AC-4** Invitations: `A` invites `B` as MEMBER; `B` accepts; `B` sees the new workspace; `B` can edit profiles; `B` cannot manage members. `A` demotes `B` to VIEWER; `B` can no longer edit.
- [ ] **AC-5** Last-OWNER protection: attempting to remove or demote the sole OWNER returns 409 with the expected `userSafeReason`.
- [ ] **AC-6** UUID migration `V21–V24` runs cleanly on a staging DB with all existing tenants; child-table FK integrity verified post-migration; `legacy_slug_id` retained.
- [ ] **AC-7** Routes accept both UUID and `code` forms; the UI displays `code`.
- [ ] **AC-8** Repository-scan reflection test fails the build when a new `@Repository` method is added without `WorkspaceContext` / `TenantId` argument or `@WorkspaceUnscoped` annotation.
- [ ] **AC-9** Bootstrap operator is off by default in prod profile, on by default in dev/local profile. Feature flag flip restores legacy behavior.
- [ ] **AC-10** Password reset confirm invalidates all sessions for the user (in-flight requests get 401 on next call).
- [ ] **AC-11** HTTP-delegate operator first-login auto-provisions a `users` row + default workspace. Second login is idempotent.
- [ ] **AC-12** SendGrid send failure does not block signup/invite/reset; outbox retries 3x with exponential backoff.

### 7.2 M5b — hardening acceptance

- [ ] **AC-20** Rate-limit test: N+1 failed logins from same IP → 429 + `Retry-After`; from same account → lockout + email notification.
- [ ] **AC-21** CSRF test: a `POST /v1/admin/workspaces` without `X-Yaya-CSRF` header → 403.
- [ ] **AC-22** GDPR test: soft-delete user → 30-day scrub cron clears PII columns from `users` and dependent tables; `audit_user_events` rows preserve the UUID intact and no longer carry PII.
- [ ] **AC-23** Postgres CHECK constraint test: an `INSERT INTO profiles` with the wrong `tenant_id` while `SET LOCAL yaya.workspace_id = '<other>'` fails the canary.
- [ ] **AC-24** `tenants.legacy_slug_id` dropped in the next release after M5b lands.

### 7.3 Integration test suite

A new test module `src/test/java/.../user_accounts/` containing:

1. **`CrossUserIsolationIT`** — signs up `A` and `B`, each with two workspaces, runs every admin endpoint cross-boundary, asserts 404 everywhere.
2. **`TeamsRolesIT`** — invitation send / accept / role change / remove flows.
3. **`LastOwnerProtectionIT`** — every path that could orphan a workspace.
4. **`MigrationV21toV24IT`** — runs the UUID migration against a seeded V20 schema; verifies row counts and FK integrity.
5. **`RepositoryScanTest`** — reflection-based build-failing test (M5a).
6. **`RateLimitIT`** (M5b) — burst then back off.
7. **`CsrfIT`** (M5b) — missing/mismatched token → 403.
8. **`GdprScrubIT`** (M5b) — soft-delete + cron + assert PII gone.

---

## 8. Phasing & rollout

### 8.1 Release sequencing

1. **R1 (M5a, behind flag)** — All code lands, feature flag `yaya.agentic.user-accounts.enabled=false`. UUID migration runs in production. UI ships with the flag wiring. No behavior change for existing operators.
2. **R2 (M5a, flag on in staging)** — QA exercises the full flow in staging. Cross-user isolation integration test must be green.
3. **R3 (M5a, flag on in prod)** — Signup live. Bootstrap operator off by default. Existing operators register accounts.
4. **R4 (M5b)** — Rate limit, CSRF, GDPR cron, audit screen, Postgres CHECKs. Each ship-gate is its own PR.
5. **R5 (cleanup)** — Drop `tenants.legacy_slug_id` and `POST /v1/admin/tenants` alias.

### 8.2 Feature flag matrix

| Flag | Default (prod) | Default (dev/local) | Effect when off |
|---|---|---|---|
| `yaya.agentic.user-accounts.enabled` | false initially → true after R3 | true | Signup/login/teams routes return 404; `AdminController.requireTenant()` retains "default" fallback; bootstrap operator is the only path. |
| `yaya.agentic.operator-auth.bootstrap.enabled` | false (post-R3) | true | Bootstrap login disabled; only registered users can sign in. |
| `yaya.agentic.email.enabled` | true | false (logs the mail body to console for dev) | Mail send is no-op; tokens still mintable via dev console. |
| `yaya.agentic.rate-limit.enabled` (M5b) | true | true (low limits) | No rate limit checks. |
| `yaya.agentic.csrf.enabled` (M5b) | true | true | No CSRF enforcement. |
| `YAYA_FIRST_USER_IS_PLATFORM_ADMIN` (env) | unset | unset | First signup is a regular user. When set, first signup is granted platform-admin. |

### 8.3 Rollback strategy

- Code rollback for R3: flip `yaya.agentic.user-accounts.enabled=false`. Schema stays in place (additive). Signup/login routes return 404; existing operator flow resumes.
- The UUID migration (`V21–V24`) is **not** reversible. R1 cannot ship until QA has signed off on the migration runbook against a production-shape staging DB.
- For R4 (M5b), each gate (rate limit, CSRF, GDPR cron, CHECK constraints) has its own toggle so partial rollback is granular.

---

## 9. Open questions (resolved)

The seven open questions from the design doc are resolved (design §12). Restated here for PRD completeness so the implementing engineer doesn't re-litigate them:

1. **Email provider** — SendGrid (existing account). Provider-pluggable interface; `yaya.agentic.email.*` config.
2. **Tenant id shape** — UUID for `id`, `code` for slug. Routes accept either.
3. **Teams** — Ship from day one with all four roles.
4. **HTTP-delegate operator** — Auto-provision `users` row on first login; one authorization path.
5. **Rate limiting** — Redis-backed, mandatory. Sliding-window + exponential backoff.
6. **CSRF** — Double-submit token from `/v1/auth/me`; `X-Yaya-CSRF` header on mutating routes.
7. **Bootstrap operator** — Off by default in prod once a platform-admin user exists.

---

## 10. References

- `docs/design/yaya-agentic-design.md` — master architecture; the eleven SPIs.
- `docs/design/yaya-agentic-user-accounts-design.md` — feature design doc (this PRD operationalizes it).
- `docs/design/operator-auth-design.md` — current operator-auth surface this feature extends.
- `docs/design/tenant-registry-design.md` — current tenant model this feature renames (in UI) to Workspace.
- `docs/milestones/M5-multi-tenant-admin.md` — coarser milestone scope being superseded by this spec set.
- Companion specs: [TRD](TRD.md), [UI-UX](UI-UX.md), [App-Flow](App-Flow.md), [Backend-Data](Backend-Data.md).
