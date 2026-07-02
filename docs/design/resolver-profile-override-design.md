# Resolver-Driven Profile Override (v1)

> Status: **design / proposed**
> Author: (design draft)
> Related: `channel-auth-resolver-design.md` (§10 profile pinning),
> `yaya-agentic-design.md` §5.2 (Profile), §5.4 (AuthN), §5.6 (Profile
> resolver chain), §12 (trust boundaries).

---

## 1. The one-paragraph summary

Today a session's profile is chosen at yaya-agentic's edge: the channel
**binding** (WhatsApp/SMS) or **chat entry point** pins one profile, or an
operator names one explicitly. But the party that actually *knows who the
caller is* — the tenant's **auth resolver**, keyed off the caller's phone
number — has no first-class way to say "*this* caller should talk to the
`retail-vip` profile, not the binding's default." This design gives the
`AuthResolver` an optional, structured **profile selection** in its return
shape. When present, it **overrides the bound profile** for that session,
uniformly across every channel, gated by guardrails that keep it from
becoming a privilege-escalation surface. When absent, nothing changes.

---

## 2. Why this, why now

The framework's whole thesis is **profile-driven, authentication-aware**
agents (CLAUDE.md, design §4). "Which role am I talking to" is supposed to
follow from "who is this." For a phone-first channel that's literally the
auth resolver's job — it takes an E.164 number and returns a verified
identity. Yet the profile decision is made *before* and *independent of*
that identity, at bind time.

Concrete scenarios operators keep asking for:

- **Tiered service.** One WhatsApp number, one binding. VIP customers
  (known to the tenant's CRM) should land on a `retail-vip` profile with
  concierge tools + a warmer prompt; everyone else on `retail-standard`.
  The resolver already looks the caller up — it should get to say which.
- **Staff vs. customer on the same line.** A caller whose phone maps to an
  internal `staff` record should reach the `merchant-agent` profile
  (inventory, refunds); an unknown caller reaches `retail-customer`.
- **Account-state routing.** Delinquent account → a `collections` profile;
  good standing → `retail-customer`. The state lives tenant-side; only the
  resolver can see it.

None of these are expressible today without provisioning a *separate
binding per segment* and steering callers by hand — which defeats the
point of a single public number / entry point.

---

## 3. What the code does today (the real gap)

Profile selection at `engine.start()` runs the `ProfileResolverChain`
(design §5.6) — ordered, first-non-empty-wins. The registered resolvers:

| Order | Resolver | Returns |
|------:|----------|---------|
| 10 | `ExplicitProfileResolver` | `req.explicitProfile()` |
| 20 | `IdentityProfileResolver` | JWT claim → `identity_profile_mappings` row |
| 30 | `ChannelProfileResolver` | binding / entry-point default (`ChannelBinding::profile`) |
| 1000 | `FallbackProfileResolver` | empty (→ hello-world bootstrap) |

The gap is that **the bound profile enters the chain through two different
doors depending on channel**, and one of them shadows the identity path:

- **WhatsApp / SMS** (`InboundDispatchWorker`, `SmsInboundWorker`) pass
  `explicitProfile = Optional.of(binding.profile())`. So the binding
  default is resolved by `ExplicitProfileResolver` **@10**, and
  `IdentityProfileResolver` **@20 never runs**. The claim path is dead for
  these channels.
- **Chat** (`PublicChatController`) passes `explicitProfile =
  Optional.empty()` and sets a `channel.entryPoint` hint, so the bound
  profile is resolved by `ChannelProfileResolver` **@30**. Here
  `IdentityProfileResolver` **@20 *does* get a turn** and can override.

The consequence: `channel-auth-resolver-design.md` §10 states "*when both
a `_profile_id` claim and a binding profile are set, the JWT claim wins.*"
**That is true for chat and false for WhatsApp/SMS.** The one override
vehicle we ship is also indirect — the resolver has to embed a
`_profile_id` claim in the JWT *and* the operator has to maintain a
matching `identity_profile_mappings` row. The resolver knows the answer
directly; it should be able to say so directly.

Two problems to fix, then: **(a)** make the bound profile enter the chain
the same way everywhere, and **(b)** give the resolver a direct, structured
way to select a profile.

---

## 4. Non-negotiables this design preserves

Carried straight from CLAUDE.md "Non-negotiables" and design §12:

1. **The resolver is trusted for identity, not for authorization.** A
   resolver-selected profile changes *which profile's prompt + tool
   catalog* the caller sees. It does **not** widen what the caller may
   *do*: every tool call still passes schema-validation → the `Authorizer`
   chain against the verified `Principal`'s scopes (§12, non-negotiable
   #1/#2). Landing in `merchant-agent` does not grant merchant tools if the
   Principal lacks the scopes; those calls still Deny.
2. **No profile leakage.** A resolver must not be able to route a caller
   into an arbitrary profile whose *existence* or *system prompt* the
   tenant never meant to expose on that surface. → guardrail: published +
   optional per-binding allowlist, §7.
3. **Reserved claims are stamped post-verify, never trusted from the JWT
   body** (design §3 #5). The profile override is carried as a *structured*
   return value and stamped by the framework — a resolver cannot smuggle it
   through arbitrary JWT claims. §6.2.
4. **Profiles are immutable mid-session** (design §5.2; channel-auth §10).
   The override is honored **only at `start()`**. A `REFRESH` resolve that
   returns a different profile is logged and ignored.
5. **Fail safe, never fail open.** An unknown / disallowed / unpublished
   override does **not** fall through to a random profile — it falls back
   to the *bound* profile (or REJECTs, per policy). §7.

---

## 5. The model at a glance

```
  inbound (phone / chat)
        │
        ▼
  AuthResolver.resolve(req)                     ← tenant-side, phone-keyed
        │  returns ResolvedIdentity {
        │     jwt,                               (unchanged: token to verify)
        │     profile?  = ProfileSelection(id, version?)   ← NEW, optional
        │  }
        ▼
  ChannelAuthIntegrator.verifyAndStamp(...)
        │  • verify jwt via AuthenticatorChain  (unchanged)
        │  • stamp reserved channel claims      (unchanged)
        │  • stamp reserved  yaya.profile  claim  ← NEW, from structured return only
        ▼
  engine.start(req, auth)  →  ProfileResolverChain
        │  10  Explicit            (operator/caller named a profile)
        │  15  ResolverOverride    ← NEW: reads yaya.profile, validates, wins over bound
        │  20  Identity            (JWT claim → mappings table; still works)
        │  30  ChannelBound        (binding / entry-point default)   ← now the sole "bound" door
        │ 1000 Fallback
        ▼
  Session pinned to effective profile @ concrete version
```

---

## 6. The SPI change

### 6.1 `AuthResolver` return shape

Evolve the resolver's return from a bare token to a small structured
result. The token stays mandatory (identity still requires a verifiable
JWT); the profile is optional.

```java
// channel/auth/AuthResolver.java
Optional<ResolvedIdentity> resolve(ResolveRequest req) throws AuthResolverException;

record ResolvedIdentity(
        String jwt,                          // compact JWT, no "Bearer " prefix (unchanged contract)
        Optional<ProfileSelection> profile   // empty = "don't touch the bound profile"
) {
    public static ResolvedIdentity of(String jwt) {          // convenience: token only
        return new ResolvedIdentity(jwt, Optional.empty());
    }
}

record ProfileSelection(
        String profileId,     // profile *key* (e.g. "retail-vip"), not the version-qualified id
        Integer version       // null = resolve to latest published at start (§5.2 "latest")
) {}
```

**Backward compatibility.** `Optional<String> resolve(...)` implementations
(bean resolvers, tests) keep compiling via an adapter default or a one-line
migration to `ResolvedIdentity.of(token)`. `Optional.empty()` (user not
found) is unchanged and still drives `on_unresolved`.

> **Doc reconciliation.** `AuthResolver`'s current Javadoc says "*Claims,
> profile pinning … live INSIDE the JWT, not in the resolver's return
> shape.*" This design deliberately reverses that for **profile
> selection** (only), because requiring an `identity_profile_mappings`
> round-trip for what the resolver already knows is a papercut, and because
> the JWT-claim path is silently dead on WhatsApp/SMS (§3). Update that
> Javadoc and channel-auth §10 accordingly.

### 6.2 HTTP wire contract

`HttpAuthResolver` response body (channel-auth §6.2) gains two optional
fields; absence = today's behavior:

```jsonc
// tenant → yaya
{
  "token": "eyJhbGciOi…",     // unchanged, required on success
  "profileId": "retail-vip",  // OPTIONAL — profile key to override with
  "profileVersion": null       // OPTIONAL — null/omitted = latest
}
```

A malformed `profileId` (non-string) is a protocol error → surfaces as an
`AuthResolverException` (never a silent anonymous downgrade), consistent
with the existing "malformed response" rule.

### 6.3 Carrying the selection to the chain (reserved claim)

`ChannelAuthIntegrator.verifyAndStamp` already stamps channel-reserved
claims *after* JWT verification so a resolver can't spoof them
(non-negotiable #3). We add one reserved key, stamped **only** from the
structured `ProfileSelection`, never copied from the raw JWT body:

```
yaya.profile           = "retail-vip"      (profile key)
yaya.profile.version   = 3   | absent      (absent = latest)
```

Carrying it on the verified `Principal` means **zero SPI signature
changes** to the resolver chain — resolvers already receive the
`Principal`. It also generalizes: any future `Authenticator` that produces
a `Principal` bearing `yaya.profile` (through a sanctioned, framework-
controlled path) gets the same treatment.

---

## 7. Guardrails (the auth-aware spine)

An override is **honored** only if all hold; otherwise it is **denied** and
we fall back per policy — never to an unrelated profile.

1. **Published & in-tenant.** `yaya.profile` must resolve to a profile
   *published for the caller's tenant* (via `ProfileRegistry`). Unknown /
   unpublished → deny.
2. **Per-surface allowlist (optional).** The binding / chat entry point may
   carry `allowed_override_profiles` (a set of profile keys). Empty/null =
   "any published profile in-tenant is allowed" (the resolver is
   tenant-trusted). Non-empty = the override must be in the set. This lets a
   tenant open a *public* entry point to profile overrides without risking a
   buggy/compromised resolver steering callers into a privileged internal
   profile (non-negotiable #2, design §12).
3. **On-deny policy.** `on_override_denied ∈ { IGNORE, REJECT }`, default
   `IGNORE`:
   - `IGNORE` → drop the override, use the bound profile, audit it.
   - `REJECT` → drop the inbound (fail closed), for surfaces where a
     mis-route is worse than no answer.
4. **Runtime authz unchanged.** Even a *honored* override never grants
   tools; the `Authorizer` chain still gates every call against the
   Principal's scopes. The override is a *view* decision, not an
   *authorization* decision.
5. **Mid-session immutability.** Honored at `start()` only. A `REFRESH`
   resolve that changes the profile is audited
   (`profile_override_changed_during_session`) and ignored — identical
   posture to channel-auth §10.

---

## 8. The resolver chain change

### 8.1 New resolver + normalized precedence

Add `ResolverProfileOverrideResolver` **@Order(15)** — between Explicit
(10) and Identity (20):

```java
@Component @Order(15)
class ResolverProfileOverrideResolver implements ProfileResolver {
    // reads principal.claims().get("yaya.profile"); applies §7 guardrails;
    // returns Optional<ProfileId> (latest or pinned per yaya.profile.version)
}
```

**Normalize the "bound" door.** So the override behaves the same on every
channel, stop overloading `explicitProfile` for the WhatsApp/SMS binding
default. Make WhatsApp/SMS follow the pattern chat already uses: leave
`explicitProfile` empty and let `ChannelProfileResolver` **@30** supply the
binding default (it already can — it does the `ChannelBinding::profile`
lookup from the `channel.senderId` hint). After this, `explicitProfile`
**@10** means only "*a caller/operator explicitly named a profile*"
(playground act-as, direct API), which is what "explicit" should mean.

Resulting precedence (uniform across all channels):

| Order | Resolver | Wins when |
|------:|----------|-----------|
| 10 | Explicit | an operator/API client *named* a profile (playground, act-as) |
| **15** | **ResolverOverride** | **the auth resolver returned a (valid) `ProfileSelection`** |
| 20 | Identity | a JWT claim matches an `identity_profile_mappings` row |
| 30 | ChannelBound | the binding / entry-point default |
| 1000 | Fallback | nothing else matched |

This satisfies the ask — the resolver override (15) **beats the bound
profile** (30) — while a *deliberate* operator test pick (10) still wins,
and the legacy claim-mapping path (20) keeps working.

> **Alternative (smaller diff, weaker semantics):** put the override at
> **@Order(5)**, above Explicit, and leave WhatsApp/SMS injecting bound as
> explicit. Rejected as the default because it lets a resolver override an
> operator's deliberate playground "act-as this profile" pick, and it
> leaves the WhatsApp-vs-chat inconsistency (§3) in place. Offered as a
> fallback if the `explicitProfile` reclassification proves too broad to
> land in one PR.

---

## 9. Audit & inspector

The auth-flow inspector (`playground-auth-flow-inspector-design.md`) must
show *why* a session got the profile it did. Extend `ChannelAuthTrace` to
record, at start:

- `bound_profile` — what the binding/entry-point would have given.
- `effective_profile` — what the session actually got.
- `profile_source ∈ { EXPLICIT, RESOLVER_OVERRIDE, IDENTITY_CLAIM, BOUND,
  FALLBACK }`.
- on deny: `override_denied_reason ∈ { UNPUBLISHED, NOT_ALLOWLISTED }` +
  the requested key (audit, not user-safe).

So the inspector reads: *"bound = retail-standard; effective = retail-vip
(resolver override); allowlist ✓."* This is also where a `REJECT`
(`on_override_denied=REJECT`) surfaces for debugging while the public door
fails closed.

---

## 10. Data model

```sql
-- per-surface allowlist + policy. Both nullable/empty ⇒ today's behavior.
ALTER TABLE channel_bindings
  ADD COLUMN allowed_override_profiles text[]  NULL,   -- profile keys; NULL = any in-tenant
  ADD COLUMN on_override_denied        varchar(8) NOT NULL DEFAULT 'IGNORE';  -- IGNORE|REJECT

ALTER TABLE chat_entry_points
  ADD COLUMN allowed_override_profiles text[]  NULL,
  ADD COLUMN on_override_denied        varchar(8) NOT NULL DEFAULT 'IGNORE';
```

No new table. The override itself is ephemeral (carried on the `Principal`,
persisted only as the session's pinned `profile_id`, exactly as any
resolved profile is today).

---

## 11. Delivery plan (PR slices)

> **Status (2026-07): slices 1–5 landed (chat admin-form UI the one gap).**
> The feature is live end-to-end and per-surface-configurable — an HTTP/BEAN
> resolver returning a `ProfileSelection` overrides the bound profile on
> WhatsApp and chat, validated (published + in-tenant + per-surface allowlist),
> cached across hits, stamped post-verify, honored at `@15`, and audited in the
> inspector (bound vs. effective + denial reason). `channel_bindings` and
> `chat_entry_points` carry `allowed_override_profiles_json` +
> `on_override_denied` (V61); admin CRUD reads/writes them. The only remaining
> item is the Flutter **chat entry-point** admin form control for the allowlist
> (the WhatsApp binding form + the inspector display shipped).

1. **SPI + wire.** ✅ `ResolvedIdentity` / `ProfileSelection`, `AuthResolver`
   `resolveIdentity` default adapter, `HttpAuthResolver` response parsing.
   Token-only resolvers unchanged (adapter default). Pure plumbing.
2. **Stamp + carry.** ✅ `ChannelAuthIntegrator` validates + stamps the
   reserved `yaya.profile` claim from the structured return (WhatsApp + chat
   via `resolveFor`), stripping any JWT-body spoof first. Profile rides the
   Redis cache so a HIT reapplies it. Greptest covered by
   `ChannelAuthProfileOverrideTest#jwt_body_cannot_smuggle_the_reserved_profile_claim`.
3. **Chain.** ✅ `ResolverProfileOverrideResolver @15`; guardrails enforced
   in the integrator (single choke point — lets `REJECT` drop the inbound,
   which a chain resolver can't). WhatsApp reclassified off `explicitProfile`
   onto `ChannelProfileResolver @30`. SMS untouched (it runs no resolver —
   FAQ-mode only — so no override can arise there).
4. **Guardrail config.** ✅ Flyway `V61` adds `allowed_override_profiles_json`
   (jsonb) + `on_override_denied` to `channel_bindings` and `chat_entry_points`;
   entities/domain/services/DTOs carry them; admin CRUD reads/writes them. The
   per-surface `OverridePolicy` is threaded from the binding (WhatsApp) and
   entry point (chat) into `resolve`/`resolverExchange`, replacing `allowAny()`.
   `OnOverrideDenied` lives in `channel.binding` (sibling of `OnUnresolvedPolicy`).
5. **Audit/inspector.** ✅ `ChannelAuthTrace` gains effective-profile / source /
   denial-reason; the WhatsApp worker + `ChatAuthService` record them; the
   inspector API (`BindingStageDto`) + Flutter panel surface "bound → effective
   (resolver override)" and denial notes. ◑ The Flutter **chat entry-point**
   admin form control for the allowlist is the one remaining piece.
6. **Docs.** ◑ Reconciled `AuthResolver` Javadoc + channel-auth §10. Admin
   runbook ("route VIP callers to a premium profile") still to write.

> **Enforcement-location note.** The design (§8.1) sketched the guardrails
> living in the `@15` resolver. Implementation moved them into
> `ChannelAuthIntegrator` at stamp time instead, for two reasons: (a) it is
> the single point that has both the structured selection and the ability to
> fail the inbound closed (`REJECT`) — a profile resolver inside `engine.start`
> can only pick a profile or abstain, it cannot drop the message; (b) it keeps
> one enforcement point rather than splitting allowlist logic. The `@15`
> resolver is therefore a thin, trusting reader of the already-validated
> `yaya.profile` claim.

---

## 12. Acceptance criteria

- A resolver returning `ProfileSelection("retail-vip", null)` starts the
  session on `retail-vip@latest` on **both** WhatsApp and chat, over a
  binding whose default is `retail-standard`.
- A resolver returning no profile leaves the bound profile untouched
  (byte-for-byte today's behavior) on every channel.
- An override to a profile **not** in a non-empty `allowed_override_profiles`
  is dropped: `IGNORE` → bound profile + audit row; `REJECT` → inbound
  dropped, inspector shows the denial.
- An override to an unpublished / cross-tenant profile is denied (never
  honored), regardless of allowlist.
- A `REFRESH` resolve returning a different profile does **not** change the
  live session; it logs `profile_override_changed_during_session`.
- A tool the effective profile doesn't list, or that the Principal lacks
  scopes for, still Denies through the `Authorizer` chain — the override
  granted no authority.
- Greptest: `yaya.profile` is stamped only in `ChannelAuthIntegrator`'s
  post-verify path, never read from `jwtPrincipal.claims()`.

---

## 13. Non-goals

- **Generic `Authenticator` SPI override.** This design scopes the override
  to the channel `AuthResolver` path (the phone-keyed identity source the
  user asked about). The reserved-claim mechanism (§6.3) generalizes to any
  authenticator later, but wiring the bearer/OIDC `Authenticator` SPI to
  emit `yaya.profile` is out of scope here.
- **Mid-session profile switching.** Explicitly forbidden (§4.4).
- **Per-*capability* overrides.** The resolver selects a whole profile, not
  individual capabilities/tools.

---

## 14. Open questions

1. **Allowlist default — open or closed?** Proposed: open (any published
   in-tenant profile), because the resolver is tenant-trusted and a closed
   default makes the common "VIP tiering" case need extra config. Tenants
   who want it closed set an allowlist. Reasonable people could argue the
   safer default is closed for *public* entry points specifically.
2. **Profile *key* vs. version-qualified id on the wire.** Proposed: key +
   optional version (mirrors `identity_profile_mappings`), so a resolver
   need not know version numbers.
3. **Should `on_override_denied=REJECT` distinguish "unpublished" (likely a
   resolver bug) from "not allowlisted" (policy)?** They have different
   operational responses; §9 records both, but do we want different
   inbound-drop semantics.
