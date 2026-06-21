# User Accounts & Workspaces — Application Flow

> **Companion to:** [PRD](PRD.md) · [TRD](TRD.md) · [UI-UX](UI-UX.md) · [Backend-Data](Backend-Data.md).
> **Status:** Draft v1 — implementation-ready.

This doc captures the runtime sequence for every primary flow. Diagrams are ASCII (deliberate — easy to diff). Each flow is anchored to a PRD user story (US-N), a TRD endpoint, and the relevant tables in Backend-Data.

## Legend

```
Browser   →  the Flutter admin app
Filter    →  OperatorAuthWebFilter (existing)
Guard     →  WorkspaceAccessGuard (new)
Acct      →  OperatorAccountService (new)
Inv       →  InvitationService (new)
Chain     →  OperatorAuthenticator chain (UserPassword + Delegate + Bootstrap)
Sess      →  OperatorSessionService (existing, extended)
Mail      →  MailSender → SendGrid (or DevConsoleMailSender)
DB        →  Postgres
Repo (X)  →  workspace-scoped repository for resource X
```

`-->` blocking call. `=>` async fan-out. `[…]` is a guard/decision. `# …` is a comment about side-effects.

---

## 1. Signup → verify → bootstrap-default-workspace

**Stories:** US-01, US-02. **Endpoints:** `POST /v1/auth/signup`, `GET /v1/auth/verify`.

### 1.1 Signup (enumeration-safe)

```
Browser --POST /v1/auth/signup {email, displayName, password}--> AuthController
AuthController --register(req, meta)--> Acct
[Acct] validates email + password policy; on policy fail → 400
[Acct] hashes password (argon2id via Argon2PasswordEncoder)
Acct --findByEmail--> Repo(User)
[Repo] returns Some(existing) OR None

# Path A: email is new
Acct --insert User(status=PENDING_VERIFICATION)--> Repo(User)
Acct --mint random 32-byte token; sha256 it--> (in-memory)
Acct --insert VerificationToken(hash, user_id, expires=now+24h)--> Repo(EmailVerificationToken)
Acct --enqueue MailMessage(template=VerifyEmail, raw token in body, idempotencyKey=hash)--> Mail
Acct --emit audit_user_events(kind=SIGNUP, user_id, ip, ua)--> Repo(AuditUserEvent)

# Path B: email exists
Acct --enqueue MailMessage(template=DuplicateSignup)--> Mail
Acct --emit audit_user_events(kind=SIGNUP_DUPLICATE_ATTEMPT, user_id=existing, ip, ua)

# Both paths
Acct ↩ SignupOutcome(created)
AuthController ↩ 202 Accepted (empty body)
Browser routes → /verify/pending?email=<from URL hint, not server>
```

Notes:
- The response body is identical across paths A and B — enumeration-safe.
- Mail send is via outbox; if `enqueue` fails, the user can still resend later (US-01 + resend endpoint).
- Token TTL = 24h. Resend endpoint enforces 60s cooldown + 5/h cap.

### 1.2 Email verification + default-workspace bootstrap (single transaction)

```
Browser --GET /v1/auth/verify?token=<raw>--> AuthController
AuthController --verifyEmail(raw, meta)--> Acct

BEGIN tx
  Acct --hash = sha256(raw)--> (in-memory)
  Acct --UPDATE user_email_verification_tokens
         SET consumed_at = now()
         WHERE token_hash = :hash
           AND consumed_at IS NULL
           AND expires_at > now()
         RETURNING user_id--> DB
  [no row returned] → ROLLBACK, return 410 Gone (expired/consumed/unknown)

  Acct --UPDATE users SET status='ACTIVE', email_verified_at=now() WHERE id=:user_id--> DB

  # Default workspace bootstrap
  Acct --SELECT display_name, email FROM users WHERE id=:user_id--> DB
  Acct --slug = slugify(display_name); collision → slug+"-"+rand3()--> (in-memory)
  Acct --INSERT INTO tenants(id, code, display_name, created_by, owner_user_id)
         VALUES (gen_random_uuid(), :slug, :display_name, :user_id, :user_id)--> DB
  Acct --INSERT INTO user_workspace_memberships(user_id, workspace_id, role='OWNER', added_by=:user_id)--> DB

  # Redeem any pre-existing invitations to this email
  Acct --SELECT id, workspace_id, role FROM user_invitations
         WHERE email = (SELECT email FROM users WHERE id=:user_id)
           AND consumed_at IS NULL
           AND expires_at > now()--> DB
  for each: INSERT user_workspace_memberships; UPDATE user_invitations.consumed_at, consumed_by

  Acct --emit audit_user_events(kind=VERIFY, user_id, workspace_id=default)--> DB
COMMIT

AuthController ↩ 302 /login?verified=1
Browser routes → /login (shows green banner) → user signs in → /workspaces (shows the new workspace)
```

Failure modes:
- Expired/consumed/unknown token → 410 Gone; UI shows "That link expired — request a new one."
- Slug collision retry: try `slug`, then `slug-xxx` with a fresh random suffix; cap at 5 attempts; failing 5 emits an `INSERT` error logged with WARN (very rare, would require ~5 specific collisions).

### 1.3 Resend verification

```
Browser --POST /v1/auth/verify/resend {email}--> AuthController
AuthController --findByEmail--> Acct -- Repo(User)
[Acct] cooldown check (60s since last token + 5/h cap)
  [denied] → 202 (silent — enumeration-safe)
Acct --insert new VerificationToken; enqueue Mail--> ...
AuthController ↩ 202 Accepted
```

The cooldown check is **enumeration-safe**: the 202 response is identical whether the email is known or not, whether the cooldown blocked or not.

---

## 2. Login

**Stories:** US-03, US-06, US-32, US-33. **Endpoint:** `POST /v1/auth/login`.

### 2.1 User-password login

```
Browser --POST /v1/auth/login {strategy:"user-password", email, password}--> AuthController
AuthController --rateLimiter.check(email, ip)--> LoginRateLimiter (existing)
  [denied] → 429 Retry-After
AuthController --chain.tryAuthenticate(creds)--> OperatorAuthenticator chain

  → UserPasswordOperatorAuthenticator.tryAuthenticate(creds)
    [creds.strategy != "user-password"] → return Optional.empty()  # yield
    --findByEmail(email)--> Acct → Repo(User)
    [no user OR status != ACTIVE] → throw OperatorAuthenticationException("invalid")
    --argon2.matches(password, user.password_hash)--> Argon2PasswordEncoder
    [no match] → throw "invalid"
    --mint Operator(subject=user.id.toString, displayName=user.display_name,
                    source=USER_PASSWORD, attributes={user_id, email, is_platform_admin, csrf_token=rand32})
    → return Optional.of(operator)

[chain has Operator]
AuthController --revoke any inbound session cookie--> Sess  # session-fixation guard
AuthController --emit audit_user_events(kind=LOGIN, user_id, ip, ua, strategy)
AuthController --issueSession(operator, ip, ua, ttl=12h)--> Sess
  Sess --INSERT operator_sessions(id, source='USER_PASSWORD', subject=user_id,
                                  user_id=user_id, csrf_token, expires_at)--> DB
  Sess ↩ ResponseCookie(YAYA_SESSION=<sid>, HttpOnly, Secure, SameSite=Strict, MaxAge=12h)
  Sess ↩ ResponseCookie(XSRF-TOKEN=<csrf>, NOT HttpOnly, SameSite=Strict)  # M5b double-submit
AuthController --build MeResponse(user, memberships, platformAdmin, csrfToken)--> Acct.workspacesFor(user_id)
AuthController ↩ 200 MeResponse + cookies
```

### 2.2 Bootstrap login (legacy)

```
... rate-limit + chain ...
  → UserPasswordOperatorAuthenticator → yields (different strategy)
  → HttpDelegateOperatorAuthenticator → yields (different strategy)
  → BootstrapOperatorAuthenticator:
    [yaya.agentic.operator-auth.bootstrap.enabled == false] → throw "bootstrap_disabled"
    --argon2.matches(password, BOOTSTRAP_HASH)--> Argon2PasswordEncoder
    [match] → mint Operator(source=BOOTSTRAP, attributes={user_id=<synthetic platform-admin uuid from V21>,
                                                          is_platform_admin=true,
                                                          csrf_token=rand32})

AuthController --issueSession(operator, ttl=1h /* shorter for bootstrap */)
AuthController ↩ 200 MeResponse + cookies (with platformAdmin=true)
```

### 2.3 HTTP-delegate login (auto-provision on first success)

```
... rate-limit + chain ...
  → UserPasswordOperatorAuthenticator → yields (different strategy)
  → HttpDelegateOperatorAuthenticator:
    --POST to configured delegate URL with token--> external IdP
    [200 OK with subject+email+displayName] → mint Operator(source=HTTP_DELEGATE, subject, email)
    [non-200] → throw "delegate_denied"

# Aspect/post-processor before session minting:
DelegateAccountProvisioner --provisionFromDelegate(subject, email, displayName)--> Acct
  Acct --findByEmail(email)--> Repo(User)
  [exists] → update last_login_at, return user
  [not exists] →
    BEGIN tx
      INSERT users(status=ACTIVE, email, display_name, password_hash=<never-matchable sentinel>, …)
      slug + INSERT tenants + INSERT membership (OWNER)
      emit audit_user_events(kind=SIGNUP, source=DELEGATE_AUTOPROV)
    COMMIT
  return user

# Operator attributes patched with user_id, is_platform_admin
AuthController --issueSession(operator with user_id, ttl=12h)
```

Idempotency: a second login by the same delegate identity hits the "exists" branch and updates `last_login_at` only — no duplicate tenant created.

### 2.4 Failed login → lockout (M5b)

```
... rate-limiter sees consecutive failures for the same email ...
[LoginRateLimiter counter exceeds threshold (e.g. 10 failures / 15m)]
LoginRateLimiter --record lockout in Redis (TTL 30m)
LoginRateLimiter => enqueue Mail(template=LockoutNotification, to=user.email)
AuthController ↩ 429 + Retry-After (lockout TTL)

# Subsequent attempts during the lockout window:
LoginRateLimiter --check--> Redis returns "locked" → 429 Retry-After (immediately)
```

The lockout email contains a password-reset link as recovery action.

---

## 3. Logout

**Story:** US-07. **Endpoint:** `POST /v1/auth/logout`.

```
Browser --POST /v1/auth/logout (X-Yaya-CSRF header in M5b)--> AuthController
Filter --extract YAYA_SESSION cookie; load session--> Sess
AuthController --DELETE operator_sessions WHERE id=:sid--> Sess
AuthController --emit audit_user_events(kind=LOGOUT, user_id, ip)
AuthController ↩ 204 + Set-Cookie: YAYA_SESSION=; Max-Age=0; ... (cleared)
                    + Set-Cookie: XSRF-TOKEN=; Max-Age=0
Browser routes → /login
```

---

## 4. Password reset

**Stories:** US-04, US-05. **Endpoints:** `POST /v1/auth/password-reset/request`, `POST /v1/auth/password-reset/confirm`.

### 4.1 Request (enumeration-safe)

```
Browser --POST /v1/auth/password-reset/request {email}--> AuthController
AuthController --rateLimiter.check(email, ip)--> RateLimiter
AuthController --requestPasswordReset(email, meta)--> Acct
Acct --findByEmail--> Repo(User)
  [no user OR status != ACTIVE] → return silently (NO mail, NO audit)
  [user exists & ACTIVE] →
    Acct --mint token; sha256 hash--> (in-memory)
    Acct --INSERT user_password_reset_tokens(hash, user_id, expires=now+1h)--> Repo(PasswordResetToken)
    Acct --enqueue Mail(template=ResetPassword, raw token)--> Mail
    Acct --emit audit_user_events(kind=PASSWORD_RESET_REQUEST, user_id, ip)
AuthController ↩ 204 (always)
Browser shows "If an account exists for that email, we just sent a reset link."
```

### 4.2 Confirm (rotate hash + invalidate sessions)

```
Browser --POST /v1/auth/password-reset/confirm {token, newPassword}--> AuthController
AuthController --confirmPasswordReset(raw, new, meta)--> Acct
[Acct] validate newPassword policy (≥12 chars, ≥3 classes) → 400 if fail

BEGIN tx
  Acct --hash = sha256(raw)
  Acct --UPDATE user_password_reset_tokens
         SET consumed_at = now()
         WHERE token_hash = :hash
           AND consumed_at IS NULL
           AND expires_at > now()
         RETURNING user_id--> DB
  [no row] → ROLLBACK, return 410 Gone

  Acct --UPDATE users SET password_hash = :argon2(newPassword), updated_at = now()
         WHERE id = :user_id--> DB
  Acct --DELETE FROM operator_sessions WHERE user_id = :user_id--> DB   # invalidates every active session
  Acct --emit audit_user_events(kind=PASSWORD_RESET_CONFIRM, user_id, ip)
COMMIT

AuthController ↩ 204
Browser routes → /login?reset=1
```

In-flight requests from the user's other sessions get 401 on next call (their `YAYA_SESSION` cookie maps to a session row that no longer exists).

---

## 5. Invitations

**Stories:** US-20–US-23. **Endpoints:** `POST/GET/DELETE /v1/admin/workspaces/{id}/invitations`, `GET /v1/auth/invitations/accept`.

### 5.1 Send invitation

```
Browser --POST /v1/admin/workspaces/acme/invitations {email, role}
                          (X-Yaya-CSRF header in M5b)--> InvitationController
Filter --auth cookie--> Sess → loads Operator(user_id=jane)
Guard --require(jane, workspace=acme, atLeast=ADMIN)--> WorkspaceAccessGuard
  Guard --SELECT role FROM user_workspace_memberships WHERE user_id=jane AND workspace_id=acme--> DB
  [no row AND not platform_admin] → throw WorkspaceNotFoundException → 404
  [role < ADMIN] → throw WorkspaceForbiddenException → 403
  [OK] → return Membership

InvitationController --InvitationService.create(workspace, email, role, invitedBy=jane)--> Inv
  Inv --mint token; sha256 hash--> (in-memory)
  Inv --INSERT user_invitations(id=uuid, workspace_id, email, role, token_hash,
                                invited_by=jane, expires_at=now+7d)--> Repo(Invitation)
  Inv --enqueue Mail(template=Invitation, raw token, workspace.display_name, role)--> Mail
  Inv --emit audit_user_events(kind=INVITE_SEND, user_id=jane, workspace_id=acme, detail_json={email, role})

InvitationController ↩ 201 Created Invitation
Browser routes back to Invitations tab; toast "Invitation sent to {email}."
```

### 5.2 Accept invitation — signed-in path

```
Browser --GET /v1/auth/invitations/accept?token=<raw>--> AuthController
Filter --auth cookie--> Sess → loads Operator(user_id=alex)
AuthController --InvitationService.accept(raw, Optional.of(alex), meta)--> Inv

BEGIN tx
  Inv --hash = sha256(raw); SELECT * FROM user_invitations
        WHERE token_hash=:hash AND consumed_at IS NULL AND expires_at > now()
        FOR UPDATE--> DB
  [no row] → return Expired or AlreadyConsumed (404 to browser)

  Inv --INSERT user_workspace_memberships(user_id=alex, workspace_id=inv.workspace_id, role=inv.role,
                                          added_by=inv.invited_by)--> DB
        ON CONFLICT (user_id, workspace_id) DO NOTHING  # idempotency
  Inv --UPDATE user_invitations SET consumed_at=now(), consumed_by=alex WHERE id=:inv.id--> DB
  Inv --emit audit_user_events(kind=INVITE_ACCEPT, user_id=alex, workspace_id=inv.workspace_id, detail={role})
COMMIT

AuthController ↩ 302 /workspaces/<inv.workspace.code>
Browser routes → workspace detail; toast "Joined {workspaceName} as {role}."
```

### 5.3 Accept invitation — anonymous path (signup carry)

```
Browser --GET /v1/auth/invitations/accept?token=<raw>--> AuthController
Filter --no auth cookie--> proceeds with Operator absent
AuthController --InvitationService.peek(raw)--> Inv  # validates but does NOT consume
  Inv --SELECT * FROM user_invitations WHERE token_hash=:hash AND consumed_at IS NULL
                                         AND expires_at > now()--> DB
  [no row] → 410 Gone (UI shows "Invitation no longer valid")
  [row] → return InvitationPreview(email, workspace.display_name, role, raw_token)

AuthController ↩ 302 /signup?invite=<raw_token>&email=<inv.email>
Browser routes → /signup, pre-fills + locks email field, shows banner

# User completes signup; verify-email-bootstrap flow (§1.2) discovers the invitation via
# `SELECT … FROM user_invitations WHERE email = users.email AND consumed_at IS NULL`
# and redeems it in the same tx. No second click needed.
```

### 5.4 Revoke invitation

```
Browser --DELETE /v1/admin/workspaces/acme/invitations/{invId} (CSRF header)--> InvitationController
Guard --require(jane, acme, ADMIN)--> ...
InvitationController --InvitationService.revoke(invId, actor=jane)--> Inv
  Inv --UPDATE user_invitations SET consumed_at=now(), consumed_by=jane
         WHERE id=:invId AND consumed_at IS NULL--> DB
  [no row affected] → return silently (already consumed/revoked)
  Inv --emit audit_user_events(kind=INVITE_REVOKE, user_id=jane, workspace_id, detail={invitation_id})
InvitationController ↩ 204
```

A subsequent click on the revoked invitation link returns 410 (token-bearer accept finds no row matching `consumed_at IS NULL`).

---

## 6. Workspace lifecycle

### 6.1 Create workspace

**Story:** US-10. **Endpoint:** `POST /v1/admin/workspaces`.

```
Browser --POST /v1/admin/workspaces {displayName, code?} (CSRF)--> WorkspaceController
Filter --auth--> Sess → Operator(user_id=jane)
WorkspaceController --workspaceService.create(jane, displayName, code?)
BEGIN tx
  Service --slug = code ?? slugify(displayName); collision → slug+"-"+rand3()
  Service --INSERT INTO tenants(id=gen_random_uuid(), code, display_name, created_by=jane, owner_user_id=jane)--> DB
  Service --INSERT INTO user_workspace_memberships(user_id=jane, workspace_id, role='OWNER', added_by=jane)--> DB
  Service --emit audit_user_events(kind=WORKSPACE_CREATE, user_id=jane, workspace_id)
COMMIT
WorkspaceController ↩ 201 Created Workspace
Browser routes → /workspaces/{code}; switcher updates
```

### 6.2 Patch workspace (rename)

```
Browser --PATCH /v1/admin/workspaces/acme {displayName} (CSRF)--> WorkspaceController
Guard --require(jane, acme, ADMIN)--> ...
WorkspaceController --UPDATE tenants SET display_name=:new, updated_at=now() WHERE id=:resolvedId
                     (resolvedId from idOrCode lookup)--> DB
WorkspaceController --emit audit_user_events(kind=WORKSPACE_RENAME, user_id, workspace_id, detail={old,new})
WorkspaceController ↩ 200 Workspace
```

Note: `code` is immutable except via explicit operator action (PRD FR-2.5).

### 6.3 Archive workspace

**Story:** US-15. **Endpoint:** `DELETE /v1/admin/workspaces/{idOrCode}`.

```
Browser --DELETE /v1/admin/workspaces/acme {confirmCode:"acme"} (CSRF)--> WorkspaceController
Guard --require(jane, acme, OWNER)--> ...
WorkspaceController validate confirmCode matches resolved tenants.code exactly (case-sensitive)
[mismatch] → 400 "Confirmation does not match the workspace code."

WorkspaceController --UPDATE tenants SET archived_at=now() WHERE id=:resolvedId--> DB
WorkspaceController --emit audit_user_events(kind=WORKSPACE_ARCHIVE, user_id, workspace_id)
WorkspaceController ↩ 204
Browser switcher refresh — archived workspace no longer appears
```

Restore: out of scope for M5a; reserved for platform admins (manual SQL until a `POST /v1/admin/platform/workspaces/{id}/restore` lands later).

---

## 7. Workspace switch (Flutter)

**Story:** US-12. **Endpoint:** none — pure client state change + provider invalidation.

```
User --clicks workspace chip in admin shell header
WorkspaceSwitcher --reads myWorkspacesProvider--> existing cache
[user selects "globex"]
WorkspaceSwitcher --currentWorkspaceProvider.set(TenantCode("globex"))

# Provider invalidation cascade (managed in lib/app/workspace_scoped_providers.dart):
ref.invalidate(profilesProvider)
ref.invalidate(capabilitiesProvider)
ref.invalidate(toolsProvider)
ref.invalidate(knowledgeSourcesProvider)
ref.invalidate(recorderStrategyProvider)
ref.invalidate(sessionsProvider)
ref.invalidate(membersProvider)
ref.invalidate(invitationsProvider)

# Each re-mounted provider issues its fetch with the new workspace code in the URL/body:
profilesProvider --GET /v1/admin/profiles?tenant=globex--> AdminController
Filter --auth + CSRF (POSTs)--> ...
Guard --require(jane, globex, VIEWER)--> ...
AdminController --Repo(Profile).listByWorkspace(globex)
AdminController ↩ 200 [...profiles for globex...]

router.go("/workspaces/globex")
window.localStorage["yaya.lastWorkspaceId"] = "globex"  # persist for reload
```

If `Guard.require` returns 404 (user lost access in another browser tab, etc.): the dio interceptor maps the 404 to `NoAccessException`; the UI renders `NoAccessView` in place of the screen content.

---

## 8. Workspace-scoped admin request — guard + repo flow

This is the canonical pattern every admin endpoint follows.

```
Browser --POST /v1/admin/profiles?tenant=acme {…} (CSRF header)--> AdminController#createProfile
Filter --auth cookie--> Sess → Operator(user_id=jane)
CsrfWebFilter --validate X-Yaya-CSRF matches session.csrf_token--> [OK or 403]
WorkspaceScopedAspect @Around for method annotated @WorkspaceScoped(MEMBER):
  --resolveWorkspaceId from request param "tenant" → TenantId (via idOrCode)
  --guard.require(jane, resolvedId, MEMBER)--> Guard
  [Guard.WorkspaceNotFoundException] → ControllerAdvice → 404 with body identical to "tenant does not exist"
  [Guard.WorkspaceForbiddenException — rare, e.g. role below atLeast] → 403 with userSafeReason
  [OK] → bind WorkspaceContext(workspaceId, userId, role) into Reactor Context
  --proceed--> AdminController method runs

AdminController#createProfile:
  --read WorkspaceContextHolder.current() → WorkspaceContext
  BEGIN tx (M5b: SET LOCAL yaya.workspace_id = :workspaceId at tx start)
    --Repo(Profile).insert(profile, workspaceContext)
       → executes: INSERT INTO profiles(id, tenant_id, …) VALUES(?, :workspaceId, …)
       (M5b: Postgres CHECK validates tenant_id = current_setting('yaya.workspace_id'))
    --emit audit_authz(…, user_id=jane, …)
  COMMIT

AdminController ↩ 201 Created Profile
```

This is the **single load-bearing pattern** — every workspace-scoped admin endpoint is exactly this shape. The repo-scan test enforces it at build time.

### 8.1 Cross-user isolation in practice

User `bob` (not a member of `acme`) attempts the same call:

```
Browser --POST /v1/admin/profiles?tenant=acme {…}--> AdminController
Filter → Operator(user_id=bob)
Guard --SELECT role FROM user_workspace_memberships WHERE user_id=bob AND workspace_id=acme--> DB
  [no row AND not platform_admin] → throw WorkspaceNotFoundException
ControllerAdvice → 404 with body: { error: "workspace_not_found", message: "Workspace not found." }
```

The same 404 body is returned for `tenant=does-not-exist-at-all`. Bob cannot tell whether `acme` exists.

---

## 9. Platform admin action

**Story:** US-30.

```
Browser (platform admin "root") --GET /v1/admin/audit/user-events?workspace_id=acme--> AccountAuditController
Filter --auth--> Operator(user_id=root, is_platform_admin=true)
Guard --require(root, acme, ADMIN)--> Guard
  Guard --SELECT FROM users WHERE id=root AND is_platform_admin=true--> DB → true
  → short-circuit; synthesise Membership(role=PLATFORM_ADMIN)
  → emit audit_user_events(kind=PLATFORM_ADMIN_ACTION, user_id=root, workspace_id=acme,
                           detail={action:"audit_user_events.list"})
AccountAuditController --Repo(AuditUserEvent).list(workspace=acme, …)
AccountAuditController ↩ 200 [...]
```

Every platform-admin action emits a `PLATFORM_ADMIN_ACTION` audit row. The detail JSON carries the canonical path of the action (e.g., `audit_user_events.list`, `profiles.read`, `members.remove`). Operators can grep for this single kind to surface all break-glass use.

---

## 10. Member role change & last-OWNER protection

**Stories:** US-24, US-25, US-26.

### 10.1 Role change

```
Browser --PATCH /v1/admin/workspaces/acme/members/alex {role:"VIEWER"} (CSRF)--> MembersController
Guard --require(jane, acme, ADMIN)--> ...
MembersController --MembershipService.changeRole(workspace, target=alex, newRole=VIEWER, actor=jane)
BEGIN tx
  Service --SELECT FROM user_workspace_memberships WHERE user_id=alex AND workspace_id=acme FOR UPDATE--> DB
  [no row] → 404
  Service --check ADMIN actor not changing OWNER--> if alex.role=OWNER and actor.role<OWNER → 403
                "Only an owner can change owner roles."

  # Last-OWNER protection (only if demoting an OWNER)
  if alex.role=OWNER and newRole != OWNER:
    Service --SELECT COUNT(*) FROM user_workspace_memberships WHERE workspace_id=acme AND role='OWNER'--> DB
    [count == 1] → ROLLBACK, 409 Conflict
                   "A workspace must have at least one owner. Promote another member to owner first."

  Service --UPDATE user_workspace_memberships SET role=:newRole WHERE user_id=alex AND workspace_id=acme--> DB
  Service --emit audit_user_events(kind=ROLE_GRANT or ROLE_REVOKE, user_id=jane, workspace_id, detail={target=alex, from=OWNER, to=VIEWER})
COMMIT
MembersController ↩ 200 updated Membership
```

### 10.2 Remove member

```
Browser --DELETE /v1/admin/workspaces/acme/members/alex (CSRF)--> MembersController
Guard --require(jane, acme, ADMIN)--> ...
BEGIN tx
  Service --SELECT FROM user_workspace_memberships WHERE user_id=alex AND workspace_id=acme FOR UPDATE--> DB
  [alex.role=OWNER AND actor.role < OWNER] → 403 "Only an owner can remove an owner."
  [alex.role=OWNER AND COUNT(OWNER)==1] → 409 "A workspace must have at least one owner. Promote another member to owner before removing yourself."
  Service --DELETE FROM user_workspace_memberships WHERE user_id=alex AND workspace_id=acme--> DB
  Service --emit audit_user_events(kind=ROLE_REVOKE, user_id=jane, workspace_id, detail={target=alex, removed=true})
COMMIT
MembersController ↩ 204
```

A removed member's existing `YAYA_SESSION` cookie still works (sessions are not auth tokens to specific workspaces) but the next `WorkspaceAccessGuard.require()` returns 404 for that workspace — they see `NoAccessView`.

---

## 11. Account deletion (M5b)

**Story:** US-44.

### 11.1 Self soft-delete

```
Browser --GET /v1/auth/me/deletion-eligibility--> AuthController
AuthController --finds workspaces where caller is sole OWNER--> Acct
AuthController ↩ { soleOwnerWorkspaces: [{code, displayName}], eligible: bool }

# If eligible:
Browser --DELETE /v1/auth/me (CSRF; typed-name confirm in body)--> AuthController
Acct --soft-delete user:
  BEGIN tx
    UPDATE users SET status='DELETED', deleted_at=now() WHERE id=:userId
    DELETE FROM operator_sessions WHERE user_id=:userId
    emit audit_user_events(kind=ACCOUNT_DELETE_SOFT, user_id)
  COMMIT
AuthController ↩ 204 + cleared cookies
Browser routes → /goodbye (or /login)
```

### 11.2 Nightly PII scrub cron

Runs at the cron in `yaya.agentic.gdpr.cron` (default `0 0 3 * * *`).

```
@Scheduled MailOutboxDrainer's cousin — UserScrubCron.scrub():
  SELECT id FROM users
  WHERE status='DELETED' AND deleted_at < now() - INTERVAL '30 days'

  for each user:
    BEGIN tx
      UPDATE users SET email = 'deleted-' || id || '@scrubbed.local',
                       display_name = 'Deleted user',
                       password_hash = '',
                       email_verified_at = NULL,
                       last_login_at = NULL
      WHERE id = :id
      # audit_user_events rows already only carry UUIDs + ids — nothing to scrub there
      emit audit_user_events(kind=ACCOUNT_PII_SCRUB, user_id=id)
    COMMIT
```

After scrub the user row remains for foreign-key integrity (memberships, audit, invitations all FK to it) but carries no PII.

---

## 12. Mail outbox drain

```
@Scheduled(fixedRateString="${yaya.agentic.email.outbox.drainer-fixed-rate}") MailOutboxDrainer.drain():
  SELECT * FROM email_outbox
  WHERE status IN ('PENDING','RETRYING') AND next_attempt_at <= now()
  ORDER BY next_attempt_at LIMIT 100

  for each msg:
    try:
      SendGridMailSender.send(msg)
      UPDATE email_outbox SET status='SENT', sent_at=now() WHERE id=:id
    catch transient (5xx, timeout):
      attempts := attempts + 1
      if attempts >= maxRetries:
        status='DEAD'; emit audit_user_events(kind=MAIL_DEAD, detail={template, to_hash})
      else:
        next_attempt_at = now() + backoff(attempts)
        status='RETRYING'
    catch permanent (4xx other than 429):
      status='DEAD'; emit audit_user_events(kind=MAIL_DEAD)
```

`idempotencyKey` on the outbox row prevents double-send if the drainer races with the original enqueue (both upsert into the same key).

---

## 13. UUID migration cutover (V21–V24)

This flow is one-time, run during the R1 deploy window.

```
SRE: take DB snapshot
SRE: put app in read-only mode at the load balancer
Flyway: apply V20 (additive — new tables)
Flyway: apply V21 (additive — seed synthetic platform-admin user + OWNER memberships)
Flyway: apply V22 (additive — add tenants.code, tenants.id_uuid, tenants.created_by, tenants.owner_user_id)
Flyway: apply V23 (RISKY — for every child table T with tenant_id VARCHAR(64):
  - ALTER TABLE T ADD COLUMN tenant_id_uuid UUID
  - UPDATE T SET tenant_id_uuid = (SELECT id_uuid FROM tenants WHERE tenants.id = T.tenant_id)
  - ALTER TABLE T DROP CONSTRAINT <old fk>; DROP COLUMN tenant_id
  - ALTER TABLE T RENAME COLUMN tenant_id_uuid TO tenant_id
  - ALTER TABLE T ADD CONSTRAINT <new fk> FOREIGN KEY (tenant_id) REFERENCES tenants(id_uuid) ON DELETE CASCADE
)
Flyway: apply V24 (RISKY — drop tenants_pkey, rename id→legacy_slug_id, rename id_uuid→id,
                   ADD PRIMARY KEY (id), repoint child FKs from id_uuid to id)
Flyway: apply V25 (additive — operator_sessions.user_id + source enum widen)
SRE: re-enable writes at the load balancer
SRE: smoke-test (login, list workspaces, hit a tool)
SRE: monitor for 30 min before flipping yaya.agentic.user-accounts.enabled=true
```

If V23 mid-fails → Postgres tx aborts; the partially-added `tenant_id_uuid` columns are dropped via a `V23.1__cleanup.sql` repair migration. Schema is back to V22 state. Re-attempt after diagnosis.

V24 is wrapped in a single transaction (all `ALTER … RENAME` are DDL but DDL-tx-safe in Postgres). Failure mid-V24 leaves both `id` and `id_uuid` intact; re-attempt safely.

---

## 14. Failure / fallback summary

| Failure | User-visible behavior | System behavior |
|---|---|---|
| SendGrid down | Signup/invite/reset still 202/204; emails delayed | Outbox queues; drainer retries 3× then DEAD |
| Redis down (M5b rate limit) | No user-visible change | Rate limiter fails OPEN; loud WARN log + audit canary |
| Postgres CHECK violation (M5b) | 500 to user with "We couldn't complete that operation. Please contact support." | Loud ERROR + audit; means Java guard missed a query — fix the code path |
| Bootstrap operator login while disabled | 401 "Invalid credentials" | Same generic deny; no special audit kind |
| `WorkspaceAccessGuard.require()` returns 404 | `NoAccessView` rendered | Cross-user probe; logged at DEBUG only (avoid noise) |
| Session expired mid-request | 401; UI redirects to `/login?next=…` | Filter strips cookie |
| CSRF mismatch (M5b) | 403 "We couldn't authorize that request. Refresh and try again." | Logged at INFO |
| Migration V23 mid-fail | App stays in maintenance mode | Cleanup migration restores to V22; SRE re-attempts |

---

## 15. References

- [PRD](PRD.md), [TRD](TRD.md), [UI-UX](UI-UX.md), [Backend-Data](Backend-Data.md).
- `src/main/java/.../operator_auth/web/AuthController.java` — current `/v1/auth` shape extended by these flows.
- `src/main/java/.../operator_auth/web/OperatorAuthWebFilter.java` — the auth filter referenced as `Filter` throughout.
- `src/main/java/.../operator_auth/session/OperatorSessionService.java` — `Sess` in the diagrams.
- `src/main/java/.../operator_auth/ratelimit/LoginRateLimiter.java` — extended for §2.4 lockout flow.
- `src/main/java/.../operator_auth/web/CsrfWebFilter.java` — extended for double-submit enforcement.
