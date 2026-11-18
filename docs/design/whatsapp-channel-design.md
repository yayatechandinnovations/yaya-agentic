# WhatsApp Channel — Twilio Sender Onboarding & Profile Binding (v1)

> Companion to `yaya-agentic-design.md` §5.2 (Profile), §5.4 (AuthN), §5.5 (AuthZ),
> §5.6 (Profile resolver chain), §5.7 (Session), §5.9 (Recording), §6 (Lifecycle),
> §8 (Data model), §9 (API surface), §12 (Safety).
>
> Companion to `tenant-registry-design.md` (the tenant is still the single trust
> root — this design adds **channel endpoints** as a fourth projection of that
> trust bundle, alongside inbound origin, outbound host, and authenticator).
>
> Companion to `yaya-agentic-user-accounts-design.md` only in passing: the
> WhatsApp end user is **not** an operator account. They are a phone-identified
> principal, minted by a new authenticator, and never enter the operator-auth
> store.

| | |
|---|---|
| **Status** | 🟡 Proposed |
| **Owner** | Yenfri Herrera |
| **Audience** | Engineering team building yaya-agentic; PM stakeholders evaluating the WhatsApp go-to-market |
| **Last updated** | 2026-06-25 |
| **Scope** | Make WhatsApp a first-class deployment target for any profile a tenant has already built. Programmatic onboarding of WhatsApp senders, with the underlying BSP (Twilio) **fully invisible to the tenant** — they register a sender by describing their business and a phone number, and the platform handles the rest. A typed **channel binding** that hands inbound WhatsApp messages to a yaya-agentic profile, runs them through the normal engine, and dispatches the reply. End-user identity is the phone number, minted by a new `Authenticator`. All inbound + outbound traffic is auditable and survives a Twilio outage with sane backoff. |
| **Out of scope (v1)** | Other WhatsApp BSPs (Meta Cloud API direct, MessageBird, 360dialog) — designed-against but only Twilio ships. **BYO Twilio account.** All v1 senders are provisioned under a single platform-owned Twilio account; per-tenant Twilio credentials are deliberately not modelled here (see §7.3 for the seam that admits them later). Other channels (SMS, Telegram, RCS, Instagram, Messenger) — the `MessagingChannelProvider` SPI is shaped to admit them, no implementations yet. Voice. Click-to-WhatsApp ads attribution. End-user-initiated account linking ("hi, this is also user u_42 on your web app"). |

---

## 1. The problem in one paragraph

Today a tenant who has built a profile in yaya-agentic can only ship it through the SSE web playground or a custom host app they wrote themselves. That works for B2E and B2B SaaS surfaces; it does not work for the largest emerging-markets and SMB channel — WhatsApp — where the user expectation is "I message a phone number, the business answers." The platform should let an operator (1) register a WhatsApp sender against Twilio without leaving the admin console, (2) wait while Meta verifies the business and Twilio approves the display name, and (3) flip a binding so inbound messages on that sender land on a chosen profile. From there the existing engine — personality, profile, AuthZ chain, recorder, RAG — does its job, and the end user gets a coherent conversation over WhatsApp without the engine knowing or caring what transport it came in on. The pieces that don't exist yet are: a real channel abstraction (today `channel` is a free-form string on `StartConversationRequest`), the Twilio sender lifecycle, the inbound webhook + signature path, an outbound dispatcher that respects WhatsApp's 24-hour service window and template rules, and a phone-based authenticator.

---

## 2. Trust principle (read first)

> A **channel endpoint** (a registered WhatsApp sender) is a new projection of the tenant's trust bundle — the fourth, alongside inbound web origin, outbound HTTP host, and authenticator. The phone number IS a tenant-owned identity that the platform speaks AS, and its inbound webhook IS a tenant-owned trust root the platform accepts FROM. They cannot drift from the tenant row, and they cannot be re-pointed at another tenant's profile by anyone who isn't an operator of the owning tenant.

Three corollaries:

- **A sender is owned by exactly one tenant.** It can route to multiple profiles within that tenant (keyword / language / opt-in routing), but never across tenants. Cross-tenant routing is a cloning operation, not a binding flip — same shape as `cloneProfile(A → B)` in `tenant-registry-design.md` §7.
- **The webhook is the inbound trust edge for a sender.** Signature verification is mandatory and non-bypassable; an unsigned (or wrong-signed) call is dropped with an audit row, never with an LLM round-trip. Same posture as the inbound-origin check, just at a different layer.
- **The BSP is a platform implementation detail, not a tenant concern.** All senders are provisioned under a single platform-owned Twilio account. Tenants never see, configure, or hold Twilio credentials; they describe their business and a phone number and the platform handles registration. This is opinionated — see §7 for why and what seam we keep open for the BYO case later.

The fourth, subtler corollary: **the LLM never sees the phone number unless the profile authorizes it.** The principal subject is `whatsapp:+E164`, and like every other principal claim it goes through the same audit-redaction layer §12 calls out. A profile that wants to address the user by phone (rare) declares it as a tool argument and the `Authorizer` evaluates it.

---

## 3. Glossary additions

| Term | Definition |
|---|---|
| **Channel** | The transport a conversation arrives over. Today `web-sse` is implicit; this design promotes channel to a typed first-class concept with its own SPI surface (§5). |
| **Channel endpoint** | A concrete addressable thing inside a channel. For WhatsApp, a *sender* (a phone number registered with Twilio + approved by Meta). For SMS it would be a long code; for Telegram, a bot username. |
| **Channel binding** | A row that routes a channel endpoint to a profile, with an optional routing rule and a session policy. |
| **Sender** | A WhatsApp endpoint the platform owns/operates on the tenant's behalf — phone number + display name + business profile + Twilio account ref. |
| **BSP** | Business Solutions Provider — Meta's term for Twilio, MessageBird, 360dialog, etc. v1 ships one BSP (Twilio); the SPI shape admits more. |
| **Service window** | WhatsApp's 24-hour customer-service window. Inside it, free-form messages are allowed; outside, only pre-approved templates. |
| **Template** | A pre-approved outbound message form (with placeholders), reviewed by Meta. Required for re-engagement and notifications outside the service window. |
| **Completion policy** | Whether a channel adapter streams partial tokens to the user (web SSE) or buffers until the engine emits `End` and dispatches one finalized message (WhatsApp, SMS). |
| **End-user principal** | The `Principal` minted for a WhatsApp sender (`subject = whatsapp:+E164`, `tenant = senderTenant`, claims = `{ phone, locale, twilioContactSid }`). Not an operator account. |

---

## 4. Goals & non-goals

### 4.1 Goals (v1)

- **G1.** A tenant operator can register a WhatsApp sender end-to-end from the admin console without **knowing what Twilio is**: they enter a business profile and a phone number, the platform submits to Twilio behind the scenes, polls for Meta + Twilio approval, and surfaces status live.
- **G2.** Once approved, binding the sender to a profile is one click, reversible, audited.
- **G3.** Inbound WhatsApp messages reach the existing `ConversationEngine` with no special-casing — the engine sees a normal `StartConversationRequest` / `send`, just with `channel="whatsapp"` and a phone-identified `Principal`.
- **G4.** Outbound replies respect WhatsApp's service window: free-form inside 24h, templates outside, refusal-with-fallback if neither is possible.
- **G4a.** Interactive WhatsApp surfaces (Quick Reply Buttons up to 3, List Messages up to 10) are first-class — the engine's existing `UiHint` events render natively, not as numbered text. Inbound button/list selections are normalized back into the engine as if the user had typed the label, so no profile is aware of the channel-level affordance.
- **G5.** The Twilio account is platform-singleton, configured once at deploy time. Tenants have **no Twilio surface** at all — no credential CRUD, no console links, no documentation about Twilio in the admin UI.
- **G6.** Sessions resume coherently across messages from the same end user. New-session keywords (`#new`, `restart`) work. Idle timeout (default 24h) closes a session cleanly and starts a fresh one on the next message.
- **G7.** Every inbound is signature-verified; every outbound is recorded with a `MessageSid` we can correlate to a Twilio delivery callback.
- **G8.** The shape generalizes: SMS, Telegram, RCS, Instagram, and direct Meta Cloud API can be added later by writing one `MessagingChannelProvider` each — no engine changes. A future per-tenant BYO Twilio account can be slotted in behind the existing `ProviderCreds` lookup without changing any other layer (§7.3).
- **G9.** Inbound **voice notes are transcribed and handed to the engine as text** in v1 — a `MediaTranscriber` SPI (default impl: OpenAI Whisper, since the project already takes an `OPENAI_API_KEY` dependency for embeddings) runs before the engine is invoked. Customers consistently ask for this; WhatsApp without voice-note handling feels broken in the target markets.
- **G10.** The end user's **phone number is available to the LLM through the existing `EntityMemory` SPI**, not by widening the principal's prompt projection. The channel adapter captures `endUserPhone`/`endUserChannel`/`endUserLocale` as SESSION-scoped entities at session start; profiles that want to address the user by phone, or pass it as a tool argument, pick it up from entity memory. Profiles that don't reference it never see it. PII redaction in the prompt projection is the profile's call, not the channel's.

### 4.2 Non-goals (v1)

- No outbound media generation (images, audio replies). Inbound non-audio media (images, PDFs) is parsed and surfaced to the engine as `UserMessage.attachments`; engine still answers in text. Inbound audio IS transcribed (G9).
- No automatic template authoring — operators write templates and submit them through the admin console; Meta approval happens out-of-band and we poll/surface the status.
- No campaign / broadcast / marketing-blast support. The engine is conversation-shaped; a campaign tool is a different product surface and would belong on top of this primitive.
- No multi-BSP failover. One sender = one Twilio account. If Twilio is down, queue and retry; we don't sideline to MessageBird.
- No end-user account linking (associating `whatsapp:+1...` with the tenant's web account `u_42`). Doable later via a tool the LLM calls with a one-time code; the principal SPI already supports it.

---

## 5. Channel as a first-class concept

`channel` is currently a free-form `String` on `StartConversationRequest`. This design promotes it to a typed aggregate with its own SPI, because WhatsApp introduces three behaviors that the engine cannot keep guessing per call:

1. **Completion semantics** — WhatsApp can't stream; the adapter must buffer the `TurnEvent` flux to an `End` and send one message.
2. **Outbound dispatch** — the engine doesn't write to the wire on WhatsApp; the adapter does, asynchronously, through Twilio.
3. **End-user identity** — the principal comes from the channel itself (phone number from the inbound payload), not from a JWT header.

### 5.1 The SPIs

```java
public interface MessagingChannelProvider {       // Twilio, MessageBird, Meta Cloud (future)
    ProviderId id();                              // e.g. "twilio"
    Set<ChannelKind> supports();                  // WHATSAPP, SMS, RCS, ...

    /* Sender lifecycle (§6) */
    SenderRegistrationResult registerSender(SenderRegistrationRequest req, ProviderCreds creds);
    SenderSyncResult         syncSender(SenderRef ref, ProviderCreds creds);
    void                     revokeSender(SenderRef ref, ProviderCreds creds);

    /* Inbound trust (§9) */
    InboundValidation validateInbound(RawInboundRequest req, SenderTrustMaterial trust);

    /* Outbound dispatch (§10) */
    OutboundResult sendFreeform(SenderRef ref, OutboundFreeform msg, ProviderCreds creds);
    OutboundResult sendTemplate(SenderRef ref, OutboundTemplate msg, ProviderCreds creds);

    /* Templates (§11) */
    TemplateSyncResult syncTemplates(SenderRef ref, ProviderCreds creds);
}

public interface MessagingChannelAdapter {        // one per ChannelKind; bridges to the engine
    ChannelKind kind();
    CompletionPolicy completionPolicy();
    InboundMessage   parseInbound(RawInboundRequest req, MessagingChannelProvider p);
    void             deliver(OutboundEnvelope env, ChannelDeliveryContext ctx);
}

public sealed interface CompletionPolicy {
    /** Token-by-token; used by web SSE today. */
    record Stream() implements CompletionPolicy {}
    /** Buffer the TurnEvent flux until End, then dispatch one message.
     *  WhatsApp, SMS. maxBuffer is a safety valve — beyond it, flush whatever
     *  we have and continue with a follow-up message. */
    record Complete(Duration maxBuffer) implements CompletionPolicy {}
}

public sealed interface ChannelKind {
    record WhatsApp() implements ChannelKind {}
    record Sms()      implements ChannelKind {}   // future
    record Telegram() implements ChannelKind {}   // future
}
```

The existing `SessionController` is reframed as the adapter for `ChannelKind.WebSse` (which we add only for shape symmetry; runtime behavior is unchanged). The WhatsApp adapter sits beside it, behind `/v1/channels/whatsapp/twilio/*`.

### 5.2 Why split provider from adapter

`MessagingChannelProvider` is per-BSP — its job is Twilio's API surface (register sender, sign, send, sync templates). `MessagingChannelAdapter` is per-channel-kind — its job is "how does WhatsApp behave?" independent of BSP. When MessageBird ships in v2 it implements `MessagingChannelProvider` only; the WhatsApp adapter is unchanged. When SMS ships, an SMS adapter is added; Twilio's provider already supports it.

### 5.3 The channel hint on `StartConversationRequest`

`StartConversationRequest.channel` becomes a structured value, but the existing `String` field is kept for backward compatibility (parsed into the structured form). Adapters populate hints:

```java
// In the WhatsApp adapter, on inbound:
StartConversationRequest req = new StartConversationRequest(
    tenant,
    Optional.empty(),                    // explicit profile resolved by ChannelProfileResolver
    "whatsapp",                          // legacy string
    Map.of(
      "channel.kind",      "whatsapp",
      "channel.senderId",  senderId.value(),
      "channel.endUser",   "+15551234567",
      "channel.locale",    "en-US",
      "channel.bsp",       "twilio",
      "channel.inbound.messageSid", "SMxx..."
    )
);
```

`ChannelProfileResolver` (the stub at `profile/resolvers/ChannelProfileResolver.java`, ordered `@Order(30)`) is the resolver that finally has work to do: it reads `channel.senderId` from hints and looks up the `channel_bindings` row.

---

## 6. Sender lifecycle (state machine)

A sender is a small state machine, durably stored. Twilio's WhatsApp Sender API is asynchronous (Meta business + display-name reviews can take days), so the platform polls and surfaces state — operators don't refresh-the-page-in-Twilio-Console.

```
                             ┌────────────────────────────────────────┐
                             │                                        │
        ┌─────────┐  submit  ┌─────────────┐  twilio ack   ┌──────────▼──────────┐
        │  DRAFT  │─────────▶│ SUBMITTING  │──────────────▶│  PENDING_REVIEW     │
        └─────────┘          └─────────────┘               │  (waiting on Meta /  │
             ▲   ▲                  │ failure              │  Twilio approval)   │
             │   │                  ▼                      └──────┬───────┬──────┘
        edit │   │ retry      ┌─────────┐                         │       │
             │   └────────────│ FAILED  │◀── error / rejection ───┘       │
             │                └─────────┘                                 │ approved
             │                                                            ▼
             │                                                  ┌──────────────────┐
       revoke│                                                  │      ACTIVE      │
             │                                                  └──────────────────┘
             │                                                            │
             │                                                  suspend   │
             │                                                            ▼
             │                                                  ┌──────────────────┐
             │                                                  │    SUSPENDED     │
             │                                                  └──────────────────┘
             │                                                            │
             │                                                  archive   │
             │                                                            ▼
             │                                                  ┌──────────────────┐
             └──────────────────────────────────────────────────│     REVOKED      │
                                                                └──────────────────┘
```

### 6.1 States

| State | Inbound accepted | Outbound allowed | Bindings active | Notes |
|---|---|---|---|---|
| `DRAFT` | no | no | no | Operator-editable. Business profile + phone captured locally; nothing sent to Twilio yet. |
| `SUBMITTING` | no | no | no | Background job calling Twilio's Senders API. Idempotent on retries (uses `SenderId` as the client request key). |
| `PENDING_REVIEW` | yes (logged, refused with friendly message) | no | yes (but dispatch refuses) | Meta + display-name review. Polled hourly. |
| `ACTIVE` | yes | yes | yes | Normal operating state. |
| `SUSPENDED` | yes (logged + acked, no LLM call) | no | yes (but pauses) | Operator-paused or platform-paused (compliance trip). Resume returns to `ACTIVE`. |
| `FAILED` | no | no | no | Terminal-until-edited. Operator edits → returns to `DRAFT`. |
| `REVOKED` | no | no | no | Terminal. Historical bindings retained read-only. |

`SUBMITTING` and `PENDING_REVIEW` can also go straight to `REVOKED` (the §15.1 **Cancel sender**): a registration stuck on an expired verification code or a Meta rejection must not lock the number. Uniqueness of `phone_e164` (and of the BSP SID) applies to non-`REVOKED` rows only (V92), so a cancelled or removed number can be registered again; the revoked row stays as history.

**Number verification.** Twilio verifies its own SMS-capable numbers itself. Any other number gets a one-time code from WhatsApp (by SMS, or voice call when the operator picks it at submit — `verificationMethod`), and the sender reports `PENDING_VERIFICATION` while it waits. The platform holds it in `PENDING_REVIEW`; the operator types the code into the sender card and `POST …/verify` hands it to `MessagingChannelProvider.verifySender`, after which the poller carries the sender to `ACTIVE`. Embedded Signup numbers are verified inside Meta's popup and normally skip this.

**Adopting an existing BSP sender.** A Twilio account can't register the same number twice, yet the first sender of a direct account *must* come from the Console's Self Sign-up, and a submit whose response was lost leaves a sender the platform never recorded. `registerSender` therefore looks the number up on the account first; a match is adopted — webhooks re-pointed at the platform (§14.1), profile left untouched so an approved display name isn't re-reviewed — instead of re-created.

The "inbound accepted, no LLM call" behavior on `PENDING_REVIEW` / `SUSPENDED` matters: WhatsApp customers will keep messaging the number; we acknowledge with a templated "we're not available right now" reply *if a template is available*, otherwise we log and drop without 200ing weirdly — same surface area, different copy per state, configured at sender level.

### 6.2 The `WhatsAppSender` aggregate

```java
public record WhatsAppSender(
    SenderId           id,                  // platform-stable; safe to use as Twilio idempotency key
    TenantId           tenant,
    String             phoneE164,           // unique platform-wide (it's a real phone, can't double-book)
    String             displayName,         // requested; Meta may approve a different one
    String             approvedDisplayName, // null until ACTIVE
    BusinessProfile    business,            // legal name, address, vertical, websites, email
    SenderStatus       status,
    OnboardingState    onboarding,          // last poll result from Twilio
    WebhookSecret      webhookSecret,       // server-generated; used for inbound signature trust
    String             defaultLocale,       // for template selection
    Map<String,Object> metadata,            // operator-set tags
    Instant            createdAt,
    Instant            updatedAt,
    Instant            lastSyncAt
) {}

public record OnboardingState(
    String       twilioSenderSid,           // SR... — Twilio's id once we've submitted
    String       lastTwilioStatus,          // e.g. "ONLINE", "OFFLINE", "PENDING"
    String       lastTwilioFailureCode,     // when applicable
    String       lastTwilioFailureMessage,
    Instant      lastPolledAt,
    int          pollAttempts
) {}

public record BusinessProfile(
    String legalName,
    String displayName,
    String contactEmail,
    String address,
    String websiteUrl,
    String vertical,                        // Meta-enumerated
    String about,                           // short description
    String logoUrl                          // optional profile photo → Twilio profile.logo_url
) {}
```

### 6.3 Why `SenderId` ≠ `twilioSenderSid`

We mint a stable internal `SenderId` at `DRAFT` time. The `twilioSenderSid` is only populated after the first successful `registerSender` call. This matters for two reasons: (1) we can audit and reference an in-progress sender before Twilio knows about it, and (2) re-submissions (after editing a failed draft) use the same `SenderId` so audit history is contiguous, even though Twilio assigns a new `SenderSid` on the second attempt.

### 6.4 The poller

A scheduled job (`SenderSyncJob`, default every 5m, configurable) sweeps senders in `SUBMITTING` or `PENDING_REVIEW` and calls `MessagingChannelProvider.syncSender`. A `SUBMITTING` sender with no BSP SID — its registration call failed transiently — is re-driven through `registerSender` instead, once it has sat for 2 minutes (so the sweep never races a submit in flight); the adopt-before-create lookup (§6.1) makes the retry duplicate-safe. On state transitions, it publishes `WhatsAppSenderStatusChangedEvent` and the cache-invalidating side-effects fire (binding resolver cache, outbound dispatcher cache). Operators can force a sync (or that retry) via `POST .../sync`.

Sync reads the Senders v2 shape: the approved name from `profile.name`, rejection detail from `offline_reasons[]` (surfaced as the card's review notes). `OFFLINE` suspends only a sender that was `ACTIVE`; mid-registration Twilio also reports `OFFLINE` (observed: 410 while the WhatsApp code was still outstanding), so the sender stays `PENDING_REVIEW` and keeps code entry and cancel. The console offers code entry for every `PENDING_REVIEW` sender with a SID, not only on `PENDING_VERIFICATION` — the reported status can lag the SMS or skip it. V93 repaired senders the old mapping had suspended.

Polling is intentionally simple — Twilio also offers status callbacks, but those are best wired up as an **additive** signal (faster updates when available) layered on top of polling (correctness when callbacks are missed). v1 ships the poller; the status-callback endpoint is wired but optional.

---

## 7. Twilio account — platform-singleton

All v1 senders are provisioned under **one** platform-owned Twilio account. There is no per-tenant Twilio row, no per-tenant credential, no Twilio surface in the tenant admin UI. The decision is intentional and opinionated; §7.3 spells out the seam that lets us add BYO without redesign when there's real demand.

### 7.1 Configuration

The account lives in application configuration, not in the database:

```yaml
yaya:
  agentic:
    channels:
      twilio:
        account-sid:      ${TWILIO_ACCOUNT_SID}
        api-key-sid:      ${TWILIO_API_KEY_SID}
        api-key-secret:   ${TWILIO_API_KEY_SECRET}     # SecretRef — read from vault
        # The hostname Twilio dials back into for inbound + status callbacks.
        # Defaults to ${yaya.agentic.public-base-url}; override only if you
        # terminate Twilio webhooks on a different ingress.
        webhook-base-url: ${yaya.agentic.public-base-url}
        # Optional messaging service SID. WhatsApp dispatch always sends
        # From the sender's own number and only falls back to this when a
        # send carries no sender phone (see §7.4).
        messaging-service-sid: ${TWILIO_MESSAGING_SERVICE_SID:}
```

`TWILIO_API_KEY_SECRET` is read through the operator-side `SecretCipher` (`operator_auth/config/SecretCipher.java`) — it's an OPERATOR-tier secret (the platform's, not any tenant's) and naturally belongs on that side of the secrets fence. The operator-deployment pipeline is what rotates it; a redeploy with a new env var is enough. No admin endpoint exists to rotate it because there is no operator role that should be doing that through the API — the rotation owner is the deployment pipeline, not an admin user.

### 7.2 Credential lookup at dispatch

The `MessagingChannelProvider` SPI accepts `ProviderCreds` per call (§5.1). For v1 there is exactly one resolver, and it returns the platform credential regardless of tenant:

```java
public interface ProviderCredsResolver {
    /** Resolve the BSP credentials to use when speaking for this sender. */
    ProviderCreds resolve(WhatsAppSender sender);
}

@Component
public class PlatformSingletonTwilioCredsResolver implements ProviderCredsResolver {
    private final YayaAgenticTwilioProperties props;          // bound from yaml
    @Override
    public ProviderCreds resolve(WhatsAppSender sender) {
        return new TwilioCreds(
            props.accountSid(),
            props.apiKeySid(),
            props.apiKeySecret().reveal(),                    // pulled from vault on read
            props.messagingServiceSid().orElse(null)
        );
    }
}
```

The resolver is the entire BSP-credential surface in v1. The sender row carries no FK to a credential — only the `tenant_id` it belongs to (for routing) and the `twilio_sender_sid` Twilio gave us when we registered it (for sync calls).

### 7.3 The seam for future BYO

When (not if) a high-volume tenant asks to use their own Twilio account, the change is **one resolver swap**:

```java
@Component
public class HybridTwilioCredsResolver implements ProviderCredsResolver {
    private final PlatformSingletonTwilioCredsResolver platform;
    private final TenantTwilioCredentialRepo           tenantCreds;
    @Override
    public ProviderCreds resolve(WhatsAppSender sender) {
        return tenantCreds.findActive(sender.tenant())
                          .<ProviderCreds>map(TwilioCreds::from)
                          .orElseGet(() -> platform.resolve(sender));
    }
}
```

…and a `tenant_twilio_credentials` table (shape similar to the one originally sketched in this design's earlier draft). No other layer of the system changes — not the sender state machine, not the webhook controller, not the dispatcher, not the admin UI's wizard for tenants on the platform path. **The tenant-facing wizard never needs to learn what Twilio is, even after BYO ships** — only the small minority of BYO tenants would see a Twilio-credentials step gated behind a feature flag. That's why the SPI carries `ProviderCreds` as an argument rather than as ambient state: the v1 simplification is "always returns the same value," but the SPI shape doesn't dead-end.

### 7.4 What this means for the operator (yaya-agentic ops, not the tenant)

- One Twilio account to monitor, one billing relationship, one Meta Tech-Provider relationship. The operator is the registered Meta Tech Provider; each tenant's business is registered as a Meta **business** under our Tech Provider — Twilio's Senders API supports this directly.
- Per-tenant outbound message volume is recorded in `outbound_messages` and aggregated for billing (tenants are billed by us, we are billed by Twilio).
- A misbehaving tenant (spam, opt-out violations, bad business profile) can be auto-suspended via the existing sender state machine (`status = SUSPENDED`) before Twilio sees enough volume to fault the master account. Detection lives in §17 dashboards; remediation is operator action.
- Outbound is pinned `From` the sender's own number, never pooled through a shared `MessagingServiceSid`: one platform-wide pool would hold every tenant's numbers, and Twilio's sender selection could answer tenant A's customer from tenant B's number. (Nothing adds senders to a service anyway.) Throughput is per number.

---

## 8. Channel binding (sender → profile)

Once a sender is `ACTIVE`, an operator binds it to a profile. The binding is the thing `ChannelProfileResolver` reads.

```java
public record ChannelBinding(
    BindingId            id,
    TenantId             tenant,
    ChannelEndpointRef   endpoint,          // sealed
    ProfileId            profile,
    Optional<Integer>    profileVersion,    // null = always the ACTIVE version; pin if needed
    RoutingRule          routing,           // sealed; default = MatchAny
    SessionPolicy        sessionPolicy,
    int                  priority,          // when multiple bindings match, lowest wins
    BindingStatus        status,            // ACTIVE | PAUSED | DEACTIVATED
    Instant              createdAt,
    Instant              deactivatedAt
) {}

public sealed interface ChannelEndpointRef {
    record WhatsAppSenderRef(SenderId id) implements ChannelEndpointRef {}
    // future: SmsNumberRef, TelegramBotRef, ...
}

public sealed interface RoutingRule {
    record MatchAny() implements RoutingRule {}
    record KeywordPrefix(List<String> keywords, boolean caseInsensitive)
            implements RoutingRule {}
    record Locale(Set<Locale> locales) implements RoutingRule {}
    record EndUserClaim(String claim, String equals) implements RoutingRule {}
}

public record SessionPolicy(
    Duration             idleTimeout,             // default 24h, max 168h
    Set<String>          newSessionKeywords,      // default: ["#new", "restart", "start over"]
    Set<String>          endSessionKeywords,      // default: ["bye", "done", "thanks bye"]
    boolean              greetOnReengagement,     // template-greet a returning user past idle
    Optional<TemplateId> reengagementTemplate,    // required if greetOnReengagement
    boolean              exposeEndUserPhoneToProfile  // §12 — default true for WhatsApp
) {}
```

### 8.1 Multiple bindings per sender

A sender CAN have multiple bindings, each with a different `RoutingRule`. They're evaluated in `priority` order; the first matching binding wins. This unlocks two real-world flows without inventing a new abstraction:

- **Language routing**: same number, English → `support-en` profile, Spanish → `support-es`.
- **Keyword routing**: same number, `#sales` → `sales-agent`, default → `support-agent`.

If none match, the engine treats the message as un-routable, logs an audit row (`no_binding_matched`), and replies with the sender's `noBindingTemplate` (a per-sender, operator-configured fallback).

### 8.2 `ChannelProfileResolver` becomes real

```java
@Component @Order(30)
public class ChannelProfileResolver implements ProfileResolver {
    private final ChannelBindingService bindings;

    @Override
    public Optional<ProfileId> resolve(StartConversationRequest req, AuthContext auth, Principal p) {
        String senderId = (String) req.hints().get("channel.senderId");
        if (senderId == null) return Optional.empty();
        return bindings.findFor(req.tenant(),
                                new ChannelEndpointRef.WhatsAppSenderRef(new SenderId(senderId)),
                                p)
                       .map(ChannelBinding::profile);
    }
}
```

`findFor` does the routing-rule evaluation. It receives the `Principal` so `EndUserClaim` rules can match against phone-derived claims (locale, opted-in tags). It returns `Optional.empty()` deliberately when no binding matches — the resolver chain falls through to `FallbackProfileResolver` (whose default behavior in v1 is "no profile" — which is what we want: don't accidentally hand a WhatsApp message to a profile the operator didn't authorize for that sender). The webhook handler treats an empty resolution as `no_binding_matched` and triggers the sender's fallback template.

### 8.3 What stays out of the binding

The binding does NOT carry: tool overrides, capability subsets, personality variants. Those are profile-level concerns. If a tenant wants "WhatsApp customers see a simpler profile than web customers," they clone the profile (`tenant-registry-design.md` §7), trim capabilities on the clone, and bind the WhatsApp sender to the clone. Bindings stay thin so they remain hot-path-cheap to evaluate per inbound message.

---

## 9. Inbound message lifecycle

```
                                        Twilio (WhatsApp BSP)
                                                │  POST /v1/channels/whatsapp/twilio/webhook
                                                │  X-Twilio-Signature: ...
                                                │  body: { MessageSid, From, To, Body, NumMedia, ... }
                                                ▼
   ┌────────────────────────────────────────────────────────────────────────────┐
   │  WhatsAppTwilioWebhookController                                           │
   │                                                                            │
   │  1. Resolve sender by To=+E164  ─────────────────────────────▶  404 if none│
   │  2. Verify X-Twilio-Signature with sender.webhookSecret  ────▶  403 if fail│
   │  3. Dedup MessageSid in inbound_messages (24h window)  ──────▶  200 if dup │
   │  4. 200 OK to Twilio  (must ack within ~15s; we DON'T wait on engine)      │
   │  5. Enqueue to InboundDispatchQueue (durable; PG-backed)                   │
   └─────────────────────────────────┬──────────────────────────────────────────┘
                                     │
                                     ▼
   ┌────────────────────────────────────────────────────────────────────────────┐
   │  InboundDispatchWorker (background, per-tenant pool)                       │
   │                                                                            │
   │  6. Mint Principal: WhatsAppPhoneAuthenticator(from=+E164, sender)         │
   │     → subject="whatsapp:+E164", claims={ phone, locale, twilioContactSid } │
   │                                                                            │
   │  7. Resolve session continuity (§9.2)                                      │
   │     - look up channel_sessions(sender_id, end_user_phone, status=OPEN)     │
   │     - if found and (idle < policy.idleTimeout)        → reuse              │
   │     - if newSessionKeyword in body                    → close + open new   │
   │     - else                                            → open new           │
   │                                                                            │
   │  8. Build StartConversationRequest with channel hints + explicit hint that │
   │     channel kind = whatsapp (so personality can drop streaming verbiage)   │
   │                                                                            │
   │  9. ConversationEngine.start(...)  OR  .send(...)                          │
   │                                                                            │
   │ 10. WhatsAppChannelAdapter buffers Flux<TurnEvent> (CompletionPolicy.      │
   │     Complete(maxBuffer=20s)) — concatenates Token events, captures         │
   │     UiHint quick-replies as numbered list, drops Citation events           │
   │     (provenance still recorded by the recorder; just not surfaced in       │
   │     WhatsApp text v1).                                                     │
   │                                                                            │
   │ 11. OutboundDispatcher.send(...) (§10)                                     │
   │     - service-window check                                                 │
   │     - free-form vs template                                                │
   │     - Twilio Messages API                                                  │
   │     - record MessageSid in outbound_messages for delivery-receipt join     │
   └────────────────────────────────────────────────────────────────────────────┘
```

### 9.1 Why ack-then-process, not synchronous

Twilio expects a 200 within ~15s. The engine's LLM round-trip plus possible tool calls routinely exceeds that — RAG + tool call + token streaming is easily 5–30s. So step 4 returns 200 immediately and step 5 onward runs out-of-band. The trade-off is that we cannot return TwiML (Twilio's response-based reply mechanism) — we use the **Messages API** for every outbound. That's the recommended path for any non-trivial bot and it's the path that lets us survive process restarts without dropping a half-built reply on the floor.

### 9.2 Session continuity

A `channel_sessions` row maps `(sender_id, end_user_phone, status)` to a `SessionId`. Lookup precedence:

1. If `body` matches `newSessionKeywords`, close any open session (`status=CLOSED`, `reason=USER_REQUESTED_NEW`) and open a fresh one. **This means the new-session keyword is consumed**; the engine doesn't see it as a user message.
2. Else if an `OPEN` session exists for `(sender, end_user_phone)` and `now - last_activity_at < policy.idleTimeout`, reuse it.
3. Else if an `OPEN` session exists but is idle past the timeout, close it (`reason=IDLE_TIMEOUT`) and open a new one. If `policy.greetOnReengagement`, the next outbound is the configured re-engagement template; the engine's first turn flows immediately after.
4. Else open a new session.

`channel_sessions` is **not** the same table as `sessions` (which the recorder owns). It's a thin index outside the recorder, because the recorder's job is the conversation transcript, not the channel-side mapping. Keeping them separate means changing recorder backends doesn't risk losing session continuity, and the recorder doesn't need to know about phone numbers at all.

### 9.3 The `WhatsAppPhoneAuthenticator`

```java
public final class WhatsAppPhoneAuthenticator implements Authenticator {
    @Override
    public Principal authenticate(AuthContext ctx) {
        String from   = (String) ctx.attributes().get("channel.endUser");
        String sender = (String) ctx.attributes().get("channel.senderId");
        if (from == null || sender == null) return null;       // not our path
        return new Principal(
            "whatsapp:" + from,
            ctx.tenant(),
            Set.of("channel:whatsapp", "channel:read"),       // narrow scopes by default
            Map.of(
                "phone",          from,
                "channel.sender", sender,
                "channel.kind",   "whatsapp"
            ),
            Instant.now()
        );
    }
}
```

Ordering: this authenticator runs **last** (lowest `@Order`), after `OidcAuthenticator` and `SharedSecretJwtAuthenticator`, so it only fires on requests that came in through the WhatsApp adapter (which is the only path that sets `channel.endUser`). For a JWT-authenticated request, the existing authenticators win.

Scopes are deliberately narrow — `channel:whatsapp` and `channel:read`. The `Authorizer` chain on the profile decides what these scopes are allowed to do. A profile that exposes an "update billing" capability with `requires.scope=billing:write` will deny by default, and the profile's denial UX (§6.5 of the master design doc) explains it in user-safe terms. This is the lever: a profile is safe to expose over WhatsApp because the scopes a phone-only principal carries can't unlock dangerous tools without an additional identity-binding step.

### 9.4 Account linking (sketch, post-v1)

When v2 ships account linking, the flow is:

1. End user says "link my account."
2. Profile invokes a `request_link_code` tool that calls back into the tenant's own web app and gets a one-time code.
3. Tenant's web app, signed in as `u_42`, shows the code.
4. End user types it into WhatsApp.
5. Tool `confirm_link_code` validates, and the platform writes a `principal_links` row: `whatsapp:+E164 ↔ u_42`.
6. On the next message, the authenticator resolves to a wider-scoped principal (`channel:whatsapp` + the operator-defined scopes for `u_42`).

The principal SPI already accommodates this; the schema and tool implementation are out of scope for v1 of this doc.

### 9.5 Voice notes & inbound media

WhatsApp voice notes are how a large fraction of users in the target markets actually communicate — typing is the exception, not the rule. Treating them as opaque attachments would make the bot feel broken. v1 ships transcription as a **first-class step in the inbound worker**, behind a swappable SPI:

```java
public interface MediaTranscriber {
    /** What this transcriber can handle. Twilio's WhatsApp voice notes are typically
     *  "audio/ogg; codecs=opus" (~mobile) or "audio/mpeg" (~desktop). */
    Set<String> supportedContentTypes();

    TranscriptionResult transcribe(InboundMediaRef media, TranscriptionContext ctx);
}

public record InboundMediaRef(
    URI    twilioMediaUrl,    // signed Twilio CDN URL
    String contentType,
    long   sizeBytes
) {}

public record TranscriptionResult(
    String   text,
    String   detectedLanguage,    // BCP-47, e.g. "es-DO"
    double   confidence,
    Duration audioDuration,
    String   modelId              // for audit
) {}
```

**Default implementation (`WhisperMediaTranscriber`):** OpenAI Whisper via Spring AI. The platform already takes `OPENAI_API_KEY` as a dependency for embeddings (per `CLAUDE.md` §Build & run), so adding the transcription dependency on the same provider keeps the operational footprint flat. Anthropic-hosted transcription, when GA, slots in as a second implementation behind the same SPI without engine changes.

**Inbound worker integration.** Step 6 of §9 (between principal mint and session resolution) gets an additional sub-step:

```
6a. For each media item in inbound_messages.raw_payload_json:
      if contentType ∈ transcriber.supportedContentTypes()
        result = transcriber.transcribe(...)
        store result in inbound_messages.media_transcripts_json
        if message body was empty:
            body = result.text
        else:
            body = body + "\n\n[voice note: " + result.text + "]"
      else:
        record as attachment metadata; LLM still sees text body only
```

The engine receives the transcribed text in `UserMessage.text` — it does NOT need to know that the source was audio. The original media URL stays in `UserMessage.attachments` for profiles that explicitly opt into multimodal handling later.

**Hard limits enforced before transcription** (cost & abuse control):

| Limit | Default | Rationale |
|---|---|---|
| Max audio duration per message | 5 min | Whisper API charges per second; a 30-min rant is almost always misuse or accident |
| Max audio messages per session | 50 | Catches loops and abuse |
| Per-tenant monthly quota | configurable, default 10k min | Operator dial; surfaced on tenant billing usage |
| Total inbound media size per message | 16 MB | Twilio's hard cap; we reject earlier with friendlier copy |

Beyond a limit, the user gets a templated reply (`"That voice note is longer than I can transcribe — could you send it as text or break it into shorter parts?"`) and the engine is NOT invoked.

**Privacy.** Transcripts are stored in `inbound_messages.media_transcripts_json` alongside the audio URL, encrypted at rest via the recorder's `EncryptingRecorder` decorator (same path as `turns.content_json`). The audio bytes themselves are not stored — we re-fetch the signed Twilio URL on-demand for replay/debug, which Twilio rotates out after 24h, so the audio is naturally ephemeral. Tenant operators can disable transcription on a per-sender basis (`whatsapp_senders.metadata_json.transcribeInboundAudio = false`); in that case audio messages are surfaced to the LLM only as `[voice note (not transcribed)]` and the engine asks the user to type instead.

**Inbound media, revised (2026-11-18 — assistant-channels design §3.3, §3.4,
§3.6, §4.2; tracker contract C).** Nothing is silently dropped any more:

- A message with media and no text is a turn: the text becomes «(foto)»,
  «(documento: nombre.pdf)» or the transcript, so the engine runs.
- Photos: `image/*` (not HEIC/HEIF, not WebP stickers) are downloaded with the
  sender's Twilio Basic auth through `InboundMediaClient` (its own 16 MB
  in-memory bound — the global codec size stays 4 MB), re-encoded by
  `ImageHygiene` (orientation kept, EXIF gone, ≤ 1,600 px), read for QR /
  barcodes (`ImageCodes`); the codes ride the user text inside the
  untrusted-data markers («Código leído en la imagen: …»), the images go to
  the model as vision input (≤ 4, 5 MB each).
- PDFs: downloaded (≤ 15 MB), text extracted with Tika through
  `ChatAttachmentService.upload`, kept as a `chat_attachments` row of the
  engine session (`principal_subject = whatsapp:<phone>`), composed as
  delimited data, marked referenced with the turn id when the turn ends.
- Fixed replies, no model call: video / stickers (`image/webp`) / contacts /
  locations → «Todavía no puedo abrir ese tipo de archivo. Mándame una foto,
  un PDF o una nota de voz.»; HEIC/HEIF → «Mándala como JPG o PNG»; a voice
  note with no transcriber bean, or one that fails → «No pude escuchar la nota
  de voz, ¿me lo escribes?»; over 120 s → «La nota de voz es muy larga (máximo
  2 minutos)»; the tenant's voice allowance used up → «Este mes se agotaron
  los minutos de voz; escríbeme.». When the message also carries text, the
  reply is sent and the text still runs. A media-only message that got a fixed
  reply is stamped `processing_error = media_unsupported`.
- Voice: the cap is now **120 s**
  (`yaya.agentic.channels.whatsapp.transcription.max-seconds`); the
  transcriber receives the sender's locale as `language` and the domain
  `prompt` (`yaya.agentic.transcribe.*`); the length is read from the
  container before the API is called; `voice_seconds` and `images_in` are
  metered per tenant and profile (`UsageMeter`, V96). The table above is
  superseded where it differs (5 min → 2 min; the monthly quota is
  `yaya.agentic.voice.monthly-seconds`).

**SPI swap surface.** A future `AnthropicAudioTranscriber` or `LocalWhisperTranscriber` replaces the default by Spring's standard `@Primary` + `@Profile` machinery. Profiles can be picky (a high-trust medical profile that requires HIPAA-eligible transcription would bind a different transcriber) — left as a future per-binding override; out of scope for v1, but the SPI shape doesn't dead-end.

---

## 10. Outbound dispatch

The engine emits a `Flux<TurnEvent>`. For `web-sse`, the SSE controller pipes it through. For WhatsApp, the adapter buffers, transforms, and dispatches.

### 10.1 Buffering rules (`CompletionPolicy.Complete`)

- `Token` events concatenated until `End` — yields the message body text.
- `ToolCall` / `ToolResult` events surfaced internally to the recorder via the existing path; not sent to WhatsApp.
- `Citation` events captured in metadata; not sent to WhatsApp in v1 (Twilio can carry them as message attributes for analytics but no end-user surface).
- `UiHint` events drive the **interactive payload** that wraps the message (§10.2).
- `maxBuffer` (default 20s) is a guardrail. If we hit it without an `End`, we flush what we have followed by a soft continuation marker (`(more...)`) and continue dispatching as additional WhatsApp messages — within the same session.

### 10.2 Interactive rendering (Quick Reply Buttons, List Messages)

WhatsApp's interactive API has two shapes the adapter renders natively. The engine emits the SAME `UiHint` events it already emits for the web SSE adapter; the WhatsApp adapter picks the right shape per the constraints below.

```
// Inputs (from the engine — channel-agnostic; the one canonical shape of
// assistant-channels design §2.2, every producer converted by the engine):
TurnEvent.UiHint(
  kind    = "quick_replies",
  payload = {
    "options": [
      { "id": "by_category", "label": "By category", "send": "Show me the catalogue by category" },
      { "id": "show_list",   "label": "Show list",   "send": "Show list" },
      { "id": "export",      "label": "Export",      "send": "Export" }
    ],
    "source": "rule|start|ask|suggester|frame",
    "headerText": null,      // optional; rendered as List header when present
    "footerText": null       // optional; rendered as List footer when present
  }
)
```

The renderer also still reads the legacy `items:[…]` / `actions:[{label,send}]`
shapes (a parked outbound from an older build). Button and row ids carry the
`opt:` prefix (`opt:<option id>`), so the inbound worker tells an option apart
from a frame (`confirm:` / `cancel:`) or a bound action (`act:`).

Adapter decision (`InteractiveRenderer.render(UiHint)`):

| Option count | Twilio surface | Notes |
|---|---|---|
| **0** | plain text message | nothing to render |
| **1–3** | **Quick Reply Buttons** through the in-session `twilio/quick-reply` Content resource (`InSessionContents.quickReply`) | payload `opt:<id>`, `title` ≤ 20 chars; a yes/no question (`_askUser yes_no`, suggester `yes_no`) is the two buttons «Sí» / «No» |
| **4–10** | **native list picker** through the in-session `twilio/list-picker` resource (`InSessionContents.listPicker`): body, button «Elegir», rows `{id, item ≤ 24, description ≤ 72}` | a row's description is the option's `send` when it differs from its label; if the Content API refuses, the numbered-text fallback below goes out instead |
| **>10** | list picker, **paginated** | first 9 options + a 10th row «Más…» (`opt:__more__`); the inbound worker answers it with the next page (`renderPage`, offset +9) straight from the options last offered, without waking the engine |
| labels > 20 chars (button mode) | downgrade to the list picker | WhatsApp enforces 20-char button title cap |

A confirm or plan frame plus chips in the same turn sends the **frame's buttons
only**: a WhatsApp message carries one interactive block, and the frame is the
turn's question (`OutboundDispatcher.Buffer` keeps the frame).

Body text comes from the concatenated `Token` stream; the interactive payload is attached as Twilio's `ContentVariables`/`PersistentAction` fields per the Twilio Content API. Citation footnotes (if any) go into the optional `footerText` truncated to WhatsApp's 60-char cap; the rest is recorded but not surfaced.

The renderer is its own SPI so a non-Twilio BSP can swap it:

```java
public interface InteractiveRenderer {
    InteractivePayload render(String bodyText, UiHint hint, RenderContext ctx);
}

public sealed interface InteractivePayload {
    record PlainText(String body) implements InteractivePayload {}
    record Buttons(String body, List<Button> buttons, String footer)
            implements InteractivePayload {}
    record List(String body, String header, String buttonText,
                List<Section> sections, String footer)
            implements InteractivePayload {}

    record Button(String id, String title) {}
    record Section(String title, List<Row> rows) {}
    record Row(String id, String title, String description) {}
}
```

When the engine emits more than one `UiHint` in the same turn, only the **last** one shapes the outbound (consistent with the web SSE's single quick-reply rail). Earlier hints are recorded but dropped from the wire payload — they're a UX bug at the profile level and we surface a `channel_outbound_multiple_uihints_total` counter so it shows up.

### 10.3 Inbound selection normalization

A user tapping a button or picking a list row sends a structured Twilio webhook payload that includes a `ButtonText` / `ListId` / `ListTitle` (Twilio surfaces these alongside `Body`). The adapter normalizes this back to the engine as if the user had typed the label, **and** preserves the option `id` as a hint:

```
// Inbound after a button tap on option id="by_category", title="By category":
UserMessage.text  = "By category"
UserMessage.hints = {
  "channel.inbound.interactiveId":   "by_category",
  "channel.inbound.interactiveKind": "button"        // or "list_row"
}
```

The profile/LLM only ever sees `"By category"` and doesn't have to know about WhatsApp's interactive surface. The structured `id` is available for profiles that want deterministic dispatch — a `LlmRouter` can short-circuit on `interactiveId` before invoking the LLM, saving a turn — but that's a profile-level optimization, not a channel-level requirement.

**Options map back to what was offered** (assistant-channels design §2.6).
Every outbound with a `quick_replies` hint records its options in Redis for
20 minutes per (tenant, sender, phone) — `OfferedOptionsStore`, key
`yaya:wa:offered:<tenant>:<sender>:<phone>` — together with whether they went
out as numbered text and which page is showing; an outbound without options
clears the record. On the next inbound:

- a button payload `opt:<id>` or a `ListId` → `selection {kind:"option", id, send}`
  and the engine's user text is the option's `send` (its label when the record
  is gone, as before);
- a reply that is only a number («2») while a **numbered-text** list is on
  record → that row's `send` — the model never reads «2» as a quantity; after
  native buttons or once the record expired a digit is just a digit;
- the «Más…» row (`opt:__more__`, or its number) → the next page goes out and
  the engine is not woken.

The record is cleared once a tap or a number was consumed.

For the special **"More…" pagination row** (the 10-options-overflow case), the adapter intercepts the inbound, sends the next list page directly, and never wakes the engine — the conversation hasn't actually advanced. The recorder still gets a `channel_event` row so the audit trail shows what the user saw.

### 10.4 Fallback and template interactions

- **Templates can carry interactive components too** (button templates, list templates). When `send_template` is invoked, the template's BSP-side definition already encodes its interactive payload; the adapter passes through `variables` and lets Twilio render. The `UiHint`-based renderer in §10.2 is the **free-form** path only.
- **Free-form interactive messages are bound by the 24h service window** like any other free-form message — no special privilege. If outside the window, they get parked in `outbound_pending` (hint payload included) and released on the next inbound, just like plain text; the released hint renders with the same buttons / list picker and records its options then.
- **Non-WhatsApp BSPs that don't support interactives** at all can register an `InteractiveRenderer` that always returns `PlainText` with numbered options inline — the same numbered-list fallback we'd otherwise have shipped as the default. Twilio doesn't need it.

### 10.2 The 24-hour service window

For every outbound, the adapter checks `now - last_inbound_at(sender, end_user)`:

- **≤ 24h**: send free-form via `MessagingChannelProvider.sendFreeform`.
- **> 24h**: free-form is forbidden by Meta policy; the platform refuses with a typed result:

```java
public sealed interface OutboundResult {
    record Sent(String messageSid, Instant sentAt) implements OutboundResult {}
    record OutsideServiceWindow(Duration sinceInbound) implements OutboundResult {}
    record TemplateRequired(TemplateId suggested) implements OutboundResult {}
    record ProviderFailure(String code, String message, boolean retriable)
            implements OutboundResult {}
}
```

The engine treats `OutsideServiceWindow` and `TemplateRequired` as **engine-level back-pressure**: the buffered reply is parked in `outbound_pending` and the next inbound from the same end user releases it (it's now back inside the window). For genuine re-engagement (the bot wants to push something, not reply to a user), the operator must publish a template and the profile's tool calls `send_template`, which goes through `sendTemplate`.

The combination is intentional: free-form replies "just work" within the window; outside the window, the only way to reach the user is a template approved at config time, which is exactly the WhatsApp Business policy. The engine doesn't try to be smarter than the rules.

### 10.3 Retries and delivery callbacks

Twilio's response on `sendFreeform` gives us a `MessageSid` but not delivery status. We store the sid in `outbound_messages(sender_id, end_user_phone, message_sid, status='QUEUED', ...)` and Twilio calls our `/v1/channels/whatsapp/twilio/status-callback` with terminal status. Failed deliveries are retried per `OutboundRetryPolicy` (default: 3 attempts, exponential 1s → 8s → 64s, jitter 20%) — `5xx`s and rate-limited errors only; `4xx` user-error responses (invalid phone, blocked recipient) are terminal and audited.

The status-callback path also has a signature verification — same `webhookSecret` flow as inbound — because Twilio status callbacks are also a trust edge.

---

## 11. Templates registry & opt-in / opt-out

### 11.1 Templates

> **Superseded 2026-11-16** by `whatsapp-shared-templates-design.md`: a template is a row tied
> to a WhatsApp account + locale + name (`account_key`), and `whatsapp_template_holders` records
> which workspaces may use it. The shape below is the original per-sender one.

A template is a row tied to a sender + locale + name:

```
whatsapp_templates(
    id              BIGINT PK,
    sender_id       VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    locale          VARCHAR(16)  NOT NULL,
    category        VARCHAR(32)  NOT NULL,   -- MARKETING | UTILITY | AUTHENTICATION
    body            TEXT         NOT NULL,   -- with {{1}} {{2}} placeholders
    placeholder_count INT        NOT NULL,
    bsp_template_id VARCHAR(128),            -- Meta/Twilio's id once approved
    status          VARCHAR(16)  NOT NULL,   -- DRAFT | SUBMITTED | APPROVED | REJECTED | PAUSED
    submitted_at    TIMESTAMPTZ,
    approved_at     TIMESTAMPTZ,
    UNIQUE(sender_id, name, locale)
);
```

Profiles reach templates through a typed tool: `send_template`. The tool descriptor is part of a built-in **`channel-whatsapp` capability bundle** that any profile can opt into:

```
capability: send_whatsapp_template
tool:       send_whatsapp_template_tool   (BEAN)
requires:   scope=channel:whatsapp:write
input:      { templateName: string, locale: string, variables: string[] }
output:     { messageSid: string }
```

The Authorizer chain decides whether the current principal can call it. By default a phone-only principal CANNOT (no `channel:whatsapp:write` scope); the profile must escalate (account linking, or operator opt-in). This is the same model as everything else — the LLM proposes, the Authorizer disposes.

### 11.2 Opt-in / opt-out

Inbound `STOP`, `UNSUB`, `CANCEL`, `QUIT` (case-insensitive, exact match): the adapter immediately:

1. Records an `opt_outs(sender_id, end_user_phone, optout_at)` row.
2. Closes the open session (`reason=USER_OPTED_OUT`).
3. Replies with a fixed confirmation: *"You're unsubscribed. Reply START to resume."*
4. **Does not** invoke the engine.

Inbound `START`, `UNSTOP`, `SUBSCRIBE`: removes the opt-out row, opens nothing automatically — the next real message starts a fresh session.

Outbound dispatch consults `opt_outs` and refuses, returning `OptedOut` (a fifth `OutboundResult` variant we'll need; left out of §10.2 to keep the explanation focused).

This is mandated by Meta policy; we ship it on by default. Operators cannot disable it.

### 11.3 Human hold (operator takeover)

An integration that answers customers itself — a support desk picking up "talk to a person" — needs the assistant to
stop answering while a person is on the conversation. A trusted system sender holds it:

```
POST   /v1/integrations/{tenant}/senders/{sid}/holds           { phone, minutes, reason }  → 201 { phone, heldUntil }
POST   /v1/integrations/{tenant}/senders/{sid}/holds/release   { phone }                   → 200
GET    /v1/integrations/{tenant}/senders/{sid}/holds/messages?phone=&since=                → the held transcript
```

While a hold is live, the inbound worker acks and stores the message with `processing_error = human_hold` and
**does not invoke the engine** (§9.2, step 1c, right after the opt-out checks). Outbound is untouched: that is what
lets the desk answer through the ingest edge (`system-messaging-design.md` §5) and what makes the transcript readable
back through `/holds/messages`, which returns the stored bodies since the hold started.

The hold is keyed by `(tenant_id, end_user_phone)`, not by sender: a phone belongs to one person whatever sender they
reached, and the desk knows the phone, not our sender ids. Every hold carries an expiry, clamped to seven days, so a
desk that forgets to release one gives the customer the assistant back instead of silence. The trust gate is the same
one the ingest edge uses (`IntegrationSenderGate`: sender enabled, verified principal with `system:inbound:send`,
pinned credential, nonce and timestamp), so one service token covers sending, holding and reading.

---

## 12. End-user identity, audit, PII

A WhatsApp end user's phone number is PII. The principal subject `whatsapp:+15551234567` is a stable identifier we need everywhere for session continuity and audit. The phone reaches the LLM through a controlled path: the channel adapter publishes it into the session's `EntityMemory` (see `entity-memory-design.md` §3–4) and the standard prompt projection picks it up. **The principal itself stays PII-free in the prompt** — `yaya-agentic-design.md` §7 already enforces "principal display name only, no PII," and we don't widen that.

### 12.1 EntityMemory capture at session start

On every `engine.start` for `channel=whatsapp`, the adapter captures three SESSION-scoped singleton entities:

```
endUserPhone     = "+15551234567"   producedBy = SyntheticToolId.CHANNEL_WHATSAPP_INBOUND
endUserChannel   = "whatsapp"        producedBy = SyntheticToolId.CHANNEL_WHATSAPP_INBOUND
endUserLocale    = "en-US"           producedBy = SyntheticToolId.CHANNEL_WHATSAPP_INBOUND
```

`SyntheticToolId.CHANNEL_WHATSAPP_INBOUND` is a reserved ToolId — `EntityRef.producedBy` is typed as `ToolId` in the entity-memory design, and this is the path for non-tool capture sources to plug in without weakening the type. The id namespace is reserved at startup; no real tool can claim it.

Profiles that want the phone in-prompt get it for free — the entity is projected automatically by `PromptBuilder` per the entity-memory design's §9. Profiles that don't reference `endUserPhone` never see it. **There is no special channel-side prompt patching** — this is the same SPI that any tool would use to capture context.

Per-binding override: `SessionPolicy.exposeEndUserPhoneToProfile = false` skips the `endUserPhone` capture but still captures channel + locale. This is the lever for the rare profile that legitimately doesn't want phone exposure (e.g., an anonymous-feedback bot).

### 12.2 PII rendering & redaction

`EntityMemory` projection respects the same PII tagging rules as `WorkingMemory`. The default capture metadata for `endUserPhone` includes `pii: true`, which means the recorder's `RedactingRecorder` decorator masks it in audit logs / exports unless the reading operator role has `pii:read`. In-prompt rendering is verbatim by default; profiles that want to render the phone as `(555) ***-1234` for the LLM set a render hook in the profile's metadata (out of scope for this design; the profile-side hook is the right place).

### 12.3 Storage / audit invariants

- `audit_authz.principal_subject` stores `whatsapp:+15551234567` literally — needed to query "what did this user do." It is treated as PII in exports.
- `turns.content_json` may carry the phone if the LLM referenced it; the recorder's redaction decorator masks per the standard `pii: true` flag in the entity capture.
- Twilio's `From` field on inbound messages is the only source of truth for the phone; we never accept a phone in the `Body` and treat it as identity. (Spoofing the `From` requires defeating Twilio signature verification.)
- The `endUserChannel` entity makes it cheap for profiles to vary behavior across channels without a `if (channel == "whatsapp")` branch in code — the LLM reads the entity and adjusts (shorter messages, fewer hyperlinks). That's leverage we get for free out of treating channel as data.

---

## 13. Data model

All tables tenant-scoped (except the platform-singleton Twilio config, which lives in `application.yaml`, not the DB — see §7.1). The first migration lands `whatsapp_senders` in `V25__whatsapp_senders.sql` — V23 + V24 are reserved by `V22__tenants_additive_columns.sql`'s preamble for the tenants UUID PK cutover, so the channel feature slots in at V25. Subsequent channel tables (bindings, sessions, inbound/outbound, templates, opt-outs) land in their own per-milestone migrations (V26, V27, …) so each W-milestone has a self-contained data delta. New tables:

```sql
CREATE TABLE whatsapp_senders (
    id                       VARCHAR(64)  PRIMARY KEY,           -- platform-stable
    tenant_id                VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    phone_e164               VARCHAR(20)  NOT NULL,
    display_name             VARCHAR(128) NOT NULL,
    approved_display_name    VARCHAR(128),
    business_profile_json    JSONB        NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    twilio_sender_sid        VARCHAR(64),                        -- assigned on SUBMIT
    onboarding_state_json    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    webhook_secret_enc       BYTEA        NOT NULL,
    default_locale           VARCHAR(16)  NOT NULL DEFAULT 'en-US',
    metadata_json            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_sync_at             TIMESTAMPTZ,
    UNIQUE(phone_e164),                                          -- phone numbers are global
    CHECK (status IN ('DRAFT','SUBMITTING','PENDING_REVIEW','ACTIVE',
                      'SUSPENDED','FAILED','REVOKED'))
);
CREATE INDEX whatsapp_senders_tenant_idx ON whatsapp_senders(tenant_id, status);

CREATE TABLE channel_bindings (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    endpoint_kind       VARCHAR(32)  NOT NULL,        -- 'WHATSAPP_SENDER' | future
    endpoint_id         VARCHAR(64)  NOT NULL,        -- senders.id
    profile_id          VARCHAR(128) NOT NULL,
    profile_version     INT,
    routing_json        JSONB        NOT NULL DEFAULT '{"kind":"MatchAny"}'::jsonb,
    session_policy_json JSONB        NOT NULL,
    priority            INT          NOT NULL DEFAULT 100,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deactivated_at      TIMESTAMPTZ,
    CHECK (status IN ('ACTIVE','PAUSED','DEACTIVATED'))
);
CREATE INDEX channel_bindings_lookup_idx
    ON channel_bindings(tenant_id, endpoint_kind, endpoint_id, status, priority);

CREATE TABLE channel_sessions (
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    sender_id         VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id),
    end_user_phone    VARCHAR(20)  NOT NULL,
    session_id        VARCHAR(64)  NOT NULL,         -- → recorder's sessions.id
    status            VARCHAR(16)  NOT NULL,         -- 'OPEN' | 'CLOSED'
    opened_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at         TIMESTAMPTZ,
    last_inbound_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_outbound_at  TIMESTAMPTZ,
    close_reason      VARCHAR(32),
    UNIQUE(sender_id, end_user_phone, status)        -- only one OPEN at a time
        DEFERRABLE INITIALLY IMMEDIATE
);
CREATE INDEX channel_sessions_open_idx
    ON channel_sessions(sender_id, end_user_phone)
    WHERE status = 'OPEN';

CREATE TABLE inbound_messages (                       -- dedup + audit
    message_sid             VARCHAR(64)  PRIMARY KEY, -- Twilio's SMxx...
    tenant_id               VARCHAR(64)  NOT NULL,
    sender_id               VARCHAR(64)  NOT NULL,
    end_user_phone          VARCHAR(20)  NOT NULL,
    received_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    body_redacted           TEXT,                     -- post-PII-scrub copy for ops
    num_media               INT          NOT NULL DEFAULT 0,
    raw_payload_json        JSONB        NOT NULL,    -- exact Twilio body, for replay/debug
    media_transcripts_json  JSONB,                    -- §9.5; null when no audio media
    processed_at            TIMESTAMPTZ,
    processing_error        TEXT
);

CREATE TABLE outbound_messages (
    message_sid       VARCHAR(64)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    sender_id         VARCHAR(64)  NOT NULL,
    end_user_phone    VARCHAR(20)  NOT NULL,
    session_id        VARCHAR(64),
    sent_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    body              TEXT,
    template_id       BIGINT,
    status            VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    delivered_at      TIMESTAMPTZ,
    failed_at         TIMESTAMPTZ,
    failure_code      VARCHAR(32),
    failure_message   TEXT,
    retry_count       INT          NOT NULL DEFAULT 0
);

CREATE TABLE outbound_pending (                       -- parked when outside service window
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    sender_id         VARCHAR(64)  NOT NULL,
    end_user_phone    VARCHAR(20)  NOT NULL,
    session_id        VARCHAR(64)  NOT NULL,
    body              TEXT         NOT NULL,
    queued_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    released_at       TIMESTAMPTZ,
    UNIQUE(sender_id, end_user_phone, session_id, queued_at)
);

CREATE TABLE whatsapp_templates (
    id                BIGSERIAL    PRIMARY KEY,
    sender_id         VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id),
    name              VARCHAR(128) NOT NULL,
    locale            VARCHAR(16)  NOT NULL,
    category          VARCHAR(32)  NOT NULL,
    body              TEXT         NOT NULL,
    placeholder_count INT          NOT NULL,
    bsp_template_id   VARCHAR(128),
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    submitted_at      TIMESTAMPTZ,
    approved_at       TIMESTAMPTZ,
    UNIQUE(sender_id, name, locale)
);

CREATE TABLE channel_opt_outs (
    sender_id         VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id),
    end_user_phone    VARCHAR(20)  NOT NULL,
    opted_out_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (sender_id, end_user_phone)
);

-- §11.3 operator takeover: while a row is live the engine does not run for this phone
CREATE TABLE conversation_holds (
    tenant_id         VARCHAR(64)  NOT NULL,
    end_user_phone    VARCHAR(20)  NOT NULL,
    held_until        TIMESTAMPTZ  NOT NULL,
    reason            VARCHAR(64),
    held_from         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, end_user_phone)
);
```

The `whatsapp_senders.webhook_secret_enc` is per-sender and unique-per-sender; rotating it requires re-saving the Twilio webhook URL on Twilio's side (the URL embeds the secret hash). This is conscious — one compromised sender's secret can't be used to forge inbound to another.

---

## 14. API surface

All admin endpoints are tenant-scoped under `/v1/admin/tenants/{tenantId}/...`.

> The platform-singleton Twilio account (§7) is config, not an API resource — there are no `/twilio-accounts` endpoints in v1. Tenants see only the resources below.

```
# Senders
POST   /whatsapp-senders                         create DRAFT (no Twilio call yet)
GET    /whatsapp-senders
GET    /whatsapp-senders/{sid}
PUT    /whatsapp-senders/{sid}                   edit DRAFT/FAILED; immutable when ACTIVE
POST   /whatsapp-senders/{sid}/submit            DRAFT → SUBMITTING → Twilio call; optional body { verificationMethod: sms|voice }
POST   /whatsapp-senders/{sid}/verify            body { code } — the OTP WhatsApp sent to the number (PENDING_VERIFICATION)
POST   /whatsapp-senders/{sid}/sync              force a sync poll (retries a submission that never reached Twilio)
POST   /whatsapp-senders/{sid}/suspend           ACTIVE → SUSPENDED
POST   /whatsapp-senders/{sid}/resume            SUSPENDED → ACTIVE
POST   /whatsapp-senders/{sid}/revoke            terminal; also cancels an in-flight registration
GET    /whatsapp-senders/{sid}/onboarding        last poll result, recommended next steps

# Templates
POST   /whatsapp-senders/{sid}/templates         create DRAFT template
POST   /whatsapp-senders/{sid}/templates/{tid}/submit   submit to Twilio/Meta for review
POST   /whatsapp-senders/{sid}/templates/{tid}/sync     force template-status sync
GET    /whatsapp-senders/{sid}/templates

# Channel bindings (the sender → profile mapping)
POST   /channel-bindings                         body: { endpoint, profileId, routing, sessionPolicy, priority }
GET    /channel-bindings?endpointKind=...&endpointId=...
PUT    /channel-bindings/{bid}                   edit routing/policy/priority/status
DELETE /channel-bindings/{bid}                   soft-delete (DEACTIVATED)

# Opt-outs
GET    /channel-opt-outs?senderId=...
DELETE /channel-opt-outs/{senderId}/{phoneE164}  operator-triggered removal (audited)
```

The integration edge is not admin-scoped: a trusted system sender proves itself per request (§11.3,
`system-messaging-design.md` §4.1).

```
POST   /v1/integrations/{tenant}/senders/{sid}/holds           hold the conversation (operator takeover)
POST   /v1/integrations/{tenant}/senders/{sid}/holds/release    give it back to the assistant
GET    /v1/integrations/{tenant}/senders/{sid}/holds/messages   what the customer wrote while it was held
```

The webhook receiver is **not** tenant-scoped in the URL (Twilio sends to one URL per sender; the sender row resolves the tenant):

```
POST  /v1/channels/whatsapp/twilio/webhook/{senderId}        inbound message
POST  /v1/channels/whatsapp/twilio/status-callback/{senderId} delivery receipts
```

The `{senderId}` segment is a quick lookup hint; the canonical sender resolution is still by `To` field plus signature verification. The path segment exists so wrong-sender webhooks fail fast at routing rather than at signature.

### 14.1 Webhook URLs are platform-internal

Because the Twilio account is platform-singleton (§7), webhook + status-callback URLs are set **by the platform** on `submit` — they are not surfaced to the tenant. Internally the platform calls Twilio's Senders API with:

```
WebhookUrl       = ${yaya.agentic.channels.twilio.webhook-base-url}/v1/channels/whatsapp/twilio/webhook/{senderId}
StatusCallback   = ${yaya.agentic.channels.twilio.webhook-base-url}/v1/channels/whatsapp/twilio/status-callback/{senderId}
```

The `{senderId}` path segment is a routing hint; the canonical sender resolution is still by `To` field plus signature verification (§9). The path exists so wrong-sender webhooks fail fast at routing rather than at signature.

There is **no admin response field** that exposes these URLs to a tenant operator. They show up only in operator-level debug endpoints (out of scope of this design; lives under the existing operator-auth admin surface).

---

## 15. Flutter admin UX

A new top-level nav entry: **Channels** → tabs: `WhatsApp`, `Bindings`, `Templates`, `Opt-outs`. No Twilio tab — Twilio is invisible to the tenant operator (§7).

### 15.1 WhatsApp sender wizard

Four steps, each saving progress (the sender stays `DRAFT` until "Submit"):

1. **Business profile** — legal name, contact email, address, vertical, website. Field-level validation surfaces stable error codes. The vertical list is the one Senders v2 accepts; the provider maps the stored code to Twilio's label.
2. **Phone number** — E.164 input + country selector, plus how WhatsApp sends the verification code (SMS / voice call). The form checks platform-wide uniqueness (it's a real phone, can't double-book) and gives a clean 409 with the offending tenant's display name when relevant — but a tenant operator never sees the phrase "Twilio" in any error.
3. **Display name** — show the request + a preview of how it appears in WhatsApp; Meta restriction tooltip on display-name rules. New senders also pick the **assistant that answers the number**; saving creates a `MatchAny` binding (§8) to it.
4. **Review & submit** — final read of everything; **Save & submit** creates the sender, its binding, and moves it to `SUBMITTING` in one click (**Save as draft** stops before the submit).

After submit, the wizard's last screen swaps to a live status panel — note the absence of any BSP-specific vocabulary; the operator sees the WhatsApp/Meta review steps in their own terms:

```
Status:           PENDING_REVIEW
WhatsApp review:  Awaiting display-name approval (est. 2–3 days)
Last checked:     2 minutes ago
[Refresh]  [Edit]  [Cancel sender]
```

The status panel polls the admin API every 60s while open; backend polls Twilio every 5m regardless. The word "Twilio" never appears in the tenant-facing UI — internally, "Refresh" triggers `POST .../sync` which calls Twilio.

### 15.2 Bindings tab

A simple grid: `Sender · Profile · Routing · Priority · Status`. Inline edit. Adding a binding shows a profile picker filtered to the tenant's `ACTIVE` profiles (`yaya-agentic-user-accounts-design.md` already restricts the list to profiles the operator can administer).

### 15.3 Playground integration

The playground (the existing test surface for profiles) gets a new "Channel" toggle: `Web (SSE)` or `WhatsApp (simulated)`. In the WhatsApp mode the playground:

- Renders chat with WhatsApp affordances (single-message-at-a-time, no streaming, numbered quick replies).
- Routes through the same `ChannelProfileResolver` path by setting `channel.kind=whatsapp` and `channel.senderId=__playground_sim__`.
- Uses a **fake sender** (`__playground_sim__`) that exists for every tenant with no Twilio dependency — outbound dispatches go to an in-memory sink, not Twilio.

This lets an operator stress-test a profile's WhatsApp behavior (buffering, quick-reply numbering, denial UX in non-streaming form) before any real Twilio traffic exists. Same lever that `playground-actas-auth-design.md` uses for principals — simulate the runtime, run the real engine.

---

## 16. Security & safety

Building on §12 of the master design doc:

- **Inbound signature verification is non-bypassable.** No flag, no environment variable, no test-mode hatch. Test traffic uses the playground's simulated sender, which goes through a different controller path.
- **Twilio credentials never appear in logs.** The `JwtSecretCipher` envelope is unwrapped in-memory only; serialization of `TwilioAccount` redacts the encrypted fields.
- **Outbound webhook secrets** for status callbacks rotate alongside inbound on sender re-save.
- **Sender phone numbers** are validated as E.164 and rejected if they fall in test/reserved ranges (`+1 555 011x xxxx`, etc.) unless the tenant has `feature.acceptTestNumbers=true`.
- **Replay attacks.** `MessageSid` dedup window is 24h in `inbound_messages` (Twilio's spec says a `MessageSid` can be replayed when their callback retries; we treat the row's existence as "already processed" and 200 immediately).
- **SSRF on inbound media.** Twilio media URLs are pre-signed and served from Twilio's CDN; we fetch through the existing `HttpEgressPolicy` allowlist (Twilio CDN hosts allowed at the platform-default level).
- **Rate limiting.** Per-sender inbound rate limit (default 100 msg/s, configurable) at the webhook controller — beyond it, return 429 to Twilio (which will retry with backoff). Per-end-user rate limit (default 20 msg/min) shed in the dispatch worker — message is acked to Twilio but ignored, with an `inbound_messages.processing_error="rate_limited"`.
- **Profile-level kill switch on the binding** — flipping `status=PAUSED` immediately stops outbound (queues nothing), continues to ack inbound, and audits the pause.
- **End-user blocklist.** `channel_opt_outs` is the canonical blocklist; outbound dispatch consults it before every send.

---

## 17. Observability

OTel spans for the inbound path:

```
http.server.request
  └── channel.whatsapp.inbound
        ├── signature.verify
        ├── sender.lookup
        ├── inbound.dedup
        ├── channel.enqueue
        └── http.response   (200 OK to Twilio)

channel.whatsapp.dispatch       (background worker — different trace, linked via correlation)
  ├── principal.mint
  ├── session.resolve
  ├── engine.start | engine.send
  │     └── (existing engine spans)
  ├── channel.buffer
  └── channel.deliver
        ├── service.window.check
        └── twilio.messages.create
```

Metrics:

| Metric | Type | Labels |
|---|---|---|
| `channel_inbound_total` | counter | tenant, sender_id, channel_kind, outcome |
| `channel_inbound_signature_failures_total` | counter | tenant, sender_id |
| `channel_dispatch_latency_seconds` | histogram | tenant, sender_id, completion_policy |
| `channel_outbound_total` | counter | tenant, sender_id, kind (freeform|template), result |
| `channel_outbound_service_window_blocked_total` | counter | tenant, sender_id |
| `channel_session_open` | gauge | tenant, sender_id |
| `sender_status` | gauge | tenant, sender_id (0=DRAFT, 1=SUBMITTING, …, 6=REVOKED) |
| `twilio_api_call_latency_seconds` | histogram | tenant, operation |

Dashboards (per tenant): inbound volume; signature-failure rate (should be near zero); time-to-first-outbound; service-window-blocked rate (high = the profile has an outbound design problem); per-sender status changes over time.

---

## 18. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Twilio webhook delivery exceeds 15s and Twilio considers our endpoint unhealthy | Ack 200 before engine work begins (§9.1). Background dispatch is durable. |
| LLM reply blows past WhatsApp's 1600-char limit | Adapter splits on sentence boundaries before dispatch; first chunk in original message, subsequent as follow-up messages, all within session. Surfaces a `channel_outbound_split_total` counter so we know if this is common. |
| Inbound spike (viral / abuse) saturates engine | Per-tenant and per-sender rate limits at controller; per-end-user shedding in worker; admission queue is PG-backed so we don't OOM under spike. |
| Twilio API quota exhausted mid-conversation | OutboundResult.ProviderFailure(retriable=true) routes to retry queue; user-visible message degrades to "I'm having trouble reaching you right now, give me a moment." |
| Operator misconfigures binding to wrong profile | Binding edits audited; admin UI shows the most recent 5 turns from any sender, marked with `profile_id` so operator can verify quickly. |
| Service-window math drifts (timezones, daylight saving) | All `last_inbound_at` stored in UTC; comparisons are `now() - last_inbound_at`, no calendar math. |
| Phone number leaks into LLM context | Phone is in `Principal.claims`, not in `UserMessage.text`. Personality includes a rule: "the user's phone is private; do not mention it unless explicitly asked or as a tool argument." |
| Cross-tenant binding mistake (operator picks the wrong tenant) | Admin endpoints are tenant-pathed; binding's `endpoint_id` is validated against the same tenant; cross-tenant references 400 with `cross_tenant_endpoint_forbidden`. |
| Display name fails Meta review repeatedly | UI surfaces Twilio's structured failure reason (`approvedDisplayName` stays null; `onboardingState.lastTwilioFailureMessage` shows the rejection); edits return to `DRAFT` cleanly. |
| Re-engagement template misused as marketing | Template `category` is enforced; `UTILITY` templates are allowed without explicit opt-in tracking, `MARKETING` requires an `opt_in_at` per-end-user row (out of scope to fully model in v1; we accept a `marketing_opt_in_at` column on `channel_sessions` and refuse `MARKETING` template dispatch if null). |
| Twilio creds rotated, in-flight messages fail | Rotation is a deploy event (env var swap), not an admin API call; the rolling-restart drains in-flight dispatches before the new instance takes over. No mid-process cache to invalidate. |
| **Shared Twilio account = shared blast radius** — a single misbehaving tenant could get the master account flagged by Meta, taking down every tenant | (1) Per-tenant outbound rate caps configured at sender creation, enforced in the dispatch worker. (2) Spam-pattern detection on `outbound_messages` (high opt-out-rate per sender → auto `SUSPENDED`). (3) Business-profile review before `submit` is allowed in production (operator-tier approval flag; not a tenant action). (4) §7.3's BYO escape hatch ready to enable for any tenant whose volume / risk profile justifies cleaving them off. |
| Twilio suspends the master account anyway | All senders go `SUSPENDED` automatically (the next poll detects it); inbound still acked + audited but no LLM round-trip; operator runbook is to contact Twilio + Meta, then resume. This is the same failure shape as any single-vendor outage. |

---

## 19. Open questions (resolved)

This section was an open list in the draft; each item below records the resolution that landed in the design above.

1. **`channel_sessions` ownership.** ✅ Decided: stays a side-table, NOT in the recorder. The recorder is per-conversation; this index is per-(sender, end_user) and outlives any single recorded session. Revisit only if a customer asks for "search by phone number" admin tooling, at which point a recorder-side index makes sense.
2. **Free-form template fallback outside the service window.** ✅ Decided: park in `outbound_pending`, release on next inbound, no operator notification. "No template configured" is operator misconfiguration, not a runtime concern.
3. **Per-binding personality override.** ✅ Decided: no override fields on `ChannelBinding`. If a profile needs a different voice on WhatsApp, clone it (`tenant-registry-design.md` §7) and bind the WhatsApp sender to the clone. Bindings stay thin.
4. **Inbound audio handling.** ✅ **Decided: v1 transcribes inbound voice notes** via the new `MediaTranscriber` SPI (§9.5). Default impl is OpenAI Whisper. The earlier "behind a flag in v2" lean was wrong for the target markets — voice notes are how a large fraction of WhatsApp users actually communicate, and treating them as opaque attachments would make the bot feel broken on day one.
5. **How the LLM learns the end user's phone.** ✅ Decided: capture phone + channel + locale into the session's `EntityMemory` at session start (§12.1) and let the existing `PromptBuilder` projection do the work. The `Principal` itself stays PII-free in the prompt — `yaya-agentic-design.md` §7's "no PII from principal" rule is preserved. Scope-widening / account-linking is unchanged from §9.4: still v2.
6. **Twilio subaccount provisioning automation.** ⛔ Overtaken by §7's platform-singleton decision. There are no subaccounts to provision; all senders live under the one platform Twilio account. Question reactivates if BYO ships (§7.3), and at that point the answer is still "lazily, on first sender."
7. **Webhook secret rotation policy.** ✅ Decided: dual-key window — the old secret stays accepted for 60m after rotation, then evicted. Twilio's Senders API does support updating the webhook URL with the new secret atomically; rotation is two API calls (set new on Twilio, then evict old after window).
8. **Quiet hours.** ✅ Decided: NOT a channel-level concern. Removed `SessionPolicy.quietHours` from §8. If a profile wants schedule-aware behavior ("we're closed until 8am, send a template"), it expresses that in its system prompt + a tool the LLM calls to check business hours. The channel doesn't second-guess the profile.
9. **Conversation handoff to human.** ✅ Decided as future work: third binding-status `HANDED_OFF` with a per-binding handoff hook. Out of scope here; flagged because it constrains how `OutboundDispatcher` interacts with `ChannelBinding.status=PAUSED` (we want "paused for human" to keep inbound flowing, not bit-bucket them).
10. **When to enable BYO Twilio for a tenant.** ✅ Decided: don't preempt. §7.3 keeps the seam open; we wait for the first paying tenant who asks (volume / compliance / existing-Meta-business-verification), then ship a tenant-scoped credentials store behind a feature flag.

---

## 20. Worked example

**Tenant:** `acme-retail`. **Profile:** `retail-customer@v3` (from the master doc §14).

**Operator setup (one-time)** — note that the tenant operator never sees, types, or hears about Twilio:

```
1. POST /v1/admin/tenants/acme-retail/whatsapp-senders
   { phoneE164:     "+15551234567",
     displayName:   "Acme Retail",
     businessProfile: { legalName: "Acme Retail Inc.",
                        contactEmail: "ops@acme.com",
                        address: "...", vertical: "RETAIL",
                        websiteUrl: "https://acme.com",
                        about: "We sell things." } }
   → 201 { id: "snd_acme_main", status: "DRAFT" }

2. POST /v1/admin/tenants/acme-retail/whatsapp-senders/snd_acme_main/submit
   → 200 { status: "SUBMITTING" }
   (internally: platform calls Twilio Senders API under the platform-singleton
    account, with the platform-set webhook + status-callback URLs; tenant
    sees none of this)

3. (poller, 5 minutes later)
   status = "PENDING_REVIEW"
   wizard surfaces: "WhatsApp review: awaiting display-name approval"

4. (poller, 2 days later)
   status = "ACTIVE", approvedDisplayName = "Acme Retail"

5. POST /v1/admin/tenants/acme-retail/channel-bindings
   { endpoint: { kind: "WHATSAPP_SENDER", id: "snd_acme_main" },
     profileId: "retail-customer", routing: { kind: "MatchAny" },
     sessionPolicy: { idleTimeoutMinutes: 1440, newSessionKeywords: ["#new"] } }
   → 201
```

**End-user run:**

```
Twilio → POST /v1/channels/whatsapp/twilio/webhook/snd_acme_main
   { MessageSid: "SMabc", From: "+15559998877", To: "+15551234567",
     Body: "Hi, can you show me my last transactions?" }

← 200 (in <50ms)

(worker)
  Authenticator: WhatsAppPhone → subject="whatsapp:+15559998877",
                 scopes=[channel:whatsapp, channel:read]
  ChannelProfileResolver → binding 47 → profile retail-customer
  channel_sessions: no OPEN row → open new, session_id=ses_q1q2q3

  engine.start(... channel=whatsapp ...)
    greeting Turn:
      Token "Hi! I'm Yaya, your Acme assistant. I can help you with..."
      UiHint quick_replies=["Balance","Transactions","Dispute"]
      End
  WhatsAppAdapter buffers → final text:
    "Hi! I'm Yaya, your Acme assistant. I can help you with balances,
     transactions, and disputes. Reply with:
       1. Balance
       2. Transactions
       3. Dispute"
  OutboundDispatcher: service-window OK (just inbound) → sendFreeform
  outbound_messages: SMxyz, status QUEUED → delivery callback → DELIVERED

End user replies "2"
  Adapter maps "2" → "Transactions" and the conversation continues
  on the existing session — engine doesn't know it was a WhatsApp digit.
```

The denial UX from the master doc's §14 example works identically — the user-safe denial reason is rendered as a plain message, no streaming, no special-casing.

---

## 21. Milestone breakdown

| Milestone | Scope |
|---|---|
| **W0 — Channel SPI** (1 wk) | `MessagingChannelProvider`, `MessagingChannelAdapter`, `CompletionPolicy` shapes. Refactor existing SSE controller into the WebSse adapter shape (no behavior change). |
| **W1 — Sender CRUD + platform Twilio config** (0.5 wk) | `whatsapp_senders` table, sender controllers, `ProviderCredsResolver` (platform-singleton), `TwilioProperties` binding, startup verification probe against `/v1/Accounts/{sid}.json`. No real submit yet — DRAFT only. Flutter wizard skeleton through step 4 review. |
| **W2 — Sender registration + poller** (1.5 wk) | `submit` actually calls Twilio; `SenderSyncJob`; status transitions; event publication; admin status panel polling. |
| **W3 — Channel binding + ChannelProfileResolver** (0.5 wk) | Binding CRUD; resolver implementation; admin bindings tab. |
| **W4 — Inbound webhook + dispatch worker** (2 wk) | Signature verification, dedup, ack-then-process, queue, worker, `WhatsAppPhoneAuthenticator`, `channel_sessions`. EntityMemory capture of `endUserPhone`/`endUserChannel`/`endUserLocale` at session start. `MediaTranscriber` SPI + `WhisperMediaTranscriber` default impl with the §9.5 limits enforced. Engine integration via existing `start`/`send`. |
| **W5 — Outbound dispatcher + service window** (1.5 wk) | Buffer, `InteractiveRenderer` with Quick Reply Buttons (1–3) + List Messages (4–10) + paginated "More…" (>10), inbound selection normalization, service-window check, `outbound_pending`, retries, status callbacks. |
| **W6 — Templates + opt-in/opt-out** (1 wk) | Template CRUD, submit-to-Twilio, sync, `send_template` tool, opt-out keyword handling, opt-out admin endpoints. |
| **W7 — Playground simulated sender** (0.5 wk) | `__playground_sim__` sender path; Flutter playground channel toggle. |
| **W8 — Observability + hardening** (1 wk) | OTel spans, metrics, rate limits, redaction, evals. Two eval scenarios per profile that exercise the WhatsApp adapter (buffering, quick-reply mapping, denial). |

Total ~8.5 weeks elapsed (W1 collapsed from 1.0 → 0.5 since there's no per-tenant Twilio credential CRUD; W4 expanded 1.5 → 2.0 to absorb the `MediaTranscriber` SPI + Whisper integration + EntityMemory capture); can compress with parallel backend + Flutter tracks. W0–W4 is the MVP that demonstrably routes a Twilio inbound (including voice notes) to a profile and gives the profile the user's phone via entity memory. W5+ is the production-quality cliff, including the native interactive surfaces (buttons + lists) that make WhatsApp feel like an app rather than a chat window.

---

## 22. Appendix — minimal interfaces recap

```java
public interface MessagingChannelProvider {
    ProviderId id();
    Set<ChannelKind> supports();

    SenderRegistrationResult registerSender(SenderRegistrationRequest req, ProviderCreds creds);
    SenderSyncResult         syncSender(SenderRef ref, ProviderCreds creds);
    void                     revokeSender(SenderRef ref, ProviderCreds creds);

    InboundValidation validateInbound(RawInboundRequest req, SenderTrustMaterial trust);

    OutboundResult sendFreeform(SenderRef ref, OutboundFreeform msg, ProviderCreds creds);
    OutboundResult sendTemplate(SenderRef ref, OutboundTemplate msg, ProviderCreds creds);

    TemplateSyncResult syncTemplates(SenderRef ref, ProviderCreds creds);
}

public interface MessagingChannelAdapter {
    ChannelKind     kind();
    CompletionPolicy completionPolicy();
    InboundMessage   parseInbound(RawInboundRequest req, MessagingChannelProvider p);
    void             deliver(OutboundEnvelope env, ChannelDeliveryContext ctx);
}

public sealed interface ChannelEndpointRef {
    record WhatsAppSenderRef(SenderId id) implements ChannelEndpointRef {}
}

public interface ChannelBindingService {
    Optional<ChannelBinding> findFor(TenantId tenant,
                                     ChannelEndpointRef endpoint,
                                     Principal endUser);
}

public sealed interface OutboundResult {
    record Sent(String messageSid, Instant sentAt) implements OutboundResult {}
    record OutsideServiceWindow(Duration sinceInbound) implements OutboundResult {}
    record TemplateRequired(TemplateId suggested) implements OutboundResult {}
    record OptedOut(Instant since) implements OutboundResult {}
    record ProviderFailure(String code, String message, boolean retriable) implements OutboundResult {}
}

public sealed interface CompletionPolicy {
    record Stream() implements CompletionPolicy {}
    record Complete(Duration maxBuffer) implements CompletionPolicy {}
}

public interface InteractiveRenderer {
    InteractivePayload render(String bodyText, TurnEvent.UiHint hint, RenderContext ctx);
}

public interface MediaTranscriber {
    Set<String> supportedContentTypes();
    TranscriptionResult transcribe(InboundMediaRef media, TranscriptionContext ctx);
}

public sealed interface InteractivePayload {
    record PlainText(String body) implements InteractivePayload {}
    record Buttons(String body, List<Button> buttons, String footer)
            implements InteractivePayload {}
    record ListMessage(String body, String header, String buttonText,
                       List<Section> sections, String footer)
            implements InteractivePayload {}

    record Button(String id, String title) {}
    record Section(String title, List<Row> rows) {}
    record Row(String id, String title, String description) {}
}
```

These plug into the existing SPI inventory (`yaya-agentic-design.md` §SPI inventory) as **four new top-level SPIs** (`MessagingChannelProvider`, `MessagingChannelAdapter`, `InteractiveRenderer`, `MediaTranscriber`) plus a domain service (`ChannelBindingService`) that backs the existing `ChannelProfileResolver` and a thin BSP-credentials lookup (`ProviderCredsResolver`, §7.2). No changes to `ConversationEngine`, `Authorizer`, `Authenticator`, `ConversationRecorder`, or `Retriever`. The end user's phone reaches the LLM through the existing `EntityMemory` SPI (`entity-memory-design.md`), not through any channel-specific prompt patching. The WhatsApp channel is, by construction, an additional way to drive the engine — not a fork of it.
