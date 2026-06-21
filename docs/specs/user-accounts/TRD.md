# User Accounts & Workspaces — Technical Requirements (TRD)

> **Companion to:** [PRD](PRD.md) · [UI-UX](UI-UX.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).
> **Source design:** `docs/design/yaya-agentic-user-accounts-design.md`.
> **Status:** Draft v1 — implementation-ready.

This doc tells an engineer what to build at the API, service, repository, and configuration layers. Product intent lives in the PRD; data shapes in Backend-Data; screen shapes in UI-UX; runtime sequence in App-Flow.

---

## 1. Architectural fit

### 1.1 Trust boundaries

The system has two distinct identity planes and this feature touches only one:

| Plane | Identity type | Authenticated by | Authorized by | This feature changes? |
|---|---|---|---|---|
| **Conversation runtime** (master design §5.4–5.5, §6) | `core.Principal` | `Authenticator` SPI | `Authorizer` SPI | **No.** Frozen contracts. |
| **Admin/operator console** (operator-auth design) | `operator_auth.Operator` | `OperatorAuthenticator` chain (`bootstrap`, `delegate`, **new** `user-password`) | NEW: `WorkspaceAccessGuard` | **Yes.** All work happens here. |

The two planes deliberately do not share an identity type. Conflating them would force a leaky abstraction (design §6) — this is non-negotiable.

### 1.2 Package layout

New + modified backend packages live under `com.yayatechandinnovations.yayaagentic`:

```
operator_auth/
├── (existing) OperatorAuthenticator, Operator, OperatorCredentials, ...
├── (existing) bootstrap/, delegate/, ratelimit/, session/, audit/, web/, config/, chain/
├── NEW accounts/                       ← user accounts service + repo
│   ├── OperatorAccountService.java          (SPI)
│   ├── DefaultOperatorAccountService.java
│   ├── User.java                            (record)
│   ├── UserId.java                          (record wrapping UUID)
│   ├── UserStatus.java                      (enum: PENDING_VERIFICATION, ACTIVE, SUSPENDED, DELETED)
│   ├── Membership.java                      (record: userId, workspaceId, role, addedBy, addedAt)
│   ├── Role.java                            (enum: OWNER, ADMIN, MEMBER, VIEWER, PLATFORM_ADMIN)
│   ├── SignupRequest.java                   (record)
│   ├── UserCredentials.java                 (record)
│   ├── AuthenticationResult.java            (record)
│   ├── jpa/UserEntity.java
│   ├── jpa/UserRepository.java
│   ├── jpa/MembershipEntity.java
│   ├── jpa/MembershipRepository.java
│   ├── jpa/EmailVerificationTokenEntity.java
│   ├── jpa/EmailVerificationTokenRepository.java
│   ├── jpa/PasswordResetTokenEntity.java
│   └── jpa/PasswordResetTokenRepository.java
├── NEW workspace/                      ← workspace access guard
│   ├── WorkspaceAccessGuard.java            (SPI)
│   ├── DefaultWorkspaceAccessGuard.java
│   ├── WorkspaceContext.java                (record: workspaceId, requestingUserId, role)
│   ├── WorkspaceContextHolder.java          (ThreadLocal or Reactor Context bridge)
│   ├── WorkspaceUnscoped.java               (annotation — marks repos exempt from scan test)
│   └── WorkspaceContextTransactionAdvice.java   (sets SET LOCAL yaya.workspace_id at tx start; M5b)
├── NEW invitations/
│   ├── InvitationService.java               (SPI)
│   ├── DefaultInvitationService.java
│   ├── Invitation.java                      (record)
│   ├── InvitationId.java                    (UUID wrapper)
│   ├── jpa/InvitationEntity.java
│   └── jpa/InvitationRepository.java
├── NEW userpassword/                   ← new operator-authenticator strategy
│   └── UserPasswordOperatorAuthenticator.java  ← implements OperatorAuthenticator
└── (existing) web/
    ├── AuthController.java                  ← extend: signup, verify, reset, /me {memberships, csrf}
    ├── NEW WorkspaceController.java         ← /v1/admin/workspaces/**
    ├── NEW InvitationController.java        ← /v1/admin/workspaces/{id}/invitations + accept
    └── NEW AccountAuditController.java      ← /v1/admin/audit/user-events (M5b)
```

```
email/                                  ← NEW top-level package
├── MailSender.java                            (SPI)
├── SendGridMailSender.java                    (default impl; opt-out via config)
├── DevConsoleMailSender.java                  (local profile; logs body)
├── OutboxEntity.java + OutboxRepository.java  (retry-buffer)
├── MailOutboxDrainer.java                     (@Scheduled fixed-rate)
├── templates/
│   ├── VerifyEmailTemplate.java
│   ├── ResetPasswordTemplate.java
│   ├── InvitationTemplate.java
│   ├── DuplicateSignupTemplate.java
│   └── LockoutNotificationTemplate.java       (M5b)
└── config/EmailProperties.java
```

```
tenant/                                 ← refactor (M5a)
├── (existing) TenantSlug.java
├── (rename) TenantCode.java                   ← new value type for the human-readable slug
│   (TenantSlug deprecated; kept as alias for one release)
└── workspace/
    ├── WorkspaceController moved here? NO — controllers stay under api/
    └── TenantId is unchanged in core/Ids.java but its String value becomes a UUID at runtime
```

```
core/
└── Ids.java                            ← TenantId rewrap: still String value, but validator
                                          enforces UUID syntax after M5a cutover.
                                          (Keeping the wrapper avoids touching every SPI.)
```

> **Decision:** `Ids.TenantId` keeps `record TenantId(String value)` but its `value` is now a UUID string. A static `TenantId.of(UUID)` factory + `TenantId.of(String)` validating UUID-format is added. This avoids a sweeping signature change to the eleven runtime SPIs that reference `TenantId` — they continue to compile unchanged. `TenantCode` is a separate type used only at the edge (URL parsing, UI display).

### 1.3 Flutter layout

```
yayaagenticweb/lib/
├── features/
│   ├── auth/                           (extended — see UI-UX §3)
│   │   ├── presentation/
│   │   │   ├── login_screen.dart             (extended w/ strategy switcher)
│   │   │   ├── signup_screen.dart            NEW
│   │   │   ├── verify_email_screen.dart      NEW
│   │   │   ├── forgot_password_screen.dart   NEW
│   │   │   ├── reset_password_screen.dart    NEW
│   │   │   └── accept_invitation_screen.dart NEW
│   │   ├── application/
│   │   │   ├── auth_controller.dart          (extended)
│   │   │   └── invitation_controller.dart    NEW
│   │   └── data/auth_api.dart                (extended)
│   ├── workspaces/                     NEW feature
│   │   ├── presentation/
│   │   │   ├── workspace_list_screen.dart
│   │   │   ├── workspace_settings_screen.dart
│   │   │   ├── members_screen.dart
│   │   │   ├── invite_member_dialog.dart
│   │   │   └── workspace_switcher.dart
│   │   ├── application/
│   │   │   ├── workspaces_controller.dart
│   │   │   ├── current_workspace_provider.dart
│   │   │   └── members_controller.dart
│   │   └── data/workspaces_api.dart
│   └── settings/                       (extend platform-settings)
│       └── presentation/platform_settings_screen.dart  (gates existing operator_auth_screen)
└── app/router.dart                     (extend routes — UI-UX §6)
```

---

## 2. Endpoint catalog

All endpoints are reactive (`Mono<…>`) using Spring WebFlux, matching the existing `AuthController` style.

### 2.1 Auth endpoints

| Method | Path | Auth req'd | Rate-limited (M5b) | CSRF (M5b) | Body | Response |
|---|---|---|---|---|---|---|
| POST | `/v1/auth/signup` | none | per-IP + per-email | no | `SignupRequest { email, displayName, password }` | 202 (empty) |
| GET | `/v1/auth/verify` | none (token) | per-IP | no | `?token=…` | 302 → `/login?verified=1` |
| POST | `/v1/auth/verify/resend` | none | per-IP + per-email (60s/5h) | no | `{ email }` | 202 |
| POST | `/v1/auth/login` | none | per-IP + per-username | no | `LoginRequest { strategy: "bootstrap"\|"delegate"\|"user-password", … }` | 200 `MeResponse` + cookie |
| POST | `/v1/auth/logout` | session | n/a | yes | empty | 204 + cleared cookie |
| GET | `/v1/auth/me` | session | n/a | no | n/a | `MeResponse { user, memberships, platformAdmin, csrfToken }` |
| POST | `/v1/auth/password-reset/request` | none | per-IP + per-email | no | `{ email }` | 204 (always) |
| POST | `/v1/auth/password-reset/confirm` | none (token) | per-IP | no | `{ token, newPassword }` | 204 |
| GET | `/v1/auth/invitations/accept` | optional | per-IP | no | `?token=…` | 302 → `/workspaces/{code}` or `/signup?invite=…` |

### 2.2 Workspace endpoints

All under `/v1/admin/workspaces`. Every one runs `WorkspaceAccessGuard.require(...)` before business logic except `GET /` and `POST /`.

| Method | Path | Min role | Notes |
|---|---|---|---|
| GET | `/v1/admin/workspaces` | session (any user) | Returns workspaces where caller has a membership. Platform admin → all (audited). |
| POST | `/v1/admin/workspaces` | session | Creates workspace + OWNER membership atomically. Body: `{ displayName, code? }`. |
| GET | `/v1/admin/workspaces/{idOrCode}` | `VIEWER` | Workspace detail. |
| PATCH | `/v1/admin/workspaces/{idOrCode}` | `ADMIN` | Rename, change settings. Body: `{ displayName?, settings? }`. |
| DELETE | `/v1/admin/workspaces/{idOrCode}` | `OWNER` | Soft archive. Body requires `{ confirmCode: "<code>" }` (typed match). |
| GET | `/v1/admin/workspaces/{idOrCode}/members` | `VIEWER` | List members + roles. |
| PATCH | `/v1/admin/workspaces/{idOrCode}/members/{userId}` | `ADMIN` | Change role. ADMINs cannot promote/demote OWNER. |
| DELETE | `/v1/admin/workspaces/{idOrCode}/members/{userId}` | `ADMIN` | Remove member. 409 if last OWNER. |
| GET | `/v1/admin/workspaces/{idOrCode}/invitations` | `ADMIN` | List pending. |
| POST | `/v1/admin/workspaces/{idOrCode}/invitations` | `ADMIN` | Send invitation. Body: `{ email, role }`. |
| DELETE | `/v1/admin/workspaces/{idOrCode}/invitations/{invId}` | `ADMIN` | Revoke pending invitation. |

### 2.3 Existing endpoints — hardening

- **Aliasing:** `POST /v1/admin/tenants` keeps working as an alias of `POST /v1/admin/workspaces` for one release (`@RequestMapping({"/v1/admin/tenants","/v1/admin/workspaces"})` on the new controller; old `TenantController` is gutted). Returns identical body.
- **Required `tenant` param:** Every existing admin endpoint that accepts `?tenant=…` or `req.tenant()` now requires the value. The `"default"` fallback in `AdminController.requireTenant()` is **removed**.
- **Guard injection:** Every admin endpoint gets a `WorkspaceAccessGuard.require(userId, workspaceId, role)` call at the top of the handler. Suggested pattern: a `WorkspaceScoped` request-scoped bean populated by an `@Aspect` that intercepts methods annotated `@WorkspaceScoped(Role.VIEWER)` and binds the verified context to `WorkspaceContextHolder`.

### 2.4 Audit endpoint (M5b)

| Method | Path | Min role | Notes |
|---|---|---|---|
| GET | `/v1/admin/audit/user-events` | `ADMIN` (workspace-scoped) OR `PLATFORM_ADMIN` (cross-workspace) | Paged, default 50. Filters: `kind`, `user_id`, `workspace_id`, `from`, `to`. |

---

## 3. SPI contracts

### 3.1 `OperatorAccountService`

```java
package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import java.util.List;
import java.util.Optional;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;

/**
 * Admin-plane account lifecycle. Distinct from the runtime {@code Authenticator}
 * SPI (which authenticates end-user {@code Principal}s in conversations) —
 * see TRD §1.1 and the design doc §6.
 */
public interface OperatorAccountService {

    /**
     * Idempotent and enumeration-safe. Returns 202-shaped result even if email
     * already exists. Side-effects: insert user (PENDING_VERIFICATION) +
     * mint+email verification token, OR send duplicate-signup decoy mail.
     */
    SignupOutcome register(SignupRequest req, RequestMeta meta);

    /** Activates user; provisions default workspace + OWNER membership; redeems any pending invitations to this email. Single tx. */
    VerifyOutcome verifyEmail(String rawToken, RequestMeta meta);

    /** Argon2 verify + session-shape result. Throws AccountAuthException on bad creds, locked, etc. */
    AuthenticationResult login(UserCredentials creds, RequestMeta meta);

    /** Always returns silently. Side-effect: if user exists & ACTIVE, mint reset token + send mail. */
    void requestPasswordReset(String email, RequestMeta meta);

    /** Rotates password hash AND deletes all operator_sessions for this user_id in one tx. */
    void confirmPasswordReset(String rawToken, String newPassword, RequestMeta meta);

    Optional<User> findByEmail(String email);
    Optional<User> findById(UserId id);
    List<Membership> workspacesFor(UserId userId);

    /** Auto-provisions a users row from a verified HTTP-delegate Operator (first-login only). Idempotent. */
    User provisionFromDelegate(String subject, String email, String displayName, RequestMeta meta);

    /** Soft-delete (status=DELETED, deleted_at=now). PII scrub happens later via cron. */
    void deleteAccount(UserId userId, RequestMeta meta);

    record SignupOutcome(boolean created, UserId userId /* nullable */) {}
    record VerifyOutcome(UserId userId, TenantId defaultWorkspaceId) {}
    record RequestMeta(String clientIp, String userAgent, String attemptId) {}
}
```

**Implementation notes:**
- Argon2id via the existing `Argon2PasswordEncoder` bean (no second crypto dep).
- All token I/O uses `sha256(rawToken)` for the DB row and the raw token only in the outbound email.
- Token-consume operations use `UPDATE … WHERE consumed_at IS NULL RETURNING …` — single-shot atomic.
- `provisionFromDelegate` is called from a thin adapter inside `delegate/` that wraps the existing `HttpDelegateOperatorAuthenticator` post-success.

### 3.2 `WorkspaceAccessGuard`

```java
package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import java.util.Collection;
import java.util.Set;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Membership;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;

public interface WorkspaceAccessGuard {

    /**
     * Returns the membership iff (user has it AND role >= atLeast) OR user is
     * platform-admin. Throws {@link WorkspaceNotFoundException} otherwise —
     * which the controller advice maps to 404 with the exact same body as
     * "workspace does not exist." Existence non-leak: 404, not 403.
     */
    Membership require(UserId user, TenantId workspace, Role atLeast);

    /** Restricts a candidate id set to those visible to this user. Platform-admin gets the full set. */
    Set<TenantId> visible(UserId user, Collection<TenantId> candidates);

    /** Convenience: resolve from current OperatorContext, validate, and bind to {@link WorkspaceContextHolder}. */
    WorkspaceContext bindFromCurrent(TenantId workspace, Role atLeast);

    final class WorkspaceNotFoundException extends RuntimeException {}
}
```

**Why not extend `Authorizer`?** Already covered in design §6. `Authorizer` operates on `PermissionRequirement` in conversation context — admin-plane access is a different shape (URL → workspace → membership). Two SPIs, one trust boundary each.

### 3.3 `InvitationService`

```java
public interface InvitationService {
    Invitation create(TenantId workspace, String email, Role role, UserId invitedBy);
    List<Invitation> listPending(TenantId workspace);
    void revoke(InvitationId id, UserId actor);
    /** Token-bearer accept. Idempotent if already consumed by the same user. */
    AcceptOutcome accept(String rawToken, Optional<UserId> signedInUser, RequestMeta meta);

    sealed interface AcceptOutcome {
        record Accepted(UserId userId, TenantId workspace, Role role) implements AcceptOutcome {}
        record NeedsSignup(String email, TenantId workspace, String invitationTokenForCarry) implements AcceptOutcome {}
        record Expired() implements AcceptOutcome {}
        record AlreadyConsumed() implements AcceptOutcome {}
    }
}
```

### 3.4 `MailSender`

```java
package com.yayatechandinnovations.yayaagentic.email;

public interface MailSender {
    /** Synchronous from the caller's POV; impl decides queue vs. direct. May be invoked from within a tx — must not throw on transient send failure (enqueue to outbox instead). */
    void send(MailMessage msg);

    record MailMessage(String to, String subject, String html, String text, String templateId, String idempotencyKey) {}
}
```

Two production implementations:
- `SendGridMailSender` — direct send via SendGrid API; on failure (`5xx` or timeout) writes to `email_outbox`. `MailOutboxDrainer` re-tries.
- `DevConsoleMailSender` — `log.info("[MAIL] to={} subject={}\n{}\n", to, subject, text)`. Used in `local` and `dev` profiles.

Bean selection via `@ConditionalOnProperty(name = "yaya.agentic.email.provider", havingValue = "sendgrid", matchIfMissing = false)`.

### 3.5 Existing SPIs touched

| SPI | Change |
|---|---|
| `OperatorAuthenticator` | New strategy `UserPasswordOperatorAuthenticator` registered in the chain at order 50 (before bootstrap@100). Yields when `creds` is not a user-password attempt. |
| `OperatorSessionService` | Persist `user_id` (nullable) and widen `source` enum. New method `deleteAllForUser(UserId)` called by password-reset confirm. |
| Eleven runtime SPIs | **Untouched.** |

---

## 4. Authentication flow integration

### 4.1 Chain order

```
OperatorAuthenticator chain (ordered):
  10  UserPasswordOperatorAuthenticator      ← NEW
  50  HttpDelegateOperatorAuthenticator      (existing)
 100  BootstrapOperatorAuthenticator         (existing; off-by-default in prod via config)
```

Each authenticator returns `Optional<Operator>`. First non-empty wins. The chain runs inside `AuthController#login` already.

### 4.2 `LoginRequest` discriminator

Body becomes:

```java
record LoginRequest(
    String strategy,              // "bootstrap" | "delegate" | "user-password"; backward-compat null → "bootstrap"
    String username, String password,   // bootstrap / user-password
    String email,                       // user-password (aliased into username)
    String token                        // delegate (existing)
) {}
```

The existing `username/password` fields keep working for bootstrap. `user-password` uses `email`-or-`username`+`password`. Backward-compat: `null` strategy assumes `bootstrap` (matches today's behavior).

### 4.3 Session row shape

`operator_sessions` adds:
- `user_id UUID NULL REFERENCES users(id) ON DELETE CASCADE`
- `source` enum gains `USER_PASSWORD`, `USER_OIDC`.

`OperatorSessionService.create()` accepts an optional `UserId`. `OperatorAuthWebFilter` reads the session row, looks up the user (cached), and populates `Operator.attributes` with:
- `user_id` (UUID string)
- `email`
- `is_platform_admin` (bool)
- `csrf_token` (rotated on login)

### 4.4 HTTP-delegate auto-provision

`HttpDelegateOperatorAuthenticator` is wrapped by a `DelegateAccountProvisioner` aspect: on successful auth, it calls `OperatorAccountService.provisionFromDelegate(...)`. Idempotent — first call inserts, subsequent calls look up by `subject` or `email`.

The aspect runs **inside** the auth chain (before session minting) so the session row always has a `user_id` populated.

### 4.5 Bootstrap operator

`BootstrapOperatorAuthenticator` keeps minting `Operator(source=BOOTSTRAP, attributes.user_id=null, attributes.is_platform_admin=true)`. The guard short-circuits on `is_platform_admin=true`. Audit row carries the synthetic platform-admin user id (seeded in V21) so foreign keys hold.

Disabled by default in prod via:
```java
@ConditionalOnProperty(name = "yaya.agentic.operator-auth.bootstrap.enabled",
                       havingValue = "true",
                       matchIfMissing = false /* dev profile sets it true */)
public class BootstrapOperatorAuthenticator { … }
```

---

## 5. Repository pattern & workspace propagation

### 5.1 The `WorkspaceContext` contract

```java
public record WorkspaceContext(TenantId workspaceId, UserId requestingUserId, Role role) {}

public final class WorkspaceContextHolder {
    public static WorkspaceContext current();           // throws IllegalStateException if unbound
    public static Optional<WorkspaceContext> tryCurrent();
    public static <T> T runWith(WorkspaceContext ctx, Supplier<T> body);
    public static Disposable subscribe(WorkspaceContext ctx);   // Reactor Context bridge
}
```

Reactive code uses Reactor `Context` (not ThreadLocal) — the holder bridges between annotation-driven advice (thread-bound) and `Mono.deferContextual(...)` for service code.

### 5.2 Repository convention

Every `@Repository` interface method either:
1. Accepts `WorkspaceContext` or `TenantId` as a parameter and uses it in the query, OR
2. Is annotated `@WorkspaceUnscoped` (allowed **only** on the following repositories):
   - `UserRepository`
   - `MembershipRepository` (writes are scoped; reads of "my memberships" are by `userId`)
   - `InvitationRepository` (token-bearer accept must look up across workspaces)
   - `EmailVerificationTokenRepository`, `PasswordResetTokenRepository`
   - `OperatorSessionRepository` (already scoped by session_id; user_id is the only join)
   - `AuditUserEventRepository` (cross-workspace by design when `PLATFORM_ADMIN`)

### 5.3 Build-failing scan test

`src/test/java/.../RepositoryScanTest.java` (M5a deliverable):
1. Scans the classpath for `@Repository`-annotated classes.
2. For each public method: assert at least one parameter is `WorkspaceContext` or `TenantId`, OR the method/class is `@WorkspaceUnscoped`.
3. Fail with a precise message naming the violator. New repository methods without scoping go red on the build.

### 5.4 Postgres GUC belt (M5b)

A `WorkspaceContextTransactionAdvice` `@Around` advice on every `@WorkspaceScoped` controller method opens a transaction and executes:

```sql
SET LOCAL yaya.workspace_id = '<uuid>'
```

immediately after the tx begins. Tables `profiles`, `capabilities`, `tools_registry`, `tenant_jwt_secrets`, `tenant_oidc_issuers` carry a Postgres CHECK:

```sql
ALTER TABLE profiles ADD CONSTRAINT profiles_workspace_guc_chk
  CHECK (tenant_id::text = current_setting('yaya.workspace_id', true));
```

If the Java path forgets to scope a query and the row's `tenant_id` differs from the GUC, the INSERT/UPDATE fails loudly. Reads are unaffected — this is a write-canary, not a read-isolator.

> **Decision:** CHECKs land in M5b only; M5a relies on the Java guard + repo-scan test exclusively. CHECKs require the GUC plumbing to be 100% reliable first; we want a phase to observe.

---

## 6. Configuration keys

### 6.1 New top-level groups under `yaya.agentic.*`

```yaml
yaya.agentic:
  user-accounts:
    enabled: false                         # M5a feature flag
    first-user-is-platform-admin: false    # mirror of YAYA_FIRST_USER_IS_PLATFORM_ADMIN env
    password:
      min-length: 12
      classes-required: 3                  # of {lower, upper, digit, symbol}
    verification:
      token-ttl: 24h
      resend-cooldown: 60s
      resend-per-hour-cap: 5
    reset:
      token-ttl: 1h
    invitation:
      token-ttl: 7d
    session:
      user-ttl: 12h
      bootstrap-ttl: 1h
  email:
    enabled: true
    provider: sendgrid                     # sendgrid | dev-console
    from: "noreply@yaya.example.com"
    reply-to: "support@yaya.example.com"
    sendgrid:
      api-key: ${SENDGRID_API_KEY:}        # never in config files
    outbox:
      max-retries: 3
      backoff-initial: 30s
      backoff-multiplier: 2.0
      drainer-fixed-rate: 60s
  rate-limit:                              # M5b
    enabled: true
    signup:
      per-ip: { window: 1h, max: 5 }
      per-email: { window: 1h, max: 3 }
    login:
      per-ip: { window: 5m, max: 20 }
      per-account: { window: 15m, max: 10, lockout: { after: 10, ttl: 30m } }
    password-reset:
      per-ip: { window: 1h, max: 10 }
      per-email: { window: 1h, max: 3 }
    invitation-accept:
      per-ip: { window: 1h, max: 10 }
  csrf:                                    # M5b
    enabled: true
    header: X-Yaya-CSRF
    rotate-on-login: true
  gdpr:                                    # M5b
    scrub-after: 30d
    cron: "0 0 3 * * *"                    # 3am daily
  operator-auth:
    bootstrap:
      enabled: false                       # NEW: prod=false, dev/local=true
```

### 6.2 Profile-specific overrides

- `application-local.yml`: `user-accounts.enabled=true`, `operator-auth.bootstrap.enabled=true`, `email.provider=dev-console`.
- `application-docker.yml`: same as local plus a fixed SendGrid key for staging mail.
- Prod profile (file not in repo): `bootstrap.enabled=false`, `email.provider=sendgrid`, `rate-limit.enabled=true`, `csrf.enabled=true`.

---

## 7. Dependencies

### 7.1 Added Maven dependencies

```xml
<!-- SendGrid official Java SDK; isolated impl behind MailSender SPI -->
<dependency>
  <groupId>com.sendgrid</groupId>
  <artifactId>sendgrid-java</artifactId>
  <version>4.10.3</version>
</dependency>
```

That's it. No second crypto dep, no new validator dep, no new ORM tooling.

### 7.2 Existing dependencies leveraged

| Dep | Usage |
|---|---|
| `Argon2PasswordEncoder` (Spring Security, already in `CoreConfiguration`) | password + token hashing |
| `spring-boot-starter-data-redis` | rate limiting (M5b) |
| `nimbus-jose-jwt` | unchanged (used by end-user auth, not by user accounts) |
| `CsrfWebFilter` (existing) | extended for double-submit token validation (M5b) |
| `LoginRateLimiter` (existing) | extended with the new endpoint coverage (M5b) |
| Flyway | migrations V20–V25 |
| Postgres 16 + pgvector | unchanged |

### 7.3 Flutter dependencies (Flutter app)

No new deps. Existing `flutter_riverpod + dio + freezed + json_serializable + go_router` covers everything per UI-UX §1.2.

---

## 8. Performance & security requirements

### 8.1 Performance budgets

| Surface | Budget | Notes |
|---|---|---|
| `WorkspaceAccessGuard.require()` | p99 ≤ 5ms in-process | Single PK lookup, optionally cached per-request in `OperatorContext`. |
| `OperatorAccountService.login()` (argon2) | p50 ≤ 100ms, p99 ≤ 500ms | Hardware-dependent; matches design §3.1 |
| `GET /v1/auth/me` | p99 ≤ 50ms in-process | Memberships fetched lazily then cached in OperatorContext. |
| `OperatorAccountService.verifyEmail()` | p99 ≤ 200ms | Includes tenant insert + membership insert + invitation redemption in one tx. |
| Mail outbox drain | every 60s; processes ≤ 100 msgs/run | Fixed-rate `@Scheduled`. |

### 8.2 Security requirements

| Requirement | Implementation |
|---|---|
| No password in logs ever | `OperatorCredentials` already uses `char[]`. Extend `UserCredentials` to mirror. Logback masking config kept. |
| No raw token in logs ever | Hash at the controller boundary; pass only the hash deeper. Outbound mail body is the only place the raw token appears. |
| Constant-time string compare for token lookup | `sha256` hash + DB PK lookup is already constant-time at the DB layer; `MessageDigest.isEqual(...)` for any in-memory fallback. |
| Cookie flags | HttpOnly + Secure + SameSite=Strict (already set by `OperatorSessionService`; just verify on user-source sessions too). |
| CSRF on mutating routes | Extend `CsrfWebFilter` to require `X-Yaya-CSRF` matching `OperatorContext.csrfToken()` on POST/PATCH/PUT/DELETE under `/v1/admin/**`, `/v1/auth/logout`. |
| Rate limit fairness | Sliding window + per-IP **and** per-account axes to prevent IP-rotation bypass. |
| 404 (not 403) for cross-workspace access | `WorkspaceAccessGuard.WorkspaceNotFoundException` mapped via controller advice to exact same body as `TenantRegistryNotFoundException`. |
| Email enumeration mitigation | Signup always 202; reset request always 204; duplicate-signup template sent if email already exists. |
| Token replay prevention | Single-use enforced by `consumed_at` + partial unique index `WHERE consumed_at IS NULL`. |
| GDPR scrub | Nightly `@Scheduled` job updates user PII columns to null/pseudonyms after 30 days post-`deleted_at`. |

### 8.3 Threat coverage

Per design §11 — all covered in implementation:

| Threat | Mitigation in code |
|---|---|
| Existence probe | `WorkspaceAccessGuard` → 404 |
| Cross-workspace query | Repo scan test + repo signatures + `SET LOCAL` GUC + CHECK constraints (M5b) |
| Stale session post-reset | `confirmPasswordReset()` deletes all sessions for user in same tx |
| Brute force | Per-IP + per-account rate limit + lockout (M5b) |
| Email enumeration | 202 / 204 / decoy mail |
| Bootstrap misuse | Off-by-default in prod + every action audited |
| Token replay | `consumed_at` unique partial index |
| Workspace blast radius | Soft archive only + typed-name confirm |
| Token storage | `sha256(raw)` stored |
| Code squatting | `UNIQUE(code)` + reserved list + signup retry with random suffix |
| Invitation token leak | `sha256` stored, single-use, TTL 7d, revocation marks consumed |
| Audit retention vs GDPR | Soft + 30-day scrub; UUID kept for joins |

---

## 9. Testing

### 9.1 Unit test coverage

- `DefaultOperatorAccountService` — every method, including idempotency, enumeration safety, and tx rollback paths.
- `DefaultWorkspaceAccessGuard` — membership / no-membership / platform-admin / unknown workspace.
- `DefaultInvitationService` — create / list / revoke / accept (signed-in, anonymous, expired, already-consumed).
- `UserPasswordOperatorAuthenticator` — chain ordering, claim/yield semantics.
- Token services — hash equality, single-use enforcement.

### 9.2 Integration tests (Spring + Testcontainers Postgres + Redis)

| Class | Coverage |
|---|---|
| `SignupVerifyIT` | Happy path; duplicate signup → decoy mail; expired token; resend cooldown. |
| `LoginIT` | All three strategies; bad password; locked account (M5b); session cookie set; CSRF token rotates (M5b). |
| `PasswordResetIT` | Request always 204; confirm rotates hash; existing sessions deleted. |
| `WorkspacesCRUDIT` | Create / list / patch / archive; OWNER membership minted atomically. |
| `MembersRolesIT` | Invite / accept / role change / remove; last-OWNER 409. |
| `CrossUserIsolationIT` | The big one (PRD AC-1). Two users × two workspaces; every endpoint cross-boundary → 404. |
| `HttpDelegateAutoProvisionIT` | First login provisions; second login idempotent. |
| `BootstrapModeIT` | Bootstrap on in dev profile; bootstrap off in prod profile. |
| `MigrationV21toV24IT` | UUID cutover against seeded V20 schema. |
| `RepositoryScanTest` | Build-failing reflection scan (M5a). |
| `MailOutboxIT` | Send failure → outbox → retry → success. |
| `RateLimitIT` (M5b) | Per-IP burst; per-account lockout + notification email. |
| `CsrfIT` (M5b) | Mutating request without `X-Yaya-CSRF` → 403. |
| `GuardCheckConstraintIT` (M5b) | Direct SQL write with mismatched GUC → constraint violation. |
| `GdprScrubIT` (M5b) | Soft-delete → cron → PII gone; audit UUID preserved. |

### 9.3 Flutter test coverage

- Widget tests for each new screen (loading / error / success states).
- `workspace_switcher_test.dart` — switching invalidates `currentWorkspaceProvider` and causes feature providers to re-fetch.
- `accept_invitation_screen_test.dart` — token redirects to signup when unauthenticated; mints membership when authenticated.
- `role_aware_gating_test.dart` — VIEWER doesn't see edit buttons; ADMIN sees member-management but not OWNER-only actions.

---

## 10. Migration runbook

### 10.1 V20–V25 sequence

Detailed shapes in [Backend-Data §4](Backend-Data.md). Summary:

| Migration | Purpose | Reversible? |
|---|---|---|
| V20 | Create `users`, `user_workspace_memberships`, `user_invitations`, `user_email_verification_tokens`, `user_password_reset_tokens`, `audit_user_events`, `email_outbox`. | yes (drop tables) |
| V21 | Seed: `platform-admin@local` synthetic user (no usable password). OWNER membership for every existing tenant. | yes |
| V22 | `tenants` add columns: `code VARCHAR(64) UNIQUE NOT NULL` (backfill from current `id`), `id_uuid UUID DEFAULT gen_random_uuid() NOT NULL`, `created_by UUID NOT NULL` (backfilled to synthetic user), `owner_user_id UUID NOT NULL`. | yes (additive) |
| V23 | For every child table T with `tenant_id VARCHAR(64)`: add `tenant_id_uuid UUID`, backfill from `tenants.id → tenants.id_uuid`, drop old column + FK, rename, add FK to `tenants(id_uuid)`. Driven by a Flyway Java-callback that enumerates from `information_schema`. | **NO** (one-way) |
| V24 | Drop `tenants_pkey`; rename `tenants.id` → `tenants.legacy_slug_id` (retained one release); rename `id_uuid` → `id`; add PK on `id`. Repoint all child FKs to renamed `id`. | **NO** |
| V25 | `operator_sessions.user_id UUID NULL REFERENCES users(id) ON DELETE CASCADE`; widen `source` enum (`USER_PASSWORD`, `USER_OIDC`). | yes (additive) |

### 10.2 Pre-cutover steps

1. **Backup the production DB.** Snapshot + replica lag ≤ 0.
2. **Dry-run V21–V25 against a prod-shape staging DB.** Compare row counts before/after for every child table.
3. **Run `MigrationV21toV24IT`** against the staging DB.
4. **Lock writes** (read-only mode via load balancer) for the V23 backfill window. Typical duration: < 60s for ≤ 100k child rows total; longer for larger datasets.
5. **Apply migrations.** Re-enable writes after V25 completes.
6. **Verify** by querying:
   - `SELECT COUNT(*) FROM tenants` (unchanged).
   - For each child table: `SELECT COUNT(*) WHERE tenant_id IS NULL` = 0.
   - `SELECT COUNT(*) FROM user_workspace_memberships` = `COUNT(*) FROM tenants` (one OWNER per tenant from V21).

### 10.3 Cutover failure & rollback

- V20–V22 + V25 are additive — drop to roll back.
- V23 + V24 are **not** reversible. The pre-cutover dry-run + staging soak is the only safety net.
- If V23 fails mid-run: Postgres tx aborts; the partially-added `tenant_id_uuid` columns are dropped via a Flyway repair migration. Schema is back to V22.
- If V24 fails: schema rolls back inside a single tx; `id` and `id_uuid` both still exist; can re-attempt.
- Application-level rollback: flip `yaya.agentic.user-accounts.enabled=false`. Signup/login/teams routes 404. Existing operator flow resumes.

### 10.4 Post-cutover cleanup (R5)

- Drop `tenants.legacy_slug_id`.
- Remove `POST /v1/admin/tenants` alias (clients have migrated to `/workspaces`).
- Remove the temporary `TenantSlug` type alias.

---

## 11. Code-style conventions to honor

From `CLAUDE.md`:

- **Records over classes** for value types. `User`, `Membership`, `Invitation`, `WorkspaceContext`, `SignupRequest`, etc. are records.
- **Sealed types** for `Role` (actually an enum — fine), `InvitationService.AcceptOutcome` (sealed).
- **Typed IDs.** `UserId`, `InvitationId` wrap UUIDs; never pass raw `UUID`/`String` across SPIs.
- **No business logic in `package-info.java`.** Each new package gets one anchoring back to a design-doc / spec section.
- **No comments that explain WHAT.** Only the WHY (a hidden constraint, a denial-by-design choice).
- **Authorization denials carry two reasons.** For 409s and 403s, `userSafeReason` ≠ `auditReason`.

---

## 12. Open implementation choices (small)

These are *not* re-litigating the design's open questions — they are micro-decisions left for the implementing engineer. Defaults are recommended.

1. **Reactor Context vs ThreadLocal for `WorkspaceContextHolder`** — **Reactor Context (recommended).** The codebase is WebFlux throughout. ThreadLocal works but loses context across schedulers.
2. **JPA vs hand-rolled JDBC for new repositories** — match the prevailing repo style in `operator_auth/session/`. Probably Spring Data JPA with explicit `@Query` for the tx-sensitive token-consume paths.
3. **Slug generator library vs hand-rolled** — hand-rolled. Spec: lowercase ASCII alphanumeric + dash, ≤ 32 chars, strip leading/trailing dashes, append `-xxx` random suffix on collision. ~30 LOC; avoids a dep.
4. **CSRF token shape** — 32-byte random base64-URL-encoded; stored in the session row; sent in `/v1/auth/me` response and in a non-HttpOnly `XSRF-TOKEN` cookie (the "double-submit" half).
5. **Outbox-vs-direct send for confirmation/invitation mail** — always go through the outbox for at-least-once semantics. Set `idempotencyKey` from `(template, token_hash)`.

---

## 13. References

- [PRD](PRD.md), [UI-UX](UI-UX.md), [App-Flow](App-Flow.md), [Backend-Data](Backend-Data.md).
- `docs/design/yaya-agentic-user-accounts-design.md` — source design.
- `docs/design/operator-auth-design.md` — current operator-auth surface this TRD extends.
- `docs/design/tenant-registry-design.md` — current tenant model now UI-renamed to Workspace.
- `src/main/java/.../operator_auth/web/AuthController.java` — current `/v1/auth` controller this TRD extends.
- `src/main/java/.../config/CoreConfiguration.java` — Argon2 bean already wired.
