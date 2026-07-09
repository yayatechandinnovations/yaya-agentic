# yaya-identity — Application Flow

> **Companion to:** [PRD](PRD.md) · [TRD](TRD.md) · [UI-UX](UI-UX.md) · [Backend-Data](Backend-Data.md).
> **Implements:** [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) (master design — AuthN §6, PDP §7, API §9, integration §10).
> **Status:** Draft v1 — implementation-ready.

This doc captures the runtime sequence for every primary flow yaya-identity serves. Diagrams are ASCII (deliberate — easy to diff). Each flow lists its **actors**, **preconditions**, **numbered steps** (with the exact endpoints from design §9 and the tokens/claims that move), **edge/error branches**, and a **Traceability** line tying it to a PRD user story (US-xx) and acceptance criterion (AC-xx).

Two non-negotiables thread through everything and are called out where they bite:
- **#3 — two-reason denials.** Every `Deny` carries a `userSafeReason` (the app may show it) and an `auditReason` (the internal truth). They **MUST differ**. Apps surface only `userSafeReason`.
- **#6 — no silent token forwarding.** Forwarding a user identity downstream is an **explicit RFC 8693 token-exchange** that rebinds `aud`. There is no silent pass-through path.

## Legend

```
Browser    →  the actor's browser / SPA (admin console, or a relying-party app's login UI)
Device     →  an input-constrained device (courier's shared terminal / POS)
Phone      →  the actor's second device (verification / device-code approval)
IdP        →  yaya-identity (the OAuth2/OIDC issuer + PDP)
  ├ AS       →  Authorization Server (tokens module — §9.1)
  ├ Cred     →  credentials module (password / phone-OTP / TOTP — §6.2)
  ├ PDP      →  Policy Decision Point (policies module — §7)
  ├ RBAC     →  roles module (scoped assignments — §5.5)
  ├ KeyRing  →  signing-keys / JWKS (platform module — §6.3)
  └ Audit    →  append-only audit_log (§14)
Agent      →  yaya-agentic (a relying party AND a delegating caller)
Sales      →  yaya-sales (the first relying party / PEP)
SDK        →  the thin Java client SDK (cached JWKS verify + short-TTL decision cache)
DB         →  the yaya_identity Postgres schema
```

`-->` blocking call. `=>` async fan-out / outbox event. `[…]` a guard/decision. `# …` a side-effect note.

---

# Part I — Authentication flows

## 1. Authorization-code + PKCE human login (operator into the admin console)

**Actors:** Org Admin/Owner/Member (`member`+), the admin console (a `PUBLIC` client), yaya-identity AS + Cred + KeyRing.
**Preconditions:** The console is a registered `Client` with `authorization_code` grant, a redirect URI, and an `aud` it will be issued for. The user has an `ACTIVE` membership and a `PASSWORD` credential. The org may have an MFA policy requiring TOTP for privileged roles (FR-1.7).

### 1.1 Happy path — password, no MFA required

```
Browser --GET /oauth2/authorize?response_type=code&client_id=console
              &redirect_uri=…&code_challenge=<S256>&code_challenge_method=S256
              &scope=openid profile&state=…--> AS
[AS] validates client_id, redirect_uri, PKCE params
  [unknown client / bad redirect_uri] → 400 (never redirect to an unregistered URI)
AS ↩ 302 → hosted login page (session-bound to the authorize request)

Browser --POST credential {email, password}--> Cred
Cred --verify(user, PASSWORD)--> Argon2id
  [no user OR wrong password] → generic "invalid credentials" + increment lockout counter (§Appendix E2)
  [ok] → VerificationResult(success)
Cred --check org MFA policy for the user's roles--> RBAC
  [MFA required AND TOTP enrolled] → jump to 1.2 (challenge)
  [MFA required AND NOT enrolled] → jump to 1.3 (forced enrol)
  [MFA not required] → continue

# Primary factor satisfied → mint an authorization code bound to (client, user, PKCE challenge)
AS --INSERT authorization code (single-use, TTL ≤ 60s, code_challenge stored)--> DB
AS => Audit(event=LOGIN, factor=PASSWORD, user, org, ip, ua)
AS ↩ 302 → redirect_uri?code=<code>&state=…

Browser --POST /oauth2/token
              grant_type=authorization_code&code=<code>
              &code_verifier=<verifier>&client_id=console--> AS
[AS] SHA256(code_verifier) == stored code_challenge ?  [no] → 400 invalid_grant
[AS] code unconsumed & unexpired ?                     [no] → 400 invalid_grant (consume attempt audited)
AS --resolve effective scoped roles for the user--> RBAC
AS --sign access token (RS256, kid=active)--> KeyRing
AS ↩ 200 {
       access_token:  <RS256 JWT>,   # claims below
       refresh_token: <opaque, rotating>,
       id_token:      <RS256 JWT>,   # OIDC login only
       token_type: "Bearer", expires_in: 900
     }
AS => Audit(event=TOKEN_ISSUE, grant=authorization_code, aud, user)
```

Access-token claims minted (design §6.3 / FR-1.3):

```json
{
  "iss": "https://identity.yaya/",
  "sub": "usr_8fk2",
  "org": "org_acme",
  "groups": ["warehouse-staff"],
  "roles": [
    { "role": "store-manager", "scope": "store:st_123" },
    { "role": "viewer",        "scope": "store:st_999" }
  ],
  "scope": "openid profile",
  "aud": ["console"],
  "iat": 1751500000,
  "exp": 1751500900
}
```

### 1.2 MFA sub-flow — TOTP required and enrolled

```
# after 1.1 primary factor
Cred ↩ MFA_REQUIRED (challenge_id)
Browser --POST mfa {challenge_id, totp_code}--> Cred
Cred --verify TOTP against enrolled secret (±1 window)--> Cred
  [wrong / expired code] → generic MFA failure + lockout-counted; re-prompt
  [ok] → factor complete
Cred => Audit(event=MFA_VERIFY, method=TOTP, user)
# → resume authorization-code mint (1.1 from "mint an authorization code")
```

### 1.3 MFA sub-flow — required but NOT enrolled (forced enrolment)

```
Cred ↩ MFA_ENROLMENT_REQUIRED
AS ↩ 302 → hosted enrol page (still inside the authorize session; no token issued yet)
Browser --POST enrol/totp/begin--> Cred
Cred --generate TOTP secret; render otpauth:// QR--> Browser
Browser --POST enrol/totp/confirm {totp_code}--> Cred
  [code matches secret] → persist TOTP credential; enrolment complete
  [mismatch] → re-render QR, retry
Cred => Audit(event=MFA_ENROL, method=TOTP, user)
# → resume 1.1 mint. The org MFA policy (FR-1.7) blocks token issuance
#   for a privileged role until this completes.
```

**Edge/error branches**
- **Wrong password:** uniform generic error (enumeration-safe, NFR-Sec-6); the per-identity + per-IP lockout counter increments (FR-6.2). After N failures the account locks → 429 `Retry-After` (see Appendix E2).
- **Consumed/expired authorization code:** `400 invalid_grant`; the reuse attempt is audited.
- **PKCE mismatch:** `400 invalid_grant`; no token issued.
- **redirect_uri not registered:** `400` at `/oauth2/authorize` — the AS never redirects to an unregistered URI.

**Traceability:** US-01, US-02, US-04 · AC-1, AC-5.

---

## 2. Phone-OTP login (shopper)

**Actors:** End User / Shopper, a relying-party login surface (or the agent's chat onboarding), yaya-identity AS + Cred.
**Preconditions:** The user has a `PHONE_OTP` credential (a verified phone). OTP is single-use, TTL ≤ 10 min, rate-limited (FR-1.6).

```
Browser --POST /oauth2/authorize … (or the SDK helper) selects PHONE_OTP--> AS
AS ↩ 302 → OTP request page

Browser --POST otp/request {phone}--> Cred
Cred --rateLimiter.check(phone, ip)--> platform
  [over limit] → 429 Retry-After (uniform response — enumeration-safe)
Cred --mint 6-digit code; store hash(code) TTL≤10m single-use--> DB
Cred => SMS(code)   # reuses ecosystem OTP machinery (chat account verification)
Cred ↩ 202 (uniform whether the phone is known or not)

Browser --POST otp/verify {phone, code}--> Cred
Cred --compare hash, check TTL + unconsumed--> DB
  [no match / expired / already used] → generic failure + lockout-counted
  [ok] → consume code; factor complete
Cred => Audit(event=LOGIN, factor=PHONE_OTP, user)

AS --mint authorization code → /oauth2/token (as §1.1)--> AS
AS ↩ 200 { access_token, refresh_token, id_token }
```

The shopper's token carries `sub` = the shopper's user id and `aud` = the login client. It is **not yet** valid at yaya-sales — that requires the token-exchange in §10.

**Edge/error branches**
- **OTP expired / reused:** generic failure; a fresh request is required (single-use).
- **Rate limited:** `429 Retry-After`; the 202 on request is uniform so existence is never leaked.

**Traceability:** US-03 · AC-5.

---

## 3. Device-code login (courier on a shared device)

**Actors:** Delivery Courier, a shared/constrained Device (POS/terminal), the courier's Phone, yaya-identity AS.
**Preconditions:** The device app is a registered client permitted the `device_code` grant. The courier is a yaya-identity user carrying a `courier` role scoped to `store:{id}` (§5.5).

```
Device --POST /oauth2/device_authorization {client_id, scope}--> AS
AS --mint device_code + user_code (short, human-typable); TTL; interval--> DB
AS ↩ 200 {
       device_code, user_code:"WDJB-MJHT",
       verification_uri:"https://identity.yaya/activate",
       verification_uri_complete:"…/activate?user_code=WDJB-MJHT",
       expires_in:600, interval:5
     }
Device --render user_code + verification_uri (QR)--> Courier

# Courier completes on their phone (a full browser) — this is a normal auth-code login:
Phone --GET /activate?user_code=WDJB-MJHT--> AS
Phone --auth-code + PKCE login (§1), incl. MFA if org policy requires--> AS
Phone --approve "Sign in this device?"--> AS
AS --bind device_code → (user, granted scopes)--> DB
AS => Audit(event=DEVICE_APPROVED, user, device)

# Meanwhile the device polls:
loop:
  Device --POST /oauth2/token grant=device_code&device_code=…--> AS
    [pending]  → 400 authorization_pending  (device waits `interval`)
    [too fast] → 400 slow_down              (device backs off)
    [expired]  → 400 expired_token          (restart from device_authorization)
    [approved] → 200 { access_token(aud, roles scoped to store), refresh_token }
AS => Audit(event=TOKEN_ISSUE, grant=device_code, user)
```

The device never sees the courier's password. The issued token carries `roles:[{role:"courier", scope:"store:st_123"}]`.

**Edge/error branches**
- **Device code expires before approval:** `expired_token`; the device restarts at `/oauth2/device_authorization`.
- **Courier abandons approval:** device keeps polling `authorization_pending` until expiry, then restarts.
- **Identity vs domain split:** a valid courier token only says *who they are and which store*. Whether they may **act** still passes yaya-sales' KYC-approved domain gate — see §11.

**Traceability:** US-07 · AC-6.

---

# Part II — Trust & integration flows

## 4. yaya-agentic trusts yaya-identity with ZERO agent code *(keystone)*

**Actors:** Platform Operator, yaya-identity (issuer + JWKS), yaya-agentic (`OidcAuthenticator`, existing).
**Preconditions:** yaya-agentic already resolves per-tenant OIDC trust from `tenant_oidc_issuers` and verifies RS256/ES256 JWTs (design §5.4). yaya-identity serves `/.well-known/openid-configuration` + `/oauth2/jwks`. **No new agent SPI, no agent code change.**

### 4.1 Sequence

```
┌──────────────┐        ┌───────────────────┐          ┌───────────────────────────┐
│ PlatformOper │        │ yaya-identity     │          │ yaya-agentic              │
│              │        │ (issuer + JWKS)   │          │ (OidcAuthenticator)       │
└──────┬───────┘        └─────────┬─────────┘          └─────────────┬─────────────┘
       │                          │                                  │
       │ 1. INSERT tenant_oidc_issuers row                           │
       │    (issuer, discovery URL, jwks_uri, aud) — admin CRUD      │
       │────────────────────────────────────────────────────────────▶│
       │                          │        # TenantOidcIssuersChangedEvent
       │                          │        # evicts per-tenant processor cache
       │                          │                                  │
       │ 2. user logs in (§1) at yaya-identity                       │
       │─────────────────────────▶│                                  │
       │                          │ 3. mint RS256 access token       │
       │◀─────────────────────────│    {iss, sub, org, roles, aud}   │
       │                          │                                  │
       │ 4. client calls agent with Bearer <that token>              │
       │    POST /v1/sessions                                        │
       │────────────────────────────────────────────────────────────▶│
       │                          │ 5. GET /.well-known/openid-configuration
       │                          │◀────────────────────────────────── (discover)
       │                          │ 6. GET /oauth2/jwks (kid → RS256) │
       │                          │◀──────────────────────────────────
       │                          │                                  │ 7. verify sig,
       │                          │                                  │    iss, aud, exp
       │                          │                                  │ 8. build Principal
       │                          │                                  │ 9. ProfileResolverChain
       │                          │                                  │    (IdentityProfileResolver
       │                          │                                  │     reads claims → profile)
       │ 10. 200 session started (principal + profile resolved)     │
       │◀────────────────────────────────────────────────────────────│
```

### 4.2 Steps

1. **Register the issuer.** The platform operator adds a `tenant_oidc_issuers` row for the tenant: the yaya-identity `issuer` URL, its discovery + `jwks_uri`, and the expected `aud`. This is the agent's *existing* admin CRUD; it fires `TenantOidcIssuersChangedEvent`, evicting the per-tenant JWT-processor cache.
2. A user logs in at yaya-identity (§1) and receives an RS256 access token whose `iss` is yaya-identity.
3–4. A production client calls the agent's `POST /v1/sessions` with `Authorization: Bearer <token>`.
5–7. `OidcAuthenticator` discovers the issuer config, fetches JWKS, resolves the token's `kid`, and verifies signature + `iss` + `aud` + `exp`. (It yields on HS-family algs so `SharedSecretJwtAuthenticator` wins there — not relevant for RS256.)
8. On success it builds the agent's `Principal` from `sub`/`org`/claims.
9. The `ProfileResolverChain` runs; `IdentityProfileResolver` matches the verified claims against `identity_profile_mappings` to pick a profile.
10. The session starts with a resolved principal + profile.

**Edge/error branches**
- **`aud` mismatch / tampered signature / expired:** `OidcAuthenticator` rejects → `401` at `/v1/sessions`. No principal is built.
- **Issuer not yet registered:** the token is untrusted → `401`. The fix is a data change (add the row), never a code change — this *is* metric M1.
- **Key mid-rotation:** verification still succeeds because retiring keys stay in JWKS through the token TTL (§5).

**Traceability:** US-06 · AC-2 · metric M1 (integration test `AgentTrustZeroCodeIT`).

---

## 5. JWKS make-before-break key rotation

**Actors:** Platform Operator, yaya-identity KeyRing/JWKS, every relying party (via cached JWKS).
**Preconditions:** Signing keys carry a `status ∈ {ACTIVE, RETIRING}` and a `kid`. JWKS serves current + retiring public keys. A retired key stays in JWKS for **at least the max access-token TTL** after rotation (FR-1.4, NFR-Avail-3).

```
t0  KeyRing: ACTIVE=kA. Tokens signed with kid=kA. JWKS={kA}.
    A token T is minted at t0 with kid=kA, exp=t0+15m.

t1  Operator --rotate--> KeyRing
    KeyRing --generate kB; INSERT signing_keys(kB, ACTIVE)
             UPDATE kA → RETIRING (kept, not deleted)--> DB
    JWKS now = {kB (active), kA (retiring)}    # make-before-break
    New tokens signed with kid=kB.
    KeyRing => Audit(event=KEY_ROTATE, from=kA, to=kB)

t1..t1+TTL  Relying party receives token T (kid=kA):
    SDK --lookup kid=kA in cached JWKS--> found (still present)
    → verifies OK.  In-flight tokens never break.

t1+TTL+ε  Operator (or a scheduled sweep) --retire kA--> KeyRing
    KeyRing --DELETE/mark kA fully retired (all kA tokens now expired)--> DB
    JWKS = {kB}.
```

**Edge/error branches**
- **Relying party cache is stale and lacks kB:** on an unknown `kid` the SDK refetches JWKS once (bounded), then verifies. A brief IdP outage still lets coarse verification continue until cache expiry (NFR-Avail-1).
- **kA retired too early (misconfig):** any still-live kA token fails verification — the runbook forbids retiring before `now + maxTokenTTL`; the sweep enforces it.

**Traceability:** US-05, US-62 · AC-3 (integration test `KeyRotationIT`).

---

# Part III — Organizations & lifecycle

## 6. Org onboarding — create → invite → accept → ACTIVE

**Actors:** Org Owner (creator), an invitee (future Member), yaya-identity organizations + users + credentials modules.
**Preconditions:** Invite-gated onboarding (FR-2.3). Invitations are TTL-bound, single-use. An org maps 1:1 to a yaya-agentic tenant.

### 6.1 Create org

```
Browser (creator) --POST /orgs {name, slug} [Idempotency-Key]--> admin API
[admin API] PDP-gated: create-org permission (self-service org creation is a guarded flow)
BEGIN tx
  --INSERT organizations(id, name, slug, status=ACTIVE, parent=null)--> DB
  --INSERT users(creator) if new; INSERT memberships(creator, org, ACTIVE)--> DB
  --assign system role `owner` to creator, Scope.Org(org)--> RBAC
  => Audit(event=ORG_CREATE, actor=creator, org)
  => outbox: user.created?, membership.added
COMMIT
admin API ↩ 201 { org, membership(owner) }
```

### 6.2 Invite a user

```
Browser (owner/admin) --POST /orgs/{id}/invitations {email|phone, role, scope}
                          [Idempotency-Key]--> admin API
[admin API] PDP-gated: identity.user:invite on org  (§Appendix — admin API is itself PDP-gated)
  --mint invite token; store hash; TTL, single-use--> DB
  => Mail/SMS(invite link with raw token)
  => Audit(event=INVITE_SEND, actor, org, {email/phone, role, scope})
admin API ↩ 201 { invitation }
```

### 6.3 Accept + set credential (single transaction) — AC-10

```
Invitee --GET /invitations/accept?token=<raw>--> admin API
  --hash=sha256(raw); SELECT invitation WHERE hash & unconsumed & unexpired FOR UPDATE--> DB
  [no row] → 410 Gone (expired/consumed/unknown — uniform)

Invitee --POST accept {password | enrol phone-OTP}--> Cred
BEGIN tx
  --INSERT user (if new) OR reuse existing global User (cross-org, US-25)--> DB
  --set credential (Argon2id password, or PHONE_OTP enrolment)--> Cred
  --INSERT membership(user, org, status=ACTIVE); apply invited (role, scope)--> RBAC
  --UPDATE invitation consumed_at, consumed_by--> DB
  => Audit(event=INVITE_ACCEPT + MEMBERSHIP_ACTIVE, user, org)
  => outbox: user.created, membership.added, role.assigned
COMMIT
Cred ↩ 200 → user may now log in (§1)
```

**Edge/error branches**
- **Expired / consumed / unknown invite:** `410 Gone`, uniform (no existence leak).
- **Existing global user accepting an invite from a second org:** one `User`, a new `Membership` row (US-25) — no duplicate identity.
- **Idempotent replay of accept:** the single-use token is already consumed → `410`; membership already `ACTIVE` is a no-op.

**Traceability:** US-20, US-21, US-22, US-25 · AC-10.

---

# Part IV — Authorization flows (RBAC + PDP)

## 7. Scoped RBAC assignment → token claims → coarse PEP check

**Actors:** Relying-party app (declares its catalog), Org Owner (composes a role), Org Admin (assigns it scoped), a Member, and yaya-sales as the coarse PEP.
**Preconditions:** Permission keys are `<app>.<resource>:<action>` (FR-3.1). Scoped roles ride in the token `roles` claim so a PEP can do coarse checks with **no PDP round-trip** (FR-3.5).

### 7.1 Declare the app permission catalog (manifest)

```
Sales (service token) --POST /v1/identity/apps/sales/permissions
     { permissions:[ "sales.order:refund", "sales.order:read",
                     "sales.delivery:collect-cod", "sales.stock:adjust" ] }--> app-facing API
  --UPSERT app_permissions(app='sales', key=…)--> DB
app-facing API ↩ 200
# The console can now compose roles ONLY from declared permissions.
```

### 7.2 Compose a role + assign it within a scope

```
Browser (owner) --POST /orgs/{id}/roles
     { name:"store-manager", permissions:["sales.order:refund","sales.order:read"] }--> admin API
admin API ↩ 201 { role: rol_sm }

Browser (admin) --POST /orgs/{id}/role-assignments
     { subject:{kind:USER, id:usr_8fk2}, role:rol_sm, scope:"store:st_123" }--> admin API
[admin API] PDP-gated
  --INSERT role_assignments(subject, role, scope_kind=RESOURCE, scope_type=store, scope_id=st_123)--> DB
  => Audit(event=ROLE_ASSIGN); outbox: role.assigned
admin API ↩ 201

# also: viewer on a different store
Browser (admin) --POST …/role-assignments
     { subject:usr_8fk2, role:rol_viewer, scope:"store:st_999" }--> admin API
```

### 7.3 Next token carries scoped roles → coarse PEP check

```
usr_8fk2 --logs in (§1); AS resolves scoped assignments--> RBAC
AS ↩ access token with:
  "roles":[ {"role":"store-manager","scope":"store:st_123"},
            {"role":"viewer",        "scope":"store:st_999"} ]

# yaya-sales renders store st_123's admin screen:
Sales --SDK.verify(token) via cached JWKS (no IdP round-trip)--> SDK
Sales --coarse check: does token grant `store-manager` on scope store:st_123 ?--> (read claim)
  [yes] → enable "Refund" and "Edit" controls
Sales --coarse check on store:st_999--> viewer only
  [read-only] → show data, disable mutating controls
```

Group assignment (US-33/AC-12) works identically: assign `courier` to the `downtown-fleet` **group** scoped to `store:st_123`; every member inherits courier rights **on st_123 only** and nothing on `st_999`. Scope is enforced (FR-3.4): an assignment scoped to `store:A` grants nothing on `store:B`.

**Edge/error branches**
- **Undeclared permission in a role:** the console rejects it — roles compose only from `app_permissions` (FR-3.1).
- **Revoke (US-35/AC-13):** `DELETE …/role-assignments/{id}` → next token drops the role; the PDP reflects it immediately; `role.assigned`/revoke outbox event invalidates client caches (§15).
- **Coarse check is a floor, not the ceiling:** anything conditional (amounts, ownership, time) must still go to the PDP (§8).

**Traceability:** US-30, US-31, US-32, US-33, US-34 · AC-11, AC-12.

---

## 8. Fine-grained PDP decision *(the heart)*

**Actors:** yaya-sales as the **PEP**, yaya-identity **PDP**, and the audit log.
**Preconditions:** RBAC assignments exist (§7). Policies may layer conditional PERMIT/DENY on top. **Evaluation order** (FR-4.2, design §7): (1) resolve effective scoped permissions → absent = base deny; (2) matching policies by priority — a matching **DENY short-circuits**, a matching **PERMIT** can grant what RBAC didn't; (3) no explicit permit → **default deny**; (4) audit. **Deny overrides** (FR-4.3). Every `Deny` carries two different reasons (#3).

### 8.1 Sequence

```
┌──────────────────────────┐             ┌────────────────────────────────────────┐
│ yaya-sales (PEP)          │             │ yaya-identity — PDP                     │
│ operator wants: refund    │             │                                         │
│ order o_5, amount 750     │             │                                         │
└────────────┬─────────────┘             └───────────────────┬────────────────────┘
             │ POST /pdp/decide                              │
             │ { subject:{id:usr_8fk2, roles, org, groups},  │
             │   action:"sales.order:refund",                │
             │   resource:{type:"order", id:"o_5",           │
             │             store:"st_123", amount:750,        │
             │             assignedAgent:"usr_8fk2"},         │
             │   context:{ time, ip } }                      │
             │──────────────────────────────────────────────▶│
             │                                               │ 1. effective perms for
             │                                               │    resource(store:st_123)
             │                                               │    → has sales.order:refund
             │                                               │ 2. policies matching
             │                                               │    action=sales.order:refund
             │                                               │    priority order:
             │                                               │    • DENY refund-cap
             │                                               │      cond amount>refundLimit
             │                                               │      750 > 500 → MATCH
             │                                               │    → DENY short-circuits
             │                                               │ 3. (skipped — deny wins)
             │                                               │ 4. Audit(DENY, matched=refund-cap)
             │  Deny(userSafeReason, auditReason)            │
             │◀──────────────────────────────────────────────│
             │ show ONLY userSafeReason to the operator      │
             │ log auditReason internally                    │
```

### 8.2 The two-reason `Deny` (#3 — MUST differ)

```json
{
  "decision": "Deny",
  "userSafeReason": "You can't refund orders over $500.",
  "auditReason": "policy 'refund-cap' denied: amount 750 > refundLimit 500 for role store-manager on store:st_123 (subject usr_8fk2)"
}
```

yaya-sales surfaces `userSafeReason` in the UI and writes `auditReason` only to its own logs. A build-failing test rejects any construction where `userSafeReason == auditReason` (AC-26, metric M5).

### 8.3 Ownership-policy branch (courier COD collect)

```
Sales --POST /pdp/decide
     { subject:{id:usr_courier_A, roles:[{courier, store:st_123}]},
       action:"sales.delivery:collect-cod",
       resource:{type:"delivery", id:"d_9", store:"st_123",
                 assignedAgent:"usr_courier_A"} }--> PDP
PDP  1. effective perms → courier has sales.delivery:collect-cod on store:st_123
     2. policy `cod-ownership` (PERMIT, cond resource.assignedAgent == subject.id)
        usr_courier_A == usr_courier_A → MATCH → PERMIT
     4. Audit(PERMIT, matched=cod-ownership)
PDP ↩ Permit(obligationsJson:null)

# A DIFFERENT courier attempts the same delivery:
Sales --POST /pdp/decide { subject:usr_courier_B, resource.assignedAgent:usr_courier_A … }--> PDP
PDP  policy `cod-ownership`: usr_courier_B != usr_courier_A → no PERMIT
     no other permit → DEFAULT DENY
PDP ↩ Deny(
        userSafeReason:"This delivery isn't assigned to you.",
        auditReason:"default-deny: cod-ownership PERMIT unmatched — assignedAgent usr_courier_A != subject usr_courier_B")
```

### 8.4 Default-deny branch

```
Sales --POST /pdp/decide { action:"sales.stock:adjust", subject has no matching assignment }--> PDP
PDP  1. no effective permission for the resource → base deny
     2. no PERMIT policy matches
     3. DEFAULT DENY
     4. Audit(DENY, matched=[])
PDP ↩ Deny(
        userSafeReason:"You don't have permission to do that.",
        auditReason:"default-deny: no permit for sales.stock:adjust on store:st_123 (subject usr_8fk2)")
```

### 8.5 `decide-batch` (AC-25)

```
Sales (list view of 40 orders) --POST /pdp/decide-batch
     [ {action:"sales.order:refund", resource:o_1…}, …, {…o_40} ]--> PDP
PDP --evaluate each; amortize fixed cost--> ...
PDP ↩ { decisions:[ Permit, Deny(…,…), Permit, … ] }   # order-preserving, N answers, 1 round-trip
```

Results are identical to N individual `decide` calls; the list UI enables/disables each row's controls from the batch answer.

### 8.6 `simulate` dry-run (AC-24)

```
Browser (policy author, console) --POST /orgs/{id}/policies/{pid}/simulate
     { subject, action:"sales.order:refund", resource:{amount:750}, context }--> admin API
admin API --run the SAME evaluation with the *draft* policy, persist NOTHING--> PDP
admin API ↩ { wouldBe:"Deny", userSafeReason, auditReason, matchedRules:["refund-cap"] }
# No audit_log write for a simulation? — simulate is a dry-run; it does not emit a decision row.
```

The author sees the would-be decision before saving — so they don't lock themselves out (US-45).

**Edge/error branches**
- **Condition references a missing attribute:** the sandboxed evaluator (JSON-logic, FR-4.5) treats it as unsatisfied — never throws, never does I/O; a policy can't run wild.
- **Two policies match (one PERMIT, one DENY):** DENY wins regardless of priority (deny-overrides, FR-4.3).
- **`GET /pdp/permissions?subject=&resource=`** returns effective permissions so the PEP can pre-enable/disable UI controls without a per-action decide (US-36).

**Traceability:** US-40, US-41, US-42, US-43, US-44, US-45, US-46, US-48 · AC-20, AC-21, AC-22, AC-23, AC-24, AC-25, AC-26.

---

# Part V — Delegation & machine-identity flows

## 9. Service-to-service: SERVICE_TOKEN (client-credentials, audience-bound)

**Actors:** yaya-agentic (a `SERVICE` client / service account), yaya-identity AS, yaya-sales as verifier.
**Preconditions:** yaya-agentic is registered as a `SERVICE` client with the `client_credentials` grant, least-privilege scoped roles, and `audiences` including `yaya-sales` (FR-5.1, FR-6.6). Tokens are audience-bound (FR-5.2).

```
Agent --POST /oauth2/token
     grant_type=client_credentials
     &client_id=agent&client_secret=<hashed-verified>
     &audience=yaya-sales&scope=sales.order:read--> AS
[AS] verify client secret (hashed compare)         [bad] → 401 invalid_client
[AS] `yaya-sales` in the client's allowed audiences? [no] → 400 invalid_target
AS --sign SERVICE_TOKEN (RS256)--> KeyRing
AS ↩ 200 { access_token, token_type:"Bearer", expires_in }
AS => Audit(event=TOKEN_ISSUE, grant=client_credentials, aud=yaya-sales, client=agent)
```

SERVICE_TOKEN claims:

```json
{
  "iss": "https://identity.yaya/",
  "sub": "svc_agent",
  "aud": ["yaya-sales"],
  "roles": [ { "role": "sales-reader", "scope": "org:org_acme" } ],
  "scope": "sales.order:read",
  "iat": 1751500000, "exp": 1751500900
}
```

```
Agent --GET /sales/orders/o_5  Authorization: Bearer <SERVICE_TOKEN>--> Sales (HTTP tool)
Sales --SDK.verify: sig via JWKS + iss + exp + `yaya-sales` ∈ aud--> SDK
  [aud does NOT include yaya-sales] → 401 (replay across apps rejected)
  [ok] → serve; coarse role check / PDP as needed
```

**Edge/error branches**
- **Wrong-audience token** (minted for another app) → yaya-sales `401` (FR-5.2, US-53).
- **Leaked secret:** rotate with an overlap window (`POST …/clients/{cid}/rotate-secret`) — both old + new verify during the window, zero failed calls; old secret retired (US-54, AC-32).

**Traceability:** US-50, US-51, US-53 · AC-30.

---

## 10. Shopper delegation via RFC 8693 token-exchange *(keystone, #6)*

**Actors:** Shopper (subject), yaya-agentic (delegating caller), yaya-identity AS (exchange), yaya-sales (attributes by `sub`).
**Preconditions:** The shopper holds a yaya-identity token (from §2), `aud` = the chat/login client — **not** yaya-sales. The agent must call yaya-sales *on the shopper's behalf*. **#6: no silent pass-through — the exchange is the seam.** Metric M7: zero shopper tokens reach yaya-sales without an explicit exchange (aud rebinding).

### 10.1 Sequence

```
┌──────────┐        ┌──────────────────────────┐        ┌──────────────────────────┐
│ Shopper  │        │ yaya-agentic (caller)     │        │ yaya-identity  ·  yaya-sales│
└────┬─────┘        └────────────┬──────────────┘        └───────────────┬──────────┘
     │ chats: "refund my order"  │                                       │
     │──────────────────────────▶│                                       │
     │  (shopper token STshop,    │                                       │
     │   aud=chat, sub=usr_shop)  │                                       │
     │                           │ 1. POST /oauth2/token                 │
     │                           │    grant=urn:ietf:params:oauth:       │
     │                           │          grant-type:token-exchange    │
     │                           │    subject_token=STshop               │
     │                           │    subject_token_type=…access_token   │
     │                           │    audience=yaya-sales                 │
     │                           │──────────────────────────────────────▶│ (AS)
     │                           │                                       │ 2. verify STshop;
     │                           │                                       │    rebind aud→yaya-sales;
     │                           │                                       │    PRESERVE sub=usr_shop;
     │                           │                                       │    apply delegation scope
     │                           │  200 { access_token: STsales }        │
     │                           │◀──────────────────────────────────────│
     │                           │       # Audit(TOKEN_EXCHANGE,          │
     │                           │       #   actor=agent, sub=usr_shop,   │
     │                           │       #   aud_from=chat aud_to=sales)  │
     │                           │ 3. call sales WITH STsales (not STshop)│
     │                           │──────────────────────────────────────▶│ (Sales)
     │                           │                                       │ 4. verify: yaya-sales ∈ aud;
     │                           │                                       │    attribute customer = sub
     │                           │  order/refund result                  │
     │                           │◀──────────────────────────────────────│
     │  agent replies to shopper │                                       │
     │◀──────────────────────────│                                       │
```

### 10.2 The audience rebinding (the trace M7 asserts)

```json
// subject_token STshop (before exchange)         // exchanged STsales (after)
{ "sub":"usr_shop", "aud":["chat"],  … }     →    { "sub":"usr_shop", "aud":["yaya-sales"],
                                                    "act": { "sub":"svc_agent" }, … }
```

`sub` is **preserved** (yaya-sales attributes the customer correctly); `aud` is **rebound** to `yaya-sales`; an optional `act` (actor) claim records the delegating agent. The original `STshop` is **never** forwarded to yaya-sales — the exchange step is the only path.

**Edge/error branches**
- **Agent tries to forward `STshop` directly:** yaya-sales sees `aud=["chat"]`, rejects `401` (aud mismatch, §9). The M7 trace assertion proves no silent forward occurred (AC-31).
- **Subject token expired/revoked:** the exchange fails at step 2 → the agent gets nothing to forward.
- **Delegation scope:** the exchanged token may be down-scoped (least privilege) so a shopper delegation can't do more than a shopper could.

**Traceability:** US-52 · AC-31 · metric M7 (integration test `ServiceTokenAndExchangeIT`).

---

## 11. Courier identity vs domain-approval split (two gates)

**Actors:** Delivery Courier, yaya-identity (identity gate), yaya-sales (KYC-approval domain gate).
**Preconditions:** The courier is a yaya-identity user with `courier` scoped to `store:{id}` (§5.5). **KYC-approval is a yaya-sales domain state, not an identity concept** (design §10.2 / §16 Q5). Both gates must pass to act.

```
# GATE 1 — identity (yaya-identity)
Device --device-code login (§3)--> AS
AS ↩ token { sub:usr_courier_A, roles:[{courier, store:st_123}] }
# says WHO they are and WHICH store — nothing about whether they may act yet.

# Courier attempts a domain action (accept a delivery / collect COD):
Courier --action on delivery d_9 (Bearer courier token)--> Sales
Sales --SDK.verify token (JWKS)--> ok   # identity gate passed

# GATE 2a — authorization (PDP, §8.3 ownership)
Sales --POST /pdp/decide {action:sales.delivery:collect-cod, resource.assignedAgent…}--> PDP
  [Deny] → surface userSafeReason, stop
  [Permit] → continue

# GATE 2b — DOMAIN approval (yaya-sales owns this, NOT identity)
Sales --SELECT courier_profile.kyc_status WHERE user=usr_courier_A AND store=st_123--> Sales DB
  [kyc_status != APPROVED] → 403 "Your courier account isn't approved to operate on this store yet."
  [APPROVED] → perform the action
```

Clean split: *identity says who you are and which store you serve; the app says whether your domain profile is approved to act.* yaya-identity never learns or stores KYC state; yaya-sales never mints identity.

**Edge/error branches**
- **Valid token, KYC not approved:** identity + PDP pass, but the yaya-sales domain gate blocks — a domain 403, not an identity/PDP denial.
- **Approved courier, wrong store scope in token:** the PDP/coarse check denies on scope before KYC is even consulted.

**Traceability:** Courier persona (PRD §2.5), design §10.2 / §16 Q5 · exercises US-07 (login) + US-42/AC-22 (ownership PDP).

---

## 12. Refresh rotation + reuse-detection

**Actors:** Any user's client, yaya-identity AS/tokens module.
**Preconditions:** Refresh tokens are opaque, rotating, revocable; a token **family** shares a `family_id`. Presenting a **consumed** refresh token revokes the whole family (FR-1.5, NFR-Sec-2).

```
# Normal rotation
Client --POST /oauth2/token grant=refresh_token&refresh_token=RT1--> AS
[AS] RT1 status=ACTIVE ?  [ok]
AS --consume RT1 (status=CONSUMED); mint RT2 (same family_id); mint new access token--> DB
AS ↩ 200 { access_token, refresh_token:RT2 }
AS => Audit(event=TOKEN_REFRESH, family, rotated)

# … time passes; client uses RT2 normally …

# REUSE / replay of the already-consumed RT1 (stolen token)
Attacker --POST /oauth2/token grant=refresh_token&refresh_token=RT1--> AS
[AS] RT1 status == CONSUMED → REUSE DETECTED
AS --revoke the ENTIRE family (RT1, RT2, …) → status=REVOKED--> DB
AS => Audit(event=REFRESH_REUSE_DETECTED, family, ip, ua)   # loud
AS ↩ 400 invalid_grant

# The legitimate user's next refresh with RT2 now also fails → forced re-auth (§1)
Client --POST … refresh_token=RT2--> AS ↩ 400 invalid_grant  (family revoked)
```

**Edge/error branches**
- **Legitimate user after a reuse event:** forced back through full login (§1) — the family is dead. This is the containment cost of a detected theft.
- **Refresh after logout/lock:** the family is already revoked → `400 invalid_grant`.

**Traceability:** US-08 · AC-4.

---

# Part VI — Lifecycle / ops flows

## 13. Logout + back-channel logout to relying parties

**Actors:** A user, yaya-identity sessions/tokens module, the relying-party clients that hold sessions.
**Preconditions:** Clients that need logout notification registered a back-channel logout URI.

```
Browser --POST /oauth2/logout {id_token_hint | session}--> AS
AS --resolve session; DELETE/revoke session; revoke associated refresh family--> DB
AS => back-channel: for each client with a session in this login
        POST <client back-channel logout URI> { logout_token(sub, sid) }
      # clients terminate their local session on receipt
AS => Audit(event=LOGOUT, user, clients_notified)
AS ↩ 200 (or redirect to post-logout URI)
```

**Edge/error branches**
- **A client's back-channel endpoint is down:** logout at the IdP still succeeds; the client's own session expires naturally / on next token introspection (`/oauth2/introspect`, US-55). Best-effort, not blocking.
- **Access token still within TTL after logout:** coarse verification may still pass until `exp`; high-value actions that call the PDP or `introspect` see the revocation. (Short access-token TTL bounds this — FR-1.3.)

**Traceability:** US-09 (+ US-55 introspect/revoke).

---

## 14. PII deletion — scrub PII, retain non-PII UUID in audit

**Actors:** A user (or an operator on their behalf), yaya-identity users + audit modules.
**Preconditions:** PII (emails, phones, profiles) has a retention/deletion path; audit rows retain non-PII UUIDs after a subject is scrubbed (FR-6.5, aligns agent §5.9 / yaya-sales §15).

```
Requester --POST …/users/{uid}/delete-pii (or self-service)--> admin API
[admin API] PDP-gated
BEGIN tx
  --revoke all sessions + refresh families for uid--> DB
  --UPDATE users SET primary_email='deleted-'||id||'@scrubbed.local',
                     phone=NULL, display_name='Deleted user',
                     status='DISABLED'--> DB
  --NULL/redact credential secrets for uid--> DB
  # audit_log rows already carry only UUIDs + action/decision — nothing to scrub there
  => Audit(event=PII_SCRUB, subject=uid /* the UUID is retained */)
COMMIT
admin API ↩ 204
```

The user row survives for FK integrity (memberships, role_assignments, audit all FK to it) but carries no PII; the audit trail keeps the non-PII UUID so historic decisions remain investigable (US-66).

**Edge/error branches**
- **User is the sole `owner` of an org:** deletion must first transfer ownership or the org is orphaned — guarded like the last-owner protection pattern (transfer before scrub).
- **Re-request after scrub:** idempotent no-op; already `DISABLED` + scrubbed.

**Traceability:** US-65 · AC-44.

---

## 15. Event-driven cache invalidation

**Actors:** yaya-identity (outbox), a relying-party client SDK (short-TTL decision cache).
**Preconditions:** Outbox events `user.created` / `membership.added` / `role.assigned` / `policy.changed` / `client.secret_rotated` (design §10.3 #5). Clients cache decisions ≤ 30s (NFR-Perf-5); events invalidate.

```
# A model change happens (e.g. §7.2 revoke, or a policy edit):
admin API --DELETE role-assignment / PUT policy--> DB
admin API => outbox INSERT { event:"role.assigned"|"policy.changed", org, subject/policy id }
OutboxRelay => publish event to subscribers (message bus / webhook)

# Relying-party SDK reacts:
SDK --receive role.assigned / policy.changed--> SDK
SDK --invalidate matching entries in its short-TTL decision cache--> (in-memory)
# Next decide() for that subject/policy re-hits the PDP (fresh), not the stale cache.
```

Without an event, a cached decision still self-expires within ≤ 30s (bounded staleness) — the event just tightens it to near-immediate (US-72).

**Edge/error branches**
- **Event bus lag / miss:** the ≤ 30s TTL is the safety floor — staleness is always bounded even if an event is dropped.
- **Client offline during the event:** on reconnect it relies on TTL expiry; the outbox is replayable (NFR-Obs-3) for durable consumers.

**Traceability:** US-71, US-72.

---

# Appendix A — Cross-flow error / edge matrix

| Condition | Where it surfaces | User-visible behavior | System behavior |
|---|---|---|---|
| **Access token expired** (`exp` past) | Any relying party / agent verify | 401; client refreshes (§12) or re-auths (§1) | SDK/`OidcAuthenticator` rejects on `exp`; no principal built |
| **`aud` mismatch** (token minted for another app) | yaya-sales / agent verify | 401 | Replay-across-apps rejected (FR-5.2); audited at the PEP. The fix is a token-exchange (§10), never a silent forward (#6) |
| **Tampered signature / unknown `kid`** | Verify | 401 | JWKS lookup fails; SDK refetches JWKS once then rejects (§5) |
| **MFA required but not enrolled** | Login (§1.3) | Redirected into forced TOTP enrolment; no token until complete | Org MFA policy blocks token issuance for the privileged role (FR-1.7) |
| **Wrong password / wrong OTP / wrong TOTP** | Login (§1, §2) | Uniform generic error (enumeration-safe) | Lockout counter increments; N failures → lock (E2) |
| **Rate-limited / locked out** | Any auth endpoint | 429 + `Retry-After` | Per-IP + per-identity sliding window; lockout in Redis; Redis outage → fail-open + loud audit warn (NFR-Avail-2) |
| **PDP default-deny** (no matching permit) | `POST /pdp/decide` (§8.4) | App shows `userSafeReason` only | `Deny(userSafeReason ≠ auditReason)`; decision audited (deny-by-default, FR-4.2) |
| **PDP deny-override** (a DENY policy matched) | `POST /pdp/decide` (§8.2) | `userSafeReason` only | DENY beats any permit regardless of priority (FR-4.3); two reasons, always different (#3) |
| **Key mid-rotation** | Verify during rotation | Transparent — no failure | Retiring key stays in JWKS ≥ max token TTL; make-before-break (§5, NFR-Avail-3) |
| **Refresh reuse / replay** | `/oauth2/token` refresh (§12) | Forced re-auth | Whole token family revoked; loud audit (FR-1.5) |
| **Consumed / expired invite** | Accept (§6.3) | 410 Gone (uniform) | Single-use, TTL-bound; no existence leak |
| **Admin action lacking permission** | Admin API | 403 with `userSafeReason` | Admin API is itself PDP-gated (FR-6.4, AC-43) |
| **Domain-approval (KYC) not met** | yaya-sales action (§11) | Domain 403 | Identity + PDP passed; yaya-sales owns KYC state — not an identity denial (§10.2) |

### E2 — Lockout counter
Consecutive credential failures (password/OTP/TOTP) increment a per-identity + per-IP sliding-window counter (FR-6.2). At the threshold the account/IP is locked for a TTL; further attempts return `429 Retry-After` immediately. Every auth failure and the lockout itself is audited (FR-6.1). Step-up MFA may be issued as a PDP **obligation** rather than a hard lock (§7 obligations, design §11).

---

# Appendix B — References

- [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) — AuthN §6, PDP §7, module map §8, **API surface §9**, ecosystem integration §10, security §11, data model §14, open questions §16.
- [PRD](PRD.md) — personas §2, user stories §3, functional reqs §4, acceptance criteria §7, integration test suite §7.6.
- [`../../yaya-sales/docs/design/yaya-sales-design.md`](../../yaya-sales/docs/design/yaya-sales-design.md) — first relying party (service token, shopper exchange, courier identity, KYC domain state).
- `docs/design/yaya-agentic-design.md` — §5.4 AuthN, §5.5 AuthZ (two-reason `AuthzDecision`), §5.6 profile/identity resolution, §12 trust boundaries.
- `CLAUDE.md` — non-negotiables **#3** (two-reason denials) and **#6** (no silent token forwarding); `OidcAuthenticator` + `tenant_oidc_issuers` (existing agent trust machinery this integration reuses).
- House-style template: `docs/specs/user-accounts/App-Flow.md`.
