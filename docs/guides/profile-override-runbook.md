# Resolver-driven profile override — operator + tenant runbook

Companion to `docs/design/resolver-profile-override-design.md`. Covers the
one thing this feature adds on top of the auth resolver: letting the
tenant's resolver **pick the caller's profile** — VIP tiering, staff-vs-
customer routing, account-state routing — instead of every caller on a
number/entry point landing on the binding's one default profile.

Prerequisite: you already have a working auth resolver. If you don't, do
`docs/guides/auth-resolver-runbook.md` first — a profile override rides on
top of an identity resolution, it does not replace it.

If you're debugging "why did this caller land on profile X", jump to §5
(the inspector) and §6 (failure modes).

---

## 1. What it does, in 60 seconds

The party that knows *who the caller is* — the resolver, keyed off the
caller's phone — gets to also say *which profile* they should talk to. It
returns a profile selection alongside the token; the framework validates
it and, if it passes, the session starts on that profile instead of the
bound one. When the resolver says nothing about profile, **nothing
changes** — the binding/entry-point default wins exactly as before.

```
resolver returns { token, profileId?, profileVersion? }
        │
        ▼
guardrails (in ChannelAuthIntegrator, post-JWT-verification):
   • published + in-tenant?   (ProfileRegistry lookup)
   • on the surface's allowlist? (empty allowlist = any published)
        │
   ┌────┴─────────────────────────┐
   ok                          denied
   │                              │
 stamp reserved                on_override_denied:
 yaya.profile claim              IGNORE → drop override, use bound profile
   │                             REJECT → drop the inbound (fail closed)
   ▼
ProfileResolverChain @ engine.start:
   10 Explicit         (operator/API named a profile — playground act-as)
   15 ResolverOverride ← reads yaya.profile, wins over the bound profile
   20 Identity         (_profile_id / identity_profile_mappings — still works)
   30 ChannelBound     (binding / entry-point default)
 1000 Fallback
   │
   ▼
session pinned to the effective profile @ a concrete version (immutable
for the session's life)
```

Two invariants worth burning in:

- **The override is a *view* decision, not an *authorization* one.**
  Landing a caller in `merchant-agent` does **not** grant merchant tools.
  Every tool call still passes the `Authorizer` chain against the verified
  Principal's scopes; a caller without `refund:write` still gets denied,
  whatever profile they're in. Use the override to choose *prompt + tool
  catalog visible*, and scopes to choose *what actually runs*.
- **The profile selection is never trusted from the JWT body.** It travels
  in the structured resolver return and is stamped by the framework as the
  reserved `yaya.profile` claim *after* verification. A resolver that jams
  `yaya.profile` into its JWT claims gains nothing — it's stripped.

Storage: `channel_bindings` and `chat_entry_points` each gain
`allowed_override_profiles_json` (jsonb) + `on_override_denied` (V61). The
override itself is ephemeral — carried on the Principal, persisted only as
the session's pinned `profile_id`, exactly like any resolved profile.

Only channels that actually run a resolver can produce an override:
**WhatsApp** and **chat**. SMS is FAQ-mode only (no resolver), so no
override arises there.

---

## 2. Operator: the three scenarios

All three are the same mechanic — the resolver returns a `profileId`, the
surface allows it — differing only in what the tenant keys the decision on.

### 2.1 Tiered service (VIP)

> One WhatsApp number. VIP customers get `retail-vip` (concierge tools,
> warmer prompt); everyone else `retail-standard`.

1. Publish both profiles (`retail-standard`, `retail-vip`).
2. Bind the sender to `retail-standard` (the default the resolver
   *overrides up from*). Channel bindings → the sender's binding →
   profile = `retail-standard`.
3. Attach the resolver (§2.2 of the auth-resolver runbook).
4. In the same binding form, **Profile override guardrails** (shown once a
   resolver is attached):
   - **Allowed override profiles**: `retail-vip` — or leave empty to allow
     any published profile.
   - **When an override is denied**: `IGNORE` — a mis-tier should still get
     *an* answer.
5. Tenant returns `profileId: "retail-vip"` for VIP callers, nothing for
   the rest (§3).

### 2.2 Staff vs. customer on the same line

> A caller whose phone maps to an internal `staff` record reaches
> `merchant-agent`; unknown callers reach `retail-customer`.

Same as VIP, but the allowlist is `merchant-agent` and you almost
certainly want **`on_override_denied = REJECT`** on a *public* number:
if a caller *claims* staff and the profile isn't allowlisted (config
drift, compromised resolver), failing closed is safer than quietly
serving them the customer profile. Remember the scope guarantee — even a
staff *profile* grants no staff *tools* without the scopes on the JWT.

### 2.3 Account-state routing

> Delinquent account → `collections`; good standing → `retail-customer`.

The account state lives tenant-side; only the resolver sees it. Allowlist
`collections` (and any other state profiles), `on_override_denied = IGNORE`.

---

## 3. Tenant integration — returning the profile

### 3.1 HTTP resolver

Add the two optional fields to the success body. Absence = "don't touch
the bound profile" (today's behavior).

```http
200 OK
Content-Type: application/json

{
  "token": "eyJhbGciOiJIUzI1NiIs...",   ← unchanged, required
  "profileId": "retail-vip",             ← OPTIONAL — profile KEY, not "key@version"
  "profileVersion": null                 ← OPTIONAL — null/omitted = latest published
}
```

- `profileId` is the profile **key** (`retail-vip`), the same value the
  admin console shows — not a version-qualified id.
- `profileVersion` pins a concrete version; omit it (or send `null`) to
  ride "latest published", which is what you almost always want.
- A **malformed** `profileId` (non-string, blank) or a non-numeric
  `profileVersion` is a *protocol error* (`bad_profile`), not a silent
  no-override — the same posture as any malformed response. Return the
  fields correctly or not at all.

Minimal Node.js delta over the auth-resolver runbook's example:

```js
const user = await users.findByPhone(callerPhone);
if (!user) return res.status(404).end();

const token = jwt.sign({ sub: user.id, scope: user.scopes.join(' ') },
                       TENANT_SIGNING_KEY,
                       { algorithm: 'HS256', issuer: 'acme-identity', expiresIn: '15m' });

// NEW: steer VIPs to the premium profile. Omit for everyone else.
const body = { token };
if (user.tier === 'VIP') body.profileId = 'retail-vip';
res.json(body);
```

> **Migration note.** If you're on the older `_profile_id`-claim +
> `identity_profile_mappings` path, it still works (chain step 20). But you
> no longer need the mapping-table round-trip for something the resolver
> already knows — return `profileId` directly and it wins at step 15, above
> the claim path, uniformly on WhatsApp and chat.

### 3.2 BEAN resolver

Token-only beans are unchanged. To select a profile, override
`resolveIdentity` (the default wraps `resolve` with no override):

```java
@Override
public Optional<ResolvedIdentity> resolveIdentity(ResolveRequest req) {
    return users.findByPhone(req.e164CallerPhone()).map(u -> {
        String jwt = tokens.mintFor(u, req.session());
        return u.isVip()
            ? new ResolvedIdentity(jwt,
                  Optional.of(new ProfileSelection("retail-vip", null)))  // null = latest
            : ResolvedIdentity.of(jwt);                                   // no override
    });
}
```

---

## 4. Guardrails — what the surface enforces

Every override is checked at stamp time, *after* the JWT verifies, before
the session starts:

1. **Published & in-tenant.** `profileId` must resolve to a profile
   published for the caller's tenant. Unknown / unpublished → denied. This
   is unconditional — it holds even with an empty allowlist.
2. **Per-surface allowlist.** `allowed_override_profiles`:
   - **Empty (default)** → any published in-tenant profile is allowed. The
     resolver is tenant-trusted; this is the low-friction default.
   - **Non-empty** → the override must be in the set. Use this on *public*
     entry points to keep a buggy/compromised resolver out of privileged
     internal profiles.
3. **On denial** (`on_override_denied`):
   - `IGNORE` (default) → drop the override, run the **bound** profile,
     record the denial in the inspector.
   - `REJECT` → drop the inbound (no reply). For surfaces where a mis-route
     is worse than no answer.

Setting both: **channel bindings** (WhatsApp) or **chat entry points**
(chat) admin form → the *Profile override guardrails* block, which appears
only when the surface has a resolver attached (nothing else can override).
The allowlist field is comma-separated profile keys.

These are also settable via the admin API directly:

```jsonc
// PUT /v1/admin/tenants/{tenantId}/channel-bindings/{id}   (or POST to create)
{
  "profileId": "retail-standard",
  "authResolverId": 7,
  "onUnresolved": "ANONYMOUS",
  "allowedOverrideProfiles": ["retail-vip"],   // [] = any published
  "onOverrideDenied": "REJECT"                  // or "IGNORE"
}

// chat: POST/PUT /v1/admin/tenants/{tenantId}/chat-entry-points/{id}
// same two fields: allowedOverrideProfiles, onOverrideDenied
```

Mid-session immutability is preserved: the override is honored **only at
`start()`**. A REFRESH resolve that returns a different profile is logged
and ignored — the live session keeps the profile it started on.

---

## 5. Debugging — the auth-flow inspector

Open a session in the inspector (`/v1/sessions/{id}/inspector` — signed in to
the console as an ADMIN of the workspace, or with an API key of that role —
or the playground's inspector panel). The **Binding** stage now shows bound vs.
effective:

- **Applied:** a green "Profile override → retail-vip" row, reading
  `resolver selected retail-vip (bound was retail-standard)`.
- **Denied:** an amber "Profile override denied" row, reading e.g.
  `NOT_ALLOWLISTED (requested=retail-vip) · ran under bound profile
  retail-standard`.
- **No override:** no extra row — the resolver didn't select a profile,
  the bound profile ran.

Under the hood these come from `BindingStageDto`:

| field | meaning |
|---|---|
| `profileId` | the **bound** profile (binding / entry-point default) |
| `effectiveProfile` | what the session actually ran under |
| `profileSource` | `BOUND` or `RESOLVER_OVERRIDE` |
| `overrideDeniedReason` | audit detail when an override was dropped (`UNPUBLISHED` / `NOT_ALLOWLISTED` + the requested key); null otherwise |

The `auth_resolver_calls` audit table (see the auth-resolver runbook §4.2)
is still your record of *whether the resolver was reached and what it
returned status-wise* — the inspector is where the *profile decision*
surfaces.

---

## 6. Failure modes cheat sheet

| Symptom | Cause | Fix |
|---|---|---|
| Resolver returns `profileId` but the session runs the bound profile; inspector shows "override denied: UNPUBLISHED" | The profile key isn't published for this tenant (typo, or draft/unpublished) | Publish it; confirm the key matches the console exactly (key, not `key@version`) |
| Same, but inspector shows "NOT_ALLOWLISTED" | The surface has a non-empty allowlist that doesn't include the key | Add the key to *Allowed override profiles* on the binding / entry point, or clear the allowlist to allow any published |
| Inbound is silently dropped whenever the resolver returns a profile | `on_override_denied = REJECT` **and** the override is failing a guardrail (unpublished / not allowlisted) — REJECT fails closed | Fix the underlying denial (publish / allowlist), or switch to `IGNORE` if a fallback answer is acceptable |
| Override works on chat but not WhatsApp (or vice-versa) | Guardrails are **per-surface** — allowlist/policy set on one binding/entry point, not the other | Set the fields on *both* surfaces; they don't share config |
| VIP caller gets the VIP profile on their first message but the bound profile later (or vice-versa) | Not expected — the profile is cached alongside the token, so a cache HIT reapplies it. If you see this, the resolver is returning *different* profiles across calls | Make the resolver deterministic per caller; a REFRESH that changes profile is logged + ignored mid-session by design |
| Caller landed in `merchant-agent` but every merchant tool denies | Working as intended — the override is a view decision, not an authz grant | Put the merchant scopes on the tenant JWT the resolver mints; the `Authorizer` chain gates tools on scopes, not profile |
| Resolver returns `profileId: "retail-vip@3"` and it's rejected as `bad_profile` | `profileId` is the bare key; version goes in `profileVersion` | Send `{ "profileId": "retail-vip", "profileVersion": 3 }` |
| Changed the allowlist in the admin form but old sessions still route the old way | Profile is pinned at session start; the change applies to *new* sessions | Expected — no mid-session reroute. New sessions pick it up |

---

## 7. Cross-references

- `docs/design/resolver-profile-override-design.md` — the authoritative
  design doc (§6 SPI, §7 guardrails, §8 chain precedence, §9 audit)
- `docs/guides/auth-resolver-runbook.md` — the resolver this rides on:
  registering, secrets, the webhook contract, the `Test` probe
- `docs/design/channel-auth-resolver-design.md` §10 — the legacy
  `_profile_id`-claim path (still supported; superseded for *direct*
  selection by the structured return)
- `docs/design/yaya-agentic-design.md` §5.2 (Profile / "latest"), §5.6
  (profile resolver chain), §12 (trust boundaries)
- `docs/design/playground-auth-flow-inspector-design.md` — the inspector
  the bound-vs-effective stage extends
