# yaya-identity — Admin Console UI/UX Specification

> **Product:** yaya-identity admin console — the operator surface for managing organizations, users, groups, roles, policies, clients, and audit.
> **Companion to:** [PRD](PRD.md) · [TRD](TRD.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).
> **Implements (design):** [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) — admin API §9.3, PDP §7, scoped role assignments §5.5, clients/service accounts §5.6, policy simulator (§9.3, PRD US-45/US-61), Flutter admin-console decision §12.
> **Target app:** a Flutter web console (same stack as `yayaagenticweb/` and the yaya-sales console) — `flutter_riverpod` + `dio` + `freezed` + `json_serializable` + `go_router`.
> **Status:** Draft v1 — implementation-ready.
> **Anchors (yaya-agentic):** `docs/design/yaya-agentic-design.md` §5.5 (two-reason `AuthzDecision`), §12 (trust boundaries). CLAUDE.md non-negotiables **#3** (denials carry two reasons — user-safe vs audit).

This doc tells a Flutter engineer what to render, how state flows, what every error/empty/loading state looks like, and how **PDP-gated** control enablement works at the component level. Wireframes are ASCII (intentional — quick to iterate, no Figma dep). It traces every screen to the PRD personas (§2), user stories (US-xx), and acceptance criteria (AC-xx).

---

## 1. Principles

### 1.1 Design principles

1. **The org is the unit of context.** Every screen below the org switcher is implicitly scoped to one organization (design §5.1 — the tenant boundary). There is no "global" page once you're signed in except the org list (`/orgs`), your own profile (`/me`), and platform-operator settings (signing keys, cross-org audit).
2. **The console eats its own dog food — it is PDP-gated (PRD FR-6.4, AC-43).** yaya-identity enforces its own model on its own management surface. The UI drives every control's enablement from the operator's **effective permissions** via `GET /pdp/permissions?subject=<me>&resource=<org|resource>`. A control the operator lacks is rendered disabled with a tooltip that paraphrases *why*, not hidden — discoverability beats UI cleanliness. **The UI never relies on hiding for security**: every mutating call is independently PDP-gated server-side (defense in depth).
3. **Server-side authorization is authoritative.** The console hides/disables buttons it knows are forbidden, but a hidden button is a courtesy, not a guard. Every action also gets a server-side `decide`.
4. **Two-reason denials are shown correctly, always (non-negotiable #3, design §7, PRD FR-4.6).** When the API returns a `Deny(userSafeReason, auditReason)`, a normal operator sees **only** `userSafeReason`. `auditReason` is **never** rendered outside the Audit viewer / Policy Simulator's privileged panes, and only to a subject the PDP grants `identity.audit:read`. This is a load-bearing security rule: the console must never leak internal policy truth to a non-privileged viewer.
5. **Existence is never leaked (design §5.8 discipline, PRD FR-7.1).** A 404 or a `Deny` on a scoped fetch renders identically to "not available" — the console never reveals whether an org/user/policy exists to someone who can't see it.
6. **Deny-by-default is visible.** Because the model defaults deny (PRD FR-4.2), the console's default posture for an unknown permission is *disabled*, resolved to enabled only after `GET /pdp/permissions` confirms the grant.
7. **Loading states have shape.** Skeleton placeholders matching the eventual layout (not a centered spinner) for every list/detail screen. The single spinner is reserved for full-page transitions (login/MFA challenge).
8. **Errors are recoverable.** Every error state shows what failed, why (plain language, the paraphrased `userSafeReason`), and a clear next action.
9. **Destructive actions are typed-confirm.** Revoking an assignment, disabling a user, deleting a policy, and rotating a client secret each require an explicit confirmation; the highest-blast-radius ones (delete policy, disable user, rotate secret) use a typed-name match.
10. **Org switching is one click** and re-mounts every org-scoped provider in < 500ms p95.

### 1.2 Tech stack constraints

- `flutter_riverpod` for state; controllers are `Notifier`/`AsyncNotifier`.
- `dio` interceptor for the bearer access token (yaya-identity is an OAuth2/OIDC RP for its **own** console — see §3) + `Idempotency-Key` on mutating calls (PRD FR-7.3).
- `go_router` for routing.
- `freezed` + `json_serializable` for DTOs.
- No new dependencies beyond the shared console baseline.

---

## 2. Visual system (lightweight)

Notes for the implementer — reuse existing tokens in `lib/app/theme.dart` or its equivalent.

| Token | Usage |
|---|---|
| `primary` | `owner` role chip; primary CTAs |
| `secondary` | `admin` role chip |
| `tertiary` | `member` role chip |
| `outline` | custom-role chip default; disabled-state borders |
| `error` | Validation errors; **DENY** effect chip; deny decision; destructive-action confirmation |
| `success` | **PERMIT** effect chip; permit decision; secret-rotated toast |
| `warning` | Platform-operator banner; "you are acting on the identity root of trust" indicator; MFA-required nudge |
| `info` | Scope chips (`org` vs `resource:…`); system-role read-only badge |

### 2.1 Role / scope / effect / decision chips (reused everywhere)

Color is **never** the only signal (PRD NFR-A11y-3): every chip carries a text label and, where it encodes a boolean outcome, an icon.

```
Role chip           Scope chip                     Effect chip        Decision chip
┌───────────┐       ┌────────────────────┐         ┌──────────┐       ┌──────────────┐
│ ● owner   │       │ ⦿ org-wide         │         │ ✓ PERMIT │       │ ✓ PERMIT     │
└───────────┘       └────────────────────┘         └──────────┘       └──────────────┘
┌───────────┐       ┌────────────────────┐         ┌──────────┐       ┌──────────────┐
│ ● admin   │       │ ▣ store:st_123     │         │ ✕ DENY   │       │ ✕ DENY       │
└───────────┘       └────────────────────┘         └──────────┘       └──────────────┘
```

- **Role chip:** colored dot + role name; system roles carry a small `SYS` badge (read-only).
- **Scope chip:** `⦿ org-wide` (`Scope.Org`) vs `▣ <type>:<id>` (`Scope.Resource`) — design §5.5. The resource variant shows the resolved human label when known (e.g. "Downtown store") with the raw id as a tooltip.
- **Effect chip:** `✓ PERMIT` / `✕ DENY` for policies (design §5.7).
- **Decision chip:** the PDP outcome, icon + word, `success`/`error` — used by the Simulator and Audit viewer.

---

## 3. Console authentication model

The console is itself a registered **`PUBLIC` (PKCE) client** of yaya-identity (design §5.6, PRD FR-5.1) with `aud: yaya-identity-console`. It logs the operator in via **authorization-code + PKCE** (US-01), completes **TOTP MFA** when the org's MFA policy requires it for the operator's privileged role (US-04, FR-1.7), and thereafter carries a short-lived RS256 access token as a bearer on every admin/PDP call. The **Platform Operator** persona (PRD §2.8) additionally holds platform-level grants that unlock signing-key and cross-org-audit surfaces.

Because the admin API is PDP-gated (FR-6.4), the console's *own* first call after login is `GET /pdp/permissions?subject=<me>&resource=org:<currentOrg>` to bootstrap the enablement map (§9).

---

## 4. Information architecture & navigation

### 4.1 Primary navigation (org-scoped shell)

The admin shell renders an **org switcher** (left of nav) and a **user menu** (right). Primary nav is a left rail:

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ ⌂ yaya-identity   [ Globex ▾ ]                            ● alex@globex  ▾  ⚠   │
├──────────────┬─────────────────────────────────────────────────────────────────┤
│ Dashboard    │                                                                 │
│ Users        │   (org-scoped screen content)                                   │
│ Groups       │                                                                 │
│ Roles & Perms│                                                                 │
│ Policies     │                                                                 │
│ Clients & SA │                                                                 │
│ Audit        │                                                                 │
│ Settings     │                                                                 │
├──────────────┤                                                                 │
│ ⚙ Platform   │  ← only when me.platformOperator === true (warning-tinted)      │
└──────────────┴─────────────────────────────────────────────────────────────────┘
```

- `[ Globex ▾ ]` — org switcher chip (§10.1). Lists the operator's orgs + "Create organization".
- `● alex@globex ▾` — user menu: My profile / MFA enrolment, Sign out.
- `⚠` — a persistent, low-key warning affordance reminding the operator they are administering the **root of trust** (design §11). Present for all operators; amplified for platform operators.
- Each rail item is **enabled per the PDP map** (§9): e.g. "Policies" is disabled with a tooltip when the operator lacks `identity.policy:read` in this org.

### 4.2 Screen inventory

| # | Screen | Route | Min permission (PDP) | Persona | US / AC |
|---|---|---|---|---|---|
| I-01 | Login + MFA | `/login`, `/mfa`, `/callback` | none | all humans | US-01, US-04, US-64 / AC-1, AC-5 |
| I-02 | Org list / create / switch | `/orgs` | any membership | Owner, Operator | US-20 / AC-10 |
| I-03 | Org dashboard | `/orgs/:org` | `identity.org:read` | Owner, Admin | US-20 |
| I-04 | Users: list | `/orgs/:org/users` | `identity.user:read` | Admin | US-21..26 |
| I-05 | User: invite dialog | (modal over I-04) | `identity.user:invite` | Owner, Admin | US-21 / AC-10 |
| I-06 | User: detail | `/orgs/:org/users/:uid` | `identity.user:read` | Admin | US-22..26 / AC-14 |
| I-07 | Groups: list + membership editor | `/orgs/:org/groups[/:gid]` | `identity.group:read` | Admin | US-24 / AC-12 |
| I-08 | Roles & Permissions | `/orgs/:org/roles[/:rid]` | `identity.role:read` | Owner | US-30, US-31 |
| I-09 | **Role assignment** | `/orgs/:org/assignments` (+ inline in I-06/I-07) | `identity.assignment:read` | Admin | US-32, US-33, US-34, US-35 / AC-11..13 |
| I-10 | Policies: list + editor | `/orgs/:org/policies[/:pid]` | `identity.policy:read` | Owner | US-41, US-42, US-47 / AC-21, AC-22 |
| I-11 | **Policy Simulator** | `/orgs/:org/policies/simulate` (+ inline in I-10) | `identity.policy:simulate` | Owner | US-45, US-61 / AC-24 |
| I-12 | Clients & Service Accounts | `/orgs/:org/clients[/:cid]` | `identity.client:read` | Owner | US-50, US-54 / AC-32 |
| I-13 | Audit viewer | `/orgs/:org/audit` | `identity.audit:read` | Operator, Admin | US-66 / AC-40 |
| I-14 | Settings (MFA policy) | `/orgs/:org/settings` | `identity.org:manage` | Owner | US-64 |
| I-15 | Platform: signing keys | `/platform/keys` | platform + `identity.key:manage` | Operator | US-62 / AC-3 |

### 4.3 Navigation map

```
/login ──(auth-code+PKCE)──▶ /callback ──(MFA required?)──▶ /mfa ──▶ /orgs
                                                                       │
                                                                       ▼  (pick / create org)
/orgs/:org  (Dashboard) ───┬── Users ──▶ /users ──▶ /users/:uid ──(inline)──▶ Assignments
                           ├── Groups ─▶ /groups ─▶ /groups/:gid ─(inline)──▶ Assignments
                           ├── Roles & Perms ─▶ /roles ─▶ /roles/:rid
                           ├── Policies ─▶ /policies ─▶ /policies/:pid ──▶ Simulate (dry-run)
                           ├── Clients & SA ─▶ /clients ─▶ /clients/:cid (rotate-secret)
                           ├── Audit ─▶ /audit
                           └── Settings ─▶ /settings (MFA policy)
me menu ─▶ /me (profile + MFA enrolment)
platform (operator only) ─▶ /platform/keys
```

---

## 5. Screen-by-screen specs

Each screen: **purpose · key components · states (empty/loading/error) · primary actions · US/AC satisfied · API called (design §9.3 / §9.2)**.

### 5.1 I-01 — Login + MFA

**Purpose.** Authenticate a human operator via authorization-code + PKCE, then step up to TOTP MFA when the org policy requires it for the operator's privileged role. Enumeration-safe throughout (PRD FR-1.9, NFR-Sec-6). Satisfies **US-01, US-04, US-64; AC-1, AC-5**.

**Login (`/login`)**

```
┌────────────────────────────────────────────────────────────────────────┐
│                          yaya-identity                                  │
│                 ┌────────────────────────────────────┐                  │
│                 │           Sign in                  │                  │
│                 │  Email                             │                  │
│                 │  ┌──────────────────────────────┐  │                  │
│                 │  │ alex@globex.com              │  │                  │
│                 │  └──────────────────────────────┘  │                  │
│                 │  Password                          │                  │
│                 │  ┌──────────────────────────────┐  │                  │
│                 │  │ ••••••••••••                 │  │                  │
│                 │  └──────────────────────────────┘  │                  │
│                 │  [           Sign in           ]   │                  │
│                 │  Forgot password? → Reset          │                  │
│                 └────────────────────────────────────┘                  │
└────────────────────────────────────────────────────────────────────────┘
```

- The console kicks off `GET /oauth2/authorize` (PKCE) and lands on the hosted login; on success it returns to `/callback` and swaps the code at `POST /oauth2/token`. (The email/password card above is the hosted login surface; if the console reuses the IdP's own login page, this screen is the redirect landing.)
- **Generic failure** (unknown email, wrong password, locked — never distinguished): "Invalid email or password." (FR-1.9).
- **Lockout** (US-63, FR-6.2): red banner "Too many attempts. Try again in **{n}s**." with the CTA disabled for `Retry-After` seconds (429 carries it).

**MFA challenge (`/mfa`)** — shown when the org MFA policy requires TOTP for the operator's role (FR-1.7, AC-5):

```
┌────────────────────────────────────────────────────────────────────────┐
│                 ┌────────────────────────────────────┐                  │
│                 │   Two-factor authentication        │                  │
│                 │   Enter the 6-digit code from your │                  │
│                 │   authenticator app.               │                  │
│                 │   ┌──┐┌──┐┌──┐ ┌──┐┌──┐┌──┐        │                  │
│                 │   │0 ││0 ││0 │ │0 ││0 ││0 │        │                  │
│                 │   └──┘└──┘└──┘ └──┘└──┘└──┘        │                  │
│                 │   [        Verify        ]         │                  │
│                 │   Lost your device? → Recovery     │                  │
│                 └────────────────────────────────────┘                  │
└────────────────────────────────────────────────────────────────────────┘
```

**MFA enrolment** (from `/me`, or forced pre-first-privileged-action): shows the TOTP QR + secret, a confirm-code field, and one-time recovery codes revealed **once** (see §10.5 one-time reveal).

- **States:** Idle (verify disabled until 6 digits); Submitting (spinner, "Verifying…"); Error ("That code didn't match. Try again." — announced via `Semantics` per NFR-A11y-2); Rate-limited (countdown).
- **APIs:** `GET /oauth2/authorize`, `POST /oauth2/token` (code + MFA), `GET /oauth2/userinfo` (bootstrap `me`).

### 5.2 I-02 — Org list / create / switch

**Purpose.** The one un-scoped landing after auth. List the operator's orgs; create a new one (guarded self-service, FR-2.3); pick one to enter. Satisfies **US-20; AC-10**.

```
┌────────────────────────────────────────────────────────────────────────┐
│  yaya-identity                                    ● alex@globex ▾        │
│  Your organizations                              [ + Create organization]│
│                                                                          │
│  ┌───────────────────────────┐  ┌───────────────────────────┐           │
│  │ Globex Industries         │  │ Acme Retail Co            │           │
│  │ globex                     │  │ acme                      │           │
│  │ ● owner · 42 users         │  │ ● admin · 7 users         │           │
│  │ 6 roles · 12 policies      │  │ 3 roles · 2 policies      │           │
│  │ [ Open ]                   │  │ [ Open ]                  │           │
│  └───────────────────────────┘  └───────────────────────────┘           │
│                                                                          │
│  ⚙ Platform operator: [ View all organizations ]  ← platform only, audited│
└────────────────────────────────────────────────────────────────────────┘
```

- **Create** opens a dialog (name + slug); creator becomes `owner` (design §5.1, FR-2.1); maps 1:1 to an agent tenant. `POST /orgs`.
- **States:** Empty ("You don't belong to any organization yet." + create CTA, for the rare post-onboarding case); Loading (2 skeleton cards); Platform-operator ("View all organizations" is audited each visit).
- **APIs:** `GET /orgs`, `POST /orgs`.

### 5.3 I-03 — Org dashboard

**Purpose.** At-a-glance org health for owner/admin: member count by status, groups, recent audit tail, MFA-policy state, outstanding invitations. Satisfies **US-20**.

```
┌────────────────────────────────────────────────────────────────────────┐
│  Globex Industries  ● owner                                             │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│  │ 42 users     │ │ 5 groups     │ │ 6 roles      │ │ 12 policies  │    │
│  │ 3 invited    │ │              │ │ 3 custom     │ │ 4 DENY       │    │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘    │
│                                                                          │
│  Recent activity                                    [ View full audit → ]│
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ 2m ago   ROLE_ASSIGNED   alex   → sam  store-manager @ store:st_1 │   │
│  │ 14m ago  POLICY_CHANGED  alex   refund-cap (priority 10)          │   │
│  │ 1h ago   DECISION ✕DENY  sam    sales.order:refund @ order:o_88   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│  ⚠ MFA policy: required for owner/admin — 1 admin not yet enrolled.      │
└────────────────────────────────────────────────────────────────────────┘
```

- The recent-activity tail shows decision chips; the **audit reason is not shown here** — only the user-safe summary and non-sensitive metadata (§10.4).
- **APIs:** `GET /orgs/:org`, `GET /orgs/:org/audit?limit=…`.

### 5.4 I-04 / I-05 / I-06 — Users: list, invite, detail

**Purpose.** Manage the org's human and service users: list & filter, invite by email/phone (invite-gated, FR-2.3), inspect a user, reset credentials, lock/disable, and view memberships & scoped assignments. Satisfies **US-21..US-26; AC-10, AC-14**.

**Users list (I-04)**

```
┌────────────────────────────────────────────────────────────────────────┐
│  Users (42)     Status:[ All ▾ ] Kind:[ All ▾ ]  🔍[        ] [+ Invite]│
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ Name            Email/Phone         Kind     Status     Roles    │   │
│  ├──────────────────────────────────────────────────────────────────┤   │
│  │ Sam Wong        sam@globex.com      PERSON   ● ACTIVE   2 scoped │   │
│  │ Tara Cole       tara@globex.com     PERSON   ○ INVITED  —        │   │
│  │ courier-bot     +1•••••23           PERSON   ● ACTIVE   courier  │   │
│  │ agent-svc       (service)           SERVICE  ● ACTIVE   1 scoped │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

- Status pills: `● ACTIVE`, `○ INVITED`, `▲ LOCKED`, `◌ DISABLED` (FR-2.2) — icon + word, never color alone.
- **SERVICE** kind is visually distinct (machine icon) — these are service accounts (design §5.6) surfaced read-mostly here; full client config lives in I-12.
- **[+ Invite]** disabled (with tooltip) unless PDP grants `identity.user:invite`.

**Invite dialog (I-05)**

```
                ┌────────────────────────────────────┐
                │   Invite a user to Globex          │
                │   Email or phone                   │
                │   ┌──────────────────────────────┐ │
                │   │ new.hire@globex.com          │ │
                │   └──────────────────────────────┘ │
                │   Initial role (optional)          │
                │   [ member ▾ ]  Scope [ org-wide ▾]│
                │   The invitation expires in 7 days.│
                │   [ Cancel ]     [ Send invitation ]│
                └────────────────────────────────────┘
```

- TTL-bound, single-use (FR-2.3). After send: toast "Invitation sent to new.hire@globex.com."
- **API:** `POST /orgs/:org/invitations`.

**User detail (I-06)**

```
┌────────────────────────────────────────────────────────────────────────┐
│  ← Users   Sam Wong   sam@globex.com   ● ACTIVE   PERSON                 │
│  ┌─ Actions ─────────────────────────────────────────────────────────┐  │
│  │ [ Reset credential ]  [ Lock ]  [ Disable ]                        │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│  Memberships:  Globex (active) · Acme (active)                          │
│                                                                          │
│  Scoped role assignments (2)                    [ + Assign role ]        │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ Role           Scope                Granted     Actions          │   │
│  ├──────────────────────────────────────────────────────────────────┤   │
│  │ ● store-manager ▣ store:st_123      2026-06-01  [ Revoke ]       │   │
│  │ ● viewer        ▣ store:st_999      2026-06-04  [ Revoke ]       │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│  [ Preview effective permissions → ]                                     │
└────────────────────────────────────────────────────────────────────────┘
```

- **Reset credential** (US-26): enumeration-safe path; on confirm, invalidates all sessions (FR-1.9). `POST …/users/:uid/reset-credential`.
- **Lock / Disable** (US-23, AC-14): each opens a typed-confirm dialog; on success, existing sessions are revoked (FR-2.4). `POST …/users/:uid/lock|disable`.
- **Assign role** and **Revoke** launch the assignment flow (I-09) pre-bound to this user (subject = user).
- **Preview effective permissions** → the effective-permissions panel (§5.9), the same one the assignment screen shows.
- **APIs:** `GET/PUT /orgs/:org/users/:uid`, `POST …/users/:uid/{lock|disable|reset-credential}`, `GET /pdp/permissions?subject=user:<uid>&resource=…`.

### 5.5 I-07 — Groups: list + membership editor

**Purpose.** Aggregate users into groups so a role can be assigned in bulk; a role assigned to a group applies to all members (design §5.3, FR-2.5). Satisfies **US-24; AC-12**.

```
┌────────────────────────────────────────────────────────────────────────┐
│  Groups (5)                                          [ + New group ]     │
│  downtown-fleet · warehouse-staff · finance · ops · contractors         │
│  ────────────────────────────────────────────────────────────────────   │
│  downtown-fleet — 8 members            [ + Assign role to group ]        │
│  ┌── Members ─────────────────┐  ┌── Add member ──────────────────────┐  │
│  │ ☑ Sam Wong                 │  │ 🔍 search users…                   │  │
│  │ ☑ Nia Bello                │  │ ┌────────────────────────────────┐ │  │
│  │ ☑ courier-bot              │  │ │ + Rob Diaz                     │ │  │
│  │ …                          │  │ │ + Amy Fox                      │ │  │
│  └────────────────────────────┘  └────────────────────────────────────┘  │
│  Assignments on this group:  ● courier ▣ store:st_123                    │
└────────────────────────────────────────────────────────────────────────┘
```

- The membership editor is a dual-pane transfer list; checkbox toggles persist via `PUT …/groups/:gid/members`.
- **Assign role to group** launches I-09 with subject = group; the resulting assignment (e.g. `courier @ store:st_123`) inherits to every current member there only (AC-12).
- **APIs:** `GET/POST /orgs/:org/groups[/:gid]`, `PUT …/groups/:gid/members`.

### 5.6 I-08 — Roles & Permissions

**Purpose.** Compose custom roles from **declared permissions** grouped by app namespace `<app>.<resource>:<action>` (design §5.4, FR-3.1/3.2). System roles (`owner`/`admin`/`member`) are read-only. Satisfies **US-30, US-31**.

```
┌────────────────────────────────────────────────────────────────────────┐
│  Roles (6)                                           [ + New role ]      │
│  ┌── Roles ────────────┐  ┌── Role: store-manager ────────────────────┐ │
│  │ owner       SYS 🔒  │  │ Name  [ store-manager            ]        │ │
│  │ admin       SYS 🔒  │  │ Permissions (8 selected)                  │ │
│  │ member      SYS 🔒  │  │                                           │ │
│  │ store-manager       │  │ ▾ sales  (sales.*)                        │ │
│  │ courier             │  │   ☑ sales.order:read                      │ │
│  │ auditor             │  │   ☑ sales.order:refund                    │ │
│  └─────────────────────┘  │   ☐ sales.stock:adjust                    │ │
│                           │ ▾ identity  (identity.*)                  │ │
│                           │   ☐ identity.user:invite                  │ │
│                           │   ☑ identity.audit:read                   │ │
│                           │ [ Cancel ]              [ Save role ]      │ │
│                           └───────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

- The permission tree is populated from each app's **declared catalog** (`GET /v1/identity/apps/{app}/permissions`, design §9.4) — the console composes roles only from declared permissions (FR-3.1). Undeclared keys can't be typed in.
- System roles: the editor shows a `🔒 System role — read-only` banner and disables save.
- **States:** Loading (permission tree skeleton); Empty per app group ("This app has declared no permissions yet."); Error (catalog fetch failed → retry).
- **APIs:** `GET/POST/PUT /orgs/:org/roles[/:rid]`, `GET /v1/identity/apps/{app}/permissions`.

### 5.7 I-09 — Role assignment (the load-bearing RBAC screen)

**Purpose.** Assign a role to a **user or group** *within a scope* — org-wide (`Scope.Org`) or a specific resource (`Scope.Resource`, e.g. `store:st_123`) — and show an **effective-permissions preview** before committing (design §5.5, FR-3.3/3.4/3.5). This is where "manager of store A but not B" becomes native. Satisfies **US-32, US-33, US-34, US-35; AC-11, AC-12, AC-13**.

```
┌────────────────────────────────────────────────────────────────────────┐
│  Assign a role                                                          │
│                                                                          │
│  1. Subject                                                              │
│     ( ● ) User   ( ○ ) Group                                            │
│     🔍 [ Sam Wong (sam@globex.com)                           ▾ ]        │
│                                                                          │
│  2. Role                                                                 │
│     [ store-manager ▾ ]   ← composed from declared permissions (I-08)   │
│                                                                          │
│  3. Scope                                                                │
│     ( ○ ) Org-wide  — grants on every resource in Globex               │
│     ( ● ) A specific resource                                           │
│         Resource type [ store ▾ ]                                       │
│         Resource       🔍 [ Downtown store (store:st_123)         ▾ ]   │
│         Selected scope:  ▣ store:st_123                                  │
│                                                                          │
│  ┌── Effective-permissions preview (for the selected scope) ─────────┐  │
│  │ On ▣ store:st_123, Sam WILL have:                                 │  │
│  │   ✓ sales.order:read      ✓ sales.order:refund                    │  │
│  │   ✓ sales.delivery:assign ✓ identity.audit:read                   │  │
│  │ Newly granted by THIS assignment are ● highlighted.               │  │
│  │ (Union of all assignments covering this resource — direct+group.) │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  [ Cancel ]                                   [ Assign store-manager ]   │
└────────────────────────────────────────────────────────────────────────┘
```

**Component detail.**

- **Subject toggle** switches the picker between users and groups (US-32 vs US-33). Selecting a group makes the preview say "every member of downtown-fleet WILL have…".
- **Scope selector** is the crux: a segmented control between `⦿ Org-wide` and `▣ A specific resource`. Choosing the resource variant reveals a **resource-type dropdown** (types are enumerated per the org's registered resource kinds, e.g. `store`) and a **resource picker** (typeahead resolving human labels → ids). The chosen scope renders as a **scope chip** (`▣ store:st_123`) so it's unmistakable before commit.
- **Effective-permissions preview** calls `GET /pdp/permissions?subject=<user|group>&resource=<scope>` and shows the *union* the subject would hold on that resource after this assignment, with the deltas this assignment adds highlighted. This directly de-risks over-granting and is the same read a relying party uses to enable/disable its own controls (US-36, FR-3.4).
- **Revoke** (US-35, AC-13): from the user/group detail tables, `[ Revoke ]` opens a typed-confirm ("Revoke `store-manager @ store:st_123` from Sam? Access ends at the next token and immediately at the PDP."). `DELETE …/role-assignments/:id`.

- **States:** Idle (Assign disabled until subject + role + scope chosen); Preview-loading (preview pane skeleton while `/pdp/permissions` resolves); Empty resource list ("No `store` resources are registered for this org yet."); Error (assignment 409/deny → inline `userSafeReason`).
- **APIs:** `POST /orgs/:org/role-assignments {subject, role, scope}`, `DELETE …/role-assignments/:id`, `GET /pdp/permissions?subject=&resource=`.

### 5.8 I-10 — Policies: list + editor

**Purpose.** Author ABAC policies: `effect` (PERMIT/DENY), `target` (actions/resource-types), a **condition** (a safe JSON-logic expression over subject/resource/env attribute bags), and `priority`. Deny overrides (design §5.7, §7; FR-4.3/4.4/4.5). Satisfies **US-41, US-42, US-47; AC-21, AC-22**.

**Policy list**

```
┌────────────────────────────────────────────────────────────────────────┐
│  Policies (12)   Effect:[ All ▾ ]  🔍[            ]     [ + New policy ] │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ Prio  Effect     Name           Target                    State  │   │
│  ├──────────────────────────────────────────────────────────────────┤   │
│  │ 10    ✕ DENY     refund-cap     sales.order:refund         ● on  │   │
│  │ 20    ✓ PERMIT   cod-ownership  sales.delivery:collect-cod ● on  │   │
│  │ 50    ✕ DENY     after-hours    sales.stock:*              ○ off │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│  ⓘ Deny overrides any permit. Lower priority number = evaluated first.  │
└────────────────────────────────────────────────────────────────────────┘
```

**Policy editor**

```
┌────────────────────────────────────────────────────────────────────────┐
│  Policy: refund-cap                                                     │
│  Name     [ refund-cap                     ]   Priority [ 10 ]  ● Active │
│  Effect   ( ✕ ) DENY   ( ○ ) PERMIT                                     │
│                                                                          │
│  Target                                                                  │
│    Actions        [ sales.order:refund  ×] [ + add ]                    │
│    Resource types [ order ×]                        [ + add ]           │
│                                                                          │
│  Condition (JSON-logic over subject / resource / env)                   │
│  ┌── Condition builder ──────────────────────────────────────────────┐  │
│  │  WHEN  [ ALL ▾ ] of:                                              │  │
│  │  ┌───────────────────────────────────────────────────────────┐   │  │
│  │  │ [ resource.amount ▾ ] [ > ▾ ] [ subject.refundLimit ▾ ]   │✕│  │  │
│  │  └───────────────────────────────────────────────────────────┘   │  │
│  │  [ + add condition row ]        [ ⟳ switch to raw JSON ]          │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  [ Cancel ]        [ ▶ Simulate before saving ]        [ Save policy ]  │
└────────────────────────────────────────────────────────────────────────┘
```

**Condition builder component detail.**

- A **combinator** (`ALL` / `ANY` / `NONE` → JSON-logic `and`/`or`/`!`) wraps a list of **attribute/operator/value rows**.
- Each row is three cells: **left operand** (a typeahead over the three attribute bags — `subject.*`, `resource.*`, `env.*`, e.g. `resource.amount`, `subject.refundLimit`, `env.time`), an **operator** (`==`, `!=`, `>`, `>=`, `<`, `<=`, `in`, `contains`), and a **right operand** (a literal, or another attribute reference). Rows are addable/removable; nesting a combinator inside a row builds compound conditions.
- A **"switch to raw JSON" toggle** exposes the underlying JSON-logic for power users; edits round-trip back to the builder when structurally valid, else the builder shows "This expression is too complex to edit visually — edit as JSON." The expression is **sandboxed, side-effect-free** (FR-4.5, US-47) — the builder only offers safe operators; no I/O, no function calls.
- **Save is gated on a valid, parseable condition.** A **[ ▶ Simulate before saving ]** button is prominent (US-45, so an author can't lock themselves out) — it opens I-11 pre-seeded with this draft policy (dry-run, no persistence).
- **Delete policy** (from the list overflow menu) → typed-confirm ("Type the policy name to delete: `refund-cap`"). Deleting or toggling a policy emits `policy.changed` (relying-party caches invalidate, US-72).

- **States:** Loading (list skeleton / editor field skeletons); Empty ("No policies yet — RBAC alone governs this org. Add a policy to layer conditional rules."); Error (invalid condition inline; save 409/deny → `userSafeReason`).
- **APIs:** `GET/POST/PUT /orgs/:org/policies[/:pid]`, `POST …/policies/:pid/simulate`.

### 5.9 I-11 — Policy Simulator (highlight screen)

**Purpose.** A **dry-run** decision form: enter a hypothetical `(subject, action, resource, context)` and get the **would-be** PDP decision — including, for a denial, the **two reasons displayed side by side** (user-safe vs audit). Persists nothing (design §9.3, FR-4.7; US-45, US-61; AC-24). This is the screen that lets an owner test a change before it goes live and never lock themselves out.

```
┌────────────────────────────────────────────────────────────────────────┐
│  Policy simulator                          Dry-run — nothing is saved ✓ │
│  ┌── Request ──────────────────────────────────────────────────────────┐│
│  │ Subject   🔍 [ Sam Wong (user:u_42) ▾ ]                             ││
│  │ Action       [ sales.order:refund ▾ ]                              ││
│  │ Resource  Type [ order ▾ ]  🔍 [ Order #o_88 (order:o_88) ▾ ]      ││
│  │                                                                     ││
│  │ Context attributes (env / resource overrides)                       ││
│  │  [ resource.amount ] [ = ] [ 750        ] ✕                         ││
│  │  [ env.time        ] [ = ] [ 2026-07-08T22:10Z ] ✕                 ││
│  │  [ + add attribute ]                                                ││
│  │                                                                     ││
│  │ Draft policy under test: refund-cap (unsaved)   [ using draft ✓ ]   ││
│  │ [               ▶ Simulate decision               ]                 ││
│  └─────────────────────────────────────────────────────────────────────┘│
│                                                                          │
│  ┌── Decision ─────────────────────────────────────────────────────────┐│
│  │                        ┌──────────┐                                  ││
│  │                        │ ✕ DENY   │                                  ││
│  │                        └──────────┘                                  ││
│  │  ┌── User-safe reason ───────────┐ ┌── Audit reason 🔒 ───────────┐  ││
│  │  │ "You can't refund orders over │ │ policy `refund-cap` denied:   │  ││
│  │  │  $500."                       │ │ amount 750 > limit 500 for    │  ││
│  │  │ (shown to the end user)       │ │ role store-manager @ store:.. │  ││
│  │  └───────────────────────────────┘ └───────────────────────────────┘  ││
│  │  Matched rule: DENY refund-cap (priority 10) — deny overrides.       ││
│  │  Evaluation trace:  RBAC permit(sales.order:refund) → DENY wins.     ││
│  └─────────────────────────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────────────────────────┘
```

**Load-bearing behaviors.**

- **Two-reason side-by-side (non-negotiable #3, FR-4.6, AC-24).** On a `Deny`, the **user-safe reason** and the **audit reason** render in two clearly-labelled panels. The audit panel carries a `🔒` badge and is **only shown when the simulating operator holds `identity.audit:read`** (PDP-checked). A non-privileged operator sees the user-safe panel and, in place of the audit panel, "Audit reason hidden — requires audit access." The console guarantees the two strings differ (they always do server-side); if it ever received `userSafeReason == auditReason` it surfaces a build/QA error, mirroring the build-failing backend test (AC-26).
- **Permit** renders the `✓ PERMIT` chip, any obligations (e.g. "step-up MFA", "log") from `obligationsJson`, and the matched permit source (RBAC vs a PERMIT policy).
- **Dry-run guarantee** is stated in the header ("Dry-run — nothing is saved") and reinforced by calling `POST …/policies/:pid/simulate` (never `decide`), which the design guarantees persists nothing (AC-24).
- **Seeded from the editor:** when reached via I-10's "Simulate before saving", the draft (unsaved) policy is included in the evaluated set (badge "using draft"), so the author sees the change's effect pre-commit.
- **Evaluation trace** (read-only) shows the ordering that produced the outcome (default-deny vs RBAC permit vs policy deny/permit, deny-overrides) — the transparency that makes the simulator trustworthy.

- **States:** Idle (Simulate disabled until subject+action+resource set); Running (decision pane skeleton); Result (decision chip + reasons/trace); Error ("Couldn't simulate — check the request and retry.").
- **API:** `POST /orgs/:org/policies/:pid/simulate` (or an org-level simulate for ad-hoc requests not tied to a saved policy).

### 5.10 I-12 — Clients & Service Accounts

**Purpose.** Register relying-party apps and service accounts (type `PUBLIC` / `CONFIDENTIAL` / `SERVICE`), configure grants, redirect URIs, **audiences**, and allowed scopes; rotate the secret with a **one-time reveal**. A `SERVICE` client's client-credentials token *is* the `SERVICE_TOKEN` relying parties expect (design §5.6, FR-5.1..5.4). Satisfies **US-50, US-54; AC-32**.

**Register / edit client**

```
┌────────────────────────────────────────────────────────────────────────┐
│  Register client                                                        │
│  Name  [ yaya-agentic-svc                                     ]         │
│  Type  ( ○ ) PUBLIC (PKCE, no secret)                                  │
│        ( ○ ) CONFIDENTIAL (has secret)                                 │
│        ( ● ) SERVICE (client-credentials → SERVICE_TOKEN)             │
│                                                                          │
│  Grants        [☑ client_credentials] [☐ authorization_code] [☐ refresh]│
│                [☐ device_code] [☐ token_exchange]                       │
│  Redirect URIs [ https://console.example/callback  ×] [ + add ]        │
│                (shown only for PUBLIC / CONFIDENTIAL)                    │
│  Audiences     [ yaya-sales ×] [ + add ]   ← binds tokens to these aud  │
│  Allowed scopes[ ▣ store:st_123 ×] [ ⦿ org-wide ×] [ + add scope ]     │
│                                                                          │
│  [ Cancel ]                                          [ Register client ] │
└────────────────────────────────────────────────────────────────────────┘
```

- **Type toggle** reshapes the form: `PUBLIC` hides the secret entirely (PKCE), `CONFIDENTIAL`/`SERVICE` will mint a secret, redirect URIs hidden for `SERVICE`. Audiences are always present and central — a token is rejected at any app it isn't `aud`-bound to (FR-5.2). Allowed scopes reuse the **scope chip** component (org-wide / resource).

**One-time secret reveal** (on register of a `CONFIDENTIAL`/`SERVICE` client, and on **rotate-secret**, US-54):

```
                ┌──────────────────────────────────────────┐
                │  ✓ Client secret generated                │
                │                                            │
                │  Copy it now — we won't show it again.     │
                │  ┌──────────────────────────────────────┐  │
                │  │ cs_live_9f3a…  (hidden)   [ 👁 ] [ ⧉ ]│  │
                │  └──────────────────────────────────────┘  │
                │  ⚠ Stored hashed. This is your only chance │
                │    to copy the plaintext secret.           │
                │  Rotation overlap: old secret valid 24h.   │
                │  [ I've copied it — done ]                 │
                └──────────────────────────────────────────┘
```

- The plaintext secret is shown **exactly once**; the value is masked by default, revealed with 👁, copied with ⧉; the dialog cannot be dismissed until "I've copied it — done". Thereafter only a fingerprint/last-4 is ever displayed (secrets are stored hashed, FR-5.4). See §10.5.
- **Rotate-secret** (US-54, AC-32) states the overlap window ("old secret stays valid for 24h — zero-downtime") so the operator knows to redeploy consumers before it retires. `POST …/clients/:cid/rotate-secret`.
- **States:** Loading (list/detail skeleton); Empty ("No clients registered. Register your first relying party or service account."); Error (register/rotate deny → `userSafeReason`).
- **APIs:** `GET/POST/PUT /orgs/:org/clients[/:cid]`, `POST …/clients/:cid/rotate-secret`.

### 5.11 I-13 — Audit viewer

**Purpose.** A filterable, append-only view of every authentication event and authorization decision — subject, action, resource, decision, reasons, time — exportable. This is the **only** surface where `auditReason` is shown, and only to a subject with `identity.audit:read` (design §11, FR-6.1; US-66; AC-40). Satisfies **US-66; AC-40**.

```
┌────────────────────────────────────────────────────────────────────────┐
│  Audit                                                    [ ⭳ Export ]  │
│  Subject:[ Any ▾ ] Action:[ Any ▾ ] Decision:[ All ▾ ]                  │
│  From:[ 2026-07-01 ] To:[ 2026-07-08 ]                     [ Apply ]    │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ When    Event      Subject  Action              Resource   Dec.  │   │
│  ├──────────────────────────────────────────────────────────────────┤   │
│  │ 22:10   DECISION    u_42    sales.order:refund   order:o_88 ✕DENY │▸  │
│  │ 21:03   TOKEN_ISSUE u_42    —                    aud:sales  —     │   │
│  │ 20:55   ROLE_ASSIGN alex    identity.assignment  user:u_42 ✓      │   │
│  │ 20:41   LOGIN       u_42    —                    —          ✓     │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│  Row expander (▸) for a DENY:                                            │
│  ┌── DECISION detail ──────────────────────────────────────────────┐    │
│  │ User-safe: "You can't refund orders over $500."                 │    │
│  │ Audit 🔒 : policy refund-cap: amount 750 > limit 500            │    │
│  │ Matched: DENY refund-cap (prio 10) · deny-overrides             │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│  [ < Prev ]  Page 1 of 20  [ Next > ]                                    │
└────────────────────────────────────────────────────────────────────────┘
```

- **Two-reason display rule (§10.4):** the row shows the decision chip; expanding a `DENY` reveals both reasons, the audit one behind the `🔒` and gated on `identity.audit:read`. If the current operator lacks it, the audit line reads "Audit reason hidden — requires audit access" and the console **must not** have fetched it (the API omits it for unauthorized subjects).
- **Export** (US-66) → CSV/JSON of the filtered set; append-only, so export is a read (AC-40). Audit rows retain non-PII UUIDs after a subject is scrubbed (FR-6.5).
- **States:** Loading (filter row + 10 row skeletons + paging bar); Empty ("No events match your filters." + "Clear filters"); Error (retry).
- **API:** `GET /orgs/:org/audit?from=&to=&subject=&action=`.

### 5.12 I-14 — Settings (MFA policy) & I-15 — Platform: signing keys

**Purpose (I-14).** Per-org MFA policy: require TOTP for named privileged roles so admin takeover is harder (FR-1.7, FR-6.4). Satisfies **US-64**.

```
┌────────────────────────────────────────────────────────────────────────┐
│  Settings · Security                                                    │
│  Multi-factor authentication                                            │
│   ( ● ) Require MFA for these roles: [☑ owner] [☑ admin] [☐ member]     │
│   ( ○ ) Optional for everyone                                           │
│  ⚠ 1 admin has not enrolled TOTP — they can't act with admin rights     │
│    until they do.                          [ View un-enrolled users → ] │
│  [ Save MFA policy ]                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

- Toggling on "require for owner/admin" gates token issuance carrying those roles until the holder enrols TOTP (FR-1.7). A pre-save warning lists holders who'd be locked out of privileged action so the owner isn't surprised.

**Purpose (I-15) — Platform operator only.** Signing-key status and rotation, with previous keys retained through the token TTL so verification never breaks mid-rotation (make-before-break, FR-4/NFR-Avail-3). Satisfies **US-62; AC-3**. Rail item + route visible only when `me.platformOperator === true` (PRD §2.8).

```
┌────────────────────────────────────────────────────────────────────────┐
│  Platform · Signing keys                              ⚠ platform operator│
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ kid          Alg    Status      Created      In JWKS              │   │
│  ├──────────────────────────────────────────────────────────────────┤   │
│  │ key-2026-07  RS256  ● ACTIVE    2026-07-01   ✓                    │   │
│  │ key-2026-06  RS256  ○ RETIRING  2026-06-01   ✓ (until TTL)        │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│  Rotating publishes a new ACTIVE key BEFORE old tokens expire, so        │
│  verification never has a gap (make-before-break).                       │
│  [ Rotate signing key ]  ← typed-confirm; loudly audited                 │
└────────────────────────────────────────────────────────────────────────┘
```

- **Rotate** is a typed-confirm, high-blast-radius action; every visit and rotation is loudly audited (design §11). It never removes the retiring key from JWKS before the max access-token TTL elapses (AC-3).
- **APIs:** `GET/PUT /orgs/:org/settings` (MFA); platform key endpoints (design §13/§14 `signing_keys`; exposed as a platform admin surface).

---

## 6. Component spec

### 6.1 `OrgSwitcher`

```
┌────────────────────────────┐        (expanded)
│ [ Globex ▾ ]              │        ┌────────────────────────────┐
└────────────────────────────┘        │ Switch organization        │
                                       ├────────────────────────────┤
                                       │ ✓ Globex   · owner         │
                                       │   Acme     · admin         │
                                       ├────────────────────────────┤
                                       │ + Create organization      │
                                       │ ⚙ All orgs (platform op)   │
                                       └────────────────────────────┘
```

- Source: `currentOrgProvider` + `myOrgsProvider`. On select → `currentOrgProvider.notifier.switch(id)` invalidates every org-scoped provider (list in `lib/app/org_scoped_providers.dart`) **and re-fetches the PDP permission map** for the new org.
- Persists the choice in `localStorage` under `yayaid.lastOrgId`. Loading shows the cached chip + a thin progress bar during invalidation.

### 6.2 `PermissionGate` — PDP-driven enablement (the dog-food gate)

Wraps any actionable control. Props: `permissionKey` (e.g. `identity.user:invite`), optional `resource` scope, `child`, `disabledTooltip`.

- Reads `pdpPermissionsProvider(resource)` (backed by `GET /pdp/permissions?subject=<me>&resource=<resource>`, short-TTL cached, invalidated on `role.assigned`/`policy.changed` events, PRD US-72).
- **Default posture is disabled** (deny-by-default) until the map confirms the grant → then enabled. When disabled, renders the child with `enabled:false` + a tooltip paraphrasing why ("Requires `identity.policy:write` in this organization."). Never hides.
- Server-side `decide` remains authoritative on the actual call (defense in depth).

### 6.3 `TwoReasonDenial` — the security-critical display component

Renders a `Deny(userSafeReason, auditReason)`. Props: `decision`, `canViewAudit` (bool, from PDP `identity.audit:read`).

```
┌── ✕ DENY ─────────────────────────────────────────────────────────────┐
│ User-safe: "<userSafeReason>"                    (always shown)         │
│ Audit 🔒 : "<auditReason>"        (only if canViewAudit; else hidden)   │
└────────────────────────────────────────────────────────────────────────┘
```

- **Invariant:** when `canViewAudit == false`, `auditReason` is never rendered *and* the client must not have received it (the API omits it). This encodes non-negotiable #3 at the widget boundary. If `userSafeReason == auditReason` ever arrives, the widget logs a QA-visible error (mirrors AC-26).
- Reused by the Simulator (I-11), Audit viewer (I-13), and every inline action error that surfaces a denial.

### 6.4 `ScopePicker`

Emits a `Scope` (design §5.5). Segmented `⦿ Org-wide` vs `▣ Resource`; the resource path reveals a resource-type dropdown + a typeahead resource picker resolving labels→ids; renders the result as a **scope chip**. Reused by role assignment (I-09), client allowed-scopes (I-12), and invite initial-role scope (I-05).

### 6.5 `ConditionBuilder`

The JSON-logic editor for policies (I-10, §5.8): a combinator (`ALL`/`ANY`/`NONE`) over attribute/operator/value rows, each row = attribute-bag typeahead (`subject.*`/`resource.*`/`env.*`) + operator + literal-or-attribute; add/remove/nest; a raw-JSON escape hatch; only safe/sandboxed operators (FR-4.5). Emits a validated `condition_json`.

### 6.6 `EffectivePermissionsPreview`

Given a subject + a scope, calls `GET /pdp/permissions?subject=&resource=` and lists the union of effective permissions, highlighting deltas a pending assignment would add. Reused by role assignment (I-09) and user/group detail "Preview" (I-06/I-07). This is the same API relying parties use for their own UI gating (US-36).

### 6.7 `OneTimeSecretDialog`

Reveals a freshly-minted secret exactly once (client secret on register/rotate, MFA recovery codes on enrol). Masked-by-default, reveal + copy affordances, cannot dismiss until "I've copied it", warns "stored hashed — only chance to copy". See §10.5.

### 6.8 `ConfirmTypedNameDialog`

Reusable destructive-action guard (revoke assignment, disable/lock user, delete policy, rotate secret, archive org, rotate signing key). CTA disabled until the typed value exactly matches the shown token (case-sensitive). Copy states the consequence in plain language.

### 6.9 Chips: `RoleChip`, `ScopeChip`, `EffectChip`, `DecisionChip`

Per §2.1 — icon + text, color never the sole signal. `RoleChip` shows `SYS` for system roles.

---

## 7. Routing & navigation

### 7.1 `go_router` routes

```dart
GoRoute(path: '/login',    builder: …),   // I-01
GoRoute(path: '/callback', builder: …),   // I-01 auth-code landing
GoRoute(path: '/mfa',      builder: …),   // I-01 TOTP challenge
GoRoute(path: '/orgs',     builder: …),   // I-02

ShellRoute(builder: (c,s,child) => AdminShell(child: child), routes: [
  GoRoute(path: '/orgs/:org',                         builder: …), // I-03 dashboard
  GoRoute(path: '/orgs/:org/users',                   builder: …), // I-04
  GoRoute(path: '/orgs/:org/users/:uid',              builder: …), // I-06
  GoRoute(path: '/orgs/:org/groups',                  builder: …), // I-07
  GoRoute(path: '/orgs/:org/groups/:gid',             builder: …),
  GoRoute(path: '/orgs/:org/roles',                   builder: …), // I-08
  GoRoute(path: '/orgs/:org/roles/:rid',              builder: …),
  GoRoute(path: '/orgs/:org/assignments',             builder: …), // I-09
  GoRoute(path: '/orgs/:org/policies',                builder: …), // I-10
  GoRoute(path: '/orgs/:org/policies/:pid',           builder: …),
  GoRoute(path: '/orgs/:org/policies/simulate',       builder: …), // I-11
  GoRoute(path: '/orgs/:org/clients',                 builder: …), // I-12
  GoRoute(path: '/orgs/:org/clients/:cid',            builder: …),
  GoRoute(path: '/orgs/:org/audit',                   builder: …), // I-13
  GoRoute(path: '/orgs/:org/settings',                builder: …), // I-14
  GoRoute(path: '/me',                                builder: …), // profile + MFA enrol
  GoRoute(path: '/platform/keys',                     builder: …), // I-15 (platform-gated)
]),
```

### 7.2 Redirect rules

| Condition | Redirect |
|---|---|
| Unauthenticated user hits any `/orgs/**`, `/me`, `/platform/**` | `/login?next=<encoded current>` |
| Auth succeeded but MFA required and unmet | `/mfa` (then `next`) |
| Authenticated user hits `/login` | `/orgs` |
| Authenticated user hits `/orgs/:org` and gets 404/deny on the scoped fetch | `NoAccessView` in place (no redirect — preserves URL) |
| Non-platform operator hits `/platform/**` | `/orgs` with a toast "Platform settings are restricted." |
| `/callback` without a valid code/state | `/login` |
| Operator lacks a rail item's read permission | rail item disabled with tooltip; direct URL nav → `NoAccessView` |

### 7.3 Deep-link preservation

`?next=` is propagated through login → MFA → destination. URL-encoded.

---

## 8. State management

### 8.1 Providers

```
meProvider                 AsyncNotifier<Me?>          — /oauth2/userinfo + platformOperator flag
myOrgsProvider             AsyncNotifier<List<Org>>    — /orgs; refreshed on org create
currentOrgProvider         Notifier<OrgId?>            — drives switcher + every org-scoped provider
pdpPermissionsProvider     AsyncFamily<PermSet, Scope> — GET /pdp/permissions (my effective perms); the dog-food gate
usersProvider              AsyncFamily<List<User>, OrgId>
groupsProvider             AsyncFamily<List<Group>, OrgId>
rolesProvider              AsyncFamily<List<Role>, OrgId>
assignmentsProvider        AsyncFamily<List<Assignment>, OrgId>
policiesProvider           AsyncFamily<List<Policy>, OrgId>
clientsProvider            AsyncFamily<List<Client>, OrgId>
auditProvider              AsyncFamily<Page<AuditRow>, AuditQuery>
appPermissionCatalogProvider AsyncFamily<PermTree, AppKey> — GET /v1/identity/apps/{app}/permissions
```

### 8.2 Provider invalidation matrix

When `currentOrgProvider` changes, invalidate every org-scoped provider: `usersProvider`, `groupsProvider`, `rolesProvider`, `assignmentsProvider`, `policiesProvider`, `clientsProvider`, `auditProvider`, and **`pdpPermissionsProvider`** (so the enablement map re-resolves for the new org). The list lives in `lib/app/org_scoped_providers.dart`; a widget test walks it and asserts each reacts to a switch.

On outbox-driven change events consumed by the console (US-71/US-72): `role.assigned` / `policy.changed` invalidate `pdpPermissionsProvider`, `assignmentsProvider`, `policiesProvider` (bounded staleness ≤ 30s, NFR-Perf-5). `client.secret_rotated` invalidates `clientsProvider`.

### 8.3 dio interceptors

| Interceptor | Order | Behavior |
|---|---|---|
| `BearerTokenInterceptor` | 1 | Attaches the console's access token; refreshes via rotating refresh on 401-expired. |
| `IdempotencyKeyInterceptor` | 2 | On POST/PUT/DELETE, attaches `Idempotency-Key` (PRD FR-7.3). |
| `NotFoundAsNoAccessInterceptor` | 3 | On 404/deny from an org-scoped fetch, translate to `NoAccessException` → `NoAccessView`. |
| `UnauthorizedInterceptor` | 4 | On 401 (unrecoverable), clear `meProvider`, redirect to `/login?next=…`. |

---

## 9. PDP-gated UI (the console eats its own dog food)

### 9.1 How gating resolves

Because the admin API is PDP-gated (FR-6.4, AC-43), the console mirrors the *same* model in the UI:

1. On login and on org switch, fetch `GET /pdp/permissions?subject=<me>&resource=org:<currentOrg>` → the operator's effective permissions in this org.
2. For resource-scoped actions (e.g. assigning within `store:st_123`), fetch the map for that resource on demand and cache it (short TTL).
3. Every actionable control is wrapped in a `PermissionGate` keyed on the permission it needs. **Deny-by-default**: disabled until the map confirms; enabled thereafter; disabled controls carry a "why" tooltip and are never hidden (visual hygiene, not security).
4. The mutating call is *still* PDP-gated server-side; a UI that mistakenly enabled a control gets a `Deny` and surfaces the `userSafeReason` inline.

### 9.2 Control → permission map (illustrative)

| Surface / control | Permission (PDP `action`) | US/AC |
|---|---|---|
| Users list read | `identity.user:read` | US-21 |
| Invite user | `identity.user:invite` | US-21 / AC-10 |
| Lock / disable user | `identity.user:manage` | US-23 / AC-14 |
| Reset credential | `identity.user:manage` | US-26 |
| Group membership edit | `identity.group:manage` | US-24 |
| Create/edit role | `identity.role:manage` | US-31 |
| Assign / revoke role | `identity.assignment:manage` | US-32/35 / AC-13 |
| Effective-perms preview | `identity.permissions:read` (self allowed) | US-36 |
| Create/edit/delete policy | `identity.policy:manage` | US-41 |
| Simulate | `identity.policy:simulate` | US-45 / AC-24 |
| View audit reason | `identity.audit:read` | US-66 / AC-40 |
| Register/edit client, rotate secret | `identity.client:manage` | US-50/54 / AC-32 |
| Edit MFA policy | `identity.org:manage` | US-64 |
| Rotate signing key, cross-org audit | platform + `identity.key:manage` | US-62 / AC-3 |

---

## 10. Cross-cutting patterns

### 10.1 Org switcher behavior
One click; re-mounts every org-scoped provider and re-fetches the PDP map in < 500ms p95 (§6.1). Persists last org; platform operators get an "All organizations" entry (audited each visit).

### 10.2 Permission-gated control enablement
Deny-by-default, resolved via `GET /pdp/permissions`; disabled-with-tooltip, never hidden; server-side `decide` authoritative (§9). This is the dog-food loop the PRD mandates (FR-6.4).

### 10.3 Existence never leaked
404 or a scoped `Deny` → identical `NoAccessView` ("This isn't available, or you don't have access to it."). The console cannot distinguish, by design (FR-7.1).

### 10.4 Two-reason denial display convention
- `userSafeReason` — shown wherever a denial is surfaced (inline action errors, simulator, audit).
- `auditReason` — shown **only** in the Simulator's and Audit viewer's privileged panes, and **only** to a subject with `identity.audit:read`; behind a `🔒` badge. The API omits it for unauthorized subjects, so the console can't leak what it never received. Enforced at the widget boundary by `TwoReasonDenial` (§6.3). This is the load-bearing security-UX rule (non-negotiable #3).

### 10.5 One-time secret reveal
Client secrets (register/rotate) and MFA recovery codes are shown exactly once, masked-by-default with reveal+copy, in a dialog that cannot be dismissed until acknowledged, with "stored hashed — only chance to copy" and (for rotation) the overlap-window note. Thereafter only a fingerprint/last-4 is displayed (§6.7).

### 10.6 Destructive-action confirmations
`ConfirmTypedNameDialog` for: revoke role assignment, lock/disable user, delete policy, rotate client secret, rotate signing key, archive org. Each states the consequence; the highest-blast-radius require a typed match (§6.8).

### 10.7 Empty / loading / error states
Shape-matching skeletons for every list/detail (never a lone spinner except full-page auth transitions). Empty states name the absence + a create CTA. Errors show what failed + plain-language `userSafeReason` + a next action. Toasts for transient successes ("Invitation sent", "Secret rotated", "Policy saved").

### 10.8 Standard error patterns

| Failure | Surface | Behavior |
|---|---|---|
| Network down | Toast + retry on the affected list/detail | "Couldn't reach the server. Check your connection and try again." |
| 401 (session expired) | Redirect `/login?next=…` | Toast: "Your session expired." |
| Deny (403) on a mutation | Inline in the modal/form | Render `userSafeReason` verbatim; never `auditReason`. |
| 404 / deny on scoped fetch | `NoAccessView` in place | Identical for "doesn't exist" and "no access." |
| 409 (idempotency / conflict) | Inline in dialog | `userSafeReason` verbatim. |
| 429 (rate-limit / lockout) | Inline banner + countdown | "Too many attempts. Try again in **{n}s**." (`Retry-After`). |
| 5xx | Toast + console log | "Something went wrong on our end. We're looking into it." |

---

## 11. Accessibility (mirrors PRD NFR-A11y-1..4)

- **NFR-A11y-1 — Labels.** Every form field carries a visible label; placeholder is never the only label. Applies to login, MFA code entry, invite, role/scope pickers, condition rows, simulator request, client registration.
- **NFR-A11y-2 — Announced errors & denial reasons.** Validation errors and PDP-denial reasons live in a `Semantics(liveRegion: true)` so screen readers announce them. The Simulator's decision and both reason panels are announced on result; the MFA "code didn't match" is announced.
- **NFR-A11y-3 — Color never the only signal.** `PERMIT`/`DENY`, decision chips, role/scope/effect chips, and user-status pills all carry icon + text. Deny is `✕ + "DENY"`, permit is `✓ + "PERMIT"`, scope is `⦿ org-wide` / `▣ resource`.
- **NFR-A11y-4 — Full keyboard navigation.** Every load-bearing flow is fully keyboard-operable: **login**, **MFA challenge/enrol**, **role assignment** (subject → role → scope → preview → assign), **policy editor** (combinator + rows + raw-JSON toggle), and the **simulator** (request → simulate → read decision). Tab order top-to-bottom/left-to-right; forms submit on Enter; modals trap focus and restore it to the trigger on close; route change focuses the page heading. `MediaQuery.disableAnimations` honored (skeleton shimmer → flat block; spinner → "Loading…" label).

---

## 12. Component inventory / reuse

| Component | File (suggested) | Reused by |
|---|---|---|
| `OrgSwitcher` | `lib/features/orgs/presentation/org_switcher.dart` | Admin shell |
| `PermissionGate` | `lib/features/authz/presentation/permission_gate.dart` | Every actionable control |
| `TwoReasonDenial` | `lib/features/authz/presentation/two_reason_denial.dart` | Simulator (I-11), Audit (I-13), inline errors |
| `ScopePicker` | `lib/features/authz/presentation/scope_picker.dart` | Assignment (I-09), clients (I-12), invite (I-05) |
| `ConditionBuilder` | `lib/features/policies/presentation/condition_builder.dart` | Policy editor (I-10) |
| `EffectivePermissionsPreview` | `lib/features/authz/presentation/effective_perms_preview.dart` | Assignment (I-09), user/group detail |
| `OneTimeSecretDialog` | `lib/features/clients/presentation/one_time_secret_dialog.dart` | Client register/rotate (I-12), MFA enrol |
| `ConfirmTypedNameDialog` | `lib/shared/confirm_typed_name_dialog.dart` | All destructive actions |
| `RoleChip` / `ScopeChip` / `EffectChip` / `DecisionChip` | `lib/shared/chips/` | Lists, editors, simulator, audit |
| `NoAccessView` | `lib/shared/no_access_view.dart` | Any scoped 404/deny |
| `PermissionTree` | `lib/features/roles/presentation/permission_tree.dart` | Role editor (I-08) |
| `MfaCodeField` | `lib/features/auth/presentation/mfa_code_field.dart` | MFA challenge + enrol (I-01) |

---

## 13. Traceability — screens → US / AC

| Screen | Personas (PRD §2) | User stories | Acceptance criteria |
|---|---|---|---|
| I-01 Login + MFA | Owner, Admin, Member, Courier, Operator | US-01, US-04, US-07, US-64 | AC-1, AC-5, AC-6 |
| I-02 Org list/create/switch | Owner, Operator | US-20, US-25 | AC-10 |
| I-03 Org dashboard | Owner, Admin | US-20 | AC-10 |
| I-04 Users list | Admin | US-21, US-25 | AC-10 |
| I-05 Invite dialog | Owner, Admin | US-21 | AC-10 |
| I-06 User detail | Admin, Member, Service Account | US-22, US-23, US-26 | AC-14 |
| I-07 Groups + membership | Admin | US-24 | AC-12 |
| I-08 Roles & Permissions | Owner, Relying-Party App | US-30, US-31 | AC-11 |
| I-09 Role assignment | Admin, Courier, Service Account | US-32, US-33, US-34, US-35, US-36 | AC-11, AC-12, AC-13 |
| I-10 Policies editor | Owner | US-41, US-42, US-43, US-47 | AC-21, AC-22, AC-23 |
| I-11 Policy Simulator | Owner | US-45, US-48, US-61 | AC-24, AC-26 |
| I-12 Clients & Service Accounts | Owner, Service Account, Relying-Party App | US-50, US-51, US-52, US-54 | AC-30, AC-32 |
| I-13 Audit viewer | Operator, Admin | US-66 | AC-40, AC-44 |
| I-14 Settings (MFA policy) | Owner | US-64 | AC-5 |
| I-15 Platform signing keys | Operator | US-62 | AC-3 |
| Cross-cutting: two-reason denial | all | US-44 | AC-21, AC-26 |
| Cross-cutting: PDP-gated console | Operator | US-60 | AC-43 |

---

## 14. References

- [PRD](PRD.md) — personas (§2), user stories (US-xx), acceptance criteria (AC-xx), NFR-A11y-1..4, FR-4.6/6.4.
- [`../design/yaya-identity-design.md`](../design/yaya-identity-design.md) — admin API §9.3, PDP §7, scoped role assignments §5.5, clients/service accounts §5.6, policy simulator (§9.3), two-reason denial (§7/§11), Flutter console decision §12.
- Companion specs (siblings): [TRD](TRD.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).
- `docs/specs/user-accounts/UI-UX.md` — the house-style UI/UX template this doc mirrors.
- `docs/design/yaya-agentic-design.md` — §5.5 two-reason `AuthzDecision`, §12 trust boundaries. CLAUDE.md non-negotiable **#3**.
- `apps/yaya-sales/docs/design/yaya-sales-design.md` — first relying party (operators, service token, shopper exchange, courier identity) whose actors surface as users/clients in this console.
