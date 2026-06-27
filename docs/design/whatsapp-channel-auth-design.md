# WhatsApp Channel — End-User Authentication & Step-Up (v1)

> ⚠️ **PARTIALLY SUPERSEDED — 2026-06-27**
>
> **Layer 1 (`defaultPrincipalPolicy` / `PhoneToPrincipalResolver` SPI /
> `LookupPrincipal` / `PhoneAsPrincipal` / `Anonymous` policy variants)** is
> superseded by [`channel-auth-resolver-design.md`](./channel-auth-resolver-design.md).
> The replacement collapses the resolver + materializer + reserved-claim
> guard into a single `AuthResolver` SPI that returns a tenant-signed JWT,
> verified by the existing `tenant_jwt_secrets` / `tenant_oidc_issuers`
> trust roots. Do not implement Layer 1 from this doc.
>
> **Layer 2 (engine-managed step-up: `StepUpHint` / `StepUpCoordinator` /
> OTP + deep-link verification)** remains in scope and unchanged. The
> step-up audit table, secondary-comms admin, `OtpVerifier`, and operator
> runbook shipped in W-A4 → W-A9 all carry forward as-is.
>
> **Layer 3 (WhatsApp playground auth chooser)** remains in scope; will
> be re-shaped to pick an `AuthResolver` + sentinel `callerPhone` once
> the engine wiring lands (see open question #5 in the replacement doc).
>
> ---
>
> Companion to `whatsapp-channel-design.md` §9.3 (end-user principal), §9.4
> (account linking, previously deferred — this doc supersedes that sketch),
> §12 (PII / audit), §8 (channel binding); to
> `yaya-agentic-design.md` §5.4 (Authenticator), §5.5 (Authorizer + AuthzDecision),
> §12 (LLM-is-never-trusted); to
> `playground-actas-auth-design.md` (the regular-playground auth chooser this
> doc deliberately mirrors for the WhatsApp playground); and to
> `entity-memory-design.md` §3–4 (how a step-up identity is projected into the
> prompt without widening the principal).

| | |
|---|---|
| **Status** | 🟡 Proposed |
| **Owner** | Yenfri Herrera |
| **Audience** | Engineering team shipping inbound-channel-backed profiles; PM stakeholders evaluating "transfer funds" / "look up my order" -grade WhatsApp use cases |
| **Last updated** | 2026-06-26 |
| **Scope** | Close the v1 gap where the WhatsApp end-user `Principal` carries only `channel:whatsapp` + `channel:read` scopes (`WhatsAppPhoneAuthenticator.java:78`) and therefore can't unlock any tool whose `Authorizer` checks identity. Add (1) a per-binding **default principal policy** so tenants can elevate "phone-verified" to a useful baseline without writing code, (2) a typed **step-up hint** the `Authorizer` chain can emit when an action needs identity above the baseline, and an **engine-managed step-up flow** that runs OTP or deep-link verification deterministically (not via LLM-orchestrated tool calls), (3) a **WhatsApp playground auth chooser** mirroring the regular playground's act-as panel. |
| **Out of scope (v1)** | OAuth popups against the *end user's* tenant identity provider inside WhatsApp itself (deep-link covers the web-completion path). Cross-channel principal linking (a single principal that spans WhatsApp + web + iOS) — explicitly post-v1; the step-up token is **session-scoped** here. Multi-factor step-up (OTP + email confirm). Inbound voice biometric. Step-up policies that vary per *tool call* within a single AuthZ pass — v1 elevates the whole session. |

---

## 1. The one-paragraph summary

Today an inbound WhatsApp message mints a `Principal` with subject `whatsapp:+E164` and just two scopes: `channel:whatsapp` and `channel:read`. Any `Authorizer` that gates a tool on identity (subject claim, tenant user-id, scope) denies — correctly — and the bot ends up only able to answer FAQ-shaped prompts. v1 layers three additive mechanisms onto the existing channel design. **Layer 1** adds `defaultPrincipalPolicy` to `ChannelBinding`; a tenant can choose `ANONYMOUS` (today's behavior), `PHONE_AS_PRINCIPAL` (the phone is the identity, with claims the tenant trusts), or `LOOKUP_PRINCIPAL` (an SPI plug — the tenant's user table resolves the phone to a real user record). This alone unblocks ~70% of cases without any verification dance. **Layer 2** adds a `StepUpHint` to the `AuthzDecision` sealed type and an engine-managed step-up runtime: when a tool is denied and the deny carries a hint, the engine — not the LLM — runs OTP-on-secondary-channel or one-time-signed-deep-link verification, mints an *elevated, session-scoped* `Principal`, and retries the action. **Layer 3** mirrors the regular playground's `actAs` panel for the WhatsApp playground so an operator can pre-elevate a simulated session to test identity-gated flows. Throughout: credentials never travel over WhatsApp; the LLM never orchestrates verification; elevation never outlives the session.

---

## 2. Why the gap exists, and why it is structural

The trust principle from `whatsapp-channel-design.md` §2 is correct and not in dispute: the phone number is identity. The implementation of that principle — the `WhatsAppPhoneAuthenticator` at lines 75–80 — produces a Principal with deliberately narrow scopes:

```java
return Optional.of(new Principal(
        "whatsapp:" + from,
        ctx.tenant(),
        Set.of("channel:whatsapp", "channel:read"),   // narrow on purpose
        claims,
        Instant.now()));
```

The comment on the class explains the reason: "Any sensitive tool a profile exposes requires a wider scope, which a phone-only principal can't unlock without an explicit identity-binding step (§9.4)." The §9.4 sketch in `whatsapp-channel-design.md` (lines 528–539) deferred the actual mechanism — "out of scope for v1 of this doc." This doc is that mechanism.

The gap is structural, not a bug, for two reasons:

1. **A verified phone is not the same as a verified user.** Many tenants' authorization models key on a tenant-internal user-id, an account tier, or a contractual scope set. The phone alone tells the `Authorizer` nothing about which user record to consult. A tenant who wires a `LOOKUP_PRINCIPAL` resolver explicitly maps phone → their user — that is the moment "phone" becomes "user."
2. **Some actions need a fresh interaction trust signal even when the user is known.** "Transfer funds" should not be unlockable solely because the message came from a known phone — phones get lost, SIM-swapped, lent to children. Step-up is the existing primitive every banking + healthcare flow already uses; the design needs it as a first-class engine concept, not as a tenant-rolled tool.

Both shortfalls are solved by composing the three layers below.

---

## 3. The non-negotiables this design preserves

These come from `whatsapp-channel-design.md` §2, `yaya-agentic-design.md` §12, and `operator-auth-design.md` §2. The design only matters if it preserves them:

1. **No credential ever travels over WhatsApp.** No passwords, no API tokens, no bearer JWTs. OTPs are issued on a **different** channel (email or secondary SMS). Step-up tokens minted server-side never round-trip to the user's phone.
2. **The LLM never orchestrates auth.** The bot may paraphrase "I need to verify it's you — check your email for a code." The actual mint / verify / Principal-swap is deterministic engine code; the LLM does not decide when to step up or what hint to honor.
3. **`Deny` reasons stay split.** `userSafeReason` is what the bot may paraphrase; `auditReason` is what we log. A step-up hint carries no PII and never substitutes for either field.
4. **No tool reads its own auth.** Elevation is consumed by the `Authorizer` chain on retry. Tools see a richer `Principal` or get denied — nothing in between.
5. **The phone stays PII.** Elevation never widens the principal's prompt projection past `yaya-agentic-design.md` §7's rules; identity continues to flow into the prompt via `EntityMemory` if the profile opts in.
6. **Tokens are session-bound.** A step-up `Principal` is scoped to a single `channel_sessions.session_id` and dies with it. The next session starts back at the binding's `defaultPrincipalPolicy`.
7. **No cross-tenant or cross-binding elevation.** A step-up flow initiated on sender A in tenant X cannot mint a Principal usable on sender B or in tenant Y, even if the same phone hits both.

---

## 4. The three layers — overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Layer 1 — default principal policy on the binding                           │
│   ChannelBinding.defaultPrincipalPolicy ∈                                   │
│     ANONYMOUS | PHONE_AS_PRINCIPAL | LOOKUP_PRINCIPAL(resolverId)           │
│   Picks the BASELINE Principal that flows out of the channel webhook.       │
│   Solves "I want this phone to act as the tenant-side user u_42."           │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼  inbound message
┌─────────────────────────────────────────────────────────────────────────────┐
│ Engine.send → Authorizer denies tool with a StepUpHint                      │
└────────────────────────────────────┬────────────────────────────────────────┘
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Layer 2 — engine-managed step-up                                            │
│   StepUpCoordinator interprets the hint:                                    │
│     - OTP_EMAIL / OTP_SMS_SECONDARY  →  mint OTP, ship via secondary,       │
│                                         await next-message verification     │
│     - DEEP_LINK                       →  mint one-time signed URL bound to  │
│                                         (session, audience), wait for       │
│                                         callback completion                 │
│     - CUSTOM(stepUpProviderId)        →  tenant-implemented SPI             │
│   On success: elevate Principal for the rest of THIS session, retry the     │
│   denied action exactly once.                                               │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ Layer 3 — WhatsApp playground auth chooser                                  │
│   Mirror of playground-actas-auth: select a Principal at start for testing  │
│   any of Layer 1's policies + a pre-elevated state.                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

The layers are independent. A tenant can ship just Layer 1 and have a working identity story. Layer 2 stands on top, but its trigger is `Authorizer`-driven, not tenant-mandatory. Layer 3 is purely a playground convenience.

---

## 5. Layer 1 — `defaultPrincipalPolicy` on the binding

### 5.1 The shape

`SessionPolicy` (in `whatsapp-channel-design.md` §8 / `channel_bindings.session_policy_json`) gains one field:

```java
public record SessionPolicy(
    Duration             idleTimeout,
    Set<String>          newSessionKeywords,
    Set<String>          endSessionKeywords,
    boolean              greetOnReengagement,
    Optional<TemplateId> reengagementTemplate,
    boolean              exposeEndUserPhoneToProfile,
    DefaultPrincipalPolicy defaultPrincipalPolicy   // NEW
) {}

public sealed interface DefaultPrincipalPolicy {

    /** Today's behavior. Principal carries only channel:whatsapp + channel:read. */
    record Anonymous() implements DefaultPrincipalPolicy {}

    /** Phone IS the identity. The authenticator adds tenant-supplied claims +
     *  scopes to the verified-phone principal, without consulting any external
     *  store. Use when the phone alone is enough for the tenant's authz model
     *  (e.g., "any verified phone is allowed to ask for shipment status"). */
    record PhoneAsPrincipal(
        Set<String>          additionalScopes,   // appended to {channel:whatsapp, channel:read}
        Map<String,Object>   additionalClaims    // e.g. {"role": "guest"}
    ) implements DefaultPrincipalPolicy {}

    /** Tenant resolves the phone to a real user record. Most production use
     *  cases land here. The named resolver bean is looked up by Spring; its
     *  return value REPLACES the default Principal entirely (except for the
     *  reserved channel claims, which the authenticator stamps on top). */
    record LookupPrincipal(
        String               resolverId,         // bean name; must implement PhoneToPrincipalResolver
        boolean              denyOnUnknown       // true = unknown phone gets nothing; false = falls back to PhoneAsPrincipal
    ) implements DefaultPrincipalPolicy {}
}
```

### 5.2 The new SPI

```java
public interface PhoneToPrincipalResolver {
    /** Identity returned in admin console + binding metadata. */
    String id();

    /**
     * Resolve a verified phone to a tenant-side principal. Implementations are
     * tenant code (or platform-provided sample implementations) — typically a
     * JDBC lookup against the tenant's user table.
     *
     * <p>Return {@code Optional.empty()} when the phone does not match a known
     * user; the binding's {@code denyOnUnknown} flag decides the fallback path.
     *
     * <p>The returned Principal MUST NOT contain the channel reserved claims
     * (`channel.kind`, `channel.sender`, `phone`) — those are stamped by the
     * channel authenticator on top of whatever this resolver returns.
     */
    Optional<Principal> resolve(
        TenantId tenant,
        SenderId sender,
        String   e164Phone,
        ResolutionContext ctx);
}
```

The resolver runs inside the inbound dispatch worker (not on the webhook thread). Cache TTL is per-binding (config; default 5 minutes); cache is keyed by `(tenant, sender, phone)` and invalidated when an explicit `principal_links` row changes (see §6.5).

### 5.3 How the existing `WhatsAppPhoneAuthenticator` changes

Today the authenticator hard-codes scopes (line 78). With this design, it reads the policy from the binding and either:

- **`Anonymous`** — current behavior, unchanged.
- **`PhoneAsPrincipal`** — merge `additionalScopes`/`additionalClaims` into the canonical channel claims.
- **`LookupPrincipal`** — call the resolver, then stamp the channel claims on top.

The class moves from a stateless `@Component` to a thin façade that delegates to a per-policy materializer. The reserved header keys (`HEADER_END_USER`, `HEADER_SENDER_ID`, etc.) are unchanged.

```java
@Component
@Order(900)
public class WhatsAppPhoneAuthenticator implements Authenticator {

    private final BindingPolicyResolver bindingPolicy;     // looks up DefaultPrincipalPolicy
    private final PhoneToPrincipalResolverRegistry resolvers;

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) {
        var phone   = headerCi(ctx, HEADER_END_USER);
        var sender  = headerCi(ctx, HEADER_SENDER_ID);
        if (phone == null || sender == null) return Optional.empty();
        if (!phone.startsWith("+")) {
            throw new AuthenticationException(name() + ": malformed end-user phone");
        }
        var policy = bindingPolicy.policyFor(ctx.tenant(), new SenderId(sender));
        return Optional.of(switch (policy) {
            case Anonymous a              -> minimalPrincipal(phone, sender, ctx);
            case PhoneAsPrincipal p       -> phoneAsPrincipal(phone, sender, ctx, p);
            case LookupPrincipal l        -> lookupOrFallback(phone, sender, ctx, l);
        });
    }
}
```

### 5.4 What this alone unlocks

A tenant with a `LookupPrincipal` resolver gets a real `Principal` (subject = their internal user id, scopes = whatever the resolver returns) on every inbound. Their existing `Authorizer` configuration — already tuned for the web channel — works without modification. ~70% of WhatsApp use cases stop here.

What this DOESN'T unlock: actions that require *fresh* interaction trust (high-risk operations, SIM-swap-resistant transactions, anything the tenant's web app already gates with re-auth). Those need Layer 2.

---

## 6. Layer 2 — engine-managed step-up

### 6.1 Extending `AuthzDecision`

The sealed type in `auth/AuthzDecision.java` gains a third variant. Existing `Allow` and `Deny` are unchanged — any switch that handles both already remains valid; the compiler will flag every `switch (decision)` that needs to handle the new variant, which is the right break (`CLAUDE.md` §Conventions: "Adding a new variant means re-reading every switch over the sealed type").

```java
public sealed interface AuthzDecision {
    record Allow(Map<String,Object> obligations) implements AuthzDecision { ... }
    record Deny (String userSafeReason, String auditReason) implements AuthzDecision { ... }

    /** Like Deny, but the engine MAY mount a step-up flow and retry. The same
     *  two-reason rule applies: userSafeReason is paraphrasable, auditReason
     *  is what we log. The hint never reaches the LLM. */
    record DenyOfferStepUp(
        String       userSafeReason,
        String       auditReason,
        StepUpHint   hint
    ) implements AuthzDecision {}
}

public record StepUpHint(
    StepUpMethod method,        // sealed below
    String       audience,      // e.g. "tool:transfer_funds" — used to bind tokens
    Duration     elevationTtl,  // how long the elevated principal survives
    Map<String,Object> params   // method-specific (otp length, deep-link return url, etc.)
) {}

public sealed interface StepUpMethod {
    record OtpEmail()         implements StepUpMethod {}
    record OtpSmsSecondary()  implements StepUpMethod {}  // SMS to a number OTHER than the WhatsApp sender phone
    record DeepLink()         implements StepUpMethod {}
    record Custom(String providerId) implements StepUpMethod {}
}
```

Why a separate variant rather than a metadata blob on `Deny`: callers that pattern-match `Deny(u, a) ->` keep compiling unchanged. The step-up codepath is reached only by callers that explicitly switch over the new variant; the engine is one such caller, every tenant `Authorizer` continues to use plain `Deny` until they opt in.

### 6.2 The runtime — `StepUpCoordinator`

A new engine-internal component sitting between the LLM dispatch and tool execution. When a tool call resolves to `DenyOfferStepUp`:

```
1. Look up an active StepUpProvider by hint.method.
2. Initiate the flow:
     - OTP variants: provider.startOtp(...) → mints a 6-digit code, ships it
       on the SECONDARY channel using a tenant-configured TenantSecondaryComms
       (an SMTP relay for OtpEmail, a non-WhatsApp Twilio number for
       OtpSmsSecondary). Code hash + audience + sessionId persisted in
       step_up_pending. Code itself never logged, never recorded.
     - DeepLink: provider.startDeepLink(...) → mints a JWT bound to
       (sessionId, audience, exp=120s), persists step_up_pending with the
       claim digest, returns a one-time URL the engine includes in the bot
       reply. User opens it; the callback completes the tenant-side OIDC
       (using whatever the tenant already wires for web auth) and posts back
       a server-to-server confirmation with the JWT — yaya-agentic verifies
       and writes step_up_completed.
3. Engine emits a UiHint StepUpRequested event to the channel adapter; the
   WhatsApp adapter renders the user-safe prompt ("Check your email for a
   6-digit code"; deep-link URL when DeepLink). NO tool result is sent back
   to the LLM yet — the turn is suspended.
4. On the next inbound message (OTP) or callback (DeepLink), the
   coordinator verifies, records, and on success:
     a. Mints an elevated Principal by merging the original Principal with
        the step-up scopes/claims from the provider's outcome.
     b. Stamps the elevation onto channel_sessions.elevation_json with the
        TTL from the hint.
     c. Re-runs the original tool call (same args, same tool id) with the
        new Principal. The Authorizer chain is re-evaluated; the previously
        denying Authorizer now Allows (it should — this is the contract);
        the tool executes, returns, conversation resumes.
5. On failure (wrong OTP, expired link, retry budget exhausted): the
   coordinator emits a final Deny that the LLM sees as a normal tool-call
   refusal; the engine continues without elevation.
```

### 6.3 The provider SPI

```java
public interface StepUpProvider {
    StepUpMethod method();

    StartResult start(StepUpStartContext ctx);   // mints, ships, persists
    VerifyResult verify(StepUpVerifyContext ctx); // checks pending against input
}

public record StartResult(
    StepUpPendingId pendingId,
    String          userPromptHint,   // "Check your email for a 6-digit code"
    Optional<URI>   deepLinkUrl
) {}

public record VerifyResult(
    boolean         success,
    Optional<Principal> elevatedPrincipal,
    String          auditReason
) {}
```

Defaults:
- `OtpEmailProvider` — sends via the platform SMTP relay (operator-auth design already provisions this).
- `DeepLinkProvider` — mints + verifies its own short-lived JWTs against an Ed25519 key the tenant has already trusted for `DelegatedHostAuthenticator` (`playground-actas-auth-design.md` §4.1). Reuses the same trust root.
- `OtpSmsSecondaryProvider` — uses the platform's Twilio Programmable SMS (NOT the WhatsApp sender) against a tenant-recorded `secondary_phone` claim from the resolved Principal.

Tenants implement `StepUpProvider` if they want a custom flow (FIDO2 redirect, magic link to their own app, voice-OTP). Looked up by `providerId` from `StepUpMethod.Custom`.

### 6.4 What the LLM sees

The LLM **never** sees the step-up hint. It sees, in order:

1. A tool dispatch (the LLM's own choice).
2. A `ToolResult` describing one of:
   - `pending:step_up_started` with a user-facing prompt (the channel adapter has already shown the user the prompt). The LLM is instructed by the personality system prompt to wait for the user's reply rather than retry the action.
   - `denied:step_up_failed` with a generic deny reason. The LLM treats it as a normal denial.
   - On success (after the next user message verifies), a normal tool result as if the original call had succeeded — the retry is invisible to the LLM.

This keeps the contract from `whatsapp-channel-design.md` §12 / §16 intact: the bot is never the credential channel.

### 6.5 Where the elevated `Principal` lives

A new table `channel_session_elevation` (one row per session):

```sql
CREATE TABLE channel_session_elevation (
    session_id        VARCHAR(64)  PRIMARY KEY REFERENCES channel_sessions(session_id),
    tenant_id         VARCHAR(64)  NOT NULL,
    elevated_subject  VARCHAR(255) NOT NULL,
    added_scopes      TEXT[]       NOT NULL,
    added_claims_json JSONB        NOT NULL,
    audience          VARCHAR(255) NOT NULL,           -- StepUpHint.audience
    minted_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    method            VARCHAR(32)  NOT NULL,            -- 'otp_email' | 'otp_sms' | 'deep_link' | 'custom:<id>'
    pending_audit_id  BIGINT       REFERENCES step_up_audit(id)
);
```

The webhook dispatch worker, on every inbound message, reads this table inside the same query that loads `channel_sessions`. If a row exists and is unexpired, the worker merges its scopes/claims into the Principal the authenticator produces.

`expires_at` is derived from `StepUpHint.elevationTtl` and capped server-side at the binding's `idleTimeout` (so a session that elevates and goes idle past the binding policy can't ride an elevation into a long-future re-engagement).

### 6.6 The pending-flow table

```sql
CREATE TABLE step_up_pending (
    id                BIGSERIAL    PRIMARY KEY,
    session_id        VARCHAR(64)  NOT NULL REFERENCES channel_sessions(session_id),
    audience          VARCHAR(255) NOT NULL,
    method            VARCHAR(32)  NOT NULL,
    code_hash_b64     VARCHAR(255),                      -- OTP only; bcrypt of the issued code
    deep_link_jti     VARCHAR(64),                       -- DeepLink only; JWT id we issued
    target_address    VARCHAR(255) NOT NULL,             -- redacted email / secondary phone / etc.
    attempts          INT          NOT NULL DEFAULT 0,
    max_attempts      INT          NOT NULL DEFAULT 5,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ,
    completion        VARCHAR(16),                       -- 'verified' | 'expired' | 'exhausted' | 'aborted'
    UNIQUE (session_id, audience, completion) DEFERRABLE INITIALLY IMMEDIATE
);
CREATE INDEX step_up_pending_open_idx
    ON step_up_pending(session_id, audience)
    WHERE completion IS NULL;
```

Codes are bcrypt-hashed at rest; the original code lives only in the secondary-channel delivery (email body, SMS body) and in transit. Verification is constant-time. After 5 failed attempts, the row marks `exhausted` and a new step-up MUST be initiated; we never extend an exhausted pending.

### 6.7 The dedicated audit table

```sql
CREATE TABLE step_up_audit (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    sender_id       VARCHAR(64),
    session_id      VARCHAR(64)  NOT NULL,
    audience        VARCHAR(255) NOT NULL,
    method          VARCHAR(32)  NOT NULL,
    initial_subject VARCHAR(255) NOT NULL,
    elevated_subject VARCHAR(255),
    outcome         VARCHAR(32)  NOT NULL,   -- 'started' | 'verified' | 'failed:<reason>' | 'expired' | 'aborted'
    started_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    target_address  VARCHAR(255),            -- masked: "***@example.com" / "+1***1234"
    correlation_ulid VARCHAR(26) NOT NULL    -- shows up in the bot reply on errors
);
```

This is the compliance surface — "who escalated to what, when, why we let them." Distinct from `audit_authz` (which is for normal allow/deny decisions); a single step-up cycle produces one `started` row + one terminating row. Tokens, codes, link bytes, and full target addresses NEVER land here.

---

## 7. Layer 3 — playground auth chooser (WhatsApp playground)

`playground-actas-auth-design.md` already defines an `ActAs` panel for the regular SSE playground. The WhatsApp playground (`PlaygroundWhatsAppController` at `/v1/admin/tenants/{t}/whatsapp-playground/**`) gets a parallel `WhatsAppActAs` panel — same shape as the existing one, plus channel-specific options.

### 7.1 Request shape

The playground `InboundRequest` DTO grows an optional `actAs` field (decoded once at session start, persisted alongside the simulated session):

```jsonc
{
  "text": "what's my order status?",
  "fromPhone": "+15558675309",
  "profileId": "retail-customer",
  "profileVersion": 1,
  "actAs": {                       // optional; absent → use binding's default policy
    "kind": "phone-as-principal",  // see options below
    "additionalScopes": ["order:read"],
    "additionalClaims": {"role": "tester"}
  }
}
```

Supported `kind` values:

| `kind` | What it does | Operator permission |
|---|---|---|
| `default-policy` | Run the binding's `defaultPrincipalPolicy` exactly. The "production-faithful" mode. | `playground:run` |
| `anonymous-override` | Force `Anonymous` regardless of binding. | `playground:run` |
| `phone-as-principal` | Force `PhoneAsPrincipal` with the operator-supplied scopes/claims. | `playground:run` |
| `lookup-principal` | Force `LookupPrincipal` against a named resolver (must already exist in the tenant's config). | `playground:run` |
| `pre-elevated` | Skip step-up by pre-stamping `channel_session_elevation` for the simulated session. Operator declares subject + scopes + audience. | `playground:impersonate` |

The last one is the WhatsApp analog of the regular playground's `signed-identity` shape: it lets an operator test "what happens after step-up succeeds" without running OTPs through real email infrastructure. It is gated on the same `playground:impersonate` permission and writes the same `playground_impersonation_audit` row from §6 of the act-as design.

### 7.2 Backend wiring

`PlaygroundWhatsAppController.sendInbound` (`PlaygroundWhatsAppController.java:73`), after the existing `workspaceAccess.require` + `tenantGuard.requireExists`, applies the `actAs` directive against the in-memory playground sender record. The dispatch worker is unchanged; it reads from the same `channel_session_elevation` table the real flow uses.

### 7.3 Flutter playground UI

The WhatsApp playground screen (`yayaagenticweb/lib/features/playground/whatsapp_playground_screen.dart`) gains a collapsible "Identity" panel above the inbound composer, mirroring the existing act-as panel:

```
┌───────────────────────────────────────────────────────────┐
│ Identity                                            ▾    │
│   ● Use binding's default policy                          │
│   ○ Anonymous                                             │
│   ○ Phone as principal                                    │
│     additional scopes: order:read ×                + add  │
│     additional claims: role: tester                + add  │
│   ○ Lookup principal                                      │
│     resolver: retail-customer-lookup ▾                    │
│   ○ Pre-elevated (skip step-up)            [requires perm]│
│     subject: u_123                                        │
│     scopes:  transfer:funds ×                       + add │
│     audience: tool:transfer_funds                         │
└───────────────────────────────────────────────────────────┘
```

Same persistence rules as the act-as panel: `sessionStorage` only, cleared on tab close. A persistent banner appears while a non-default identity is active:

`▮ Acting as PHONE_AS_PRINCIPAL with +1 scope on tenant acme  ·  end session`

---

## 8. Security non-negotiables (codified)

| # | Rule | Where enforced |
|---|---|---|
| 1 | OTP codes are delivered only over a tenant-configured **secondary** channel (email or non-WhatsApp SMS). Delivery via the same WhatsApp sender is forbidden. | `StepUpProvider.start` precondition; rejected at startup if the relevant secondary channel is not configured. |
| 2 | OTP codes are 6 digits, random from `SecureRandom`, single-use, valid for 5 min, max 5 attempts. Beyond that the pending row is `exhausted`; a fresh start is required. | `step_up_pending.max_attempts` + DB partial-unique. |
| 3 | Deep-link tokens are JWTs bound to `aud: wa-session:<sessionId>:audience:<audience>`. They are unusable on any other session even if intercepted. | `DeepLinkProvider.verify` checks `aud` claim with constant-time compare. |
| 4 | Elevated principals are **session-scoped**. Closing the session, idle-timing out, or sending an `endSessionKeywords` message drops the elevation row. | `ChannelSessionService.closeSession` cascade-deletes `channel_session_elevation`. |
| 5 | Elevation TTL is capped server-side at the binding's `idleTimeout` regardless of `StepUpHint.elevationTtl`. | `StepUpCoordinator.computeExpiresAt`. |
| 6 | The LLM never sees: OTP codes, deep-link URLs in the body (the channel adapter renders them; the LLM sees `pending:step_up_started`), elevation TTLs, or `StepUpHint.params`. | `StepUpCoordinator` emits only structured `pending` / `denied` results to the engine. |
| 7 | Authorizers MAY emit `DenyOfferStepUp` only for actions whose policy explicitly permits step-up. A generic Deny stays Deny; this is opt-in per Authorizer. | Authorizer chain composition convention; default Authorizers emit `Deny`. |
| 8 | A step-up that fails 3 times in a session triggers a **session-level cool-off** (no further step-up attempts for 10 min). | `step_up_audit` query in `StepUpCoordinator.start`. |
| 9 | Step-up tokens are never written to `audit_authz`, `turns.content_json`, `conversation_recorder` payloads, or any log statement except the dedicated `step_up_audit` table. | Recorder redaction rules; greptest in CI. |
| 10 | A step-up for sender A cannot satisfy a denial on sender B in the same tenant. The `audience` claim binds `senderId`. | `DeepLinkProvider.verify` checks `senderId` in the audience tuple. |
| 11 | `LookupPrincipal` resolvers run on the worker pool, not the webhook thread. A misbehaving resolver cannot stall Twilio's 15s ack window. | `InboundDispatchWorker` already runs off the reactive event loop (`whatsapp-channel-design.md` §9). |
| 12 | A `LookupPrincipal` that returns a Principal whose claims include reserved channel keys (`channel.kind`, `channel.sender`, `phone`) is rejected at runtime with a clear startup-time linter equivalent (`PhoneToPrincipalResolverContract`). | `WhatsAppPhoneAuthenticator.lookupOrFallback`. |

---

## 9. Backend wiring

### 9.1 New package layout

```
com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth
├── BindingPolicyResolver               looks up DefaultPrincipalPolicy by (tenant, senderId)
├── DefaultPrincipalPolicy              sealed: Anonymous | PhoneAsPrincipal | LookupPrincipal
├── PhoneToPrincipalResolver            SPI
├── PhoneToPrincipalResolverRegistry    bean-name → resolver lookup
├── ChannelSessionElevation             record + repository
└── package-info.java                   anchors to whatsapp-channel-auth-design.md §5–6

com.yayatechandinnovations.yayaagentic.auth.stepup
├── StepUpHint, StepUpMethod (sealed)
├── StepUpCoordinator                   the runtime
├── StepUpProvider                      SPI
├── providers/
│   ├── OtpEmailProvider                default
│   ├── OtpSmsSecondaryProvider         default
│   └── DeepLinkProvider                default
├── StepUpPendingRepository
├── StepUpAuditRepository
└── package-info.java                   anchors to §6
```

`AuthzDecision.java` gains the `DenyOfferStepUp` variant. Every `switch` over `AuthzDecision` in the codebase needs a new case — that is the explicit compile-break the design takes on, and the prod safety net we want.

### 9.2 Engine integration

`DefaultConversationEngine.send` already runs the `Authorizer` chain per tool call. The integration point is exactly one new arm:

```java
AuthzDecision decision = authorizers.evaluate(toolCall, principal, ctx);
switch (decision) {
    case Allow a            -> dispatcher.run(toolCall, principal, a.obligations());
    case Deny d             -> emitDenyToLlm(d);
    case DenyOfferStepUp ds -> stepUpCoordinator.handle(ds, toolCall, principal, ctx);
}
```

`StepUpCoordinator.handle` is responsible for **all** state transitions (mint, ship, suspend turn, await, verify, elevate, retry). The engine itself stays simple; the coordinator is a single-purpose, fully audited component.

### 9.3 Channel adapter integration

When the coordinator decides to suspend a turn pending OTP / deep-link completion, it emits a new `TurnEvent.UiHint` subtype:

```java
public sealed interface UiHint extends TurnEvent {
    record QuickReplies(...) implements UiHint {}
    record StepUpRequested(String userPrompt, Optional<URI> deepLinkUrl)
        implements UiHint {}
    // ...
}
```

The WhatsApp adapter renders `StepUpRequested` as a normal outbound message containing `userPrompt` (and, for `DeepLink`, the URL inline). It does NOT send any other content from the in-flight turn — the LLM's partial response is dropped because we don't want a half-finished tool argument leaking into the user's view.

### 9.4 Webhook + dispatch worker

`InboundDispatchWorker.process` (already responsible for principal mint and engine invocation per `whatsapp-channel-design.md` §9) gains:

1. **Before engine invocation:** read `step_up_pending` for the resolved session + audience. If a pending row matches and the inbound message body is a 6-digit numeric, route to `StepUpCoordinator.verifyOtp` BEFORE calling the engine.
2. **On verification success:** the coordinator writes `channel_session_elevation`, marks the pending row `verified`, and re-fires the original suspended turn (stored in `paused_turns` via the existing engine pause mechanism — same pattern as `pending_confirm` in `playground-actas-auth-design.md` §5.3). The new tool call runs against the elevated principal.
3. **On verification failure:** the coordinator returns a normal-shape engine response describing the failure ("That code didn't match; please try again or say 'cancel'.").

This means OTP verification is the **only** point in the inbound path where the message body is interpreted *before* the engine sees it. The behavior is gated on (a) an open pending row, (b) body that looks like an OTP. Any other shape goes straight to the engine.

### 9.5 Tenant secondary comms

`TenantSecondaryComms` is a small new abstraction on the tenant row:

```sql
ALTER TABLE tenants ADD COLUMN secondary_comms_json JSONB NOT NULL DEFAULT '{}'::jsonb;
```

```jsonc
{
  "emailRelay": { "from": "noreply@acme.example", "providerId": "platform-smtp" },
  "smsBackup":  { "providerId": "twilio-sms",     "fromNumber": "+15550100123" }
}
```

A tenant that wants OTP-by-email must configure `emailRelay`. A tenant that wants OTP-by-secondary-SMS must configure `smsBackup`. At binding time, the admin console refuses to attach a step-up policy that depends on a missing secondary channel (clear inline error, not a runtime surprise).

---

## 10. API surface

### 10.1 Admin

| Verb | Path | Purpose |
|---|---|---|
| `GET` | `/v1/admin/tenants/{t}/channel-bindings/{id}` | Includes the new `defaultPrincipalPolicy` block. |
| `PUT` | `/v1/admin/tenants/{t}/channel-bindings/{id}/default-principal-policy` | Targeted update to avoid full-row PUT. Validates the resolver bean exists. |
| `GET` | `/v1/admin/tenants/{t}/phone-principal-resolvers` | Lists registered `PhoneToPrincipalResolver` beans for the dropdown. |
| `GET` | `/v1/admin/tenants/{t}/step-up-audit?session_id=&from=&to=` | Operator-facing audit query. PII redacted unless operator has `pii:read`. |
| `POST` | `/v1/admin/tenants/{t}/channel-sessions/{sid}/elevation/revoke` | Manual nuke — closes elevation early for incident response. |

### 10.2 Webhook surface (existing, unchanged)

The Twilio webhook at `/v1/channels/whatsapp/twilio/webhook` is unchanged in shape. The behavioral change is internal: the worker now consults `step_up_pending` and may interpret an inbound code without round-tripping the LLM. No new public endpoint.

### 10.3 Deep-link callback

```
POST /v1/channels/whatsapp/step-up/callback
Authorization: <tenant-side server-to-server credential>
Body: { jwt: "...", subject: "...", scopes: [...] }
```

This is a tenant-side server-to-server call (NOT an end-user redirect target — the tenant's web app completes its own auth in the user's browser, then makes this call from its backend). The endpoint:

1. Verifies the JWT against the tenant's registered Ed25519 public key (same key as `DelegatedHostAuthenticator`).
2. Matches the JWT's `jti` to an open `step_up_pending.deep_link_jti`.
3. Marks the pending row `verified`, writes the elevation, and **wakes** the suspended turn via a session-scoped event the dispatch worker observes (Redis pub/sub or PG `LISTEN/NOTIFY`).

The end user, meanwhile, sees a "you're verified, go back to WhatsApp" page rendered by the tenant; we don't own that surface.

---

## 11. Data model — additions summary

| Table | Purpose | New / changed |
|---|---|---|
| `channel_bindings.session_policy_json` | Now carries `defaultPrincipalPolicy` | Changed (additive JSON field) |
| `channel_session_elevation` | One row per session with active elevation | New (§6.5) |
| `step_up_pending` | In-flight OTP / deep-link state | New (§6.6) |
| `step_up_audit` | Compliance audit of step-up cycles | New (§6.7) |
| `tenants.secondary_comms_json` | Email relay + SMS backup settings | New column on tenants |

All new tables ship in `V40__channel_step_up.sql` (next milestone-W slot per `whatsapp-channel-design.md` §13 — V25–V39 reserved by W0–W8).

---

## 12. Flutter admin UX

### 12.1 Default principal policy on the binding form

The existing channel-binding edit screen (`yayaagenticweb/lib/features/admin/channel_bindings/`) grows a card:

```
┌─────────────────────────────────────────────────────────────┐
│ Identity policy                                            │
│                                                            │
│   ● Anonymous (no identity beyond verified phone)          │
│   ○ Phone as principal                                     │
│       additional scopes: [+ add]                           │
│       additional claims: [+ add]                           │
│   ○ Lookup principal                                       │
│       resolver: [retail-customer-lookup ▾]                 │
│       ☑ deny on unknown (recommended)                      │
│                                                            │
│   ℹ A profile that requires user identity needs this set   │
│     to Phone-as-principal or Lookup-principal. Step-up     │
│     for sensitive actions is configured per Authorizer.    │
└─────────────────────────────────────────────────────────────┘
```

The resolver dropdown is populated from `GET /v1/admin/tenants/{t}/phone-principal-resolvers`. If no resolvers are registered, the "Lookup principal" option is disabled with an inline hint pointing at the operator guide.

### 12.2 Secondary comms on the tenant form

A new "Secondary communications" section on the tenant General tab (visible to TENANT_ADMIN+). Fields for email relay and SMS backup; validated server-side before save. Without these, the binding form blocks step-up-requiring policies.

### 12.3 Step-up audit timeline

The session viewer for a recorded WhatsApp conversation (`yayaagenticweb/lib/features/admin/conversations/`) gets a "Step-up" tab when the session has any `step_up_audit` rows: a chronological list of `started → verified/failed` cycles with the masked target address, method, audience, and outcome.

### 12.4 Playground identity panel

§7.3 above.

---

## 13. Things considered and rejected

**"Just have the bot ask for a password and store its hash."** Hard no — credential channel violation (§3 rule 1). Even with hashing, the credential traverses the WhatsApp transport which we don't trust to be confidential against the user's device (shared phones, family-plan SIMs, screen-mirroring devices in public).

**"A single 'login' tool the LLM calls when it needs auth."** Tempting because it composes with the existing tool model. Rejected because (a) it pushes credential-flow orchestration into LLM control — the LLM might decide to *not* call it, or to call it at the wrong time; (b) the per-tenant variation in how OTP/deep-link delivery works would force each tenant to write the tool from scratch with high-stakes security correctness; (c) tools can't atomically retry-after-elevation, which Layer 2's coordinator does.

**"Use WhatsApp's own 'Verify Now' notification API for OTPs in-band."** Meta does offer a one-tap verification template, but (a) it confirms the *phone* not the *user*, which is what we already had; (b) it's a marketing-category template with attendant cost and limits; (c) it normalizes "WhatsApp is where my codes show up," which we explicitly don't want.

**"Bind elevation to the phone, not the session."** Persistent elevation makes everything easier — and exactly opens the SIM-swap / lost-phone risk we're trying to mitigate. Session-bound is the right default. A future "remember this device for 30 days" flow can ride on top, but it would itself require fresh interaction trust to grant.

**"Add `obligations` to `Deny` instead of a new sealed variant."** Would be source-compatible but semantically confusing (`Deny.obligations` is unread anywhere today; adding it for one specific case bleeds meaning). Compile-time break on `switch (decision)` is a *feature* — it forces every existing site to make a conscious choice about step-up.

**"Let `DenyOfferStepUp` carry the *elevated Principal* the Authorizer would accept."** The Authorizer doesn't know what claims a successful step-up would add — that's the provider's job. The hint stays minimal; the provider's outcome shapes the elevated principal at verification time.

**"Have the StepUpProvider write directly to the Principal store."** No persistent principal store exists for channel principals (and that's a design goal — the phone is the identity). Elevation lives in a session-scoped table by intention.

---

## 14. Failure modes and visible behavior

| Failure | User-visible | What we log |
|---|---|---|
| `LookupPrincipal` resolver throws | Inbound treated as `Anonymous` (or denied per `denyOnUnknown`); user sees the binding's `noBindingTemplate` analog or a generic refusal. | `audit_authz` row, `resolver_failed:<exception>`; alarms on rate. |
| OTP delivery (email/SMS) fails | Bot says "I couldn't send the verification code right now — please try again in a minute." | `step_up_audit.outcome = 'failed:delivery'`. |
| OTP entered wrong | Bot says "That code didn't match. You have N attempts left." | per-attempt log; final exhaust writes audit row. |
| OTP pending row expired before user replies | Bot says "Your verification window expired. Want to try again?" | audit row `expired`. |
| Deep-link clicked but tenant callback never arrives within 5 min | Bot prompts user to retry; pending row marked `expired`. | audit row `expired`. |
| Deep-link callback arrives with wrong `aud` | Callback returns 400; no state change. | dedicated alarm — likely tampering. |
| Step-up succeeds, retry of original action still denies | Bot paraphrases the new Deny's `userSafeReason`. (Indicates Authorizer + StepUp config mismatch — operator misconfig.) | audit row `verified` + `audit_authz` row `denied_after_step_up` — surfaced in admin console. |
| Session closes while step-up pending | Pending row marked `aborted`; user is told the conversation ended and to start over. | audit row `aborted`. |
| Operator manually revokes elevation mid-session | Bot's next tool call against an elevation-requiring action denies; user told "session no longer authorized." | `step_up_audit` close + admin action log. |
| 3+ failures within 10 min in same session | Bot says "Too many verification attempts. Please try again later." Coordinator refuses to start new flows. | rate-limit log. |

---

## 15. Open questions

1. **Should the `audience` model be flat strings or structured?** `"tool:transfer_funds"` is readable; `{"kind":"tool", "id":"transfer_funds"}` is querier-friendly. v1 leans on flat strings with a documented prefix convention; revisit if we add elevation policies that need pattern matching across audiences.

2. **Should elevation be merge-only, or replaceable?** Today the design merges step-up scopes/claims into the existing Principal. If a session steps up twice with different audiences, the elevations accumulate. Argument for replace-on-second: predictability. Argument for merge: realistic ("verified me for funds AND verified my address"). v1 leans merge; a single elevation row per session, scopes union-merged on subsequent successful step-ups.

3. **Should `LookupPrincipal` accept an HTTP-tool-shaped resolver (i.e., not just a bean) so tenants without server-side Java can ship one?** The HTTP-tool dispatcher already exists; reusing it for principal resolution would let a tenant write a webhook in any language. Deferred — the type contract is more important than the transport. If we do this in v2, the resolver wraps a `ToolHandlerRef.Http`.

4. **Should the playground's `pre-elevated` mode be allowed against `LookupPrincipal` bindings, or only `PhoneAsPrincipal`?** Probably both, but with a different audit reason. Worth pressure-testing with the first compliance-sensitive tenant.

5. **Should we surface step-up state to the LLM as a structured "I'm currently authenticated as X" hint via `EntityMemory`?** Symmetric with how the phone gets to the prompt today. Probably yes; the entity is `endUserElevatedSubject` with the same PII rules. Open until we have a profile that needs it.

---

## 16. Test plan

| Layer | Test |
|---|---|
| Unit | `WhatsAppPhoneAuthenticator` produces the right Principal for each `DefaultPrincipalPolicy` variant, including reserved-claim rejection from a misbehaving resolver. |
| Unit | `StepUpCoordinator.handle` for each `StepUpMethod`: state transitions, retry budgets, session-cool-off after 3 failures. |
| Unit | `DeepLinkProvider.verify` rejects tokens with wrong `aud`, expired `exp`, or wrong-tenant signing key. Constant-time HMAC compare. |
| Unit | `OtpEmailProvider` rejects identical code-hash collisions; max-attempt cutoff hard-stops at the configured limit. |
| Integration | Inbound message → Authorizer Deny → no step-up → engine denies, LLM responds normally. (Regression for non-step-up flows.) |
| Integration | Inbound message → Authorizer DenyOfferStepUp(OtpEmail) → OTP shipped to tenant's mock SMTP; second inbound message with code → elevation row written; original tool retried and Allowed. |
| Integration | Same as above but Deep-link: mock callback POSTs → elevation row → suspended turn resumes via PG NOTIFY. |
| Integration | Elevation TTL expiry: elevation written with TTL=2s, sleep 3s, next message → elevation absent, identity-gated action denies. |
| Integration | Cross-session isolation: elevation on session A; new session B from same phone starts at baseline. |
| Integration | Cross-sender isolation: elevation on sender A; deep-link JWT replayed against sender B in same tenant → 400. |
| Integration | `LookupPrincipal` resolver throws → `Anonymous` fallback (or deny per flag) → audit row written. |
| Widget | Channel-binding form: "Identity policy" card renders all three options; Lookup option disabled when no resolvers exist. |
| Widget | Playground "Identity" panel: state cleared on tab close; `pre-elevated` option hidden without `playground:impersonate`. |
| Security | Greptest: no `step_up_pending.code_hash_b64`, `deep_link_jti`, or raw OTP appears in `audit_authz`, `turns`, recorder payloads, or any log line outside `step_up_audit`. |
| Security | Greptest: no `StepUpHint` field reaches `PromptBuilder` or any LLM-facing payload. |
| Security | Load test: rapid OTP-attempt floods against a single session hit the rate-limiter after 5 attempts, then the session-cool-off after 3 cycles. |

---

## 17. Migration

The change is additive. Backwards compatibility is preserved by all three layers having a no-op default:

| Component | Default after migration | Behavior change for existing tenants |
|---|---|---|
| `ChannelBinding.session_policy_json.defaultPrincipalPolicy` | `Anonymous` (matches today's behavior of the authenticator's hard-coded scopes) | None until they edit the binding |
| `AuthzDecision.DenyOfferStepUp` variant | Never emitted by stock Authorizers | None — every existing `Authorizer` keeps returning `Allow` / `Deny` |
| `StepUpCoordinator` | Configured but inert (no `DenyOfferStepUp` to handle) | None |
| `WhatsAppActAs` in playground | Defaults to `default-policy` (which is `Anonymous` for legacy bindings) | None |
| Flyway `V40__channel_step_up.sql` | Adds empty tables; populates `session_policy_json` with `defaultPrincipalPolicy: Anonymous` for every existing binding row | None |

Adoption is opt-in per binding and per Authorizer.

---

## 18. Milestone breakdown

Suggested ordering, each shippable independently:

- **W-A1** — `DefaultPrincipalPolicy` + `Anonymous` + `PhoneAsPrincipal`, refactor `WhatsAppPhoneAuthenticator`. Admin UI: card on binding form. Flutter widget tests. (~3 days)
- **W-A2** — `PhoneToPrincipalResolver` SPI + registry + `LookupPrincipal`. One sample resolver (`SampleJdbcPhoneToPrincipalResolver`). Admin UI: resolver dropdown. (~2 days)
- **W-A3** — Playground identity panel (anonymous/phone/lookup). No step-up yet. (~2 days)
- **W-A4** — `AuthzDecision.DenyOfferStepUp` + sealed switch propagation through the engine + a single Authorizer (test fixture) that emits the new variant. (~1 day)
- **W-A5** — `StepUpCoordinator` skeleton + `OtpEmailProvider` + tenant secondary comms config + `step_up_pending` / `step_up_audit` + admin audit timeline. (~5 days)
- **W-A6** — `DeepLinkProvider` + callback endpoint + suspended-turn wake. (~4 days)
- **W-A7** — `OtpSmsSecondaryProvider`. (~1 day; reuses the OTP flow)
- **W-A8** — Playground `pre-elevated` mode + permission gate + audit hookup. (~1 day)
- **W-A9** — Hardening: rate limits, cool-off, greptest CI, load tests, runbook. (~2 days)

Total roughly **3 weeks** of focused work, parallelizable across two engineers after W-A1 lands the foundation.

---

## 19. Appendix — minimal SPI recap

```java
// auth
public sealed interface AuthzDecision {
    record Allow(Map<String,Object> obligations) implements AuthzDecision {}
    record Deny(String userSafeReason, String auditReason) implements AuthzDecision {}
    record DenyOfferStepUp(String userSafeReason, String auditReason, StepUpHint hint)
        implements AuthzDecision {}
}

public record StepUpHint(StepUpMethod method, String audience,
                         Duration elevationTtl, Map<String,Object> params) {}

public sealed interface StepUpMethod {
    record OtpEmail()         implements StepUpMethod {}
    record OtpSmsSecondary()  implements StepUpMethod {}
    record DeepLink()         implements StepUpMethod {}
    record Custom(String providerId) implements StepUpMethod {}
}

public interface StepUpProvider {
    StepUpMethod method();
    StartResult  start(StepUpStartContext ctx);
    VerifyResult verify(StepUpVerifyContext ctx);
}

// channel binding
public sealed interface DefaultPrincipalPolicy {
    record Anonymous() implements DefaultPrincipalPolicy {}
    record PhoneAsPrincipal(Set<String> additionalScopes,
                            Map<String,Object> additionalClaims)
        implements DefaultPrincipalPolicy {}
    record LookupPrincipal(String resolverId, boolean denyOnUnknown)
        implements DefaultPrincipalPolicy {}
}

public interface PhoneToPrincipalResolver {
    String id();
    Optional<Principal> resolve(TenantId tenant, SenderId sender,
                                String e164Phone, ResolutionContext ctx);
}
```
