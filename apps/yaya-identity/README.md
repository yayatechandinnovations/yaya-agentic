# yaya-identity

The shared, multi-tenant **identity provider (IdP) and authorization service** for the apps we ship — shipped under `apps/` (see [`../README.md`](../README.md)) but **not** part of the yaya-agentic core. It's a *platform service* other apps depend on for authentication and authorization, so no app re-implements users, tokens, roles, or policies.

It owns **organizations, users, groups, roles, permissions, and policies**:

- **AuthN** — orgs/users/groups, credentials (password, phone OTP, TOTP MFA; passkeys/federation vNext), and standards-based tokens (OAuth 2.0 + OpenID Connect: auth-code+PKCE, client-credentials, device-code, refresh, token-exchange). yaya-identity is the **issuer**; apps are relying parties that verify its RS256 tokens via JWKS.
- **AuthZ** — **RBAC** (roles → permissions, assigned **within a scope**: org-wide or a specific resource like one store) **+ ABAC** (conditional **policies**), evaluated by a **Policy Decision Point**: `decide(subject, action, resource, context) → Permit | Deny(userSafeReason, auditReason)` — the same two-reason denial the agent framework mandates.

It plugs into what yaya-agentic already trusts: register it as an OIDC issuer (`tenant_oidc_issuers`) and **the agent trusts yaya-identity tokens with zero new agent code**. yaya-sales is the first relying party (operators, service tokens, shopper principals, delivery-agent identities). Self-hosted on Postgres — our own Keycloak/Auth0, no paid IdP.

**Start here:** [`docs/design/yaya-identity-design.md`](docs/design/yaya-identity-design.md) — domain model, AuthN flows, the PDP, data model, integration seams, and milestones (I0–I6).

**Product specifications** (implementation-ready): [`docs/specs/`](docs/specs/) — [PRD](docs/specs/PRD.md) (personas, user stories, requirements, acceptance criteria) · [TRD](docs/specs/TRD.md) · [UI-UX](docs/specs/UI-UX.md) · [App-Flow](docs/specs/App-Flow.md) · [Backend-Data](docs/specs/Backend-Data.md).

## Build & run

A standalone Spring Boot app (Java 21, servlet/MVC — Spring Authorization Server, arriving I1, is servlet-only). It is **not** part of the yaya-agentic Maven reactor; build it from this directory.

```bash
cd apps/yaya-identity
mvn test        # dependency-free unit smoke suite (cipher, RSA keys, two-reason guard, schema-isolation lint)
mvn verify      # + Testcontainers IT: boots on its own schema, serves JWKS (needs Docker)

# run locally (needs a Postgres and a master key):
export YAYA_IDENTITY_SECRETS_ENCRYPTION_KEY="$(openssl rand -base64 32)"   # AES-256 root-of-trust key
export IDENTITY_DB_URL=jdbc:postgresql://localhost:5432/yaya_identity
mvn spring-boot:run       # → http://localhost:8090/oauth2/jwks
```

Own `yaya_identity` Postgres schema, zero cross-schema coupling (a build-time lint enforces it). The signing-key private material is AES-256-GCM encrypted at rest; a master key is **required** to provision keys.

## Status

- **I0 — Scaffold + platform: ✅ landed.** App + `pom.xml`, own schema, typed IDs, `SecretCipher` (AES-256-GCM), `KeyRing` (RS256 signing keys, bootstrap + make-before-break rotation), append-only `AuditLog` (two-reason denial guard, #3), `OutboxRelay`, `IdempotencyStore`, `/oauth2/jwks`, schema-isolation lint. 17 tests green.
- **I1 — Orgs + users + OIDC issuer: ✅ landed.**
  - **I1a ✅** OAuth2/OIDC issuer on Spring Authorization Server, signing from the platform `KeyRing`: discovery, JWKS, client-credentials token. `TokenIssuanceIT` verifies a token the `OidcAuthenticator` way (JWKS signature + `iss` + `exp`) — proves **M1/AC-2** (agent trusts it via `tenant_oidc_issuers`, zero agent code).
  - **I1b ✅** Identity core — organizations, users (`PERSON`/`SERVICE`), memberships; services emit outbox events; case-insensitive email uniqueness. `IdentityCoreIT`.
  - **I1c ✅** Credentials — password (Argon2id, one-active + resettable) and phone OTP (single-use, attempt-limited, expiring); `IdentityUserDetailsService` binds user+password to the auth-code form login (sub = user id, not email); `IdentityTokenCustomizer` stamps the `org` claim from membership. Invite-gated onboarding (`InvitationService`: invite → single-transaction accept). Full **auth-code + PKCE human login proven E2E** (`AuthCodeLoginE2EIT`: login → authorize → token, asserting `sub` + `org`). `CredentialsIT`, `HumanLoginAndClaimsIT`, `InvitationFlowIT`.
  - _Deferred polish:_ richer `userinfo` claim shaping (default returns `sub`); admin HTTP endpoints for invitations land with the admin API at I5.
- **I2 — RBAC: ✅ landed.** Permission catalog + app manifest (`PermissionCatalogService`, keys `<app>.<resource>:<action>`), roles (system `owner`/`admin`/`member` bootstrapped + custom composed from declared permissions), **scoped role assignments** (`Scope` = `Org | Resource`, subject `USER | GROUP`), groups (bulk assignment), and effective-permission resolution — a role scoped to `store:A` grants there and nowhere else; org-scope covers every resource; group grants reach every member. The token carries the scoped `roles` claim (extends `IdentityTokenCustomizer`) for coarse PEP checks. `RbacIT`.
- **I3 — ABAC + PDP: ✅ landed (engine).** Policy store (`PolicyService`, DENY policies must carry two distinct reasons at creation, #3), a **sandboxed JSON-logic condition engine** (`JsonLogicConditionEvaluator` — fixed operator set `var/==/!=/>/>=/</<=/and/or/!/in` over subject/resource/env bags, no I/O), and the **`PolicyDecisionPoint`**: `decide` / `decideBatch` / `simulate` with the design's evaluation order — RBAC base → policies (deny-overrides) → default-deny → append-only audit. `PdpIT` proves permit, DENY-overrides-with-two-reasons, default-deny, ownership PERMIT policy, batch, and simulate-as-dry-run (no audit). _Remaining:_ the `/pdp/*` + policy-admin HTTP endpoints land with I4 (they need service-token protection).
- **I4 — Clients + token-exchange: ✅ landed.** DB-backed client registration (`clients` table, `ClientService`, `DbRegisteredClientRepository` adapting rows to Spring Authorization Server, seeded via `ClientBootstrap`), service accounts (client-credentials), **audience binding** (`aud` stamped from the client's audiences — a token is valid only at the app it's for), **RFC 8693 token-exchange** (shopper token → `aud: yaya-sales`, `sub` preserved — explicit, never silent, #6), and introspect/revoke (SAS). Also **closes the I3 gap**: the `/pdp/*` (decide/decide-batch/permissions), app-permission manifest (`/v1/identity/apps/*`), and policy-admin (`/v1/identity/admin/*` incl. simulate) HTTP endpoints, all behind a token-protected resource-server chain. `IdentityHttpIT`, `TokenExchangeIT`.
- **I5 — Hardening + admin API: ✅ backend landed.** TOTP MFA (`Totp` RFC 6238, enrol/verify, secret AES-GCM encrypted) + per-org **MFA policy** (`MfaPolicyService` — privileged roles must enrol); **PII deletion** (`UserService.deleteUser` scrubs PII + revokes credentials, keeps the id for audit — AC-44); brute-force **login lockout** (`LoginAttemptService`, wired into form login); the **admin REST API** (`AdminController` — orgs/users/groups/roles/scoped-assignments/clients; `AuditController` — audit read; `KeyAdminController` — make-before-break rotation); all token-protected. `PiiDeletionIT`, `TotpMfaIT`, `LoginAttemptServiceTest`, `AdminApiIT`, `KeyRotationIT`.
  - **I5 admin console: ✅ feature-complete** — Flutter web console at [`console/`](console/), **clean architecture + BLoC (event/state)**. All UI-UX screens: auth (OAuth code+PKCE), **orgs**, **users** (members + lock/disable/delete + invite), **groups** (create + add member), **roles & scoped assignment** (load-bearing RBAC), **policy editor** (author policies incl. the DENY two-reason rule), **clients & service accounts** (register + rotate), the **policy simulator** (two-reason denial in separate panels), the **audit viewer**, and **settings** (per-org MFA toggle + make-before-break key rotation). 30 BLoC tests green, `flutter analyze` clean.
- **Onboarding surface: ✅ landed.** The public (unauthenticated) `/signup/**` chain: **invitation redemption** over HTTP (`POST /signup/invitations/accept` — the admin-issued single-use token is the authorization; console page `/accept-invite?token=…`) and **phone-verified self-registration** (`POST /signup/register → /signup/verify [→ /signup/resend]` — creates an INVITED user, proves the phone via OTP, then in one transaction activates the user as system `owner` of a brand-new org, mirroring `OwnerBootstrap`; console page `/signup`). OTP delivery is a port: **Twilio Verify** when `yaya.identity.signup.twilio.*` is set, local hashed OTP otherwise (dev echo flag). Registration is **OFF by default** (`yaya.identity.signup.enabled`) and rate-limited per phone + per IP with resend cooldowns (SMS pumping is billable fraud). `SignupFlowIT`, `InvitationAcceptHttpIT`, `TwilioVerifyOtpChannelTest`, `SignupRateLimiterTest`.
- **I6 — vNext** per the design's own Non-Goals (§2.2): OIDC federation, WebAuthn/passkeys, SCIM.
- **Backend: 83 tests green** (`mvn verify`, 24 unit + 59 Testcontainers IT). **Console: 42 BLoC tests green** (`flutter test`).

## Deploy & first access

A self-contained Docker Compose stack (Postgres + backend + console behind nginx, **same-origin** so no CORS) lives in [`deploy/`](deploy/).

```bash
cd apps/yaya-identity/deploy
./deploy.sh                 # generates all secrets into .env, then `up -d --build`
# → prints the console URL + the first-access master-user login
```

`deploy.sh` generates (into a git-ignored `.env`): the **AES master key**, the DB password, the **service-client secret**, and a **first-access owner** password. Then it builds and starts everything.

Commands: `up` (default) · `secrets` · `status` · `logs` · `credentials` · `down` · `destroy`.

### Production — subpath (`yayaagentic.smartlottery.do/identity`)

yaya-identity is served under `/identity` on the **existing agent host** — reusing its cert. The whole stack is prefix-aware: the backend runs with `server.servlet.context-path=/identity`, the console is built with `--base-href /identity/`, the console nginx proxies `/identity/*`, and the console's HTTP client prepends `/identity` to API calls. The issuer is `https://yayaagentic.smartlottery.do/identity`.

`deploy/.env` defaults to this already:
```
PUBLIC_ORIGIN=https://yayaagentic.smartlottery.do/identity
IDENTITY_BASE_PATH=/identity
```

1. `./deploy.sh` — brings up Postgres + backend + console (console published on `:8091`).
2. Add the `/identity` location to the **agent host vhost** (`yayaagentic.smartlottery.do`, the 443 server block), *before* its `location /` catch-all, then reload nginx:
   ```bash
   # contents in deploy/nginx.agent-location.conf — paste into the server{} block:
   location = /identity { return 301 /identity/; }
   location /identity/ { proxy_pass http://127.0.0.1:8091; proxy_set_header Host $host;
                         proxy_set_header X-Forwarded-Proto https; proxy_http_version 1.1; }
   # then:
   nginx -t && systemctl reload nginx
   ```

The backend trusts `X-Forwarded-Proto` (`SERVER_FORWARD_HEADERS_STRATEGY=framework`) so cookies/redirects are `https`. For local dev without a domain, set `PUBLIC_ORIGIN=http://localhost:8091` and `IDENTITY_BASE_PATH=` (empty) — everything serves at the root of `:8091`.

> The SPA uses path-URL strategy (`/callback` is a real route). Login flow: SPA `/identity/signin` → `/identity/oauth2/authorize` → backend `/identity/login` form → `/identity/callback` → token.

**First access — the master user.** Out of the box there are *no* human users (only machine clients), so a fresh install seeds a **bootstrap owner** from config: `YAYA_IDENTITY_BOOTSTRAP_OWNER_EMAIL` + `…_PASSWORD` (≥12 chars). On first boot `OwnerBootstrap` creates a root org + an ACTIVE user with the system `owner` role and logs it. Sign into the console at the public origin with that email/password (`./deploy.sh credentials` reprints it), then rotate it. It's idempotent — created once, skipped thereafter.

> The console's own sign-in page is `/signin` (a "Sign in" button that starts OAuth); the backend's credential form is `/login`. nginx keeps them distinct so both live at the same origin.

**Onboarding after the master user.** Two paths, both public pages on the console:
- **Invite a teammate** (always on): console → Users → invite → hand the invitee the link `<PUBLIC_ORIGIN>/accept-invite?token=<raw token>`. They choose a name + password and land on sign-in.
- **Self-registration** (opt-in): set `YAYA_IDENTITY_SIGNUP_ENABLED=true` plus the Twilio Verify trio (`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_VERIFY_SERVICE_SID`) in `deploy/.env`. Strangers register at `<PUBLIC_ORIGIN>/signup`, confirm the SMS code, and become owner of their own brand-new org. Without Twilio, a dev-only fallback stores a local hashed OTP (`YAYA_IDENTITY_SIGNUP_OTP_ECHO_ENABLED=true` echoes it in the UI — never in production).

## Operations (runbook)

- **Signing-key rotation** (make-before-break): `POST /v1/identity/admin/keys/rotate`. Mints a new ACTIVE key; the previous one becomes RETIRING and stays in JWKS until its tokens expire (≤ access-token TTL, 15 min), so verification never gaps. Rotate on a schedule and after any suspected key exposure.
- **PII deletion** (GDPR): `DELETE /v1/identity/admin/users/{id}` scrubs email/phone/display-name, revokes credentials, and tombstones the row (`status=DELETED`). The id and audit history survive; the email is freed for re-signup.
- **MFA enforcement**: `PUT /v1/identity/admin/orgs/{org}/mfa {"required":true}` requires TOTP for holders of `owner`/`admin` in that org. Enrol via TOTP; `MfaPolicyService.mfaEnrollmentRequired` gates action until enrolled.
- **Lockout**: after `yaya.identity.login.max-attempts` (default 5) failed logins a username is locked for `login.lockout-minutes` (default 15). In-memory per instance; swap for Redis behind `LoginAttemptService` for multi-instance.
- **Master key**: `YAYA_IDENTITY_SECRETS_ENCRYPTION_KEY` (base64 32-byte) encrypts signing private keys + TOTP seeds at rest — required to provision keys.
