# User Accounts & Workspaces — UI/UX Specification

> **Companion to:** [PRD](PRD.md) · [TRD](TRD.md) · [App-Flow](App-Flow.md) · [Backend-Data](Backend-Data.md).
> **Target app:** `yayaagenticweb/` (Flutter web admin + playground).
> **Status:** Draft v1 — implementation-ready.

This doc tells a Flutter engineer what to render, how state flows, what every error/empty/loading state looks like, and how role-aware gating works at the component level. Wireframes are ASCII (intentional — quick to iterate, no Figma dep).

---

## 1. Principles

### 1.1 Design principles

1. **A workspace is the unit of context.** Every screen below the workspace switcher is implicitly scoped. There is no "global" page once you're signed in (except `/me`, `/workspaces`, and platform-settings).
2. **Server-side authorization is authoritative.** The UI hides buttons it knows are forbidden, but it never relies on hiding for security. Every action also gets a server-side guard check.
3. **Existence is never leaked.** A 404 from the API is rendered as "You don't have access to this workspace." — the same as if the workspace didn't exist. Avoid revealing whether the resource exists.
4. **Enumeration-safe by default.** The signup, forgot-password, and invitation-accept flows always show the same generic success message regardless of whether the email is registered.
5. **Role-aware UI gating is *visual hygiene*, not security.** When a user lacks a grant, the corresponding button is rendered disabled with a tooltip explaining why, not hidden — discoverability matters more than UI cleanliness, and we never want a user to think they're missing functionality.
6. **Loading states have shape.** Skeleton placeholders that match the eventual layout (not a centered spinner) for every list / detail screen.
7. **Errors are recoverable.** Every error state shows what failed, why (in plain language, paraphrased server-side reason), and a clear next action.
8. **Workspace switching is one click.** A persistent switcher in the admin shell; switching invalidates and re-mounts every workspace-scoped provider in < 500ms p95.

### 1.2 Tech stack constraints

- `flutter_riverpod` for state; controllers are `Notifier`/`AsyncNotifier`.
- `dio` interceptor for `YAYA_SESSION` cookie + `X-Yaya-CSRF` header (M5b).
- `go_router` for routing.
- `freezed` + `json_serializable` for DTOs.
- No new dependencies.

---

## 2. Visual system (lightweight)

These are notes for the implementer — not a full design-system rewrite. Reuse existing tokens in `lib/app/theme.dart` or its equivalent.

| Token | Usage |
|---|---|
| `primary` | OWNER role chip; primary CTAs |
| `secondary` | ADMIN role chip |
| `tertiary` | MEMBER role chip |
| `outline` | VIEWER role chip; disabled state borders |
| `error` | Validation errors; lockout banner; destructive-action confirmation |
| `warning` | Bootstrap-operator banner; "you are platform admin" indicator |
| `success` | Verification success, invitation-accepted toast |

Role chip layout (used in members list, switcher, breadcrumbs):

```
┌─────────┐
│ ● OWNER │  ← colored dot + uppercase label, 12pt
└─────────┘
```

---

## 3. Screen inventory

### 3.1 New screens (M5a)

| # | Screen | Route | Auth required | Notes |
|---|---|---|---|---|
| S-01 | Signup | `/signup` | none | Card layout; 3 fields + CTA + "already have an account?" link |
| S-02 | Verify email landing | `/verify?token=…` | none | Short-lived; shows progress, redirects on success/error |
| S-03 | Verify email pending | `/verify/pending?email=…` | none | "Check your inbox" + resend CTA |
| S-04 | Login (extended) | `/login` | none | Adds strategy switcher (`user-password` default) |
| S-05 | Forgot password | `/forgot-password` | none | Single email field + CTA |
| S-06 | Reset password | `/reset-password?token=…` | none | New password + confirm; CTA |
| S-07 | Accept invitation | `/accept-invitation?token=…` | optional | Routes: signed-in → confirm screen; anon → signup carry |
| S-08 | Workspace list | `/workspaces` | session | Card grid; one card per workspace + "Create" CTA |
| S-09 | Workspace settings | `/workspaces/:code/settings` | `VIEWER+` | Tabs: General, Members, Invitations, Danger Zone |
| S-10 | Members list | `/workspaces/:code/settings#members` | `VIEWER+` | Table; role change inline; remove button (with confirm) |
| S-11 | Invite member dialog | (modal over S-10) | `ADMIN+` | Email + role selector |
| S-12 | Account profile | `/me` | session | Display name, email, password change, delete account |

### 3.2 Modified screens (M5a)

| # | Screen | Change |
|---|---|---|
| S-20 | Admin shell header | Adds workspace switcher (left of nav) + user avatar/menu (right) |
| S-21 | Login | Strategy switcher (`user-password` default; expandable to show `bootstrap` / `delegate`) |
| S-22 | `operator_auth_screen.dart` | Moves under "Platform settings"; visible only when `me.platformAdmin === true` |
| S-23 | All admin feature screens (`profiles`, `capabilities`, `tools`, `knowledge`, `recorder`, `sessions`) | Now reactive to `currentWorkspaceProvider`; invalidated on switch |

### 3.3 New screens (M5b)

| # | Screen | Route | Auth required | Notes |
|---|---|---|---|---|
| S-30 | Audit user events | `/workspaces/:code/audit/users` | `ADMIN+`, or `PLATFORM_ADMIN` (cross-workspace at `/admin/audit/users`) | Table + filters |
| S-31 | Account locked banner | (overlay on S-04 when login lockout triggered) | none | "We've sent you an email" |

---

## 4. Wireframes

> ASCII wireframes are normative for layout intent — pixel-perfect treatment is the implementer's call. Each frame fits in an 80-col viewport but the actual app is responsive.

### 4.1 S-01 Signup

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          yaya-agentic                                    │
│                                                                          │
│                 ┌────────────────────────────────────┐                   │
│                 │       Create your account          │                   │
│                 │                                    │                   │
│                 │  Email                             │                   │
│                 │  ┌──────────────────────────────┐ │                   │
│                 │  │ you@example.com              │ │                   │
│                 │  └──────────────────────────────┘ │                   │
│                 │                                    │                   │
│                 │  Display name                      │                   │
│                 │  ┌──────────────────────────────┐ │                   │
│                 │  │ Jane Doe                     │ │                   │
│                 │  └──────────────────────────────┘ │                   │
│                 │                                    │                   │
│                 │  Password                          │                   │
│                 │  ┌──────────────────────────────┐ │                   │
│                 │  │ ••••••••••••                 │ │                   │
│                 │  └──────────────────────────────┘ │                   │
│                 │  ▢▢▢▢▢▢ Strong                    │                   │
│                 │  ✓ 12+ chars  ✓ upper  ✓ digit    │                   │
│                 │                                    │                   │
│                 │  [    Create account    ]          │                   │
│                 │                                    │                   │
│                 │  Already have one? → Sign in       │                   │
│                 └────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────────────┘
```

States:
- Idle: CTA enabled when all 3 fields valid and password meets policy.
- Validating: real-time password-strength meter (purely client-side).
- Submitting: CTA shows spinner, "Creating account…".
- Success: route to S-03 (`/verify/pending?email=...`). The submitted email is passed in URL **for UX only**; it is never used by the API.
- Server error (non-202): rare; show inline error banner with retry.

### 4.2 S-02 Verify email landing

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│                       ●●●●  Verifying your email…                        │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

→ redirects:
- success → `/login?verified=1` (login screen shows success banner)
- expired token → `/verify/pending?email=<from-server-response>` with banner "Your link expired — request a new one"
- already consumed → `/login?already_verified=1`
- bad token → `/verify/pending` with banner "We couldn't verify that link. Try again."

### 4.3 S-03 Verify email pending

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│                  ✉   Check your inbox                                    │
│                                                                          │
│              We sent a verification email to                             │
│                       you@example.com                                    │
│                                                                          │
│              The link expires in 24 hours.                               │
│                                                                          │
│                  [ Resend verification email ]                           │
│                                                                          │
│                  Wrong address? → Use a different one                    │
└──────────────────────────────────────────────────────────────────────────┘
```

- Resend CTA throttled client-side: disabled with countdown for 60s after click.
- Server-side cooldown still enforced (60s/5h) — additional safety.

### 4.4 S-04 Login (extended)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          yaya-agentic                                    │
│                                                                          │
│                 ┌────────────────────────────────────┐                   │
│                 │           Sign in                  │                   │
│                 │                                    │                   │
│                 │  Email or username                 │                   │
│                 │  ┌──────────────────────────────┐ │                   │
│                 │  │ you@example.com              │ │                   │
│                 │  └──────────────────────────────┘ │                   │
│                 │                                    │                   │
│                 │  Password                          │                   │
│                 │  ┌──────────────────────────────┐ │                   │
│                 │  │ ••••••••••••                 │ │                   │
│                 │  └──────────────────────────────┘ │                   │
│                 │                                    │                   │
│                 │  [        Sign in        ]         │                   │
│                 │                                    │                   │
│                 │  Forgot password? → Reset          │                   │
│                 │  New here? → Create an account     │                   │
│                 │                                    │                   │
│                 │  ▾ Other sign-in methods           │                   │
│                 └────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────────────┘
```

The "Other sign-in methods" disclosure expands to show `bootstrap` and `delegate` strategies — useful in dev/local; in prod the section is hidden unless `?advanced=1` query is present (still works for SRE without showing for normal users).

Banners:
- `?verified=1` → green "Email verified — sign in to continue."
- `?already_verified=1` → blue "This email is already verified. Sign in."
- `?reset=1` → green "Password updated — sign in with the new one."
- Lockout (M5b) → red "Too many failed attempts. We've emailed you a reset link." (CTA disabled for `Retry-After` seconds)

### 4.5 S-05 Forgot password

```
┌──────────────────────────────────────────────────────────────────────────┐
│                 ┌────────────────────────────────────┐                   │
│                 │      Reset your password           │                   │
│                 │                                    │                   │
│                 │  Email                             │                   │
│                 │  ┌──────────────────────────────┐ │                   │
│                 │  │ you@example.com              │ │                   │
│                 │  └──────────────────────────────┘ │                   │
│                 │                                    │                   │
│                 │  [   Email me a reset link   ]     │                   │
│                 │                                    │                   │
│                 │  ← Back to sign in                 │                   │
│                 └────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────────────┘
```

After submit (always 204): show "If an account exists for that email, we just sent a reset link." — same wording regardless of whether the address is registered.

### 4.6 S-06 Reset password

```
┌──────────────────────────────────────────────────────────────────────────┐
│                 ┌────────────────────────────────────┐                   │
│                 │       Choose a new password        │                   │
│                 │                                    │                   │
│                 │  New password                      │                   │
│                 │  ┌──────────────────────────────┐ │                   │
│                 │  │ ••••••••••••                 │ │                   │
│                 │  └──────────────────────────────┘ │                   │
│                 │  ▢▢▢▢▢▢ Strong                    │                   │
│                 │                                    │                   │
│                 │  Confirm new password              │                   │
│                 │  ┌──────────────────────────────┐ │                   │
│                 │  │ ••••••••••••                 │ │                   │
│                 │  └──────────────────────────────┘ │                   │
│                 │                                    │                   │
│                 │  [       Update password       ]   │                   │
│                 │                                    │                   │
│                 │  ⚠ This will sign you out of all   │                   │
│                 │    devices.                        │                   │
│                 └────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────────────┘
```

After success: redirect to `/login?reset=1` (the password-reset confirm endpoint deletes all sessions; the user must re-authenticate).

Error states:
- Expired/consumed/invalid token → redirect to `/forgot-password?expired=1` with banner "That link no longer works. Request a new one."

### 4.7 S-07 Accept invitation

#### Signed-in path:

```
┌──────────────────────────────────────────────────────────────────────────┐
│  [Workspace switcher: Acme Co (current)]    ●  jane@…  ▾                 │
│                                                                          │
│                 ┌────────────────────────────────────┐                   │
│                 │     Join “Globex Industries”       │                   │
│                 │                                    │                   │
│                 │   You've been invited to join      │                   │
│                 │   Globex Industries as an ADMIN.   │                   │
│                 │                                    │                   │
│                 │   Invited by alex@globex.com on    │                   │
│                 │   2026-06-12.                      │                   │
│                 │                                    │                   │
│                 │   [   Accept and switch   ]        │                   │
│                 │   [   Accept and stay     ]        │                   │
│                 │   [        Decline        ]        │                   │
│                 └────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────────────┘
```

#### Anonymous path:

Token recognized → redirect to `/signup?invite=<token>` with a banner above the signup form:

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ℹ You've been invited to join "Globex Industries" as ADMIN.              │
│   Create your account to accept.                                         │
└──────────────────────────────────────────────────────────────────────────┘
```

Email field is pre-filled and locked to the invitation's email. On verify-email completion, the carried invite token is auto-redeemed in the same transaction (TRD §3.1 `verifyEmail`).

Error states:
- Expired → "This invitation has expired. Ask the workspace admin to send a new one."
- Already consumed → "This invitation has already been accepted."
- Workspace archived → "This workspace is no longer available."

### 4.8 S-08 Workspace list

```
┌──────────────────────────────────────────────────────────────────────────┐
│  [Switcher: choose workspace ▾]              ●  jane@…  ▾                │
│                                                                          │
│   Your workspaces                                    [ + New workspace ] │
│                                                                          │
│   ┌─────────────────────────┐  ┌─────────────────────────┐               │
│   │ ● Acme Co               │  │ ● Globex Industries     │               │
│   │   acme                  │  │   globex                │               │
│   │                         │  │                         │               │
│   │   ● OWNER · 3 members   │  │   ● ADMIN · 12 members  │               │
│   │   12 profiles · 8 tools │  │   4 profiles · 22 tools │               │
│   │                         │  │                         │               │
│   │   [ Open ]              │  │   [ Open ]              │               │
│   └─────────────────────────┘  └─────────────────────────┘               │
│                                                                          │
│   ┌─────────────────────────┐                                            │
│   │   + Create workspace    │                                            │
│   │                         │                                            │
│   │   Set up a new          │                                            │
│   │   workspace for a       │                                            │
│   │   team or project.      │                                            │
│   └─────────────────────────┘                                            │
└──────────────────────────────────────────────────────────────────────────┘
```

States:
- Empty (rare — only post-account-delete): centered illustration + "You don't belong to any workspaces. Create one to get started." + CTA.
- Loading: 2 skeleton cards.
- Platform admin: an additional "View all workspaces (platform admin)" CTA in the header — links to `/admin/workspaces/all` (audited each visit).

### 4.9 S-09 / S-10 Workspace settings (with Members tab)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  [acme ▾]                                              ●  jane@…  ▾      │
├──────────────────────────────────────────────────────────────────────────┤
│  Acme Co  ● OWNER                                                        │
│  General  Members  Invitations  Danger zone                              │
│  ─────────────────                                                       │
│                                                                          │
│  Members (3)                                  [ + Invite member ]        │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │ Name                Email                Role        Actions     │    │
│  ├──────────────────────────────────────────────────────────────────┤    │
│  │ Jane Doe (you)      jane@acme.io         ● OWNER     —           │    │
│  │ Alex Lee            alex@acme.io         ● ADMIN ▾   [ Remove ]  │    │
│  │ Sam Wong            sam@acme.io          ● MEMBER ▾  [ Remove ]  │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

Behavior:
- Role chip with a dropdown when the actor has the grant. OWNER row is read-only to non-OWNERs.
- "Remove" disabled with tooltip when the row is the last OWNER. Click triggers a confirm modal showing the typed-name match for the email.
- ADMINs see "Remove" on MEMBER/VIEWER rows; not on OWNER rows.
- VIEWERs see the table but no Actions column.

Invitations tab:

```
│  Pending invitations (2)                                                 │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │ Email                  Role     Sent       Expires    Actions    │    │
│  ├──────────────────────────────────────────────────────────────────┤    │
│  │ tara@acme.io           ADMIN    2d ago     5d         [ Revoke ] │    │
│  │ niko@acme.io           VIEWER   1h ago     7d         [ Revoke ] │    │
│  └──────────────────────────────────────────────────────────────────┘    │
```

Danger zone:

```
│  Danger zone                                                             │
│                                                                          │
│  Archive workspace                                                       │
│  ──────────────────                                                      │
│  Archiving hides the workspace from your list. Data is preserved for     │
│  audit. Only an OWNER can archive.                                       │
│                                                                          │
│  To confirm, type the workspace code: acme                               │
│  ┌──────────────────────────────┐                                        │
│  │                              │                                        │
│  └──────────────────────────────┘                                        │
│  [ Archive workspace ]   ← disabled until typed match                    │
```

### 4.10 S-11 Invite member dialog

```
                ┌────────────────────────────────────┐
                │   Invite a teammate to Acme Co     │
                │                                    │
                │   Email                            │
                │   ┌──────────────────────────────┐ │
                │   │ teammate@acme.io             │ │
                │   └──────────────────────────────┘ │
                │                                    │
                │   Role                             │
                │   ( ) ADMIN   — manages members   │
                │   (●) MEMBER  — builds profiles   │
                │   ( ) VIEWER  — read-only         │
                │                                    │
                │   The invitation expires in 7 days.│
                │                                    │
                │   [ Cancel ]   [ Send invitation ] │
                └────────────────────────────────────┘
```

- ADMINs cannot select OWNER (radio omitted).
- After send: dialog closes; toast "Invitation sent to teammate@acme.io."

### 4.11 S-12 Account profile

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Your account                                                            │
│                                                                          │
│  Display name                                                            │
│  ┌──────────────────────────────────┐   [ Save ]                         │
│  │ Jane Doe                         │                                    │
│  └──────────────────────────────────┘                                    │
│                                                                          │
│  Email                                                                   │
│  ┌──────────────────────────────────┐                                    │
│  │ jane@acme.io                     │  ← changing requires re-verify     │
│  └──────────────────────────────────┘                                    │
│                                                                          │
│  Password                                                                │
│  Current  ┌─────────────────┐                                            │
│  New      ┌─────────────────┐                                            │
│  Confirm  ┌─────────────────┐    [ Update password ]                     │
│                                                                          │
│  ─────────────────────────────────                                       │
│                                                                          │
│  Delete account                                                          │
│  This soft-deletes your account. After 30 days, PII is permanently       │
│  scrubbed. You must first transfer ownership or archive every workspace  │
│  where you are the sole OWNER.                                           │
│                                                                          │
│  [ Delete account ]   ← disabled until eligibility-check returns OK      │
└──────────────────────────────────────────────────────────────────────────┘
```

Delete eligibility: the page makes a `GET /v1/auth/me/deletion-eligibility` call (M5b) that returns the list of workspaces where the user is the sole OWNER and must be transferred/archived first. The CTA is gated on this.

### 4.12 S-20 Admin shell header

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ⌂ yaya  [acme ▾]   Profiles  Capabilities  Tools  Knowledge   ● jane@… ▾│
└──────────────────────────────────────────────────────────────────────────┘
```

- `[acme ▾]` — workspace switcher chip. Click → dropdown lists all the user's workspaces; "Create new…" at the bottom.
- `● jane@… ▾` — user menu. Items: Account profile, Platform settings (only if `me.platformAdmin === true`), Sign out.
- The header is rendered by `lib/features/admin/shared/admin_shared.dart` (extended).

### 4.13 S-30 Audit user events (M5b)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Audit · User events                                                     │
│                                                                          │
│  Kind: [ All ▾ ]   User: [ Any ▾ ]   Workspace: [ acme ▾ ]               │
│  From: [ 2026-06-01 ]  To: [ 2026-06-20 ]   [ Apply ]                    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │ When        Kind          User           Workspace   Detail      │    │
│  ├──────────────────────────────────────────────────────────────────┤    │
│  │ 2m ago      ROLE_GRANT    jane@acme.io   acme        MEMBER alex │    │
│  │ 12m ago     INVITE_SEND   jane@acme.io   acme        sam@acme.io │    │
│  │ 1h ago      LOGIN         jane@acme.io   —           —           │    │
│  │ 3h ago      WORKSPACE_..  jane@acme.io   acme        created     │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│  [ < Prev ]  Page 1 of 8  [ Next > ]                                     │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Component spec

### 5.1 `WorkspaceSwitcher`

`lib/features/workspaces/presentation/workspace_switcher.dart`

```
┌──────────────────────────────────────┐
│ [acme ▾]                             │  ← compact, in admin shell
└──────────────────────────────────────┘

(when expanded)

┌──────────────────────────────────────┐
│ Switch workspace                     │
├──────────────────────────────────────┤
│ ✓ ● Acme Co     · OWNER              │
│   ● Globex      · ADMIN              │
│   ● Initech     · MEMBER             │
├──────────────────────────────────────┤
│ + Create new workspace               │
└──────────────────────────────────────┘
```

Behavior:
- Source: `currentWorkspaceProvider` + `myWorkspacesProvider`.
- On select: calls `currentWorkspaceProvider.notifier.switch(id)`. The notifier invalidates every workspace-scoped provider (a list maintained in `app/providers.dart`).
- Persists the choice in `localStorage` under `yaya.lastWorkspaceId` so reload returns to the same workspace.
- Loading: shows the cached chip + a thin progress bar at the bottom of the header during invalidation.
- Empty state (no workspaces): the chip says "Create workspace" and links to S-08 with a focused "create" intent.

### 5.2 `RoleChip`

`lib/features/workspaces/presentation/widgets/role_chip.dart`

Props: `Role`, optional `bool dropdown`, optional `void Function(Role) onChange`.

| Role | Color | Label |
|---|---|---|
| OWNER | `primary` | OWNER |
| ADMIN | `secondary` | ADMIN |
| MEMBER | `tertiary` | MEMBER |
| VIEWER | `outline` | VIEWER |
| PLATFORM_ADMIN | `warning` | PLATFORM ADMIN |

When `dropdown=true` and the actor has permission, renders as a dropdown. ADMINs cannot pick OWNER in the dropdown. MEMBER/VIEWER never see the dropdown.

### 5.3 `WorkspaceGuard` (Flutter widget — not the SPI)

Wraps any route content that requires `currentWorkspaceProvider`. Renders:
- a centered "Choose a workspace" view when no current workspace,
- a `NoAccessView` when the API returns 404 for the workspace-scoped fetch,
- the child otherwise.

### 5.4 `NoAccessView`

Shared component for any 404 from a workspace-scoped fetch.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│                       ⌛  Not available                                  │
│                                                                          │
│      This workspace doesn't exist or you don't have access to it.        │
│                                                                          │
│            [ Back to your workspaces ]                                   │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

Crucially: this is rendered identically whether the workspace doesn't exist or the user lacks access. Server returns 404 in both cases (PRD AC-1).

### 5.5 `PasswordStrengthMeter`

Pure-client component:
- 6 segments; lights up as character count + class diversity grows.
- Caption: "Too short" → "Weak" → "OK" → "Strong" → "Very strong."
- Below: green-check rows for each requirement met (12+ chars, lower, upper, digit, symbol).
- Submission is blocked only when server validation would also block (≥ 12 chars AND ≥ 3 classes).

### 5.6 `ConfirmTypedNameDialog`

Reusable for destructive actions (archive workspace, delete account, remove last member-of-something).

```
                ┌────────────────────────────────────┐
                │   Archive Acme Co?                 │
                │                                    │
                │   This hides the workspace. Data   │
                │   is preserved for audit.          │
                │                                    │
                │   Type the workspace code to       │
                │   confirm: acme                    │
                │   ┌──────────────────────────────┐ │
                │   │                              │ │
                │   └──────────────────────────────┘ │
                │                                    │
                │   [ Cancel ]  [ Archive ]          │
                └────────────────────────────────────┘
```

CTA disabled until the typed value matches the code/email exactly (case-sensitive).

---

## 6. Routing & navigation

### 6.1 `go_router` routes (extension to `lib/app/router.dart`)

```dart
GoRoute(path: '/signup',                builder: …),                       // S-01
GoRoute(path: '/verify',                builder: …),                       // S-02
GoRoute(path: '/verify/pending',        builder: …),                       // S-03
GoRoute(path: '/login',                 builder: …),                       // S-04 (existing, extended)
GoRoute(path: '/forgot-password',       builder: …),                       // S-05
GoRoute(path: '/reset-password',        builder: …),                       // S-06
GoRoute(path: '/accept-invitation',     builder: …),                       // S-07

ShellRoute(builder: (ctx, state, child) => AdminShell(child: child), routes: [
  GoRoute(path: '/workspaces',                              builder: …),   // S-08
  GoRoute(path: '/workspaces/:code',                        builder: …),   // dashboard
  GoRoute(path: '/workspaces/:code/settings',               builder: …),   // S-09
  GoRoute(path: '/workspaces/:code/settings/members',       builder: …),   // S-10
  GoRoute(path: '/workspaces/:code/settings/invitations',   builder: …),
  GoRoute(path: '/workspaces/:code/settings/danger',        builder: …),
  GoRoute(path: '/me',                                      builder: …),   // S-12
  GoRoute(path: '/platform-settings',                       builder: …),   // S-22 (gated)
  GoRoute(path: '/workspaces/:code/audit/users',            builder: …),   // S-30 (M5b)
  // existing profiles / capabilities / tools routes move under /workspaces/:code/
]),
```

### 6.2 Redirect rules

| Condition | Redirect |
|---|---|
| Unauthenticated user hits any `/workspaces/**` or `/me` | `/login?next=<encoded current>` |
| Authenticated user with no workspaces hits `/login` post-success | `/workspaces` (showing empty state) |
| Authenticated user hits `/login` or `/signup` | `/workspaces` |
| Authenticated user hits `/workspaces/:code` and 404 | `NoAccessView` rendered in place (no redirect — preserves URL for back navigation) |
| Authenticated non-platform-admin hits `/platform-settings` | `/me` with a toast "Platform settings are restricted." |
| `/verify?token=…` without token param | `/login` |
| `/accept-invitation?token=…` without auth | inline anon-path UI (S-07 second variant) |

### 6.3 Deep-link preservation

`?next=` is propagated through the auth redirect chain: hitting `/workspaces/acme/profiles/foo` → signin → returns to `/workspaces/acme/profiles/foo`. URL-encoded.

---

## 7. State management

### 7.1 Providers

```
authProvider                   AsyncNotifier<User?>     — /v1/auth/me result, refreshed on mount + login + logout
myWorkspacesProvider           AsyncNotifier<List<Workspace>>  — refreshed on login + on workspace create/archive
currentWorkspaceProvider       Notifier<TenantCode?>    — drives header switcher + every workspace-scoped provider
membersProvider                AsyncFamily<List<Member>, TenantCode>
invitationsProvider            AsyncFamily<List<Invitation>, TenantCode>
csrfTokenProvider              Notifier<String?>        — rotates on login; injected into dio interceptor
```

### 7.2 Provider invalidation matrix

When `currentWorkspaceProvider` changes, invalidate every workspace-scoped provider:

- `profilesProvider`, `capabilitiesProvider`, `toolsProvider`, `knowledgeSourcesProvider`, `recorderStrategyProvider`, `sessionsProvider`, `membersProvider`, `invitationsProvider`.

The list is maintained in `lib/app/workspace_scoped_providers.dart`. Adding a new feature provider that is workspace-scoped requires adding it here. A widget test (`workspace_scoped_providers_invalidation_test.dart`) walks the list and asserts each provider reacts to a switch.

### 7.3 dio interceptors

| Interceptor | Order | Behavior |
|---|---|---|
| `CookieInterceptor` (existing) | 1 | Forwards browser cookies (`YAYA_SESSION`). |
| `CsrfInterceptor` (NEW, M5b) | 2 | On every POST/PATCH/PUT/DELETE, attach `X-Yaya-CSRF: <csrfTokenProvider.current>`. |
| `NotFoundAsNoAccessInterceptor` (NEW) | 3 | On 404 from `/v1/admin/workspaces/**`, translate to a typed `NoAccessException` so the UI shows `NoAccessView`. |
| `UnauthorizedInterceptor` (existing) | 4 | On 401, clear `authProvider` and redirect to `/login?next=…`. |

---

## 8. Error / empty / loading states

### 8.1 Standard error patterns

| Failure | Surface | Behavior |
|---|---|---|
| Network down | Toast + retry on the affected list/detail | "Couldn't reach the server. Check your connection and try again." |
| 401 from a protected route | Redirect to `/login?next=…` | Toast: "Your session expired." |
| 403 (CSRF or auth) | Inline error in the affected modal/form | "We couldn't authorize that request. Refresh and try again." |
| 404 from workspace-scoped resource | `NoAccessView` in place of the screen content | Identical for "doesn't exist" and "no access." |
| 409 (last-OWNER, etc.) | Inline error in dialog | Server-supplied `userSafeReason` is rendered verbatim. |
| 429 (rate-limit) | Inline banner on the affected form | "Too many attempts. Try again in N seconds." + countdown. |
| 5xx | Toast + Sentry/console log | "Something went wrong on our end. We're looking into it." |

### 8.2 Empty states

| Screen | Empty copy | CTA |
|---|---|---|
| S-08 Workspace list (rare) | "You don't belong to any workspaces." | "Create your first workspace" |
| S-10 Members list (impossible — always at least 1 OWNER) | n/a | n/a |
| S-09 Invitations tab | "No pending invitations." | "Invite a teammate" |
| S-30 Audit (M5b) | "No events match your filters." | "Clear filters" |
| Any feature screen (profiles/tools/etc.) for a fresh workspace | "Nothing here yet." | feature-specific CTA |

### 8.3 Loading skeletons

- S-08: 2 card-shaped skeletons.
- S-09: tab-bar visible, content area shows 4 row skeletons.
- S-10: table header visible, 3 row skeletons.
- S-30: filter row visible, 10 row skeletons + a paging bar.

Never use a centered single spinner for a list/detail screen — always shape-matching skeletons. The single spinner is reserved for full-page transitions (e.g., S-02 verification).

---

## 9. Role-aware UI gating

### 9.1 Visibility / interactivity matrix

| Surface | OWNER | ADMIN | MEMBER | VIEWER | PLATFORM_ADMIN |
|---|---|---|---|---|---|
| Workspace switcher: list this workspace | ✔ | ✔ | ✔ | ✔ | ✔ (+ "View all" CTA) |
| Workspace settings: General tab | ✔ edit | ✔ edit | ✔ read | ✔ read | ✔ edit |
| Members tab: list | ✔ | ✔ | ✔ | ✔ | ✔ |
| Members tab: invite | ✔ | ✔ | ✘ disabled | ✘ disabled | ✔ |
| Members tab: change role | ✔ | ✔ (not on OWNER) | ✘ | ✘ | ✔ |
| Members tab: remove | ✔ | ✔ (not on OWNER) | ✘ | ✘ | ✔ |
| Danger zone: archive | ✔ | ✘ disabled | ✘ disabled | ✘ disabled | ✔ |
| Profiles / capabilities / tools / knowledge: read | ✔ | ✔ | ✔ | ✔ | ✔ |
| Profiles / capabilities / tools / knowledge: write | ✔ | ✔ | ✔ | ✘ disabled | ✔ |
| JWT secrets / OIDC issuers: read | ✔ | ✔ | ✘ (404) | ✘ (404) | ✔ |
| JWT secrets / OIDC issuers: write | ✔ | ✔ | ✘ | ✘ | ✔ |
| Platform settings (operator_auth config) | ✘ | ✘ | ✘ | ✘ | ✔ |
| Cross-workspace audit | ✘ | ✘ | ✘ | ✘ | ✔ |

> **Convention:** "disabled" = renders the control with `enabled: false` and a tooltip explaining why ("This action requires the OWNER role."). "404" = the server returns 404 and the UI shows `NoAccessView`.

### 9.2 Implementation pattern

A small helper:

```dart
extension RoleGate on Role {
  bool canManageMembers() => this == Role.OWNER || this == Role.ADMIN || this == Role.PLATFORM_ADMIN;
  bool canWriteResources() => this != Role.VIEWER;
  bool canArchiveWorkspace() => this == Role.OWNER || this == Role.PLATFORM_ADMIN;
  bool canManageSecrets() => this == Role.OWNER || this == Role.ADMIN || this == Role.PLATFORM_ADMIN;
}
```

Used by every relevant widget to drive `enabled`/visibility. Server-side guards are still authoritative — every action carries a defense-in-depth check on the backend.

---

## 10. Accessibility

- **Keyboard:** Every flow (signup, login, verify, reset, accept-invite, member-management) is fully keyboard-accessible. Tab order is logical (top-to-bottom, left-to-right). Forms submit on Enter.
- **Screen readers:** Form fields have visible labels (not placeholder-only). Error messages live in a `Semantics(liveRegion: true)` so they're announced.
- **Color contrast:** Role chips and CTAs meet WCAG AA. Color is never the only signal — role chips always carry the text label; errors always carry an icon.
- **Focus management:** Modal open → focus the first input. Modal close → return focus to the trigger. Route change → focus the page heading.
- **Reduced motion:** `MediaQuery.disableAnimations` honored: skeleton shimmer reduces to a flat block; CTA spinner reduces to a "Loading…" label.

---

## 11. Microcopy reference

A canonical set of strings the implementer can paste verbatim; copy-edits are cheap, inconsistency is not.

| Context | String |
|---|---|
| Signup CTA | "Create account" |
| Signup success toast | "We sent a verification email to **{email}**." |
| Duplicate signup decoy mail subject | "Someone tried to sign up as you" |
| Email verification expired | "That verification link expired. Request a new one." |
| Login CTA | "Sign in" |
| Login generic failure | "Invalid email or password." (same for unknown email, wrong password, locked — never reveal which) |
| Lockout banner (M5b) | "Too many failed attempts. We've emailed you a reset link." |
| Forgot-password CTA | "Email me a reset link" |
| Forgot-password generic success | "If an account exists for that email, we just sent a reset link." |
| Reset-password CTA | "Update password" |
| Reset-password caveat | "This will sign you out of all devices." |
| Invitation accept (signed-in) header | "Join **{workspaceName}**" |
| Invitation accept (anon) banner | "You've been invited to join **{workspaceName}** as **{role}**. Create your account to accept." |
| Invitation expired | "This invitation has expired. Ask the workspace admin to send a new one." |
| Member role change (ADMIN attempts OWNER promote) | "Only an owner can promote members to owner." |
| Last-OWNER protection | "A workspace must have at least one owner. Promote another member to owner before removing yourself." |
| Workspace archive confirm | "Type the workspace code to confirm: **{code}**" |
| Account-delete eligibility blocker | "You're the sole owner of **{n}** workspace(s). Transfer ownership or archive them first." |
| 404 no-access | "This workspace doesn't exist or you don't have access to it." |
| 429 rate-limit | "Too many attempts. Try again in **{n}s**." |
| Generic 5xx | "Something went wrong on our end. We're looking into it." |

---

## 12. Telemetry events (for M5b dashboards)

Emit a `posthog`-style event from the Flutter app (or your existing telemetry surface) for:

| Event | Properties |
|---|---|
| `signup_started` | (none) |
| `signup_submitted` | `email_domain` (hashed) |
| `verify_email_clicked` | `time_since_signup_seconds` |
| `login_attempted` | `strategy` |
| `login_succeeded` | `strategy` |
| `login_failed` | `strategy`, `reason_generic` ("bad_credentials" \| "rate_limit" \| "locked") |
| `workspace_switched` | `from_role`, `to_role` |
| `workspace_created` | (none) |
| `invitation_sent` | `role` |
| `invitation_accepted` | `role`, `time_since_sent_seconds` |
| `role_changed` | `from_role`, `to_role` |
| `member_removed` | `target_role` |
| `account_deleted` | (none) |

No PII fields. Email domains are hashed (SHA-256, first 8 hex chars) for cohort analysis without identifying users.

---

## 13. References

- [PRD](PRD.md), [TRD](TRD.md), [App-Flow](App-Flow.md), [Backend-Data](Backend-Data.md).
- `yayaagenticweb/lib/app/router.dart` — existing routes this spec extends.
- `yayaagenticweb/lib/features/auth/presentation/login_screen.dart` — existing screen to extend.
- `yayaagenticweb/lib/features/admin/shared/admin_shared.dart` — existing admin shell to extend with the switcher.
