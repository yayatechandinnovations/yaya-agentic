# Yaya Identity — Design Document

**Status:** Proposed (v1 target — the shared identity & access provider for the app ecosystem)
**Owner:** (you)
**Audience:** Engineers building yaya-identity; engineers integrating any app (yaya-sales first) with it.
**Location:** `apps/yaya-identity/` — a **shippable integration application** (see `../../README.md`). It is a *platform service* other apps depend on, not a leaf domain app — but it obeys the same `apps/` rules: separately deployable, does not import yaya-agentic internals, integrates only through published seams (here, OIDC/OAuth2 + a policy-decision API).
**Anchors (yaya-agentic):** `docs/design/yaya-agentic-design.md` §5.4 (Authentication), §5.5 (Authorization — the `AuthzDecision` Allow/Deny two-reason pattern we mirror), §5.6 (profile/identity resolution), §12 (trust boundaries). CLAUDE.md non-negotiables **#1** (LLM never trusted), **#3** (denials carry two reasons — user-safe vs audit), **#6** (tokens never silently forwarded). yaya-agentic's existing `OidcAuthenticator` (DB-first per-tenant trust via `tenant_oidc_issuers`) and `SharedSecretJwtAuthenticator` are the machinery yaya-identity plugs into as a trusted issuer.
**Relates to:** `apps/yaya-sales/docs/design/yaya-sales-design.md` — yaya-sales is the first relying party (operators, service tokens, shopper principals, delivery-agent identities). Standing constraint: **self-hosted, no paid services** — yaya-identity is our own Keycloak/Auth0/Ory, built on Postgres, not a hosted IdP.
**Product specifications:** this design is operationalized into an implementation-ready spec bundle under [`../specs/`](../specs/) — [PRD](../specs/PRD.md) (personas, user stories, functional/non-functional requirements, acceptance criteria) · [TRD](../specs/TRD.md) · [UI-UX](../specs/UI-UX.md) · [App-Flow](../specs/App-Flow.md) · [Backend-Data](../specs/Backend-Data.md).

---

## 1. Summary

**yaya-identity** is the multi-tenant **identity provider (IdP) and authorization service** shared by every app we ship. It answers two questions authoritatively, so no app has to re-implement them:

- **AuthN — *who is this?*** Organizations, users, groups, credentials (password, phone OTP, MFA, later passkeys/federation), and standards-based token issuance (OAuth 2.0 + OpenID Connect). yaya-identity is the **issuer**; apps are **relying parties** that verify its tokens.
- **AuthZ — *may they do this?*** A combined **RBAC + ABAC** model — roles/permissions assigned within a **scope** (org-wide or a specific resource like one store), plus **policies** (conditional rules) evaluated by a **Policy Decision Point (PDP)**. The PDP returns `Permit` or `Deny(userSafeReason, auditReason)` — the same two-reason denial the agent framework mandates (non-negotiable #3).

It ships under `apps/` but is **not part of yaya-agentic**. The agent framework already has pluggable `Authenticator`/`Authorizer` SPIs for *its* runtime; yaya-identity is the **provider** those and the apps trust. Concretely: yaya-identity issues RS256 JWTs and publishes a JWKS; yaya-agentic trusts it by registering it as an OIDC issuer in the existing `tenant_oidc_issuers` table — **so the agent trusts yaya-identity-issued tokens with zero new agent code**. Apps verify tokens against the same JWKS and call the PDP for fine-grained decisions.

The division of authority mirrors the agent's "the LLM proposes, the Authorizer disposes": **an app proposes an action; yaya-identity's PDP disposes.** Identity is asserted in one place, trusted everywhere, and every decision is auditable.

---

## 2. Goals & Non-Goals

### 2.1 Goals (v1)

- **G1 — One identity, trusted across apps.** Orgs, users, groups. A user authenticates once against yaya-identity and every app trusts the resulting token (audience-scoped). No per-app user tables.
- **G2 — Standards-based AuthN.** OAuth 2.0 (authorization-code + PKCE, client-credentials, device-code, refresh, token-exchange) and OpenID Connect (discovery, JWKS, userinfo). Interop with anything that speaks OIDC — including yaya-agentic's existing `OidcAuthenticator`.
- **G3 — RBAC + ABAC with scoped assignments.** Roles → permissions, assigned to users/groups **within a scope** (org or a specific resource id) so "manager of store A but not store B" is native. Policies add conditional rules on top.
- **G4 — A Policy Decision Point apps call.** `decide(subject, action, resource, context) → Permit | Deny(two reasons)`. Deny-by-default, deny-overrides. Coarse checks can also be done from token claims; fine-grained/ABAC goes through the PDP.
- **G5 — Machine identity too.** Service accounts + client credentials for service-to-service (the `SERVICE_TOKEN` yaya-sales expects), and API keys — first-class, not bolted on.
- **G6 — Auditable and self-hosted.** Every auth event and authorization decision lands in an append-only audit log. Built on Postgres; no paid IdP dependency (self-hosted constraint).
- **G7 — Deployable independently.** Own DB, migrations, container, scaling. Runs standalone; apps integrate over OIDC + PDP.

### 2.2 Non-Goals (v1)

- **Not a replacement for yaya-agentic's `operator_auth`** in v1. yaya-agentic keeps its operator accounts/workspaces/API keys; it *may* additionally trust yaya-identity as an issuer. Federating/subsuming operator_auth is a later decision (§16 Q1).
- **No SAML / enterprise SSO / SCIM provisioning in v1** — OIDC federation and SCIM are vNext. v1 is native accounts + OIDC-out.
- **No passkeys/WebAuthn in v1** — password + phone OTP + TOTP MFA in v1; WebAuthn vNext behind the credential SPI.
- **No full XACML.** The policy language is a small, safe condition expression (JSON-logic / CEL-style), not a general policy VM.
- **No consent-screen marketing / user self-service portal beyond the essentials** (login, MFA enrolment, profile) in v1.
- **No shared database with any app.** Integration is token + API only; yaya-identity never reads an app's tables and vice versa.
- **Not a secrets manager / PKI.** It manages *identity* credentials and its own signing keys, not arbitrary app secrets.

---

## 3. Glossary

| Term | Definition |
|---|---|
| **Organization (org)** | The tenant boundary in identity — one customer/company. Owns users, groups, roles, policies. Maps 1:1 to a yaya-agentic **tenant** and to a yaya-sales **store owner** (an org may own many stores). |
| **User** | A human or service identity belonging to one or more orgs. Has credentials, status, profile. |
| **Service account** | A non-human identity for service-to-service calls; authenticates via client-credentials, yields a `SERVICE_TOKEN`. |
| **Group** | A named collection of users within an org (e.g. "warehouse-staff"), used to assign roles in bulk. |
| **Permission** | An atomic right, named `<app>.<resource>:<action>` (e.g. `sales.order:refund`, `identity.user:invite`). |
| **Role** | A named bundle of permissions. System roles (`owner`, `admin`, `member`) + custom, org-scoped. |
| **Role assignment** | Grants a role to a user or group **within a scope** — `org:{id}` (all resources) or a specific resource (`store:{id}`). The scope is what makes multi-store / per-resource access native. |
| **Policy** | A conditional AuthZ rule (`effect PERMIT\|DENY`, target actions/resources, `condition` over attributes). Layered on RBAC; **deny overrides**. |
| **PDP (Policy Decision Point)** | The engine that answers `decide(subject, action, resource, context)`. Returns `Permit` or `Deny(userSafeReason, auditReason)`. |
| **PEP (Policy Enforcement Point)** | The app-side call site that asks the PDP and enforces the answer. Apps never decide policy themselves (mirrors the agent's Authorizer-is-the-only-gate rule). |
| **Client / relying party** | A registered app (yaya-sales, yaya-agentic, the admin console) with client credentials, allowed grants, redirect URIs, and **audiences**. |
| **Subject** | The authenticated principal in a token — `sub` + org + groups + scoped roles + claims. The identity analog of yaya-agentic's `Principal`. |
| **Audience (`aud`)** | The app a token is *for*. A token minted for yaya-sales can't be replayed at another app. |
| **Token exchange** | RFC 8693 — swap a token for one scoped to a downstream audience (how a shopper token is forwarded to yaya-sales, explicitly — non-negotiable #6). |
| **JWKS** | The public keys apps use to verify RS256 tokens; served at a well-known endpoint, rotated. |

---

## 4. High-Level Architecture

```
   ┌───────────────────────────────────────────────────────────────────────────┐
   │  Relying parties (apps)                                                     │
   │  yaya-sales · yaya-agentic · admin consoles · future apps                   │
   │    login (OIDC) ─┐        verify JWT (JWKS) ─┐      ask PDP ─┐              │
   └──────────────────┼───────────────────────────┼──────────────┼──────────────┘
                      │ OAuth2/OIDC                │ /.well-known │ POST /pdp/decide
                      ▼                            ▼ jwks.json    ▼
   ┌───────────────────────────────────────────────────────────────────────────┐
   │  yaya-identity  (Spring Boot modular monolith — own DB / migrations)        │
   │                                                                             │
   │  AUTHN                              │  AUTHZ                                 │
   │  ┌───────────┐ ┌───────────────┐    │  ┌──────────────────────────────────┐ │
   │  │ users     │ │ credentials    │   │  │ roles (RBAC): permissions +       │ │
   │  │ orgs      │ │ password/OTP/  │   │  │   scoped assignments (org|store)  │ │
   │  │ groups    │ │ MFA/federation │   │  │ policies (ABAC): conditions       │ │
   │  └───────────┘ └───────────────┘    │  └───────────────┬──────────────────┘ │
   │  ┌───────────┐ ┌───────────────┐    │  ┌───────────────┴──────────────────┐ │
   │  │ sessions  │ │ clients /      │   │  │ PDP  decide(sub,act,res,ctx)      │ │
   │  │ devices   │ │ service accts  │   │  │   → Permit | Deny(user, audit)    │ │
   │  └───────────┘ └───────────────┘    │  └──────────────────────────────────┘ │
   │  ┌────────────────────────────────────────────────────────────────────────┐│
   │  │ tokens: OAuth2/OIDC issuer · JWKS · refresh rotation · token-exchange   ││
   │  │ platform: signing keys (rotated) · secret cipher · audit (append-only)  ││
   │  │           · outbox/events · idempotency · observability                 ││
   │  └────────────────────────────────────────────────────────────────────────┘│
   └───────────────────────────────────────────────────────────────────────────┘
                      │ registered as an OIDC issuer
                      ▼ (tenant_oidc_issuers)
   ┌───────────────────────────────────────────────────────────────────────────┐
   │  yaya-agentic already trusts OIDC issuers → trusts yaya-identity tokens      │
   │  with ZERO new agent code (existing OidcAuthenticator, design §5.4).         │
   └───────────────────────────────────────────────────────────────────────────┘
```

**Reading it:** yaya-identity holds identity + the authorization model; apps hold enforcement (PEPs). Two seams: **verify a token** (stateless, via JWKS — cheap, coarse) and **ask the PDP** (stateful, fine-grained/ABAC). The agent framework's existing issuer-trust machinery makes yaya-identity a drop-in issuer.

---

## 5. Domain model — orgs, users, groups, roles, policies

The five primitives the ask names, plus the machine-identity and client primitives a real IdP needs.

### 5.1 Organization

```java
public record Organization(OrgId id, String name, String slug, OrgStatus status,
                           OrgId parent,            // optional hierarchy (vNext depth); null = root
                           Map<String,Object> metadata, Instant createdAt) {}
public enum OrgStatus { ACTIVE, SUSPENDED }
```

The tenant unit. All users/groups/roles/policies are org-scoped. An org maps to a yaya-agentic tenant and a yaya-sales store-owner; **an org may own many stores** (consistent with yaya-sales `StoreId ≠ TenantId`). Membership is explicit (a user joins an org), so cross-org users (e.g. a contractor) are possible without duplicating the identity.

### 5.2 User & membership

```java
public record User(UserId id, String primaryEmail, String phone, String displayName,
                   UserKind kind, UserStatus status, Instant createdAt) {}
public enum UserKind   { PERSON, SERVICE }          // SERVICE = machine identity (service account)
public enum UserStatus { INVITED, ACTIVE, LOCKED, DISABLED }

public record Membership(UserId user, OrgId org, MembershipStatus status, Instant joinedAt) {}
```

- A user is a global identity; **membership** ties them to an org. Credentials attach to the user; roles attach within a `(user, org, scope)`.
- **Invitations** (org invites a user by email/phone → they set credentials) mirror the invite-gated self-registration used by yaya-sales (merchant PSP onboarding, courier registration). Redemption is public: `POST /signup/invitations/accept {token, displayName, password}` — the admin-issued token (hashed at rest, single-use, TTL'd) IS the authorization.
- **Self-registration** (signup that *creates* an org) is a separate, guarded flow — `signup/` package, OFF by default (`yaya.identity.signup.enabled`). `POST /signup/register {orgName, displayName, email, phone, password}` creates an INVITED user + a `pending_signups` row; the caller proves control of the phone via OTP (`/signup/verify`, `/signup/resend`) and only then — in one transaction, mirroring `OwnerBootstrap` — the user turns ACTIVE and becomes system `owner` of the newly created org. OTP delivery/check is a port (`OtpChannel`): **Twilio Verify** when `yaya.identity.signup.twilio.*` is configured (Twilio stores the code; nothing OTP-shaped in our DB), otherwise the local hashed-OTP channel (credentials `PHONE_OTP`, dev echo flag for delivery). Sends are rate-limited per phone and per IP (SMS pumping is billable fraud), resends have a cooldown, and pendings expire.
- `SERVICE` users are service accounts (§5.6).

### 5.3 Group

```java
public record Group(GroupId id, OrgId org, String name, Set<UserId> members) {}
```

A convenience aggregation within an org. Roles assigned to a group apply to all its members — the bulk-management lever (e.g. assign "courier" to the "downtown-fleet" group scoped to `store:{id}`).

### 5.4 Permission & Role (RBAC)

```java
public record Permission(String key) {}   // "<app>.<resource>:<action>", e.g. "sales.order:refund"
public record Role(RoleId id, OrgId org,  // null org = system role, available to all orgs
                   String name, boolean system, Set<Permission> permissions) {}
```

- Permission keys are namespaced by app so yaya-sales, yaya-identity, and future apps never collide. Apps **declare their permission catalog** to yaya-identity (a manifest, §9.4) so the admin console can offer them.
- System roles (`owner`, `admin`, `member`) ship built-in; orgs compose custom roles from any declared permissions.

### 5.5 Role assignment with scope — the key to per-resource access

```java
public record RoleAssignment(AssignmentId id, Principal subject,   // a user or a group
                             RoleId role, Scope scope, Instant grantedAt) {}
public sealed interface Scope permits Scope.Org, Scope.Resource {
    record Org(OrgId org) implements Scope {}                       // all resources in the org
    record Resource(String type, String id) implements Scope {}     // e.g. ("store","st_123")
}
```

A user's **effective permissions for a resource** = union of permissions from all role assignments whose scope covers that resource (direct + via groups). This is how "store manager of A, viewer of B" or "courier for store X only" is expressed — and it lines up exactly with yaya-sales' `store_id`-scoped rows.

### 5.6 Clients & service accounts (machine identity)

```java
public record Client(ClientId id, OrgId org, String name, ClientType type,
                     Set<GrantType> grants, Set<String> redirectUris, Set<String> audiences,
                     Set<Scope> allowedScopes) {}
public enum ClientType { PUBLIC /*PKCE, no secret*/, CONFIDENTIAL /*has secret*/, SERVICE /*client-credentials*/ }
```

- A **relying-party app** (yaya-sales, the admin console) is a `Client`. A **service account** is a `SERVICE` client whose client-credentials grant yields a `SERVICE_TOKEN` with a machine subject and its own scoped roles.
- **Audiences** bind a token to the app(s) it's valid for; a yaya-sales token can't be replayed at another app.

### 5.7 Policy (ABAC) — conditional rules on top of RBAC

```java
public record Policy(PolicyId id, OrgId org, String name, Effect effect,
                     Target target,          // which actions/resource-types this applies to
                     Condition condition,    // safe expression over subject/resource/env attributes
                     int priority) {}
public enum Effect { PERMIT, DENY }
```

Policies express what static roles can't: *"DENY `sales.order:refund` if amount > subject.refundLimit"*, *"PERMIT `sales.delivery:collect-cod` only if resource.assignedAgent == subject.id"*, *"DENY any write outside business hours for role X"*. Conditions are a small, sandboxed expression language (JSON-logic / CEL-style — no arbitrary code) over three attribute bags: **subject** (roles, groups, org, custom claims), **resource** (owner, store, region, amount…), **environment** (time, IP, request context the app passes). **Deny overrides** everything.

---

## 6. Authentication — flows & credentials

### 6.1 Standards

OAuth 2.0 grants: **authorization-code + PKCE** (human login for apps/SPAs), **client-credentials** (service-to-service), **device-code** (input-constrained, e.g. a courier on a shared device), **refresh-token** (rotating), **token-exchange** (RFC 8693, delegation/audience swap). OpenID Connect on top: discovery (`/.well-known/openid-configuration`), **JWKS**, `userinfo`, ID tokens. This is exactly what yaya-agentic's `OidcAuthenticator` already consumes.

### 6.2 Credentials (pluggable)

```java
public interface CredentialProvider {
    CredentialKind kind();                                  // PASSWORD | PHONE_OTP | TOTP | (WEBAUTHN, OIDC_FEDERATION vNext)
    VerificationResult verify(UserId user, CredentialInput input);
}
```

- **v1:** password (Argon2id), **phone OTP** (reuses the OTP machinery already in the ecosystem — chat account verification), **TOTP MFA**. Email magic-link optional.
- **vNext:** WebAuthn/passkeys, OIDC federation (log in with an external IdP), SAML, per-org SSO enforcement.
- MFA policy is per-org (e.g. require MFA for any user holding an `admin`/`owner` role).

### 6.3 Tokens

- **Access token:** short-lived JWT (RS256) with `iss` (yaya-identity), `sub`, `org`, `groups`, **scoped `roles`**, `scope`, `aud`. Coarse authZ can be read straight from claims; fine-grained goes to the PDP.
- **Refresh token:** opaque, rotating, revocable; reuse-detection invalidates the family.
- **ID token:** OIDC login only.
- **Signing keys** live in the platform module, rotated, previous keys kept in JWKS through the token TTL so verification never breaks mid-rotation.

---

## 7. Authorization — the PDP

The heart. Deny-by-default; combine RBAC (base permits) with policies (permits and, decisively, denies); **explicit deny always wins**.

```java
public interface PolicyDecisionPoint {
    Decision decide(DecisionRequest req);   // subject, action (permission key), resource, context(attrs)
}
public sealed interface Decision permits Decision.Permit, Decision.Deny {
    record Permit(String obligationsJson) implements Decision {}          // optional obligations (e.g. "log", "step-up MFA")
    record Deny(String userSafeReason, String auditReason) implements Decision {}  // #3: two distinct reasons
}
```

**Evaluation order** for `decide(subject, action, resource, ctx)`:
1. Resolve the subject's effective permissions for `resource` from **scoped role assignments** (§5.5). Absent the permission → base = deny.
2. Evaluate applicable **policies** (matching action/resource-type), highest priority first. A matching `DENY` short-circuits to `Deny`. A matching `PERMIT` can grant a right RBAC didn't.
3. No explicit permit → **default deny**.
4. Emit the decision to the **audit log** (append-only) with the full request + matched rules.

**Two enforcement styles**, apps choose per call:
- **Token-claim check (cheap, coarse):** the PEP inspects `roles`/`scope` in the JWT — good for "is this an admin of this store?" No round-trip.
- **PDP call (fine-grained, ABAC):** for anything conditional (amounts, ownership, time) or high-value (refunds, stock adjust, COD collect). The PDP is the only place policy is evaluated — apps never re-implement it (the Authorizer-is-the-sole-gate rule, agent §12).

**Two-reason denials (non-negotiable #3).** `userSafeReason` is what the app may show the end user ("You can't refund orders over $500"); `auditReason` is the internal truth ("policy `refund-cap` denied: amount 750 > limit 500 for role store-manager"). They MUST differ — same rule the agent framework enforces.

---

## 8. Module Map

Modular monolith (Spring Modulith), root package `com.yayatechandinnovations.yayaidentity`. Per-module deep designs can follow the yaya-sales `modules/` pattern later.

| # | Module | Owns |
|---|---|---|
| 1 | **organizations** | Orgs, membership, invitations, hierarchy |
| 2 | **users** | User identities, profiles, lifecycle, service accounts |
| 3 | **credentials** | `CredentialProvider`s (password/OTP/TOTP; federation vNext), MFA policy |
| 4 | **groups** | Group membership, bulk role targeting |
| 5 | **roles** ★ | Permissions catalog, roles, **scoped role assignments** (RBAC) |
| 6 | **policies** ★ | Policy store, condition engine, **PDP** (ABAC) |
| 7 | **clients** | Relying-party + service-account registration, secrets, audiences, grants |
| 8 | **tokens** | OAuth2/OIDC issuance, JWKS, refresh rotation, token-exchange, revocation |
| 9 | **sessions** | Login sessions, devices, logout/back-channel logout |
| 10 | **audit** | Append-only auth + decision log (immutable, exportable) |
| 11 | **admin** | Management API + console (orgs/users/groups/roles/policies/clients) |
| 12 | **integration** | OIDC discovery, PDP API, client SDK, outbound events |
| 13 | **platform** | IDs, signing keys (rotation), secret cipher, outbox, idempotency, rate-limit, observability |

★ = the modules carrying the authorization weight.

---

## 9. API Surface

Disjoint surfaces by audience. Mutating admin/token calls accept `Idempotency-Key`.

### 9.1 OAuth 2.0 / OIDC — `/oauth2/*`, `/.well-known/*` (public, standards)

```
GET   /.well-known/openid-configuration          discovery
GET   /oauth2/jwks                                public verification keys (JWKS)
GET   /oauth2/authorize                           auth-code + PKCE (human login)
POST  /oauth2/token                               code | client_credentials | refresh | urn:...:token-exchange | device_code
POST  /oauth2/device_authorization                device-code start
GET   /oauth2/userinfo                            OIDC userinfo
POST  /oauth2/revoke · /oauth2/introspect         revoke / introspect a token
POST  /oauth2/logout                              session / back-channel logout
```

### 9.2 PDP — `/pdp/*` (service token; the fine-grained AuthZ seam)

```
POST  /pdp/decide        { subject, action, resource, context }     → Permit | Deny(userSafeReason, auditReason)
POST  /pdp/decide-batch  [ … ]                                      → decisions[]   (one round-trip, many checks)
GET   /pdp/permissions?subject=&resource=                          effective permissions (for UI enable/disable)
```

### 9.3 Admin — `/v1/identity/admin/*` (operator-authenticated, itself PDP-gated)

```
# organizations & users
POST/GET/PUT  /orgs[/{id}]                         · POST /orgs/{id}/invitations
POST/GET/PUT  /orgs/{id}/users[/{uid}]             · POST …/users/{uid}/{lock|disable|reset-credential}
GET/POST      /orgs/{id}/groups[/{gid}]            · PUT …/groups/{gid}/members
# roles & policies
GET/POST/PUT  /orgs/{id}/roles[/{rid}]             (compose from declared permissions)
POST/DELETE   /orgs/{id}/role-assignments          { subject, role, scope }        (grant/revoke, scoped)
GET/POST/PUT  /orgs/{id}/policies[/{pid}]          (effect, target, condition, priority)
POST          /orgs/{id}/policies/{pid}/simulate   { subject, action, resource, ctx } → would-be decision (dry-run)
# clients & service accounts
GET/POST/PUT  /orgs/{id}/clients[/{cid}]           · POST …/clients/{cid}/rotate-secret
# audit
GET           /orgs/{id}/audit?from=&to=&subject=&action=
```

### 9.4 App-facing registration — `/v1/identity/apps/*` (service token)

```
POST  /v1/identity/apps/{app}/permissions          declare an app's permission catalog (manifest)
GET   /v1/identity/apps/{app}/permissions
```

---

## 10. Integration with the ecosystem

### 10.1 yaya-agentic — trust with zero new agent code

yaya-agentic already resolves per-tenant OIDC trust from `tenant_oidc_issuers` and verifies RS256/ES256 JWTs (`OidcAuthenticator`, design §5.4). **Register yaya-identity as an issuer per tenant and the agent trusts its tokens immediately** — no new SPI, no agent change. Optionally, an agent-side `PolicyDecisionAuthorizer` (an `Authorizer` §5.5 implementation) can delegate tool-call authZ to the PDP — but that's an opt-in agent integration, not required.

### 10.2 yaya-sales — the first relying party

- **Operators:** log in via yaya-identity (auth-code + PKCE); the yaya-sales admin API is a PEP — coarse checks from token roles, fine-grained (refund caps, adjust stock) via `POST /pdp/decide`.
- **Service token:** yaya-agentic→yaya-sales calls carry a client-credentials token minted for `aud: yaya-sales`; yaya-sales verifies via JWKS. This *is* the `SERVICE_TOKEN` in the yaya-sales spec, now concretely issued.
- **Shopper principal:** a shopper token issued by yaya-identity, **explicitly token-exchanged** to the yaya-sales audience by yaya-agentic before forwarding (non-negotiable #6 — never silently forwarded). yaya-sales attributes the customer by `sub`.
- **Delivery agents:** a courier is a yaya-identity **user** in the store's org with a `courier` role scoped to `store:{id}` (§5.5). Login/token = yaya-identity; the **KYC-approved** gate stays a yaya-sales domain state (§8.9 there). Clean split: *identity says who you are; the app says whether your domain profile is approved to act.*

### 10.3 Seams (published)

1. **OIDC issuer + JWKS** — apps verify tokens statelessly.
2. **PDP decision API** — apps get fine-grained answers.
3. **Admin API** — manage the model.
4. **Thin client SDK** (Java first) — token verification (cached JWKS), PDP calls (with a short-TTL decision cache), and helpers; optional, apps can speak raw OIDC/HTTP.
5. **Events (outbox)** — `user.created`, `membership.added`, `role.assigned`, `policy.changed`, `client.secret_rotated` → apps react (e.g. invalidate a cache).

---

## 11. Security & Trust Boundaries

yaya-identity is the **root of trust** for the app ecosystem — its compromise is total, so the bar is the highest in the repo.

- **Deny-by-default, deny-overrides, PDP is the only decision point.** Apps enforce (PEP) but never decide (mirrors the agent's "tools never check policy themselves", §12).
- **Two-reason denials (#3).** `userSafeReason` ≠ `auditReason`, always.
- **Tokens are audience-bound and short-lived; forwarding is explicit token-exchange (#6),** never a silent pass-through. Refresh tokens rotate with reuse-detection.
- **Signing keys rotate; secrets encrypted at rest** (AES-256-GCM envelope, like yaya-agentic's `JwtSecretCipher`). Client secrets stored hashed.
- **Append-only audit** of every authentication and authorization decision — the identity analog of yaya-sales' stock/payment ledgers; tamper-evident, exportable.
- **Brute-force & abuse controls:** rate-limit auth endpoints, lockout, step-up MFA as a PDP obligation.
- **MFA for privileged roles;** admin API is itself PDP-gated (identity eats its own dog food).
- **PII** (emails, phones, profiles) has a retention/deletion path (aligns with yaya-agentic recorder redact/delete, §5.9, and yaya-sales §15).
- **Least privilege for service accounts** — scoped roles + audiences, never broad.

---

## 12. Tech & Deployment

- **Backend:** Java 21, Spring Boot 3.5.x, Spring Authorization Server (OAuth2/OIDC), Spring Modulith. Postgres 16 (own `yaya_identity` schema; **no cross-schema FKs/joins to other apps** so it lifts to its own instance — same isolation guarantee as yaya-sales S0).
- **Crypto:** Argon2id (passwords), RS256 (tokens) with rotated keys, AES-256-GCM (secret envelope).
- **Admin console:** Flutter web (same stack as the other consoles) — orgs/users/groups/roles/policies/clients + a **policy simulator**.
- **Self-hosted:** replaces Auth0/Okta/Keycloak-as-a-service; no paid identity dependency (standing constraint).
- Own `pom.xml`, CI target, container.

---

## 13. Milestones

| # | Milestone | Scope | Exit |
|---|---|---|---|
| **I0** | Scaffold + platform | app + `pom.xml` + platform (IDs, signing keys, secret cipher, audit, outbox, own schema). | boots on own schema; JWKS served |
| **I1** | Orgs + users + password/OTP + OIDC issuer | orgs/membership/invitations, users, password + phone-OTP, auth-code+PKCE + client-credentials + refresh, JWKS, userinfo | a user logs in and gets a verifiable RS256 token; **yaya-agentic trusts it via `tenant_oidc_issuers`** |
| **I2** | RBAC | permissions catalog + app manifest, roles, **scoped role assignments**, groups, token role claims | operator token carries scoped roles; coarse PEP checks work in yaya-sales admin |
| **I3** | ABAC + PDP | policy store, condition engine, `POST /pdp/decide` + batch + simulate, two-reason denials, audit of decisions | yaya-sales gates a refund/COD-collect via the PDP; deny carries two reasons |
| **I4** | Clients/service accounts + token-exchange | client registration, service accounts, `aud` binding, RFC 8693 exchange, revocation/introspection | yaya-agentic→yaya-sales `SERVICE_TOKEN` + shopper token-exchange flow end-to-end |
| **I5** | Admin console + MFA + hardening | Flutter admin, TOTP MFA, rate-limit/lockout, key rotation runbook, PII deletion | an operator manages the full model from the console; privileged roles require MFA |
| **I6** | Federation (vNext seam) | OIDC-in federation, WebAuthn, per-org SSO, SCIM | log in with an external IdP; passkeys |

Each milestone leaves a working, independently deployable service.

---

## 14. Data Model (highlights)

```sql
organizations(id, name, slug, status, parent_id, metadata_json, created_at)
users(id, primary_email, phone, display_name, kind /*PERSON|SERVICE*/, status, created_at)
memberships(user_id, org_id, status, joined_at, PRIMARY KEY(user_id, org_id))
credentials(id, user_id, kind /*PASSWORD|PHONE_OTP|TOTP|…*/, secret_ref /*hashed/encrypted*/, created_at)
groups(id, org_id, name)
group_members(group_id, user_id, PRIMARY KEY(group_id, user_id))

permissions(key)                                                   -- "<app>.<resource>:<action>"
roles(id, org_id /*null=system*/, name, system, created_at)
role_permissions(role_id, permission_key, PRIMARY KEY(role_id, permission_key))
role_assignments(id, subject_kind /*USER|GROUP*/, subject_id, role_id,
                 scope_kind /*ORG|RESOURCE*/, scope_type, scope_id, granted_at)   -- scoped grants

policies(id, org_id, name, effect /*PERMIT|DENY*/, target_json, condition_json, priority, active)

clients(id, org_id, name, type /*PUBLIC|CONFIDENTIAL|SERVICE*/, secret_hash,
        grants_json, redirect_uris_json, audiences_json, allowed_scopes_json, created_at)
app_permissions(app, permission_key)                               -- declared catalogs (manifest)

signing_keys(kid, alg, public_pem, private_ref /*encrypted*/, status /*ACTIVE|RETIRING*/, created_at)
refresh_tokens(id, user_id, client_id, family_id, status, expires_at)   -- rotation + reuse-detection
sessions(id, user_id, client_id, device_json, created_at, last_seen_at, revoked_at)
audit_log(id, org_id, actor, event_type, subject, action, resource, decision, reasons_json, at)  -- append-only
idempotency_keys(scope, key, response_json, created_at)
outbox(id, aggregate, event_type, payload_json, status, created_at, published_at)
```

---

## 15. Appendix — SPI Recap

```java
interface CredentialProvider   { CredentialKind kind(); VerificationResult verify(UserId, CredentialInput); }
interface PolicyDecisionPoint  { Decision decide(DecisionRequest req); }                  // Permit | Deny(user, audit)
interface ConditionEvaluator   { boolean matches(Condition c, AttributeBag subject, resource, env); } // sandboxed
interface TokenIssuer          { TokenSet issue(GrantRequest g); TokenSet exchange(ExchangeRequest x); }
interface PrincipalDirectory   { Optional<User> byId(UserId); Set<RoleAssignment> assignments(UserId, OrgId); }
interface KeyRing              { SigningKey active(); List<Jwk> jwks(); void rotate(); }
interface OutboxRelay          { void publish(); }
interface IdempotencyStore     { Optional<StoredResponse> find(String scope, String key); void put(...); }
```

Consumers integrate only through the **published seams** (OIDC + JWKS, PDP API, admin API, events, optional SDK). yaya-identity imports no app internals and no yaya-agentic internals; the agent trusts it as a standard OIDC issuer.

---

## 16. Open Questions

1. **operator_auth coexistence (yaya-agentic).** Keep the agent's `operator_auth` as-is and merely trust yaya-identity as an issuer (v1 lean), or migrate operator accounts into yaya-identity later? Lean: coexist in v1; evaluate federating operator_auth once yaya-identity is proven.
2. **App vs embeddable library.** yaya-identity is a standalone service (this doc). Do we also ship a thin **client SDK** apps embed for verify+PDP-cache (§10.3 #4)? Lean: yes, Java SDK in I4; other languages later.
3. **PDP decision caching.** Fine-grained PDP calls add latency. Cache decisions client-side with a short TTL + event-driven invalidation (`policy.changed`, `role.assigned`)? Lean: yes, TTL ≤ 30s + outbox invalidation.
4. **Condition language.** JSON-logic (simple, safe, portable) vs CEL (richer, needs a runtime). Lean: JSON-logic in v1; revisit if policies outgrow it.
5. **Where domain roles live.** Is "courier" a yaya-identity role (scoped to a store) or a yaya-sales domain concept? Lean: **identity owns the role + scope; the app owns domain *state/approval* (KYC)** — §10.2. Confirm this split.
6. **Org hierarchy depth.** Flat orgs in v1 with a nullable `parent_id` reserved, or real nested-org inheritance? Lean: flat in v1, parent column present for non-breaking nesting later.
7. **Same Postgres instance / separate schema.** Same as yaya-sales Q5: shared instance + own `yaya_identity` schema, no cross-schema coupling, so it lifts out fast. Lean: separate schema in v1.
