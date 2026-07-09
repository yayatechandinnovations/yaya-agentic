# yaya-identity — Technical Requirements (TRD)

> **Feature:** yaya-identity — the shared, multi-tenant **identity provider (IdP) and authorization service** for the apps we ship (`apps/`).
> **Implements:** [PRD](PRD.md) (US-xx / FR-xx / AC-xx / NFR-xx / M1–M7) · [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) (master design — domain §5, PDP §7, module map §8, API §9, data model §14, SPI appendix §15).
> **Maps to milestones:** I0–I6 (design §13; PRD §8 releases R0–R6).
> **Status:** Draft v1 — implementation-ready.
> **Companion docs:** [PRD](PRD.md) · [UI-UX](UI-UX.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).
> **Anchors (yaya-agentic):** `docs/design/yaya-agentic-design.md` §5.4 (AuthN), §5.5 (AuthZ / two-reason `AuthzDecision`), §5.6 (profile/identity resolution), §12 (trust boundaries). CLAUDE.md non-negotiables **#1** (LLM never trusted — mirrored here: *apps are PEPs, the PDP is the sole gate*), **#3** (denials carry two **different** reasons), **#6** (tokens never silently forwarded — explicit RFC 8693 exchange only).

This doc tells an engineer what to build at the module, API, SPI, data, and crypto layers of yaya-identity. Product intent lives in the PRD; data shapes in Backend-Data; screen shapes in UI-UX; runtime sequence in App-Flow. The design doc's decisions are settled — this TRD turns them into engineering, it does not re-open them.

Root package: `com.yayatechandinnovations.yayaidentity`. This is a **separate application** under `apps/` — it does **not** import `com.yayatechandinnovations.yayaagentic.*` and is not imported by it. All integration is over published seams (OIDC + JWKS, the PDP API, the admin API, outbound events).

---

## 1. Architectural fit

### 1.1 Trust planes

yaya-identity is the **root of trust** for the ecosystem (design §11); its compromise is total, so it carries the repo's highest security bar. It is a **producer** of identity — a peer of, not a part of, yaya-agentic's `Authenticator`/`Authorizer` SPIs, which remain **consumers**.

| Plane | Who decides | Where | This app is | Mirrors agent |
|---|---|---|---|---|
| **Authentication** — *who is this?* | yaya-identity `TokenIssuer` mints RS256 JWTs; relying parties verify statelessly via JWKS | Issuer here; verification at the RP | **Producer** (issuer) | §5.4, `OidcAuthenticator` (consumer) |
| **Authorization** — *may they?* | yaya-identity `PolicyDecisionPoint` (the sole decision point) | PDP here; enforcement (PEP) at the RP | **Producer** (PDP) | §5.5 `AuthzDecision`; §12 "tools never check policy themselves" |

The discipline is identical to the agent's *"the LLM proposes, the Authorizer disposes"*: **an app proposes an action; the PDP disposes.** Apps are **PEPs** — they call the PDP and enforce the answer; they never re-implement policy (FR-4.8, NFR-Sec-3). This is the non-negotiable-#1 mirror: the enforcement point is never the decision point.

### 1.2 Architecture at a glance

Two seams to the outside world, matching design §4:

- **Verify a token** — stateless, cheap, coarse. The RP fetches JWKS once (cached), verifies RS256 offline, reads `roles`/`scope`/`aud` claims. No round-trip. Serves coarse RBAC checks (US-34, FR-3.5).
- **Ask the PDP** — stateful, fine-grained, ABAC. `POST /pdp/decide` for anything conditional (amounts, ownership, time) or high-value. The only place policy is evaluated (US-40, FR-4.1).

Everything else — orgs, users, credentials, roles, policies, clients, tokens, sessions, audit — is internal state behind those seams plus the admin API.

### 1.3 Tech stack (design §12, NFR-Dep-1)

| Concern | Choice | Rationale |
|---|---|---|
| Language / runtime | **Java 21** | Records + sealed types + pattern-matching switches are load-bearing for the domain model (design §5, §15) and the `Decision`/`Scope`/`ToolHandlerRef`-style unions. |
| Framework | **Spring Boot 3.5.x** | Same major as yaya-agentic and yaya-sales; shared operational knowledge. |
| OAuth2/OIDC engine | **Spring Authorization Server** | See §1.4. |
| Module boundaries | **Spring Modulith** (`@ApplicationModule`) | Enforces the design §8 module map at build time (`ApplicationModules.verify()`); keeps the modular monolith from rotting into a ball of mud. |
| Persistence | **Postgres 16**, own `yaya_identity` schema, **Flyway** | FR-7.2 / NFR-Dep-3: zero cross-schema coupling so it lifts to its own instance. |
| Rate-limit / OTP throttle | **Redis** (sliding window) | FR-6.2; fail-open with loud audit on Redis outage (NFR-Avail-2). |
| Password hashing | **Argon2id** | NFR-Sec-1; Spring Security `Argon2PasswordEncoder`. |
| Token signing | **RS256** with rotated keys | FR-1.3/1.4; make-before-break JWKS (NFR-Avail-3). |
| Secret envelope | **AES-256-GCM** | NFR-Sec-1; envelope-encrypts private signing keys + client secrets, mirroring the agent's `JwtSecretCipher`. |
| Admin console | **Flutter web** (`flutter_riverpod + dio + freezed + json_serializable + go_router`) | Same stack as the other consoles; policy simulator (US-61). Detailed in UI-UX. |

### 1.4 Why Spring Authorization Server (SAS) is the OIDC engine

The self-hosted, no-paid-services constraint rules out Auth0/Okta/Cognito (PRD §1.1). Among self-hostable options (Keycloak, Ory Hydra, SAS), we pick **Spring Authorization Server** because:

1. **It is a library, not a product.** SAS is the OAuth2/OIDC *authorization-server* half of Spring Security, embedded in our own boot app. We keep one deployable, one DB, one migration story — not a second server (Keycloak) with its own realm model and admin API to reconcile against ours. The domain model (orgs/users/roles/policies) stays ours; SAS supplies only the protocol endpoints.
2. **It speaks the grants we need out of the box** — authorization-code + PKCE, client-credentials, refresh (rotating), device-code, and **token-exchange (RFC 8693)** — the exact grant set in FR-1.2. Discovery, JWKS, userinfo, introspect, revoke, logout are first-class.
3. **It composes with our SPIs.** Custom `OAuth2TokenCustomizer` injects our claim set (`org`, `groups`, scoped `roles`, `scope`, `aud`); a custom `OAuth2Authorization*Provider` bridges to our `CredentialProvider`s and `TokenIssuer`; `JWKSource` is backed by our `KeyRing`. We don't fork the protocol — we extend the seams SAS exposes.
4. **Zero-code agent trust falls out for free** (M1, NFR-Compat-1/2). SAS emits standard RS256 JWTs + a conformant discovery/JWKS document. yaya-agentic's existing `OidcAuthenticator` consumes exactly that. No custom glue on either side.

> **Settled (do not re-open):** SAS, not Keycloak-as-a-service, not a hand-rolled issuer. The remaining OAuth choices (grant set, claim set, RS256, rotation) are fixed by FR-1.x.

---

## 2. Module decomposition (Spring Modulith)

The design §8 map's **13 modules** become **13 Spring Modulith application modules** under the root package, each a `package-info.java` carrying `@ApplicationModule` and allowed dependencies. `ApplicationModules.of(YayaIdentityApplication.class).verify()` runs as a build-failing test — an illegal cross-module reference reddens the build (the identity analog of the agent's SPI discipline).

```
com.yayatechandinnovations.yayaidentity
├── platform        (#13) ★ foundation — no deps on siblings
├── organizations   (#1)
├── users           (#2)
├── credentials     (#3)
├── groups          (#4)
├── roles           (#5) ★ RBAC weight
├── policies        (#6) ★ PDP / ABAC weight
├── clients         (#7)
├── tokens          (#8)
├── sessions        (#9)
├── audit           (#10)
├── admin           (#11)
└── integration     (#12)
```

### 2.1 Modules, ownership, allowed dependencies

| # | Module | Owns (design §8) | May depend on | Implements |
|---|---|---|---|---|
| 13 | **platform** | Typed IDs, `KeyRing` (signing keys + rotation), `SecretCipher` (AES-256-GCM), `OutboxRelay`, `IdempotencyStore`, rate-limit, observability | *(nothing — leaf foundation)* | I0; FR-6.3, FR-7.3 |
| 1 | **organizations** | Orgs, memberships, invitations, `parent_id` (flat v1) | platform | US-20/21/22/25; FR-2.x |
| 2 | **users** | User identities (`PERSON`/`SERVICE`), profiles, lifecycle, service-account users | platform, organizations | US-23; FR-2.2 |
| 3 | **credentials** | `CredentialProvider`s (password/OTP/TOTP), MFA policy | platform, users | US-02/03/04; FR-1.6/1.7 |
| 4 | **groups** | Group membership, bulk role targeting | platform, users, organizations | US-24; FR-2.5 |
| 5 | **roles** ★ | Permission catalog, roles, **scoped role assignments** (RBAC) | platform, users, groups, organizations | US-30…36; FR-3.x |
| 6 | **policies** ★ | Policy store, `ConditionEvaluator`, **PDP** (ABAC) | platform, roles, users, groups, organizations | US-40…48; FR-4.x |
| 7 | **clients** | Relying-party + service-account registration, secrets, audiences, grants | platform, organizations | US-50/54; FR-5.1/5.4 |
| 8 | **tokens** | `TokenIssuer` — OAuth2/OIDC issuance (SAS), JWKS, refresh rotation, RFC 8693 exchange, revoke/introspect | platform, credentials, users, roles, clients, sessions | US-01/05/07/08/51/52/55; FR-1.x/5.2/5.3/5.5 |
| 9 | **sessions** | Login sessions, devices, logout, back-channel logout | platform, users, clients | US-09; FR-2.4 |
| 10 | **audit** | Append-only auth + decision log | platform | US-66; FR-6.1, NFR-Obs-1 |
| 11 | **admin** | Management API + Flutter console backing; **itself PDP-gated** | policies (PDP), roles, orgs, users, groups, clients, audit | US-60…66; FR-6.4 |
| 12 | **integration** | OIDC discovery surface, PDP API, client SDK, **outbound events** | policies, roles, tokens, platform | US-70/71/72; §5 |

★ = the two **authorization-weight** modules. **roles** carries RBAC — scope resolution (`Scope.Org` vs `Scope.Resource`), effective-permission union across direct + group assignments (FR-3.4). **policies** carries the ABAC/PDP core — the evaluation loop, the sandboxed condition evaluator, deny-overrides, default-deny, two-reason denials (FR-4.x). These two modules get the deepest unit coverage (§9).

**Rules.** No module reaches into another's persistence. `admin` and `integration` orchestrate but own no domain state. `audit` is written to by many but is dependency-free (append-only sink). `platform` is the only universal leaf. `tokens` depends on `roles` (to embed scoped-role claims) but **not** vice-versa.

---

## 3. Typed IDs, records & sealed types

Per CLAUDE.md: **records over classes** for value types, **sealed types** for discriminated unions, **typed IDs** across every SPI boundary — never a raw `String`/`UUID`.

### 3.1 Typed IDs (`platform`)

```java
package com.yayatechandinnovations.yayaidentity.platform.id;

public record OrgId(String value)        {}
public record UserId(String value)       {}
public record GroupId(String value)      {}
public record RoleId(String value)       {}
public record PolicyId(String value)     {}
public record ClientId(String value)     {}
public record AssignmentId(String value) {}   // a RoleAssignment
// value = a UUID string; factories OrgId.of(UUID) / OrgId.of(String)-validating mirror the
// agent's Ids convention. Never pass a bare String across a module or SPI boundary.
```

### 3.2 Domain records (design §5) — verbatim type names

```java
public record Organization(OrgId id, String name, String slug, OrgStatus status,
                           OrgId parent,                       // nullable = root; flat v1 (Q6)
                           Map<String,Object> metadata, Instant createdAt) {}
public enum OrgStatus { ACTIVE, SUSPENDED }

public record User(UserId id, String primaryEmail, String phone, String displayName,
                   UserKind kind, UserStatus status, Instant createdAt) {}
public enum UserKind   { PERSON, SERVICE }
public enum UserStatus { INVITED, ACTIVE, LOCKED, DISABLED }

public record Membership(UserId user, OrgId org, MembershipStatus status, Instant joinedAt) {}
public record Group(GroupId id, OrgId org, String name, Set<UserId> members) {}

public record Permission(String key) {}                       // "<app>.<resource>:<action>"
public record Role(RoleId id, OrgId org,                      // null org = system role
                   String name, boolean system, Set<Permission> permissions) {}

public record Client(ClientId id, OrgId org, String name, ClientType type,
                     Set<GrantType> grants, Set<String> redirectUris, Set<String> audiences,
                     Set<Scope> allowedScopes) {}
public enum ClientType { PUBLIC, CONFIDENTIAL, SERVICE }

public record Policy(PolicyId id, OrgId org, String name, Effect effect,
                     Target target, Condition condition, int priority) {}
public enum Effect { PERMIT, DENY }
```

### 3.3 Sealed unions

```java
// The scope that makes per-resource ("store A but not B") access native — design §5.5
public sealed interface Scope permits Scope.Org, Scope.Resource {
    record Org(OrgId org) implements Scope {}
    record Resource(String type, String id) implements Scope {}   // ("store", "st_123")
}

// A grant of a role to a user OR a group within a scope — the RBAC keystone (FR-3.3)
public record RoleAssignment(AssignmentId id, AssignmentSubject subject,
                             RoleId role, Scope scope, Instant grantedAt) {}
public sealed interface AssignmentSubject permits AssignmentSubject.User, AssignmentSubject.Group {
    record User(UserId id)   implements AssignmentSubject {}
    record Group(GroupId id) implements AssignmentSubject {}
}

// The PDP verdict — the two-reason denial (#3, FR-4.6)
public sealed interface Decision permits Decision.Permit, Decision.Deny {
    record Permit(String obligationsJson) implements Decision {}          // optional obligations
    record Deny(String userSafeReason, String auditReason) implements Decision {}
}
```

> Adding a variant to any sealed type means re-reading every `switch` over it (CLAUDE.md). `Decision`, `Scope`, `AssignmentSubject` are frozen for v1.

---

## 4. Key SPIs (design §15)

All in the module that owns them; signatures track the design's SPI appendix. Records for DTOs, sealed types for unions.

### 4.1 `CredentialProvider` (`credentials`) — FR-1.6

```java
public interface CredentialProvider {
    CredentialKind kind();                                    // PASSWORD | PHONE_OTP | TOTP | (WEBAUTHN, OIDC_FEDERATION vNext)
    VerificationResult verify(UserId user, CredentialInput input);
}
public enum CredentialKind { PASSWORD, PHONE_OTP, TOTP, WEBAUTHN, OIDC_FEDERATION }
public sealed interface VerificationResult
        permits VerificationResult.Ok, VerificationResult.Rejected, VerificationResult.MfaRequired {
    record Ok(UserId user) implements VerificationResult {}
    record Rejected(String auditReason) implements VerificationResult {}   // caller returns a *generic* user error (enumeration-safe, FR-1.9)
    record MfaRequired(UserId user, Set<CredentialKind> factors) implements VerificationResult {}
}
```

v1 impls: `PasswordCredentialProvider` (Argon2id), `PhoneOtpCredentialProvider` (single-use, TTL ≤ 10 min, rate-limited — reuses the ecosystem OTP machinery), `TotpCredentialProvider`. WebAuthn / federation are vNext behind the same SPI (US-81, FR-1.6).

### 4.2 `PolicyDecisionPoint` (`policies`) — FR-4.1

```java
public interface PolicyDecisionPoint {
    Decision decide(DecisionRequest req);
    List<Decision> decideBatch(List<DecisionRequest> reqs);   // one round-trip, N checks (US-46/FR-4.1)
    Set<Permission> effectivePermissions(UserId subject, Scope resource);   // GET /pdp/permissions (US-36)
    Decision simulate(DecisionRequest req);                   // dry-run, persists nothing (US-45/FR-4.7)
}
public record DecisionRequest(UserId subject, OrgId org, String action /* permission key */,
                              ResourceRef resource, Map<String,Object> context) {}
public record ResourceRef(String type, String id, Map<String,Object> attributes) {}
```

### 4.3 `ConditionEvaluator` (`policies`) — sandboxed, FR-4.5

```java
public interface ConditionEvaluator {
    boolean matches(Condition c, AttributeBag subject, AttributeBag resource, AttributeBag env);
}
public record AttributeBag(Map<String,Object> attrs) {}       // read-only view; no live objects, no I/O
public record Condition(String jsonLogic) {}                  // JSON-logic AST string (v1); CEL only if it outgrows (Q4)
```

Sandbox contract (§6): pure function of the three bags, **no I/O, no reflection, no unbounded loops**, bounded node/time budget. See §6.2.

### 4.4 `TokenIssuer` (`tokens`) — FR-1.x, FR-5.3

```java
public interface TokenIssuer {
    TokenSet issue(GrantRequest g);          // code | client_credentials | refresh | device_code
    TokenSet exchange(ExchangeRequest x);     // RFC 8693 — rebind aud, preserve sub (#6, FR-5.3)
}
public record TokenSet(String accessToken, String refreshToken /* nullable */,
                       String idToken /* OIDC login only */, Instant accessExpiresAt) {}
public record ExchangeRequest(String subjectToken, String subjectTokenType,
                              String targetAudience, Set<String> requestedScopes) {}
```

`issue`/`exchange` are the domain seam **behind** Spring Authorization Server's endpoints — SAS handles protocol parsing/response shaping; `TokenIssuer` decides claims and mints via `KeyRing`.

### 4.5 Supporting SPIs (design §15)

```java
public interface PrincipalDirectory {                          // roles/`sessions`
    Optional<User> byId(UserId id);
    Set<RoleAssignment> assignments(UserId user, OrgId org);
}
public interface KeyRing {                                     // platform — rotation, NFR-Avail-3
    SigningKey active();                                       // current signing key
    List<Jwk> jwks();                                          // active + retiring (served at /oauth2/jwks)
    void rotate();                                             // make-before-break (§7.3)
}
public interface OutboxRelay      { void publish(); }          // platform → integration events (§5.5)
public interface IdempotencyStore {                            // platform — FR-7.3
    Optional<StoredResponse> find(String scope, String key);
    void put(String scope, String key, StoredResponse response);
}
```

---

## 5. The PDP engine internals (`policies`) — FR-4.x

The heart. Deny-by-default; RBAC supplies base permits; policies layer permits and, decisively, denies; **explicit deny always wins**. Every call ends in an audit row.

### 5.1 Evaluation order (FR-4.2, FR-4.3, FR-4.8)

`decide(subject, org, action, resource, context)`:

1. **Resolve scoped effective permissions.** Union permissions from every `RoleAssignment` — direct (`AssignmentSubject.User`) and via group (`AssignmentSubject.Group`) — whose `Scope` **covers** `resource` (design §5.5, FR-3.4). Coverage: `Scope.Org(org)` covers everything in the org; `Scope.Resource(type,id)` covers only that exact resource. If `action` ∉ the resolved set → **base = deny** (but continue — a `PERMIT` policy may still grant it).
2. **Match policies by priority.** Select policies whose `target` matches `(action, resource.type)`, evaluate **highest `priority` first**. For each, run `ConditionEvaluator.matches`. A matching **`DENY` short-circuits** to `Deny` (deny-overrides, FR-4.3). A matching **`PERMIT`** grants a right RBAC lacked.
3. **Combine.** Permit iff (base permit OR a matching PERMIT policy) AND no matching DENY policy. Else → **default-deny** (FR-4.2 step 3, US-48).
4. **Audit.** Emit an append-only `audit_log` row with subject, action, resource, the verdict, the **matched rule ids**, and both reasons on a deny (FR-4.2 step 4, US-42's audit, M4).

```
resolve scoped effective perms ──► base permit?
        policies by priority ──► any DENY match? ─yes─► Deny  (short-circuit, deny-overrides)
                                 any PERMIT match? ──┐
        base permit OR permit-policy, no deny ───────┴─► Permit
        otherwise ─────────────────────────────────────► Deny  (default-deny)
        always ────────────────────────────────────────► audit_log row
```

### 5.2 The condition sandbox (FR-4.5, FR-4.7, US-47)

`ConditionEvaluator` (JSON-logic v1, Q4) is a **pure evaluator over three read-only attribute bags** — **subject** (roles, groups, org, claims), **resource** (owner, store, region, amount, `assignedAgent`, …), **environment** (time, IP, request context the PEP passes in `DecisionRequest.context`).

- **No I/O, no reflection, no host access.** The evaluator sees only the three `AttributeBag`s — never a DB handle, a live entity, or the network. It cannot mutate anything.
- **Bounded.** Hard caps on AST node count and evaluation wall-time; JSON-logic has no loops, so termination is structural. A malformed/oversized condition is rejected at **policy save**, not at decide time.
- **Deterministic.** Same bags → same verdict, so `simulate` (US-45) is exact: it builds the identical bags for a hypothetical `(subject, action, resource, ctx)` and runs the same path with **zero persistence** and **zero audit write**.

### 5.3 Decision caching (Q3, FR-4.2 latency, NFR-Perf-2/5)

The PDP itself is authoritative and uncached server-side. **Caching lives at the relying party**, in the client SDK (§7.2): a short-TTL decision cache, **TTL ≤ 30s**, keyed on `(subject, action, resource, ctx-hash)`, **invalidated by `policy.changed` / `role.assigned` outbox events** (§5.5). Bounded staleness, amortized latency. `decide-batch` amortizes the fixed per-call cost across a list view (US-46, AC-25).

### 5.4 Two-reason denials (#3, FR-4.6, NFR-Sec-4)

Every `Decision.Deny` carries a **user-safe** reason (the app may show it — *"You can't refund orders over $500"*) and an **audit** reason (internal truth — *"policy `refund-cap` denied: amount 750 > limit 500 for role store-manager on store:st_123"*). They **MUST differ**. Enforced by:

- A `Deny.of(userSafeReason, auditReason)` factory that throws if `userSafeReason.equals(auditReason)`.
- A **build-failing test** (`DenyReasonLintTest`, §9) that scans every `Decision.Deny` construction site (and `AuthN` rejection reason) and fails on any equal pair (M5, AC-26). This is the identity mirror of the agent's `AuthzDecision` rule.

---

## 6. Token design (`tokens`) — FR-1.x, FR-5.x

### 6.1 Access-token claim set (FR-1.3)

RS256 JWT, TTL ≤ 15 min (configurable). Emitted via a SAS `OAuth2TokenCustomizer` that injects our claims:

```json
{
  "iss": "https://identity.yaya.example.com",
  "sub": "usr_9f3…",
  "org": "org_7c1…",
  "groups": ["grp_downtown-fleet"],
  "roles": [
    { "role": "store-manager", "scope": { "kind": "RESOURCE", "type": "store", "id": "st_A" } },
    { "role": "viewer",        "scope": { "kind": "RESOURCE", "type": "store", "id": "st_B" } }
  ],
  "scope": "openid profile sales.read",
  "aud": ["yaya-sales"],
  "iat": 1751932800,
  "exp": 1751933700
}
```

The `roles` claim carries `(role, scope)` pairs so RPs do **coarse checks with no PDP round-trip** (US-34, FR-3.5). `aud` is what an RP enforces (FR-5.2). **ID tokens** are issued for OIDC login flows only; `userinfo` returns the standard claim set (FR-1.8, US-10).

### 6.2 JWKS rotation — make-before-break (FR-1.4, NFR-Avail-3, AC-3)

`KeyRing` holds signing keys with status `ACTIVE | RETIRING`. `rotate()`:

1. Generate a new RSA keypair, envelope-encrypt the private key (§8.2), insert `status=ACTIVE`.
2. Demote the previous active key to `RETIRING` (it keeps verifying, stops signing).
3. **Publish both in JWKS before any old token expires.** A `RETIRING` key stays in `/oauth2/jwks` for **≥ the max access-token TTL** after rotation, so an in-flight token signed just before rotation still verifies (`KeyRotationIT`, AC-3). Only then is it dropped.

`kid` on every token resolves to the JWKS entry (US-05). Verification never has a gap.

### 6.3 Refresh rotation + reuse-detection (FR-1.5, US-08, AC-4)

Refresh tokens are **opaque, rotating, revocable**, grouped by a `family_id`. Each use mints a new refresh token and consumes the old (single-shot: `UPDATE … WHERE status='ACTIVE' RETURNING …`). **Presenting an already-consumed refresh token revokes the entire family** and forces re-auth — a stolen token is contained.

### 6.4 RFC 8693 token-exchange (#6, FR-5.3, US-52, AC-31, M7)

Forwarding a user identity downstream is **an explicit exchange, never a silent pass-through**. `TokenIssuer.exchange`:

- Takes a `subject_token` (e.g. a shopper token) + `target_audience` (`yaya-sales`).
- Mints a **new** access token with `aud=yaya-sales`, `sub`=the original shopper — **audience rebound, subject preserved**.
- There is **no code path that forwards a token without going through `exchange`.** `ServiceTokenAndExchangeIT` + the M7 trace assertion prove a shopper token never reaches yaya-sales without the exchange step (AC-31).

### 6.5 Audience binding enforcement (FR-5.2, US-53, AC-30)

Tokens are audience-bound. **Enforcement is at the RP** (the PEP), not here: an RP rejects (401) any token whose `aud` does not include it. The client SDK (§7.2) does this in `verify()`; the design note for yaya-sales is that a client-credentials `SERVICE_TOKEN` minted for `aud:yaya-sales` is accepted there and a token for any other `aud` is rejected.

---

## 7. Integration engineering (design §10, §5) — the zero-code trust seam

### 7.1 yaya-agentic trusts it with **zero new agent code** (M1, FR-1.10, NFR-Compat-1, US-06, AC-2)

yaya-agentic already resolves per-tenant OIDC trust from `tenant_oidc_issuers` and verifies RS256/ES256 via `OidcAuthenticator` (agent §5.4). The **entire** integration is **one row**:

```sql
-- run against the yaya-agentic DB (agent side), NOT the yaya_identity schema
INSERT INTO tenant_oidc_issuers (tenant_id, issuer_uri, jwks_uri, audiences, enabled)
VALUES ('<agent-tenant>',
        'https://identity.yaya.example.com',
        'https://identity.yaya.example.com/oauth2/jwks',
        '{yaya-agentic}',
        true);
```

The agent then accepts yaya-identity's tokens: discovery + JWKS URLs are conformant (SAS), `TenantOidcIssuersChangedEvent` evicts the agent's per-tenant processor cache, and the principal resolves. **No new SPI, no agent code change.** `AgentTrustZeroCodeIT` proves it: register issuer → mint token → call agent `/v1/sessions` → 200 with resolved principal; a tampered / `aud`-mismatched token → 401 (AC-2). An opt-in agent-side `PolicyDecisionAuthorizer` (an `Authorizer` §5.5 impl delegating to the PDP) is a *possible* future integration, **not required** for M1.

### 7.2 The thin Java client SDK (Q2, US-70, `integration`) — shipped at I4

Optional convenience (NFR-Compat-4); apps may speak raw OIDC/HTTP. The SDK wraps the two seams:

| Helper | Behavior | Backs |
|---|---|---|
| `verify(String jwt) → VerifiedToken` | JWKS-cached RS256 verify + `aud` binding check; **p99 ≤ 5ms in-process** (M2, NFR-Perf-1). Cache honors JWKS TTL; survives a brief IdP outage (NFR-Avail-1). | FR-5.2, US-05 |
| `decide(req) → Decision` | `POST /pdp/decide` with a **short-TTL decision cache (≤ 30s)** invalidated by `policy.changed`/`role.assigned` events (§5.3). | FR-4.1, US-72 |
| `decideBatch(reqs)` | `POST /pdp/decide-batch` — one round-trip. | US-46 |
| `exchange(subjectToken, aud)` | RFC 8693 helper — the *only* sanctioned way to forward a user downstream (#6). | US-52 |

### 7.3 Outbox events (§5.5, US-71, `integration` via `platform.OutboxRelay`)

Published transactionally (write to `outbox` in the same tx as the state change; `OutboxRelay.publish()` drains):

| Event | Emitted when | Consumers use it to |
|---|---|---|
| `user.created` | a user row is inserted | seed a local profile shell |
| `membership.added` | a user joins an org | refresh org rosters |
| `role.assigned` | a `RoleAssignment` is granted/revoked | **invalidate the decision cache** (§5.3) |
| `policy.changed` | a policy is created/edited/deactivated | **invalidate the decision cache** |
| `client.secret_rotated` | a client secret rotates | pick up the new secret in the overlap window |

Events are observable and replayable for consumer cache invalidation (NFR-Obs-3).

### 7.4 yaya-sales seams (design §10.2)

| Seam | How yaya-identity provides it |
|---|---|
| **Operators** | Auth-code + PKCE login; yaya-sales admin API is a PEP — coarse checks from token `roles`, fine-grained (refund caps, stock adjust) via `POST /pdp/decide`. |
| **`SERVICE_TOKEN`** | A `SERVICE` client → client-credentials token with `aud:yaya-sales`. This *is* the `SERVICE_TOKEN` the yaya-sales spec expects, now concretely issued (US-51, AC-30). |
| **Shopper principal** | A shopper token **RFC 8693 token-exchanged** to `aud:yaya-sales` before yaya-sales acts; attributed by `sub`. Never silently forwarded (#6, US-52, AC-31). |
| **Delivery courier** | A yaya-identity **user** in the store's org with a `courier` role scoped to `Scope.Resource("store", id)`. Often device-code login (US-07). **Identity owns the role + scope; yaya-sales owns the KYC-approval domain state** (Q5, PRD §6.4). Two different concerns — do not conflate. |

---

## 8. Data layer

### 8.1 Schema & migrations (FR-7.2, NFR-Dep-3, Q7)

- **Own `yaya_identity` Postgres schema.** All tables live there. Full shapes in design §14 and [Backend-Data](Backend-Data.md); tables: `organizations`, `users`, `memberships`, `credentials`, `groups`, `group_members`, `permissions`, `roles`, `role_permissions`, `role_assignments`, `policies`, `clients`, `app_permissions`, `signing_keys`, `refresh_tokens`, `sessions`, `audit_log`, `idempotency_keys`, `outbox`.
- **Flyway**, versioned `V1__…`, `search_path=yaya_identity`, established from I0.
- **Zero cross-schema FKs/joins.** A hard guarantee so the schema lifts to its own instance without migration (FR-7.2, M6).

### 8.2 The cross-schema-isolation build lint (M6, FR-7.2, AC-41)

Mirrors the yaya-sales **S0** guard and the agent's repo-scan discipline. `SchemaIsolationLintTest` (build-failing):

1. Parse every Flyway migration under `db/migration/`.
2. Fail on any reference to a schema other than `yaya_identity` (a qualified `other_schema.table`, a `REFERENCES other_schema.…`, a cross-schema `JOIN`, or a bare unqualified name that resolves outside our schema).
3. Emit a precise message naming the offending file + line. Any migration that reaches across a schema boundary reddens the build. This is the migration-level enforcement of "integration is token + API only; yaya-identity never reads an app's tables" (PRD §6.3).

### 8.3 Idempotency (FR-7.3, US-*mutating*)

`idempotency_keys(scope, key, response_json, created_at)`, PK `(scope, key)`. Mutating admin/token endpoints accept `Idempotency-Key`; a repeated key returns the stored response via `IdempotencyStore.find` before re-executing. `scope` namespaces per endpoint so keys don't collide across surfaces.

### 8.4 Outbox (§7.3)

`outbox(id, aggregate, event_type, payload_json, status, created_at, published_at)`. Written **in the same transaction** as the state change (transactional outbox), drained by `OutboxRelay.publish()`. `status` gates at-least-once delivery; consumers dedupe on `id`.

### 8.5 Append-only audit (`audit_log`, FR-6.1, M4, AC-40)

`audit_log(id, org_id, actor, event_type, subject, action, resource, decision, reasons_json, at)`.

- **Immutability approach:** the app layer only ever `INSERT`s — no `UPDATE`/`DELETE` code path exists against `audit_log`. Backed by a DB guard: a Postgres rule/trigger that **raises on `UPDATE`/`DELETE`**, and the app DB role is granted `INSERT, SELECT` only on the table. Tamper-evident, exportable (US-66).
- Written on **every** auth event (login, MFA, token issue/refresh/revoke, logout) and **every** PDP decision (`Permit`/`Deny` + both reasons). Completeness is test-enforced (`AuditCompletenessIT`, M4).
- **PII/audit split (FR-6.5, US-65, AC-44):** audit rows carry non-PII **UUIDs** (subject/actor ids), never raw emails/phones — so a PII scrub of `users` leaves the audit trail intact.

---

## 9. Security engineering

| Control | Implementation | Req |
|---|---|---|
| **Password hashing** | Argon2id via Spring Security `Argon2PasswordEncoder`. Params tuned to NFR-Perf-3 (p50 ≤ 100ms / p99 ≤ 500ms on a 4-core box): start `memory=19456 KiB (19 MiB), iterations=2, parallelism=1`, calibrate at boot to hit the budget. | NFR-Sec-1, US-02 |
| **Signing** | RS256, 2048-bit; keys in `signing_keys`, private key `private_ref` is an AES-256-GCM ciphertext. | NFR-Sec-1, FR-6.3 |
| **Key rotation runbook** | Operator triggers `KeyRing.rotate()` (§6.2) → new `ACTIVE`, old `RETIRING` in JWKS ≥ max token TTL → drop retired. Runbook: rotate on a schedule + on suspected compromise; never drop a key younger than the max access-token TTL. Age surfaced on the ops dashboard (NFR-Obs-2). | US-62, NFR-Avail-3 |
| **Secret cipher** | `SecretCipher` — AES-256-GCM **envelope** encryption, master key from `yaya.identity.secrets.encryption-key` (env/secret, never in config files). Directly mirrors the agent's `JwtSecretCipher`. Envelope-encrypts private signing keys + (where reversible is needed) secrets; **client secrets are stored *hashed*, not encrypted** (never reversible, FR-5.4). | NFR-Sec-1, FR-6.3 |
| **Rate-limit + lockout** | Redis **sliding window**, **per-IP + per-identity** axes (prevents IP-rotation bypass) on auth endpoints; N consecutive failures **lock** the account + may notify; 429 carries `Retry-After`. Redis outage → **fail-open with a loud audit warn** (NFR-Avail-2), not a hard auth failure. | FR-6.2, US-63, AC-42 |
| **Two-reason denial lint** | `DenyReasonLintTest` — build-failing scan of every `Decision.Deny` / AuthN-rejection construction; fails on `userSafeReason == auditReason` (§5.4). | M5, #3, AC-26 |
| **MFA enforcement wiring** | Per-org MFA policy in `credentials`. When a policy requires MFA for a role the subject holds, the SAS token flow inserts a TOTP challenge (`VerificationResult.MfaRequired`) **before a token carrying that role's permissions is issued** (FR-1.7). `owner`/`admin` require enrolled TOTP to act (US-64). | US-04, AC-5 |
| **Enumeration safety** | Login/reset return **uniform** responses regardless of account existence; wrong password → generic error, lockout-counted; reset confirm **invalidates all active sessions** in the same tx (FR-1.9). | NFR-Sec-6, US-26 |
| **PII deletion mechanics** | A retention/deletion path scrubs `users` PII columns (email/phone/profile → null/pseudonym) while `audit_log` retains the non-PII UUID (§8.5). Aligns agent §5.9 recorder redact/delete and yaya-sales §15. | FR-6.5, US-65, AC-44 |
| **Admin API is PDP-gated** | The `admin` module calls the PDP for its own management surface — yaya-identity eats its own dog food; an operator lacking `identity.<resource>:<action>` is denied managing the model. | FR-6.4, US-*60*, AC-43 |
| **Least-privilege service accounts** | `SERVICE` clients get scoped roles + narrow `aud`, never a broad grant. | FR-6.6 |

---

## 10. Testing strategy

### 10.1 Integration suite — `src/test/java/.../identity_it/` (PRD §7.6)

Spring + Testcontainers (Postgres 16 + Redis). One-to-one with the PRD's named suite:

| Class | Covers | ACs |
|---|---|---|
| **`AgentTrustZeroCodeIT`** | register `tenant_oidc_issuers` row → mint token → call agent → principal resolves; tampered/`aud`-mismatch → 401. **No agent code change.** | AC-2, M1 |
| **`ScopedRbacIT`** | multi-store scoped assignments; group inheritance; `(role, scope)` token claims; `store:A` grants nothing on `store:B`. | AC-11, AC-12, AC-13 |
| **`PdpDecisionIT`** | permit; **deny-override** (refund cap beats RBAC permit, two different reasons); **default-deny**; ownership (`resource.assignedAgent == subject.id`); `simulate` persists nothing; `decide-batch` == N `decide`s. | AC-20…AC-26 |
| **`ServiceTokenAndExchangeIT`** | client-credentials `aud` binding + RFC 8693 exchange (aud rebind, sub preserved) + replay rejection; **trace proves no silent forward**. | AC-30, AC-31, M7, #6 |
| **`KeyRotationIT`** | make-before-break JWKS; token signed pre-rotation verifies post-rotation. | AC-3, NFR-Avail-3 |
| **`AuditCompletenessIT`** | every auth event + every PDP decision → an append-only `audit_log` row; `UPDATE`/`DELETE` on the table raises. | AC-40, M4 |
| **`SchemaIsolationLintTest`** | migration lint fails on any cross-schema reference (§8.2). | AC-41, M6 |

### 10.2 Unit-test targets

| Target | Coverage |
|---|---|
| `ConditionEvaluator` (JSON-logic) | subject/resource/env bags; boundary conditions (amount caps, ownership, time windows); **sandbox** — reject oversized/malformed conditions at save; no I/O reachable; termination bound. |
| `DenyReasonLintTest` | build-failing scan of every `Decision.Deny` construction site — `userSafeReason != auditReason`. |
| Scope resolution (`roles`) | `Scope.Org` vs `Scope.Resource` coverage; union across direct + group assignments; revoke reflected immediately at PDP, next-token for claims. |
| `KeyRing` | rotate; retiring key stays in JWKS ≥ TTL then drops; `active()` always the newest. |
| Refresh rotation | single-use consume; replay → family revoke. |
| `TokenIssuer` claim customizer | claim set shape; `aud` binding; ID token only on OIDC login. |
| `IdempotencyStore` | repeated key returns stored response; distinct scopes don't collide. |

### 10.3 Modulith boundary test

`ModularityTest` runs `ApplicationModules.verify()` — fails the build on any illegal cross-module dependency (§2). `ApplicationModules…writeDocumentation()` emits the module diagram for the design doc.

### 10.4 Flutter (admin console) — see UI-UX

Widget tests per screen (orgs/users/groups/roles, policy editor, **policy simulator**, clients); role-aware gating (an operator without `identity.*` can't mutate the model); a11y (NFR-A11y-1…4: labels, `Semantics`-announced denial reasons, non-color-only signals, full keyboard nav).

---

## 11. Milestone → engineering breakdown (design §13, PRD §8)

Each row leaves a **working, independently deployable service**. Build order top-to-bottom.

| Milestone | Build (concrete) | Technical exit criteria |
|---|---|---|
| **I0 — Scaffold + platform** (R0) | `apps/yaya-identity` app, `pom.xml`, CI target, container. `platform` module: typed IDs, `KeyRing` + `signing_keys`, `SecretCipher` (AES-256-GCM), `OutboxRelay` + `outbox`, `IdempotencyStore`, Redis rate-limit primitive, `audit_log` (append-only + DB guard). Flyway on `yaya_identity` schema. Modulith wired. | Boots on own schema; `/oauth2/jwks` serves; **`SchemaIsolationLintTest` green**; `ModularityTest` green. |
| **I1 — Orgs + users + AuthN + issuer** (R1) | `organizations` (orgs/memberships/invitations), `users`, `credentials` (password Argon2id + phone-OTP), `tokens` on SAS: auth-code+PKCE + client-credentials + refresh (rotating), JWKS, discovery, userinfo, `sessions`. Claim customizer (§6.1). | **AC-1** (RS256 access+refresh+ID token, `iss/sub/org/roles/aud`); **AC-2** (agent trusts via `tenant_oidc_issuers`, zero code); **AC-10** (invite→accept→`ACTIVE`, one tx); `AgentTrustZeroCodeIT` green. |
| **I2 — RBAC** (R2) | `roles`: permission catalog + `app_permissions` manifest, roles (system + custom), **scoped `role_assignments`**, `groups` + group assignments; embed `(role, scope)` in the token; `role.assigned`/`membership.added`/`user.created` outbox events; `GET /pdp/permissions`. | **AC-11, AC-12, AC-13**; coarse PEP checks work in yaya-sales admin; `ScopedRbacIT` green. |
| **I3 — ABAC + PDP** (R3) | `policies`: policy store, `ConditionEvaluator` (JSON-logic sandbox), `PolicyDecisionPoint` — `decide`/`decide-batch`/`simulate`/`permissions`; deny-overrides + default-deny; **two-reason denials** + `DenyReasonLintTest`; decision audit; `policy.changed` event. | **AC-20…AC-26**; `PdpDecisionIT` + `DenyReasonLintTest` green; yaya-sales gates a refund/COD-collect via the PDP. |
| **I4 — Clients + token-exchange** (R4) | `clients` (registration, secret hash + rotation overlap window, audiences, grants); service accounts; `aud` binding; **RFC 8693 exchange**; device-code; `introspect`/`revoke`/back-channel logout; **Java client SDK** (verify + PDP-cache + exchange). | **AC-30, AC-31, AC-32**; `ServiceTokenAndExchangeIT` green; yaya-agentic→yaya-sales `SERVICE_TOKEN` + shopper-exchange end-to-end (M7). |
| **I5 — Console + MFA + hardening** (R5) | Flutter admin + **policy simulator**; TOTP MFA + per-org MFA policy enforcement; Redis rate-limit/lockout; key-rotation runbook; PII deletion path; admin API PDP-gated. | **AC-40…AC-44**; `AuditCompletenessIT` + `KeyRotationIT` green; operator manages full model from console; privileged roles require MFA. |
| **I6 — Federation (vNext)** (R6) | OIDC-in federation, WebAuthn/passkeys behind `CredentialProvider`, per-org SSO, SCIM. | US-80…US-82. |

**Rollout keystone:** R1. Once the agent trusts yaya-identity via `tenant_oidc_issuers`, every later milestone adds authority **without touching the agent** (PRD §8 rollout notes).

---

## 12. Open technical decisions (resolved)

Restated from PRD §9 / design §16 with the engineering rationale, so the implementing engineer does **not** re-litigate:

| # | Decision | Engineering rationale |
|---|---|---|
| 1 | **operator_auth coexists** in v1 | The agent keeps its operator accounts and *additionally* trusts yaya-identity as an issuer (`tenant_oidc_issuers`). No forced migration; no shared table. Federate later (NFR-Compat-3). |
| 2 | **Standalone service + thin Java client SDK** | Service is the product; SDK (I4) is optional convenience (verify + PDP-cache + exchange). Other-language SDKs later. Apps may speak raw OIDC/HTTP (NFR-Compat-4). |
| 3 | **Decision cache: client-side, TTL ≤ 30s, event-invalidated** | Server PDP stays authoritative + uncached (simple, auditable). Latency amortized at the RP; `policy.changed`/`role.assigned` bound staleness (§5.3, NFR-Perf-5). |
| 4 | **JSON-logic (not CEL) for conditions** | Simple, safe, portable, no runtime to sandbox; no loops → structural termination. CEL only if policies outgrow it — the `Condition` record isolates the choice, so a swap is contained (§5.2). |
| 5 | **Identity owns role + scope; app owns domain state** | `courier` is a yaya-identity role scoped to `store:{id}`; **KYC-approval stays a yaya-sales domain state.** Never conflate an identity role with domain approval (PRD §6.4, §7.4). |
| 6 | **Flat orgs, `parent_id` reserved** | `Organization.parent` is nullable and present in the schema; v1 semantics are flat. Non-breaking nesting later. |
| 7 | **Own `yaya_identity` schema, zero cross-schema coupling** | Shared Postgres instance, own schema, no cross-schema FKs/joins; lifts to a separate instance without migration. Enforced from I0 by `SchemaIsolationLintTest` (§8.2, M6). |

---

## 13. Code-style conventions to honor (CLAUDE.md)

- **Records over classes** for value types — every domain type and DTO in §3–§4 is a record.
- **Sealed types** for unions — `Decision`, `Scope`, `AssignmentSubject`, `VerificationResult`. Adding a variant means re-reading every `switch`.
- **Typed IDs** across every module/SPI boundary — `OrgId`/`UserId`/`RoleId`/`PolicyId`/`ClientId`/`AssignmentId`/`GroupId`; never a bare `String`/`UUID`.
- **No business logic in `package-info.java`** — each module's `package-info` carries only `@ApplicationModule` + a one-line anchor back to a design-doc section.
- **Comment the WHY, not the WHAT** — a hidden constraint, a denial-by-design (two-reason denial, deny-overrides, no-silent-forward).
- **Two-reason denials everywhere** — `userSafeReason ≠ auditReason` at every `Decision.Deny` and AuthN rejection; enforced by `DenyReasonLintTest`.
- **Apps are PEPs** — the PDP is the sole decision point; no policy evaluation leaks into a relying party (the #1 mirror).
- **No silent token forwarding** — the only downstream-forward path is `TokenIssuer.exchange` (#6).

---

## 14. References

- [PRD](PRD.md) — product intent; US-xx / FR-xx / AC-xx / NFR-xx / M1–M7 this TRD traces to.
- [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) — master design (domain §5, PDP §7, module map §8, API §9, data model §14, SPI appendix §15, milestones §13).
- Companion specs: [UI-UX](UI-UX.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).
- `apps/yaya-sales/docs/design/yaya-sales-design.md` — first relying party (service token, shopper exchange, courier identity; the S0 schema-isolation guard this TRD mirrors).
- `docs/design/yaya-agentic-design.md` — §5.4 AuthN (`OidcAuthenticator`, `tenant_oidc_issuers`), §5.5 AuthZ (two-reason `AuthzDecision`), §5.6 profile/identity resolution, §12 trust boundaries.
- `CLAUDE.md` — repo conventions; non-negotiables #1 (LLM never trusted → PDP-is-sole-gate mirror), #3 (two-reason denials), #6 (no silent token forwarding).
- `docs/specs/user-accounts/TRD.md` — the house-style TRD this doc mirrors.
