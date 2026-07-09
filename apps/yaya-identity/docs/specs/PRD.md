# yaya-identity — Product Requirements (PRD)

> **Product:** yaya-identity — the shared, multi-tenant **identity provider (IdP) and authorization service** for the apps we ship (`apps/`).
> **Implements:** [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) (master design). This PRD operationalizes it into personas, stories, and testable requirements.
> **Maps to milestones:** I0–I6 (design §13).
> **Status:** Draft v1 — implementation-ready.
> **Companion docs:** [TRD](TRD.md) · [UI-UX](UI-UX.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).
> **Anchors (yaya-agentic):** `docs/design/yaya-agentic-design.md` §5.4 (AuthN), §5.5 (AuthZ / two-reason `AuthzDecision`), §5.6 (profile/identity resolution), §12 (trust boundaries). CLAUDE.md non-negotiables **#1** (LLM never trusted), **#3** (denials carry two reasons), **#6** (tokens never silently forwarded).

---

## 1. Problem & Vision

### 1.1 Problem statement

Every app we ship needs the same three things and today each would reinvent them: *who is this actor?* (authentication), *may they do this?* (authorization), and *how do services trust each other?* (machine identity). The yaya-agentic core already ships pluggable `Authenticator`/`Authorizer` SPIs and per-tenant OIDC/HMAC trust machinery (`tenant_oidc_issuers`, `OidcAuthenticator`), but it is a **consumer** of identity, not a **producer** of it — it verifies tokens someone else issues and decides tool-call policy for a single conversation. It does not manage organizations, users, groups, roles, or policies, and it was never meant to.

The first real relying party, **yaya-sales**, has already accreted three abstract auth dependencies its design leaves unresolved: a `SERVICE_TOKEN` for service-to-service calls, a **forwarded shopper principal**, and **delivery-courier identities** scoped to a single store. Without a shared provider, each app would grow its own user table, its own password hashing, its own token issuer, and its own bespoke role check — divergent, unauditable, and impossible to reason about across the ecosystem. Worse, authorization logic would leak into every app, violating the same discipline the agent framework holds sacred: *the enforcement point never decides policy* (§12).

There is also no self-hosted answer today. Reaching for Auth0/Okta/Cognito violates the standing **self-hosted, no paid services** constraint.

### 1.2 Vision

yaya-identity is the **one place identity is asserted and authorization is decided**, trusted everywhere:

- A user or service authenticates **once** against yaya-identity and receives a standards-based, **audience-scoped** OAuth 2.0 / OIDC token. Every app verifies it statelessly against a published JWKS. No per-app user tables.
- Access is modeled as **RBAC + ABAC**: roles → permissions, assigned **within a scope** (org-wide, or a single resource like `store:st_123`), plus conditional **policies** on top. Apps ask a **Policy Decision Point** `decide(subject, action, resource, context) → Permit | Deny(userSafeReason, auditReason)` and enforce the answer — they never decide it themselves.
- Machine identity is first-class: **service accounts** (client-credentials) *are* the `SERVICE_TOKEN` yaya-sales expects; forwarding a user identity downstream is an **explicit RFC 8693 token-exchange**, never a silent pass-through (#6).
- yaya-agentic trusts it **with zero new agent code** — register it as an issuer in the existing `tenant_oidc_issuers` table and the agent verifies its RS256 tokens through the machinery it already has.
- Every authentication event and every authorization decision lands in an **append-only audit log** — the identity analog of yaya-sales' stock/payment ledgers.
- Self-hosted on Postgres. Our own Keycloak/Auth0/Ory. No paid IdP.

yaya-identity is a **platform-service app** under `apps/`: separately deployable, its own DB/schema/migrations, it imports no app or agent internals, and it integrates only through published seams (OIDC + JWKS, the PDP API, the admin API, and outbound events).

### 1.3 Success metrics

| # | Metric | Target | How measured |
|---|---|---|---|
| M1 | Agent trust with zero new code | yaya-agentic accepts a yaya-identity RS256 token after only a `tenant_oidc_issuers` row is added — **no agent code change** | Integration test: mint token → call agent `/v1/sessions` → 200 with resolved principal |
| M2 | Token verification latency (relying party) | JWKS-cached verify p99 ≤ 5ms in-process | Micro-benchmark in the client SDK (§TRD) |
| M3 | PDP decision latency | `POST /pdp/decide` p99 ≤ 30ms in-process (excl. network); batch amortizes | Load test against the policies module |
| M4 | Authorization-decision auditability | **100%** of PDP decisions and auth events produce an append-only `audit_log` row | Audit-completeness integration test |
| M5 | Two-reason denial integrity | **0** denials where `userSafeReason == auditReason` | Build-failing unit test over every `Deny` construction site |
| M6 | Schema isolation | **0** cross-schema FKs/joins between `yaya_identity` and any app schema | Build-time migration lint (mirrors yaya-sales S0 guard) |
| M7 | Token-exchange correctness | **0** cases where a shopper token reaches yaya-sales without an explicit exchange step (aud rebinding) | Trace assertion in the yaya-agentic→yaya-sales flow test |

---

## 2. Personas

Actors are both **human** and **machine** — an IdP serves both. Human personas manage the model; machine personas *are* the model in motion.

### 2.1 Org Owner (`owner`, system role)
The first human in an organization — a company admin or founder. Owns the org, invites the first users, defines custom roles, writes policies, registers the apps (clients) their org uses. In yaya-sales terms this is the **store owner**; one org may own many stores.
**Top jobs:** create/accept org; invite users; compose roles; write & simulate policies; register clients; rotate client secrets.

### 2.2 Org Admin (`admin`, system role)
A senior operator the owner trusts with everything except deleting the org or removing the last owner. Manages users, groups, scoped role assignments, and MFA policy.
**Top jobs:** invite/disable users; manage groups; grant/revoke **scoped** role assignments; set MFA policy.

### 2.3 Org Member (`member`, system role)
A regular authenticated human — a store operator, a warehouse clerk. Holds whatever **scoped roles** they were granted (e.g. `store-manager` on `store:st_123`, `viewer` on `store:st_999`). Cannot manage the identity model.
**Top jobs:** log in (password + MFA); do their app job under their scoped permissions; enrol MFA; manage their own profile.

### 2.4 End User / Shopper (relying-party subject)
A customer of a relying party (a yaya-sales shopper) whose identity yaya-identity asserts but who never touches the admin surface. Their token is **token-exchanged** to the app's audience before an app acts on their behalf.
**Top jobs:** authenticate (often via phone OTP); be attributed by `sub` in the downstream app.

### 2.5 Delivery Courier (domain actor, identity user)
A yaya-sales courier is a yaya-identity **user** carrying a `courier` role scoped to `store:{id}`. Identity says *who they are and which store they serve*; the app owns the **KYC-approval** domain state. Often authenticates via **device-code** on a shared device.
**Top jobs:** log in on a constrained device; carry a store-scoped courier token; (approval/KYC lives in yaya-sales, not here).

### 2.6 Service Account (machine identity)
A non-human client (yaya-agentic calling yaya-sales; a background worker). Authenticates via **client-credentials**, yields a `SERVICE_TOKEN` bound to a specific `aud`, carrying its own least-privilege scoped roles.
**Top jobs:** obtain an audience-bound service token; act within narrow scoped permissions; rotate its secret.

### 2.7 Relying-Party App (client)
A registered application — yaya-sales, yaya-agentic, the admin console. Has client credentials, allowed grants, redirect URIs, and **audiences**. Declares its **permission catalog** (manifest) so the admin console can compose roles from it.
**Top jobs:** register; declare permissions; verify tokens (JWKS); call the PDP; react to identity events.

### 2.8 Platform Operator (yaya-identity staff)
Runs yaya-identity itself: key rotation, tenant/org onboarding, incident response. Flagged as a platform-level admin; every action is audited loudly. yaya-identity **eats its own dog food** — the admin API is itself PDP-gated.
**Top jobs:** rotate signing keys; onboard an org; investigate the audit log; run the break-glass path in an incident.

---

## 3. User stories

Format: **As a `<persona>`, I want `<capability>` so that `<value>`.** Each carries an ID for traceability in the TRD, App-Flow, and acceptance tests. Milestone tags map to design §13 (I0–I6).

### 3.1 Authentication & tokens (I1)

| ID | Story | Acceptance gist |
|---|---|---|
| US-01 | As a human user, I want to log in via authorization-code + PKCE so an app gets a token without ever seeing my password. | `GET /oauth2/authorize` → login → `POST /oauth2/token` (code) → RS256 access + refresh + ID token. |
| US-02 | As a user, I want to authenticate with a password so I can sign in with something I know. | Argon2id verify; wrong password is generic-error + lockout-counted. |
| US-03 | As a shopper, I want to authenticate with a phone OTP so I don't need a password. | `PHONE_OTP` credential; reuses ecosystem OTP machinery; single-use, TTL-bound code. |
| US-04 | As a user holding a privileged role, I want (or am required) to complete TOTP MFA so my account is harder to take over. | MFA challenge after primary factor when org MFA policy requires it; enrolment flow. |
| US-05 | As any relying party, I want to verify a token offline against a published JWKS so I don't round-trip for coarse checks. | `GET /oauth2/jwks`; RS256; `kid` resolves; rotated keys stay in JWKS through TTL. |
| US-06 | As yaya-agentic, I want to trust yaya-identity tokens by adding an issuer row so I need **no code change**. | Register issuer in `tenant_oidc_issuers`; `OidcAuthenticator` accepts the token; principal resolves. |
| US-07 | As a courier on a shared device, I want the device-code grant so I can log in without a browser redirect on that device. | `POST /oauth2/device_authorization` → user completes on phone → device polls `/oauth2/token`. |
| US-08 | As a user, I want my refresh token to rotate and reuse to be detected so a stolen token is contained. | Rotating refresh; replay of a consumed token revokes the whole family. |
| US-09 | As a user, I want to log out and end my session (including back-channel logout to apps). | `POST /oauth2/logout`; session revoked; back-channel notifies clients. |
| US-10 | As a discovery client, I want OIDC discovery + userinfo so standard libraries just work. | `/.well-known/openid-configuration`, `/oauth2/userinfo`. |

### 3.2 Organizations, users, groups (I1)

| ID | Story | Acceptance gist |
|---|---|---|
| US-20 | As an org owner, I want to create an organization so I have a tenant boundary that owns users, roles, and policies. | `POST /orgs`; creator becomes `owner`; maps 1:1 to an agent tenant. |
| US-21 | As an owner/admin, I want to invite a user by email/phone so they can join my org and set credentials. | `POST /orgs/{id}/invitations`; invite-gated (mirrors yaya-sales onboarding); TTL-bound, single-use. |
| US-22 | As an invited user, I want to accept and set my credential so my membership activates. | Accept token → set password/enrol OTP → `Membership.status=ACTIVE`. |
| US-23 | As an admin, I want to lock/disable a user so a compromised or departed account can't authenticate. | `POST …/users/{uid}/lock|disable`; existing sessions revoked; audited. |
| US-24 | As an admin, I want to group users so I can assign roles in bulk. | `PUT …/groups/{gid}/members`; a role assigned to a group applies to all members. |
| US-25 | As a contractor, I want to belong to more than one org without a duplicate identity. | One `User`; multiple `Membership` rows; per-org scoped roles. |
| US-26 | As a user, I want to reset a forgotten credential without leaking whether my account exists. | Enumeration-safe reset; old sessions invalidated on confirm. |

### 3.3 RBAC — roles, permissions, scoped assignments (I2)

| ID | Story | Acceptance gist |
|---|---|---|
| US-30 | As an app, I want to declare my permission catalog so the console can compose roles from real permissions. | `POST /v1/identity/apps/{app}/permissions`; keys `<app>.<resource>:<action>`. |
| US-31 | As an owner, I want to compose a custom role from declared permissions so I can model my org's job functions. | `POST /orgs/{id}/roles`; system roles (`owner`/`admin`/`member`) ship built-in. |
| US-32 | As an admin, I want to assign a role **within a scope** so "manager of store A but not B" is native. | `POST …/role-assignments {subject, role, scope: store:st_A}`; effective permissions honor scope. |
| US-33 | As an admin, I want to assign a role to a group scoped to a resource so a whole fleet gets courier rights on one store. | Group assignment + `Scope.Resource("store", id)`; every member inherits it there only. |
| US-34 | As a relying party, I want scoped roles in the token so I can do coarse checks without a PDP round-trip. | Access token `roles` claim carries `(role, scope)` pairs; app reads them directly. |
| US-35 | As an admin, I want to revoke an assignment so access ends immediately. | `DELETE …/role-assignments/{id}`; next token (and PDP call) reflects removal. |
| US-36 | As a relying party, I want to query a subject's effective permissions for a resource so my UI enables/disables controls correctly. | `GET /pdp/permissions?subject=&resource=`. |

### 3.4 ABAC — policies & the PDP (I3)

| ID | Story | Acceptance gist |
|---|---|---|
| US-40 | As an app (PEP), I want to ask `decide(subject, action, resource, context)` so fine-grained authorization is answered in one place. | `POST /pdp/decide` → `Permit` or `Deny(userSafeReason, auditReason)`. |
| US-41 | As an owner, I want a policy that denies a refund over a cap so a role can't be over-trusted. | `Policy(effect=DENY, target=sales.order:refund, condition=amount>subject.refundLimit)`; deny overrides. |
| US-42 | As an owner, I want an ownership policy so a courier can only collect COD for orders assigned to them. | `condition = resource.assignedAgent == subject.id`; PERMIT scoped narrowly. |
| US-43 | As an app, I want deny to always win over any permit so a mistaken broad grant can be fenced. | Deny-overrides evaluation; explicit `DENY` short-circuits. |
| US-44 | As an app, I want every denial to carry a **user-safe** and an **audit** reason, guaranteed different, so I never leak internals (#3). | `userSafeReason ≠ auditReason`; build-failing test enforces it. |
| US-45 | As an owner, I want to **simulate** a policy change before saving so I don't lock myself out. | `POST …/policies/{pid}/simulate` → would-be decision (dry-run), no persistence. |
| US-46 | As an app, I want to batch many checks in one call so a list view doesn't fan out N round-trips. | `POST /pdp/decide-batch` → decisions[] in one request. |
| US-47 | As a policy author, I want conditions to be a safe, sandboxed expression (no arbitrary code) so a policy can't run wild. | JSON-logic/CEL-style over subject/resource/env attribute bags; no I/O, bounded. |
| US-48 | As the platform, I want the PDP to default-deny so a missing rule is safe. | No matching permit → `Deny`; audited. |

### 3.5 Machine identity & delegation (I4)

| ID | Story | Acceptance gist |
|---|---|---|
| US-50 | As an owner, I want to register a service account so a background service can authenticate as itself. | `POST /orgs/{id}/clients {type: SERVICE}`; client-credentials grant. |
| US-51 | As yaya-agentic, I want a client-credentials token bound to `aud: yaya-sales` so my service call is accepted only there. | `POST /oauth2/token grant=client_credentials` → `SERVICE_TOKEN` with the right `aud`. |
| US-52 | As yaya-agentic, I want to **token-exchange** a shopper token to the yaya-sales audience so the shopper is forwarded explicitly, never silently (#6). | `grant=urn:...:token-exchange` → new token `aud=yaya-sales`, `sub=shopper`. |
| US-53 | As an app, I want to reject a token minted for another audience so tokens can't be replayed across apps. | `aud` mismatch → 401 at the relying party. |
| US-54 | As an owner, I want to rotate a client secret without downtime so a leaked secret is contained. | `POST …/clients/{cid}/rotate-secret`; overlap window; old secret retired. |
| US-55 | As an app, I want to introspect/revoke a token so I can react to logout/compromise. | `POST /oauth2/introspect`, `/oauth2/revoke`. |

### 3.6 Admin console & operations (I5)

| ID | Story | Acceptance gist |
|---|---|---|
| US-60 | As an owner/admin, I want a console to manage orgs, users, groups, roles, policies, and clients so I don't hand-craft API calls. | Flutter admin over the §9.3 admin API; all list/detail/edit flows. |
| US-61 | As a policy author, I want a **policy simulator** in the console so I can test a decision before it goes live. | Console surfaces `/policies/{pid}/simulate` with a subject/action/resource form. |
| US-62 | As a platform operator, I want to rotate signing keys with previous keys retained so verification never breaks mid-rotation. | Key rotation runbook; JWKS keeps retiring keys through token TTL. |
| US-63 | As a platform operator, I want auth endpoints rate-limited and accounts locked after N failures so brute force is contained. | Per-IP + per-identity sliding window; lockout; step-up MFA as a PDP obligation. |
| US-64 | As an org, I want to require MFA for privileged roles so admin takeover is harder. | Per-org MFA policy; `owner`/`admin` must have TOTP enrolled to act. |
| US-65 | As a user, I want my PII to be deletable so we meet privacy obligations. | Retention/deletion path; audit retains non-PII UUIDs (aligns agent §5.9, yaya-sales §15). |
| US-66 | As a platform operator, I want a read view of the audit log so I can investigate any decision. | `GET /orgs/{id}/audit?from=&to=&subject=&action=`; append-only, exportable. |

### 3.7 Integration & events (I2–I4)

| ID | Story | Acceptance gist |
|---|---|---|
| US-70 | As a relying party, I want a thin Java client SDK so verify + PDP-cache + token-exchange are a few lines, not bespoke. | SDK: cached JWKS verify, PDP call with short-TTL decision cache, exchange helper. |
| US-71 | As an app, I want identity events so I can invalidate caches when access changes. | Outbox events: `user.created`, `membership.added`, `role.assigned`, `policy.changed`, `client.secret_rotated`. |
| US-72 | As an app, I want a short-TTL decision cache invalidated by events so PDP latency is amortized without staleness. | Client caches decisions ≤ 30s; `policy.changed`/`role.assigned` invalidate. |

### 3.8 Federation (vNext seam, I6)

| ID | Story | Acceptance gist |
|---|---|---|
| US-80 | As an enterprise org, I want to log in with an external IdP (OIDC-in) so I reuse my corporate identity. | OIDC federation; per-org SSO enforcement. |
| US-81 | As a user, I want passkeys/WebAuthn so I can go passwordless. | WebAuthn behind the credential SPI. |
| US-82 | As an enterprise, I want SCIM provisioning so my directory syncs users automatically. | SCIM endpoints (deferred). |

---

## 4. Functional requirements

Numbered for traceability; each FR maps to one or more user stories.

### 4.1 Authentication & tokens

- **FR-1.1** yaya-identity is an **OAuth 2.0 authorization server + OIDC provider**. It exposes `/.well-known/openid-configuration`, `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/oauth2/userinfo`, `/oauth2/device_authorization`, `/oauth2/introspect`, `/oauth2/revoke`, `/oauth2/logout`.
- **FR-1.2** Supported grants: **authorization-code + PKCE**, **client-credentials**, **refresh-token** (rotating), **device-code**, **token-exchange (RFC 8693)**. Password grant is **not** offered to third parties; the password *credential* is verified only through the hosted login (auth-code) flow.
- **FR-1.3** Access tokens are **RS256 JWTs** carrying `iss`, `sub`, `org`, `groups`, **scoped `roles`** (`(role, scope)` pairs), `scope`, `aud`, `iat`, `exp`. Access-token TTL ≤ 15 min (configurable).
- **FR-1.4** The **JWKS** endpoint serves current + retiring public keys. A retired key remains in JWKS for at least the max access-token TTL after rotation so in-flight tokens verify.
- **FR-1.5** Refresh tokens are **opaque, rotating, revocable**; presenting a consumed refresh token revokes the entire token family (reuse-detection).
- **FR-1.6** Credentials are pluggable via `CredentialProvider`: v1 = **password (Argon2id)**, **phone OTP** (single-use, TTL ≤ 10 min, rate-limited), **TOTP MFA**. WebAuthn and OIDC federation are behind the same SPI, vNext.
- **FR-1.7** **MFA policy is per-org.** When a policy requires MFA for a role, a subject holding that role must complete a TOTP challenge before a token carrying that role's permissions is issued.
- **FR-1.8** **ID tokens** are issued for OIDC login flows only; `userinfo` returns the standard claim set for the subject.
- **FR-1.9** Password/credential reset is **enumeration-safe** (uniform response regardless of account existence); confirming a reset **invalidates all active sessions** for that user in the same transaction.
- **FR-1.10** yaya-identity registers into yaya-agentic's `tenant_oidc_issuers` as a trusted issuer; **no yaya-agentic code changes** are required for the agent to accept its tokens (verified by M1).

### 4.2 Organizations, users, groups

- **FR-2.1** An **Organization** is the tenant boundary; it owns all users (via membership), groups, roles, and policies. It maps 1:1 to a yaya-agentic tenant and may own many yaya-sales stores.
- **FR-2.2** A **User** is a global identity (`PERSON` or `SERVICE`) with `INVITED|ACTIVE|LOCKED|DISABLED` status; **Membership** ties a user to an org. A user may belong to multiple orgs without duplication.
- **FR-2.3** Org onboarding is **invite-gated** by default: an owner/admin invites by email/phone; the invitee accepts and sets a credential; TTL-bound, single-use tokens. Self-service org creation is a separate, guarded flow.
- **FR-2.4** Locking/disabling a user **revokes active sessions** and blocks new token issuance; the action is audited.
- **FR-2.5** **Groups** aggregate users within an org; a role assigned to a group applies to all current members. Group membership changes take effect on the next token/PDP evaluation.

### 4.3 RBAC — roles, permissions, scoped assignments

- **FR-3.1** A **Permission** is keyed `<app>.<resource>:<action>` (e.g. `sales.order:refund`, `identity.user:invite`). Apps **declare** their catalog via the app-registration manifest; the console composes roles only from declared permissions.
- **FR-3.2** A **Role** bundles permissions. System roles `owner`, `admin`, `member` ship built-in (org=null); orgs create custom roles from declared permissions.
- **FR-3.3** A **RoleAssignment** grants a role to a **user or group** within a **Scope**: `Scope.Org(orgId)` (all resources) or `Scope.Resource(type, id)` (one resource, e.g. `store:st_123`).
- **FR-3.4** A subject's **effective permissions for a resource** = the union of permissions from every assignment (direct or via group) whose scope covers that resource. Scope is enforced: an assignment scoped to `store:A` grants nothing on `store:B`.
- **FR-3.5** Scoped roles are embedded in the access token's `roles` claim so relying parties can perform **coarse checks without a PDP round-trip**.
- **FR-3.6** Revoking an assignment takes effect at the next token issuance and immediately for PDP calls.

### 4.4 ABAC — policies & the PDP

- **FR-4.1** The **PDP** exposes `POST /pdp/decide`, `POST /pdp/decide-batch`, and `GET /pdp/permissions`. `decide` returns `Permit(obligationsJson?)` or `Deny(userSafeReason, auditReason)`.
- **FR-4.2** **Evaluation order:** (1) resolve effective permissions from scoped assignments — absent → base deny; (2) evaluate matching policies by priority; a matching **DENY short-circuits**; a matching **PERMIT** can grant a right RBAC didn't; (3) no explicit permit → **default deny**; (4) emit the decision to the audit log.
- **FR-4.3** **Deny overrides.** An explicit `DENY` policy always beats any permit (RBAC or policy).
- **FR-4.4** A **Policy** has `effect ∈ {PERMIT, DENY}`, a `target` (actions/resource-types it applies to), a `condition` (sandboxed expression), and a `priority`.
- **FR-4.5** Conditions are a **sandboxed, side-effect-free** expression language (JSON-logic in v1) over three attribute bags: **subject** (roles, groups, org, claims), **resource** (owner, store, region, amount, assignedAgent…), **environment** (time, IP, request context the PEP passes). No I/O, no unbounded loops.
- **FR-4.6** Every **Deny** carries a **user-safe reason** (app may show it) and an **audit reason** (internal truth). They **MUST differ** — a build-failing test enforces `userSafeReason != auditReason` at every construction site (mirrors agent non-negotiable #3).
- **FR-4.7** `POST …/policies/{pid}/simulate` returns the **would-be decision** for a hypothetical `(subject, action, resource, context)` **without persisting** anything.
- **FR-4.8** Apps are **PEPs**: they call the PDP and enforce the answer; they **never** re-implement policy evaluation (mirrors agent §12 "tools never check policy themselves").

### 4.5 Machine identity & delegation

- **FR-5.1** A **Client** registration carries type (`PUBLIC`/`CONFIDENTIAL`/`SERVICE`), allowed grants, redirect URIs, **audiences**, and allowed scopes. A **service account** is a `SERVICE` client; its client-credentials token *is* the `SERVICE_TOKEN` relying parties expect.
- **FR-5.2** Tokens are **audience-bound**: a relying party rejects a token whose `aud` does not include it (401).
- **FR-5.3** Forwarding a user identity to a downstream app is an **explicit token-exchange** (RFC 8693) that rebinds `aud` and preserves `sub`; there is **no silent token pass-through** (#6).
- **FR-5.4** Client secrets are stored **hashed**; rotation supports an overlap window so a rotate is zero-downtime.
- **FR-5.5** `introspect`/`revoke` operate on access and refresh tokens; revocation is honored at the next introspection and via back-channel logout where applicable.

### 4.6 Auditing, security, PII

- **FR-6.1** Every **authentication event** (login, MFA, token issue/refresh/revoke, logout) and every **authorization decision** (`Permit`/`Deny` with reasons) writes an **append-only** `audit_log` row with the full request context and matched rules.
- **FR-6.2** Auth endpoints are **rate-limited** (per-IP + per-identity sliding window); N consecutive failures **lock** the account and may trigger a notification; 429s carry `Retry-After`.
- **FR-6.3** **Signing keys rotate**; private keys and client secrets are **encrypted at rest** (AES-256-GCM envelope, mirroring the agent's `JwtSecretCipher`). Passwords are Argon2id.
- **FR-6.4** The **admin API is itself PDP-gated** — yaya-identity enforces its own model on its own management surface.
- **FR-6.5** **PII** (emails, phones, profiles) has a **retention/deletion** path; audit rows retain non-PII UUIDs after a subject is scrubbed.
- **FR-6.6** Service accounts follow **least privilege** — scoped roles + audiences, never a broad grant.

### 4.7 Multi-tenancy & isolation

- **FR-7.1** All identity data is **org-scoped**; a query for one org never returns another org's users, roles, or policies.
- **FR-7.2** yaya-identity lives in its **own `yaya_identity` Postgres schema** with **zero cross-schema FKs/joins** to any app, so it lifts to a separate instance without migration (hard guarantee from I0, mirrors yaya-sales S0).
- **FR-7.3** Mutating admin/token calls accept an **`Idempotency-Key`**; a repeated key returns the stored response.

---

## 5. Non-functional requirements

### 5.1 Security
- **NFR-Sec-1** Passwords: **Argon2id**. Signing: **RS256** with rotated keys. Secret envelope: **AES-256-GCM**. Client secrets stored **hashed**, never reversible.
- **NFR-Sec-2** Tokens are **short-lived and audience-bound**; refresh rotates with **reuse-detection**.
- **NFR-Sec-3** **Deny-by-default, deny-overrides**; the PDP is the **only** decision point; apps are PEPs.
- **NFR-Sec-4** **Two-reason denials** everywhere; `userSafeReason` never leaks internals; enforced by a build-failing test (M5).
- **NFR-Sec-5** Token forwarding is **explicit token-exchange only** (#6); no silent pass-through path exists in the codebase.
- **NFR-Sec-6** Enumeration-safe auth surfaces; uniform responses on login/reset regardless of account existence.
- **NFR-Sec-7** yaya-identity is the **root of trust** — its compromise is total. It gets the repo's highest security bar: mandatory audit, MFA for privileged roles, rate-limit/lockout, encrypted secrets, key rotation runbook.

### 5.2 Performance
- **NFR-Perf-1** JWKS-cached token verify (relying party, in SDK): **p99 ≤ 5ms** in-process.
- **NFR-Perf-2** `POST /pdp/decide`: **p99 ≤ 30ms** in-process (excl. network); `decide-batch` amortizes fixed cost across items.
- **NFR-Perf-3** Argon2id verify: **p50 ≤ 100ms / p99 ≤ 500ms** on a 4-core box.
- **NFR-Perf-4** Token issuance (`/oauth2/token`, code grant): **p99 ≤ 150ms** in-process (dominated by credential + signing).
- **NFR-Perf-5** Client-side decision cache TTL ≤ 30s, invalidated by `policy.changed`/`role.assigned` events (bounded staleness).

### 5.3 Availability
- **NFR-Avail-1** JWKS and token verification are **stateless** at relying parties — an app can verify coarse tokens even during a brief yaya-identity outage (until cache expiry).
- **NFR-Avail-2** Redis outage degrades rate limiting to **fail-open with a loud audit warn**, not a hard auth failure.
- **NFR-Avail-3** Key rotation is **make-before-break** — a new active key is published in JWKS before old tokens expire; verification never has a gap.

### 5.4 Compatibility
- **NFR-Compat-1** yaya-identity requires **no yaya-agentic code change** — it integrates via the existing `tenant_oidc_issuers` OIDC-trust path (M1).
- **NFR-Compat-2** It speaks **standard OAuth 2.0 / OIDC** so any conformant library (including the agent's `OidcAuthenticator`) interoperates without custom glue.
- **NFR-Compat-3** yaya-agentic's `operator_auth` **coexists** in v1 (design §16 Q1); the agent may additionally trust yaya-identity as an issuer. No forced migration.
- **NFR-Compat-4** The client SDK is **optional** — apps may speak raw OIDC/HTTP; the SDK is convenience, not a dependency.

### 5.5 Observability
- **NFR-Obs-1** Every auth event and PDP decision emits a structured `audit_log` row (FR-6.1); audit completeness is test-enforced (M4).
- **NFR-Obs-2** SLO dashboards (future): login failure rate, lockout rate, token-issue latency, PDP decision latency + permit/deny ratio, key-rotation age.
- **NFR-Obs-3** Outbox events are observable and replayable for consumer cache invalidation.

### 5.6 Deployability & self-hosting
- **NFR-Dep-1** Java 21, Spring Boot 3.5.x, **Spring Authorization Server**, Spring Modulith; Postgres 16; own `pom.xml`, CI target, container.
- **NFR-Dep-2** **Self-hosted, no paid IdP** (standing constraint) — replaces Auth0/Okta/Keycloak-as-a-service.
- **NFR-Dep-3** Own `yaya_identity` schema; **no cross-schema coupling** to any app (FR-7.2).

### 5.7 Accessibility (admin console, Flutter)
- **NFR-A11y-1** All form fields carry visible labels; placeholder is never the only label.
- **NFR-A11y-2** Errors and PDP-denial reasons are screen-reader announced via `Semantics`.
- **NFR-A11y-3** Color is never the only signal (role/scope chips carry text; permit/deny use icon + text).
- **NFR-A11y-4** Full keyboard navigation for every flow (login, MFA enrol, role assignment, policy editor, simulator).

---

## 6. Scope

### 6.1 In scope (v1 — I0–I5)
- OAuth 2.0 / OIDC issuer: auth-code+PKCE, client-credentials, refresh (rotating), device-code, token-exchange; JWKS, discovery, userinfo, introspect, revoke, logout.
- Credentials: password (Argon2id), phone OTP, TOTP MFA; per-org MFA policy.
- Orgs, users (`PERSON`/`SERVICE`), memberships, invitations, groups.
- RBAC: permission catalog + app manifest, roles (system + custom), **scoped role assignments** (org / resource).
- ABAC: policy store, sandboxed condition engine (JSON-logic), the **PDP** (`decide`/`decide-batch`/`permissions`/`simulate`), **two-reason denials**, deny-overrides, default-deny.
- Machine identity: clients + service accounts, audience binding, client-credentials `SERVICE_TOKEN`, RFC 8693 token-exchange, secret rotation.
- Append-only audit; rate-limit/lockout; key rotation; secret encryption; PII deletion path.
- Admin console (Flutter) incl. **policy simulator**; thin Java client SDK; outbound events.
- yaya-agentic trust via `tenant_oidc_issuers` (zero agent code); yaya-sales as first relying party (operators, service token, shopper exchange, courier identity).
- Own `yaya_identity` schema with zero cross-schema coupling.

### 6.2 vNext (I6 and beyond)
- OIDC-in **federation** (log in with an external IdP), per-org **SSO enforcement**.
- **WebAuthn / passkeys** behind the credential SPI.
- **SCIM** provisioning; **SAML**.
- Nested-org hierarchy inheritance (v1 keeps a nullable `parent_id` reserved, flat semantics).
- CEL condition language (if JSON-logic proves insufficient).
- Non-Java client SDKs.

### 6.3 Out of scope
- Replacing yaya-agentic's `operator_auth` in v1 (coexist; federate later — §16 Q1).
- A **secrets manager / PKI** for arbitrary app secrets — yaya-identity manages identity credentials and its own signing keys only.
- Billing, quotas, subscriptions.
- A shared database with any app — integration is **token + API only**; yaya-identity never reads an app's tables and vice versa.
- Full **XACML** / general policy VM — the condition language is a small, safe expression only.
- End-user self-service portal beyond the essentials (login, MFA enrol, profile).

### 6.4 Non-goals (firm)
- Letting an app **decide** policy. Apps are PEPs; the PDP is the sole decision point (mirrors agent §12).
- **Silent** token forwarding. Every downstream identity forward is an explicit exchange (#6).
- Conflating identity roles with **domain approval state** — e.g. `courier` is an identity role scoped to a store, but KYC-approval is a **yaya-sales** domain state, not an identity concept (§10.2 of design, §16 Q5).
- A single `Deny` reason. Two reasons, always different (#3).

---

## 7. Acceptance criteria

### 7.1 AuthN & trust (I1)
- [ ] **AC-1** A human completes auth-code + PKCE and receives an RS256 access + refresh + ID token; the access token carries `iss/sub/org/roles/aud`.
- [ ] **AC-2** yaya-agentic accepts a yaya-identity token **after only a `tenant_oidc_issuers` row is added** — no agent code change (**M1**). A tampered/`aud`-mismatched token is rejected.
- [ ] **AC-3** A retired signing key stays in JWKS through the access-token TTL; a token signed before rotation still verifies after it (**NFR-Avail-3**).
- [ ] **AC-4** Refresh rotation works; replaying a consumed refresh token revokes the family and forces re-auth.
- [ ] **AC-5** Phone-OTP and TOTP-MFA flows complete; an org MFA policy blocks token issuance for a privileged role until TOTP is satisfied.
- [ ] **AC-6** Device-code flow lets a courier on a shared device obtain a store-scoped token.

### 7.2 Orgs / users / RBAC (I1–I2)
- [ ] **AC-10** Owner creates an org, invites a user, the user accepts and sets a credential, membership goes `ACTIVE`; the whole accept path is one transaction.
- [ ] **AC-11** A user with `store-manager` scoped to `store:A` and `viewer` scoped to `store:B` gets, in their token, exactly those scoped roles; a coarse check grants manage on A and read-only on B.
- [ ] **AC-12** A role assigned to a **group** scoped to `store:A` grants every member courier rights on `store:A` and nothing on `store:B`.
- [ ] **AC-13** Revoking an assignment removes the permission on the next token and immediately at the PDP.
- [ ] **AC-14** Locking a user revokes their sessions and blocks new token issuance.

### 7.3 ABAC / PDP (I3)
- [ ] **AC-20** `POST /pdp/decide` returns `Permit` when RBAC grants and no deny policy matches.
- [ ] **AC-21** A `DENY` refund-cap policy overrides an RBAC permit: a `store-manager` is denied a refund above the cap; the denial carries **two different reasons**.
- [ ] **AC-22** An ownership policy permits `sales.delivery:collect-cod` only when `resource.assignedAgent == subject.id`; another courier is denied.
- [ ] **AC-23** With no matching permit, the PDP **default-denies** and audits it.
- [ ] **AC-24** `simulate` returns the would-be decision and persists nothing.
- [ ] **AC-25** `decide-batch` answers N checks in one round-trip; results match N individual `decide` calls.
- [ ] **AC-26** A build-failing test rejects any `Deny` where `userSafeReason == auditReason` (**M5**).

### 7.4 Machine identity (I4)
- [ ] **AC-30** yaya-agentic obtains a client-credentials token with `aud: yaya-sales`; yaya-sales accepts it and rejects a token minted for another audience.
- [ ] **AC-31** A shopper token is **token-exchanged** to `aud: yaya-sales` before yaya-sales acts; a trace assertion proves no silent forward occurred (**M7, #6**).
- [ ] **AC-32** Client-secret rotation with an overlap window works with zero failed calls during the window.

### 7.5 Ops, isolation, audit (I5)
- [ ] **AC-40** 100% of auth events and PDP decisions produce an `audit_log` row (**M4**); the log is append-only and exportable.
- [ ] **AC-41** A migration lint fails the build if any `yaya_identity` migration references another schema (**M6, FR-7.2**).
- [ ] **AC-42** Auth-endpoint rate limiting + lockout fire under a burst; 429 carries `Retry-After`.
- [ ] **AC-43** The admin API is PDP-gated — an operator lacking the permission is denied managing the model.
- [ ] **AC-44** A PII deletion request scrubs a user's PII while the audit log retains the non-PII UUID.

### 7.6 Integration test suite (`src/test/java/.../identity_it/`)
1. **`AgentTrustZeroCodeIT`** — register issuer → mint token → call agent → principal resolves (AC-2).
2. **`ScopedRbacIT`** — multi-store scoped assignments, group inheritance, token claims (AC-11, AC-12).
3. **`PdpDecisionIT`** — permit/deny-override/default-deny/ownership/two-reason/simulate/batch (AC-20…AC-26).
4. **`ServiceTokenAndExchangeIT`** — client-credentials `aud` binding + RFC 8693 exchange + replay rejection (AC-30, AC-31).
5. **`KeyRotationIT`** — make-before-break JWKS rotation (AC-3).
6. **`AuditCompletenessIT`** — every auth event + decision is logged (AC-40).
7. **`SchemaIsolationLintTest`** — migration lint for cross-schema references (AC-41).

---

## 8. Phasing & rollout

Maps 1:1 to design §13 milestones; each leaves a **working, independently deployable service**.

| Release | Milestone | Ships | Gate |
|---|---|---|---|
| R0 | **I0** Scaffold + platform | app, `pom.xml`, own `yaya_identity` schema, IDs, signing keys, secret cipher, audit, outbox | boots on own schema; JWKS served; schema-isolation lint green |
| R1 | **I1** Orgs + users + AuthN + issuer | orgs/membership/invitations, users, password + phone-OTP, auth-code+PKCE + client-credentials + refresh, JWKS, userinfo | **AC-1, AC-2 (agent trusts token, zero code), AC-10** |
| R2 | **I2** RBAC | permission catalog + app manifest, roles, **scoped assignments**, groups, token role claims, events | **AC-11, AC-12, AC-13**; coarse PEP checks work in yaya-sales |
| R3 | **I3** ABAC + PDP | policy store, condition engine, `decide`/`batch`/`simulate`, two-reason denials, decision audit | **AC-20…AC-26** |
| R4 | **I4** Clients + token-exchange | client registration, service accounts, `aud` binding, RFC 8693, revoke/introspect | **AC-30, AC-31, AC-32** |
| R5 | **I5** Console + MFA + hardening | Flutter admin + policy simulator, TOTP MFA, rate-limit/lockout, key-rotation runbook, PII deletion | **AC-40…AC-44** |
| R6 | **I6** Federation (vNext) | OIDC-in, WebAuthn, per-org SSO, SCIM | US-80…US-82 |

**Rollout notes.** R1 is the keystone: once the agent trusts yaya-identity via `tenant_oidc_issuers`, every later milestone adds authority without touching the agent. yaya-sales adopts incrementally — service token at R4 blocks nothing earlier; operators can log in at R1 with coarse checks, gaining fine-grained PDP gating at R3.

---

## 9. Open questions (resolved)

Restated from design §16 so the implementing engineer does not re-litigate them:

1. **operator_auth coexistence** — Coexist in v1; the agent keeps its operator accounts and merely trusts yaya-identity as an issuer. Federate/subsume later.
2. **App vs embeddable library** — Standalone service **plus** a thin **Java client SDK** (verify + PDP-cache + exchange) shipped at I4; other languages later.
3. **PDP decision caching** — Client-side cache, TTL ≤ 30s, invalidated by `policy.changed`/`role.assigned` events.
4. **Condition language** — **JSON-logic** in v1 (simple, safe, portable); CEL only if policies outgrow it.
5. **Where domain roles live** — **Identity owns the role + scope; the app owns domain state/approval.** `courier` is an identity role scoped to a store; KYC-approval is a yaya-sales domain state.
6. **Org hierarchy depth** — **Flat** in v1 with a nullable `parent_id` reserved for non-breaking nesting later.
7. **DB isolation** — **Shared Postgres instance, own `yaya_identity` schema, zero cross-schema coupling** — lifts to a separate instance fast. Enforced from I0.

---

## 10. References

- [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) — master design (this PRD operationalizes it).
- `apps/yaya-sales/docs/design/yaya-sales-design.md` — first relying party (service token, shopper exchange, courier identity).
- `docs/design/yaya-agentic-design.md` — §5.4 AuthN, §5.5 AuthZ (two-reason `AuthzDecision`), §5.6 profile/identity resolution, §12 trust boundaries.
- `apps/README.md` — the `apps/` convention (leaf domain vs platform-service apps).
- `docs/specs/user-accounts/PRD.md` — the house-style spec bundle this set mirrors.
- Companion specs: [TRD](TRD.md) · [UI-UX](UI-UX.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).
