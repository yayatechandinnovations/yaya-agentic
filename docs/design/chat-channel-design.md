# Chat Channel — Branded End-User Chat App & Public Entry Points (v1)

> Companion to `yaya-agentic-design.md` §5.2 (Profile), §5.4 (AuthN), §5.5 (AuthZ),
> §5.6 (Profile resolver chain), §5.7 (Session), §5.9 (Recording), §6 (Lifecycle),
> §8 (Data model), §9 (API surface), §12 (Safety).
>
> Companion to `whatsapp-channel-design.md` and `sms-channel-design.md` — this is the
> **third channel** and reuses their machinery wholesale: `ChannelKind`,
> `MessagingChannelAdapter`, `ChannelEndpointRef`, `ChannelBinding`, the channel-auth
> resolver subsystem (`AuthResolver`, `ChannelAuthIntegrator`, `ResolverExchangeStore`),
> and the inbound→engine→outbound path. Where WhatsApp/SMS are **inbound-from-a-BSP,
> buffer-and-dispatch** channels, Chat is a **first-party, interactive, streaming**
> channel — closer to the existing web-SSE playground than to WhatsApp, but with a
> public, branded, multi-tenant front door that does not exist today.
>
> Companion to `tenant-registry-design.md`: the tenant is still the single trust root.
> A **chat entry point** is a new projection of that trust bundle — a branded, publicly
> addressable surface that speaks AS a tenant profile, alongside the WhatsApp sender and
> SMS sender.

| | |
|---|---|
| **Status** | 🟢 In implementation (C0–C5 landed; see §18) |
| **Owner** | Yenfry Herrera |
| **Audience** | Engineering team building yaya-agentic; PM stakeholders evaluating the self-serve chat go-to-market |
| **Last updated** | 2026-06-29 |
| **Scope** | A standalone end-user **chat app** (`yayaagenticchat/`, Flutter, multi-platform) where a person picks a branded tile — `LOGO ∣ NAME` — and starts a conversation with a tenant's profile. Each tile is a **chat entry point**: a tenant→profile binding with branding, an auth mode, and a public, unguessable handle. New tiles are added by **scanning a QR code** that deep-links to an entry point. Sessions stream over SSE through the existing `ConversationEngine`. Operators configure entry points in the admin console under **Inbound Channels → Chats**. Both **anonymous** and **authenticated** sessions are first-class. |
| **Out of scope (v1)** | Per-user server-side accounts/history sync across devices (the directory of added tiles lives **on-device**; sessions resume per-device). Push notifications. Group chats / multi-party. File upload from the end user (inbound attachments are a seam, not built). Operator-facing analytics dashboards beyond the existing observability. App-store distribution mechanics (we ship web first; the scaffold carries the native targets). White-label app builds per tenant — v1 is one yaya-branded shell hosting many tenant tiles. |

---

## 1. The problem in one paragraph

A tenant who has built a profile can ship it over WhatsApp, SMS, or a host app they wrote themselves — but there is no **turnkey, first-party chat surface** a non-technical operator can stand up and hand to end users. The web-SSE path (`SessionController`, `/v1/sessions/*`) exists, but it is the **operator/playground** surface: it assumes operator auth, it is keyed by tenant-admin context, it has no branding, no public front door, no discovery, and no anonymous on-ramp. What's missing is the consumer-facing equivalent of "scan this, start chatting": a person opens an app, sees the businesses they've added as branded tiles (`Buy Yaya Market` with the Yaya logo), taps one, and is talking to `tenant="Yaya Market" → profile="market_customer_sales"` — authenticated if the business requires it, anonymous if it doesn't. The pieces that don't exist yet: a **chat entry point** aggregate (branded, addressable, profile-bound), a **public** session surface that mints an anonymous-or-authenticated principal from the entry point rather than from operator auth, a **QR/deep-link** on-ramp, and the **end-user app** itself (today `yayaagenticchat/` is a bare `flutter create`).

---

## 2. Trust principle (read first)

> A **chat entry point** is a new projection of the tenant's trust bundle — the fifth, alongside inbound web origin, outbound HTTP host, authenticator, and BSP channel endpoint. It is a surface the platform exposes to the open internet **AS** a tenant profile. Unlike a WhatsApp sender (whose inbound trust edge is a signed BSP webhook), a chat entry point's inbound trust edge is **the entry-point handle plus the entry point's declared auth mode** — and nothing else is assumed about who is on the other end.

Five corollaries:

- **An entry point is owned by exactly one tenant and pins exactly one profile** (via a `ChannelBinding`, typically `MatchAny`). It can never be re-pointed at another tenant's profile by anyone who isn't an operator of the owning tenant. Branding lives on the entry point, not the profile, so two entry points can present the same profile under different names/logos (e.g. `Buy Yaya Market` and `Yaya Wholesale`).
- **The handle is a capability, not a secret-bearing token.** It is opaque and unguessable (so entry points are not enumerable — existence is not leaked, consistent with §5.8's stance on knowledge sources), but possessing it grants only the right to *attempt* a session under the entry point's auth mode. It carries no identity and no tenant secret. A leaked handle lets a stranger open the same anonymous chat a QR-scanner could — nothing more.
- **Anonymous is a real, minted principal — not "no auth".** An anonymous session gets a `Principal` from `AnonymousAuthenticator` (`§5.4`) with a minimal scope set. Every tool call and knowledge read still passes the `Authorizer` chain. "Anonymous" widens *who can start*, never *what they can do*.
- **The public surface is internet-facing and adversarial.** Unlike the WhatsApp/SMS webhooks (platform-internal, signed, low-cardinality), the chat front door is hit by browsers, bots, and scrapers. Abuse control (rate limits, optional proof-of-work/CAPTCHA, per-device and per-IP budgets) is a **first-class part of this channel**, not an afterthought — because every anonymous session can burn LLM tokens.
- **The LLM never sees device or network identifiers unless the profile authorizes it.** `deviceId`, IP, and user-agent are used for continuity and rate-limiting only; they reach the prompt exactly the way the phone number does on WhatsApp — through `EntityMemory`, picked up only by profiles that reference it.

---

## 3. Glossary additions

| Term | Definition |
|---|---|
| **Chat entry point** | A branded, publicly addressable surface that pins one tenant profile. The Chat-channel analog of a WhatsApp *sender* or SMS *sender*. Carries display name, logo, accent color, welcome text, auth mode, session policy, and a public handle. |
| **Handle** | The opaque, unguessable public identifier of an entry point (e.g. `e_7f3a…`). Encoded in the QR code and the deep link. Decoupled from the internal `ChatEntryPointId` for the same reason `SenderId ≠ twilioSenderSid` (§6.3 of the WhatsApp design). |
| **Tile** | The end user's view of an entry point inside the app: `LOGO ∣ NAME`. The directory is a list of tiles the user has added. |
| **Directory** | The on-device list of entry points a user has added (by scanning QR / following a deep link). Local to the device in v1; no server-side per-user store. |
| **Auth mode** | Per-entry-point policy: `ANONYMOUS_ONLY`, `AUTH_REQUIRED`, or `AUTH_OPTIONAL`. Drives whether the app must obtain a token before (or may upgrade during) a session. |
| **Deep link** | `https://chat.yaya.app/e/{handle}` (and custom scheme `yayachat://e/{handle}`) that the QR encodes. Resolves to entry-point metadata, then to a session. |
| **End-user principal** | The `Principal` minted for a chat session: anonymous (`chat:{handle}:anon:{deviceId}`) or authenticated (subject from the verified JWT). Never an operator account. |

---

## 4. Goals & non-goals

### 4.1 Goals (v1)

- **G1.** An operator can create a chat entry point from the admin console under **Inbound Channels → Chats**: pick a profile, set a display name (`Buy Yaya Market`), upload/point a logo, choose an accent color and welcome text, choose an auth mode. On save, the platform mints a handle and renders a **downloadable QR code** + deep link.
- **G2.** An end user opens `yayaagenticchat`, sees their added tiles as `LOGO ∣ NAME`, taps one, and is chatting with that tenant→profile in one tap — with the same streaming, quick-replies, and citations the web playground gets.
- **G3.** A user adds a new tile by **scanning a QR code** (or following a deep link). The app fetches public branding for the handle, shows a confirm card, and persists the tile to the on-device directory.
- **G4.** Inbound chat messages reach the existing `ConversationEngine` with no special-casing — a normal `StartConversationRequest`/`send`, with `channel="chat"` and an entry-point-minted `Principal`.
- **G5.** **Anonymous** entry points work with zero identity: `AnonymousAuthenticator` mints a minimal-scope principal; the profile resolves via the binding; tools/knowledge are gated by the `Authorizer` exactly as always.
- **G6.** **Authenticated** entry points work via an interactive OIDC login: the app obtains a JWT from the tenant's IdP (front-channel redirect / PKCE) and passes it as a bearer at session start; the existing `OidcAuthenticator` / `SharedSecretJwtAuthenticator` chain verifies it; profile may be pinned by the binding **or** identity-driven via `IdentityProfileResolver`.
- **G7.** **`AUTH_OPTIONAL`** lets a user browse anonymously, then upgrade mid-conversation (log in to check an order) — the session re-binds to the verified principal without losing history.
- **G8.** Sessions **stream** (`CompletionPolicy.Stream`) and **resume**: the app stores `sessionId` per tile; reopening the tile continues the conversation until the entry point's idle timeout, then starts fresh with a greeting.
- **G9.** The public surface is **rate-limited and abuse-resistant** per entry point, per device, and per IP, with a configurable anonymous budget. A tripped limit degrades gracefully (typed error, retry-after), never a silent 500.
- **G10.** The shape **reuses** the channel-auth resolver subsystem: an entry point may optionally carry an `authResolverId` so a tenant can exchange an app-held opaque token for a JWT server-side (`HttpAuthResolver`), and the existing **auth-flow inspector** (`ResolverExchangeStore`, `ChannelAuthTrace`) lights up for chat sessions for free.

### 4.2 Non-goals (v1) — and the seams kept open

- **No cross-device history sync / server-side user account.** The directory and per-tile `sessionId` live on-device. *Seam:* an authenticated principal already has a stable subject; a future `chat_user_directory` keyed by subject can back up tiles and resume cross-device without touching the channel.
- **No push notifications / proactive outbound.** Chat is pull/interactive; there is no service-window or template machinery. *Seam:* the `OutboundDispatcher` pattern from WhatsApp can drive web-push later behind the same `MessagingChannelAdapter.deliver`.
- **No inbound file upload from the end user.** Text only in v1. *Seam:* `UserMessage.attachments` already exists (WhatsApp media path); the composer can grow an attach button later.
- **No white-label per-tenant app build.** One yaya-branded shell hosts many tenant tiles. *Seam:* entry-point branding is already the per-tenant skin; a build-time flavor can pin a single tenant later.
- **No group / multi-party chat.** Single principal per session, same as every other channel.

---

## 5. What Chat reuses vs. adds

| Subsystem | WhatsApp / SMS | **Chat** |
|---|---|---|
| `ChannelKind` | `WhatsApp` / `Sms` | **add `Chat`** (sealed-variant, §5.1) |
| `MessagingChannelAdapter` | buffer-until-`End`, deliver via Twilio | **`ChatChannelAdapter`** — `CompletionPolicy.Stream`, controller-mediated SSE (reuses `WebSseChannelAdapter.toSse`) |
| `MessagingChannelProvider` | Twilio (BSP API) | **none** — Chat is first-party; there is no BSP. The provider seam stays empty. |
| Channel endpoint | `WhatsAppSenderRef` / `SmsSenderRef` | **add `ChatEntryPointRef`** to `ChannelEndpointRef` |
| Endpoint aggregate | `WhatsAppSender` / `SmsSender` (phone, regulatory state) | **`ChatEntryPoint`** (branding, auth mode, handle) — §6 |
| `ChannelBinding` | `endpoint_kind = 'WHATSAPP_SENDER' \| 'SMS_SENDER'` | **`endpoint_kind = 'CHAT_ENTRY_POINT'`**, identical machinery, typically `MatchAny` |
| `ChannelProfileResolver` | reads `channel.senderId` → binding | reads `channel.entryPoint` → binding (same code path) |
| Inbound trust edge | signed BSP webhook | **handle + auth mode + abuse controls** (§7) — the one genuinely new trust surface |
| End-user identity | `WhatsAppPhoneAuthenticator` (phone) | `AnonymousAuthenticator` **or** existing JWT chain (§8) |
| Channel-auth resolver | optional `HttpAuthResolver` per binding | **same**, optional — and the inspector reuses verbatim |
| Outbound | buffer + Twilio dispatch | **stream** — the SSE pipe is the wire (no dispatcher) |
| Recorder / RAG / AuthZ / engine | unchanged | unchanged |

The headline: **Chat is mostly subtraction.** It drops the BSP, the regulatory state machine, the buffering, the template/opt-out machinery, and the outbound dispatcher. It *adds* exactly two things of substance — branded entry points (§6) and a public, abuse-resistant front door with an anonymous on-ramp (§7–§8) — plus the app (§9–§10).

### 5.1 The one engine-visible change

`ChannelKind` gains a `Chat` variant:

```java
public sealed interface ChannelKind permits WebSse, WhatsApp, Sms, Telegram, Chat {
    record Chat() implements ChannelKind { public String wireName() { return "chat"; } }
}
```

`ChannelEndpointRef` gains a `ChatEntryPointRef`:

```java
public sealed interface ChannelEndpointRef permits WhatsAppSenderRef, SmsSenderRef, ChatEntryPointRef {
    record ChatEntryPointRef(ChatEntryPointId id) implements ChannelEndpointRef {
        public String kindName()   { return "CHAT_ENTRY_POINT"; }
        public String externalId() { return id.value(); }
    }
}
```

Adding these two sealed variants means re-reading every `switch` over `ChannelKind` / `ChannelEndpointRef` (CLAUDE.md's standing rule). That is the whole engine-side cost.

### 5.2 The channel hint on `StartConversationRequest`

The `ChatChannelAdapter` (or, more precisely, the public controller — see §9.1) populates hints the same way the WhatsApp adapter does:

```java
StartConversationRequest req = new StartConversationRequest(
    tenant,
    Optional.empty(),                       // profile resolved by ChannelProfileResolver via the binding
    "chat",                                 // legacy string
    Map.of(
      "channel.kind",        "chat",
      "channel.entryPoint",  entryPoint.id().value(),
      "channel.handle",      handle,
      "channel.deviceId",    deviceId,       // continuity + rate-limit only; never auto-projected to LLM
      "channel.locale",      "es-DO",
      "channel.authMode",    "AUTH_OPTIONAL"
    )
);
```

`ChannelProfileResolver` reads `channel.entryPoint`, looks up the active `channel_bindings` row for that endpoint, and returns the pinned profile — the exact path WhatsApp/SMS already exercise.

---

## 6. The chat entry point aggregate

```java
public record ChatEntryPoint(
    ChatEntryPointId   id,            // internal typed id
    Ids.TenantId       tenant,
    String             handle,        // opaque public handle (QR / deep link)
    Ids.ProfileId      profile,       // convenience mirror of the MatchAny binding's profile
    Branding           branding,
    AuthMode           authMode,
    AuthConfig         authConfig,    // issuer / resolver ref, depends on authMode
    SessionPolicy      sessionPolicy, // reused from channel.binding (idle timeout, new/end keywords)
    EntryPointStatus   status,        // DRAFT | ACTIVE | PAUSED | DEACTIVATED
    AbusePolicy        abuse,         // rate limits / anonymous budget (§7)
    Instant            createdAt,
    Instant            updatedAt
) {}

public record Branding(
    String displayName,    // "Buy Yaya Market"  — the NAME in LOGO | NAME
    String logoUrl,        // platform-hosted blob or validated https URL — the LOGO
    String accentColorHex, // tile + chat header theming, e.g. "#0B6E4F"
    String welcomeText,    // first bubble before the engine greeting; null → use profile introOneLiner
    String defaultLocale   // BCP-47, seeds channel.locale when the client sends none
) {}

public sealed interface AuthMode {
    record AnonymousOnly() implements AuthMode {}   // no token ever; AnonymousAuthenticator
    record AuthRequired()  implements AuthMode {}   // must present a verified JWT to start
    record AuthOptional()  implements AuthMode {}   // start anon, upgrade allowed mid-session
}

public sealed interface AuthConfig {
    record None() implements AuthConfig {}
    /** Interactive OIDC: app logs the user in at the tenant IdP and passes the JWT. */
    record Oidc(String issuer, String audience, List<String> scopes) implements AuthConfig {}
    /** Server-side exchange: app holds an opaque token; platform calls the tenant's
     *  resolver webhook to mint a JWT. Reuses channel/auth/HttpAuthResolver verbatim. */
    record ResolverExchange(Long authResolverId) implements AuthConfig {}
}
```

**`EntryPointStatus`** is a deliberately small state machine — no regulatory review, unlike WhatsApp/SMS senders:

```
  DRAFT ──publish──▶ ACTIVE ──pause──▶ PAUSED ──resume──▶ ACTIVE
    ▲                   │                                    │
    └────── edit ───────┘                deactivate ─────────┴──▶ DEACTIVATED (handle dead)
```

- `DRAFT` — created, handle minted but not yet serving sessions (QR can be previewed in admin, not public).
- `ACTIVE` — public; the handle resolves and starts sessions.
- `PAUSED` — handle resolves to a typed "temporarily unavailable" card; no sessions start. Reversible.
- `DEACTIVATED` — terminal; handle is permanently dead (404). In-flight sessions are allowed to finish; new ones are rejected.

### 6.1 Why a separate handle (and why opaque)

`ChatEntryPointId` is the internal FK target (bindings, audit, metrics). `handle` is the public capability in the QR. Keeping them separate lets us **rotate a handle** (revoke a leaked QR without rebuilding the entry point) and keeps internal ids out of public URLs. The handle is high-entropy and unguessable so the entry-point space is **not enumerable** — a scraper cannot walk `/e/1, /e/2, …` to discover every tenant's chats. Existence is not leaked: an unknown handle and a deactivated handle both return the same opaque 404.

### 6.2 Why branding lives on the entry point, not the profile

The user's framing — `tenant="Yaya Market"`, `profile="market_customer_sales"`, presented as **"Buy Yaya Market"** — is exactly the entry-point/profile split. A profile is a *role* (tone, capabilities, knowledge, auth binding); it is reused across channels and should not carry channel-presentation. The entry point is the *storefront*: the name, the logo, the color a consumer sees. This means one profile can front several differently-branded tiles, and rebranding a tile never touches the profile or its versioning.

---

## 7. The public front door & abuse control

This is the one genuinely new trust surface. Three layers, evaluated before any LLM round-trip:

1. **Handle resolution** — `GET /v1/chat/e/{handle}` returns branding + auth mode (public, cacheable, no session). Unknown/deactivated → opaque 404. Paused → 200 with a `paused` flag and no start affordance.
2. **Start gate** — `POST /v1/chat/e/{handle}/sessions` enforces, in order: (a) entry point `ACTIVE`; (b) abuse policy (rate + budget, §7.1); (c) auth-mode satisfaction (token required & verified for `AUTH_REQUIRED`); only then mint principal + `engine.start`.
3. **Per-message gate** — each `POST …/messages` re-checks the session is live and within the entry point's per-session message budget.

### 7.1 Abuse policy

```java
public record AbusePolicy(
    int  startsPerDevicePerHour,    // default 10
    int  startsPerIpPerHour,        // default 60
    int  messagesPerSessionPerHour, // default 120
    int  anonymousDailyMessageCap,  // default 200 per device; LLM-cost backstop
    Challenge challenge             // NONE | TURNSTILE | PROOF_OF_WORK
) {}
```

- `deviceId` is a client-generated stable UUID (persisted on-device). It is **not trusted for authz** — only for continuity and rate-limiting; a hostile client can rotate it, which is why per-IP and an optional `Challenge` (Cloudflare Turnstile / hashcash-style proof-of-work) backstop it.
- Limits are enforced in Redis (already a dependency) with sliding windows, keyed `chat:rl:{handle}:{dimension}:{id}`.
- A tripped limit returns `429` with `Retry-After` and a typed body `{ "reason": "rate_limited", "retryAfterSeconds": N }` — the app renders a calm "too many requests, try again shortly" state, never a raw error.
- `anonymousDailyMessageCap` is the **LLM-cost circuit breaker**: anonymous chats are free to the end user and therefore the natural abuse target. Authenticated sessions are exempt (the tenant has a verified principal to hold accountable).

---

## 8. Authentication: anonymous, interactive OIDC, and resolver-exchange

The entry point's `AuthMode` × `AuthConfig` selects the path. All three terminate in a normal `Principal` and a normal `engine.start`; the engine is unaware which path ran.

### 8.1 Anonymous (`AnonymousOnly`, or the pre-upgrade state of `AuthOptional`)

`AnonymousAuthenticator` (the reference impl named in §5.4) mints:

```
subject = "chat:" + handle + ":anon:" + deviceId
tenant  = entryPoint.tenant
scopes  = { "channel:chat", "channel:read" }
claims  = { "channel": "chat", "entryPoint": handle, "anon": true }
```

Profile resolves from the binding (`ChannelProfileResolver`). The `Authorizer` chain still gates every tool/knowledge access; an anonymous principal simply fails authz for anything requiring a verified scope, and the engine paraphrases the `userSafeReason` ("You'll need to sign in to check an order"). This is the denial-by-design path — anonymous users *can* talk, they just can't do privileged things.

### 8.2 Interactive OIDC (`AuthRequired` / `AuthOptional` upgrade) — primary authenticated path

Unlike WhatsApp/SMS (non-interactive — the platform only has a phone number and must call a webhook to learn who that is), a chat user is **in front of a screen**, so the natural, cheapest, most secure path is a standard front-channel login:

```
App ──(authMode=AUTH_REQUIRED, authConfig=Oidc{issuer,…})──▶ launch IdP authorize URL (PKCE)
IdP ──redirect with code──▶ App exchanges code→JWT at the IdP (no platform involvement)
App ──POST /v1/chat/e/{handle}/sessions  Authorization: Bearer <JWT>──▶ platform
        platform: OidcAuthenticator / SharedSecretJwtAuthenticator verify (per-tenant trust, existing)
                  → Principal(subject = JWT sub, scopes = JWT scopes/claims)
                  → profile: binding's pinned profile, OR IdentityProfileResolver from claims
```

No new authenticator is needed — this reuses the per-tenant OIDC/HS trust the platform already enforces for the web playground. The entry point's `AuthConfig.Oidc` just tells the *app* which issuer to log in against (it is presentation config, not a trust root — the trust root is still `tenant_oidc_issuers` / `tenant_jwt_secrets`).

### 8.3 Resolver-exchange (`AuthConfig.ResolverExchange`) — optional, reuses the channel-auth subsystem

For tenants whose app already holds an opaque first-party session token (not a JWT), the entry point can carry an `authResolverId`. At session start the platform calls the tenant's resolver webhook via the existing `HttpAuthResolver` — passing `{ entryPoint, deviceId, channel:"chat", reason:"INITIAL", opaqueToken }` — and receives `{ "token": "<JWT>" }`, which then flows through 8.2's verification. This is exactly the WhatsApp/SMS resolver path with a chat-shaped `ResolveRequest`, which means:

- `ResolverExchangeStore` records the (redacted) exchange,
- `ChannelAuthTrace` records the verification decision and token state,
- the **playground auth-flow inspector** (the work in `f0c57b3` / `1d7524f`) renders chat sessions with **zero new inspector code**.

This keeps a single auth-resolution mental model across all three channels.

### 8.4 The `AUTH_OPTIONAL` upgrade

A session that started anonymous can upgrade in place: `POST /v1/chat/sessions/{id}/authenticate` with a bearer JWT. The engine re-binds the session's `Principal` to the verified identity, capturing the transition as a SESSION-scoped entity (so the profile can say "thanks, you're signed in now") and re-running the `Authorizer` for any pending action. History is preserved; the prior anonymous turns remain in the transcript, attributed to the anonymous subject. Downgrade is not supported (you can only end the session).

---

## 9. API surface

The chat surface is **deliberately separate** from `/v1/sessions/*` (the operator/playground surface) and from `/v1/admin/*` (operator-authenticated config). It is public, entry-point-scoped, and abuse-gated.

```
# Public end-user surface (no operator auth; principal minted per §8)
GET  /v1/chat/e/{handle}                       resolve entry point → branding + authMode  (public, cacheable)
POST /v1/chat/e/{handle}/sessions              start a session  → { sessionId, greeting, quickReplies }
POST /v1/chat/sessions/{id}/messages           send a message   → SSE stream of TurnEvent
POST /v1/chat/sessions/{id}/authenticate       AUTH_OPTIONAL upgrade (bearer JWT)
GET  /v1/chat/sessions/{id}                     fetch state/history (resume after app restart)
POST /v1/chat/sessions/{id}/end                terminate

# Admin config surface (operator-authenticated) — Inbound Channels → Chats
GET    /v1/admin/tenants/{tid}/chat-entry-points
POST   /v1/admin/tenants/{tid}/chat-entry-points              create (mints handle)
GET    /v1/admin/tenants/{tid}/chat-entry-points/{id}
PUT    /v1/admin/tenants/{tid}/chat-entry-points/{id}         edit branding / auth / status
POST   /v1/admin/tenants/{tid}/chat-entry-points/{id}/rotate-handle
DELETE /v1/admin/tenants/{tid}/chat-entry-points/{id}         deactivate (handle dies)
GET    /v1/admin/tenants/{tid}/chat-entry-points/{id}/qr      QR PNG/SVG for the deep link

# Bindings — REUSED, no new endpoints (endpoint_kind = 'CHAT_ENTRY_POINT')
# Auth resolvers — REUSED (TenantAuthResolverController), for AuthConfig.ResolverExchange
```

### 9.1 Why the SSE pipe is controller-mediated (like web-SSE, not WhatsApp)

`ChatChannelAdapter.completionPolicy()` returns `CompletionPolicy.Stream`. Its `parseInbound`/`deliver` throw — exactly like `WebSseChannelAdapter` — because chat inbound is a typed REST body, and delivery is the SSE response Spring writes from the returned `Flux<TurnEvent>`. The controller maps each `TurnEvent` to an SSE frame by **calling the existing `WebSseChannelAdapter.toSse(...)`** (or a small shared helper extracted from it), so the wire contract (`token` / `tool_call` / `tool_result` / `citation` / `ui_hint` / `end`) is identical to §9 of the core design — one wire format, two front doors.

### 9.2 The start response

`POST …/sessions` returns the entry point's `welcomeText` (or the profile's `introOneLiner`), the engine's greeting turn, and the first `quickReplies` — so the app can paint a populated chat on first frame without waiting for a message. Quick replies are **engine-emitted `UiHint`s** (non-negotiable #8), never model-formatted inline.

---

## 10. Data model

```sql
CREATE TABLE chat_entry_points (
    id                 VARCHAR(64)  PRIMARY KEY,         -- ChatEntryPointId
    tenant_id          VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    handle             VARCHAR(64)  NOT NULL UNIQUE,     -- opaque public capability
    profile_id         VARCHAR(64)  NOT NULL,            -- mirror of the MatchAny binding
    display_name       VARCHAR(120) NOT NULL,
    logo_url           TEXT         NOT NULL,
    accent_color_hex   VARCHAR(9),
    welcome_text       TEXT,
    default_locale     VARCHAR(16),
    auth_mode          VARCHAR(24)  NOT NULL,            -- ANONYMOUS_ONLY | AUTH_REQUIRED | AUTH_OPTIONAL
    auth_config        JSONB        NOT NULL,            -- None | Oidc{...} | ResolverExchange{authResolverId}
    abuse_policy       JSONB        NOT NULL,
    session_policy      JSONB        NOT NULL,            -- same shape as channel binding SessionPolicy
    status             VARCHAR(24)  NOT NULL,            -- DRAFT | ACTIVE | PAUSED | DEACTIVATED
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_entry_points_tenant ON chat_entry_points(tenant_id);

-- channel_bindings: REUSED. One MatchAny row per entry point,
--   endpoint_kind = 'CHAT_ENTRY_POINT', sender_id = chat_entry_points.id.
-- sessions: REUSED. channel = 'chat'; auth_token_enc holds the JWT for authenticated sessions
--   (AES-256-GCM, same lifecycle as the channel-auth work).
-- No new inbound/outbound tables: chat is synchronous + streaming, so there is no
--   inbound_messages dedup table and no outbound buffer — the SSE pipe is the wire.
```

Creating an entry point is transactional: insert `chat_entry_points` **and** its `MatchAny` `channel_bindings` row together, so `ChannelProfileResolver` resolves it the instant it goes `ACTIVE`.

---

## 11. The end-user app (`yayaagenticchat`)

Today a bare `flutter create`. It adopts the same stack the admin app standardized on (M0): **flutter_riverpod + dio + freezed + json_serializable + go_router**, plus `mobile_scanner` (QR), `flutter_secure_storage`/`shared_preferences` (on-device directory + per-tile session), and `flutter_appauth` (OIDC/PKCE for authenticated entry points).

```
yayaagenticchat/lib/
├── main.dart
├── app/                 router (go_router), theme (per-tile accent override)
├── models/              freezed: EntryPointCard, Tile, ChatTurn, SseEvent
├── data/
│   ├── chat_api.dart      dio client for /v1/chat/* (+ SSE streaming)
│   ├── directory_store.dart  on-device tiles + per-tile sessionId
│   └── auth_store.dart    OIDC tokens per issuer
└── features/
    ├── directory/       grid/list of LOGO | NAME tiles + "add" FAB
    ├── scan/            QR scanner → resolve handle → confirm card → persist tile
    ├── chat/            message list, composer, streaming bubbles, quick-reply chips, citations
    └── auth/            OIDC redirect flow for AUTH_REQUIRED / upgrade
```

**Key screens**

- **Directory** — the home screen: tiles rendered as `LOGO ∣ NAME` exactly as the operator branded them. Empty state points at the QR scanner. Each tile shows a subtle "signed in" / "guest" affordance for authenticated entry points.
- **Scan / add** — `mobile_scanner` reads the QR → extracts `{handle}` from the deep link → `GET /v1/chat/e/{handle}` → shows a **confirm card** (logo, name, "Chat with …", auth requirement) → on accept, persist the tile.
- **Chat** — opens (or resumes) a session. Streams tokens into the latest bubble; renders `ui_hint` quick replies as tappable chips; renders `citation` events as source pills under the bubble; honors the entry point's accent color in the header. A "New chat" action sends the session's new-session keyword.
- **Auth** — for `AUTH_REQUIRED`, gates the chat behind an OIDC login launched from `AuthConfig.Oidc`; for `AUTH_OPTIONAL`, a "Sign in" affordance in the app bar triggers the §8.4 upgrade.

**Deep-link handling** — both the QR and the share link encode `https://chat.yaya.app/e/{handle}` (and the `yayachat://e/{handle}` custom scheme). All three surfaces resolve it through the **same** `/e/:handle` go_router route → confirm card → persist tile → open chat; if the handle is already in the directory the card is skipped and the chat opens directly.

- **Web** (implemented): the web build owns `/e/{handle}`. `usePathUrlStrategy()` keeps the URL clean (no `#`) so the link routes into go_router; the host must serve `index.html` for unmatched paths (SPA fallback) for a cold-loaded deep link to boot the app. Note `chat.yaya.app/e/{handle}` (the SPA route) is distinct from the backend's `/v1/chat/e/{handle}` JSON endpoint the app calls to resolve branding.
- **Mobile** (config in place, infra pending): Android declares an `autoVerify` App Links intent-filter for `https://chat.yaya.app/e/*` plus the `yayachat://e/*` scheme; iOS declares the `yayachat` URL scheme. `flutter_deeplinking_enabled` / `FlutterDeepLinkingEnabled` forward the URL to go_router. To make the **https** links open the app instead of the browser, the platform must still host the association files — `/.well-known/assetlinks.json` (Android) and `/.well-known/apple-app-site-association` (iOS) — and iOS needs the `applinks:chat.yaya.app` associated-domains entitlement wired in Xcode. Until then the https link falls back to the web build (which handles it), and the `yayachat://` scheme works for an installed app.

---

## 12. Admin UX — Inbound Channels → Chats

A new tab beside WhatsApp and SMS in the admin console:

- **List** — entry points as cards: logo, display name, bound profile, auth mode, status pill, live session count.
- **Create / edit wizard**
  1. *Profile* — pick the profile to front.
  2. *Branding* — display name (`Buy Yaya Market`), logo upload (validated, served from platform blob store to avoid hotlinking/XSS), accent color, welcome text. Live `LOGO ∣ NAME` tile preview.
  3. *Auth* — choose `ANONYMOUS_ONLY` / `AUTH_REQUIRED` / `AUTH_OPTIONAL`; if authenticated, pick OIDC issuer (from the tenant's configured issuers) or a resolver (from the tenant's auth resolvers).
  4. *Limits* — abuse policy defaults, editable.
  5. *Publish* — flips `DRAFT → ACTIVE`, reveals the **QR code** (download PNG/SVG) and deep link, with a "rotate handle" button for leaked-QR recovery.
- **Playground preview** — "Open as end user" launches the entry point in the chat web build (or an embedded frame) using the same public surface a real user hits, and — for authenticated/resolver entry points — surfaces the existing **auth-flow inspector** (`ResolverExchangeStore` / `ChannelAuthTrace`) so an operator can watch the JWT exchange and verification decision exactly as they can for WhatsApp today.

---

## 13. Security & safety

- **Non-negotiables hold unchanged** (CLAUDE.md): the LLM is never trusted; out-of-profile tools are never offered; AuthZ denials carry distinct `userSafeReason`/`auditReason`; the engine never touches storage except via `ConversationRecorder`; tool results & retrieved chunks are untrusted data; quick replies are engine `UiHint`s.
- **Anonymous ≠ unbounded.** Every anonymous session is scope-minimal and budget-capped (§7.1). The cost circuit-breaker is mandatory, not optional.
- **Handle hygiene.** Opaque, high-entropy, rotatable, non-enumerable; unknown and deactivated handles are indistinguishable (no existence leak).
- **Logo/branding is attacker-influenced content.** Logos are validated (content-type, size, dimensions) and re-served from the platform blob store, never hot-linked; `accentColorHex` is strictly validated; `welcomeText` is rendered as plain text, never HTML.
- **Internet-facing edge.** CORS locked to the chat app origins; TLS only; per-entry-point + per-IP + per-device rate limits in Redis; optional Turnstile/PoW challenge.
- **PII.** Anonymous users may still type PII; recording/redaction is the profile's call, unchanged. Device/IP identifiers reach the LLM only through `EntityMemory`, only if a profile references them.
- **Token at rest.** Authenticated sessions store the JWT in `sessions.auth_token_enc` (AES-256-GCM), with the same persist-and-wipe lifecycle the channel-auth work established.

---

## 14. Observability

- Per-entry-point metrics: starts (anon vs authed), active sessions, messages, abuse-policy trips, auth verification outcomes, p50/p95 first-token latency.
- The auth-flow inspector (`ChannelAuthTrace`, `ResolverExchangeStore`) covers chat sessions verbatim for the `ResolverExchange` path; for interactive OIDC it records the verification decision and token state.
- Audit rows for entry-point lifecycle (create/publish/pause/rotate-handle/deactivate) and for every rejected start (rate-limited, auth-failed, paused) — rejections are auditable without an LLM round-trip, same posture as the WhatsApp webhook drop.

---

## 15. Milestone breakdown (C0 → C5)

Following the `W0–W5` / `S0–S5` convention.

- **C0 — Channel wiring (0.5 wk).** Add `ChannelKind.Chat`, `ChannelEndpointRef.ChatEntryPointRef`, `ChatChannelAdapter` (`Stream`, reuses `toSse`). Update every sealed `switch`. No behavior yet.
- **C1 — Entry point aggregate + admin CRUD (1 wk).** `ChatEntryPoint`, `chat_entry_points` table, handle minting + rotation, branding/logo storage, transactional `MatchAny` binding, QR generation, `ChatEntryPointController`.
- **C2 — Public front door, anonymous path (1.5 wk).** `/v1/chat/*` controllers, `AnonymousAuthenticator` wiring, `ChannelProfileResolver` reads `channel.entryPoint`, SSE streaming, abuse policy + Redis rate limits, typed rejections. End-to-end anonymous chat works.
- **C3 — Authenticated entry points (1 wk).** Interactive OIDC bearer at start (reuse existing verification), `AuthConfig` modeling, `ResolverExchange` path reusing `HttpAuthResolver` + inspector, `AUTH_OPTIONAL` upgrade endpoint + session re-bind.
- **C4 — End-user app (2 wk).** `yayaagenticchat`: stack adoption, directory + on-device store, QR scan + deep links, chat screen (streaming, quick replies, citations), OIDC login, session resume.
- **C5 — Admin tab + hardening (1 wk).** Inbound Channels → Chats UI (wizard, tile preview, QR, playground preview + inspector), evals, abuse/load testing, CORS/TLS/secret review.

---

## 16. Open questions

1. **Directory portability.** Should an authenticated user's tiles + sessions back up server-side (keyed by subject) for cross-device resume, or stay strictly on-device for v1? *Lean:* on-device v1; subject-keyed backup is the named seam (§4.2).
2. **One profile, many entry points — metrics rollup.** Do we report per-entry-point only, or also roll up to the profile? *Lean:* per-entry-point primary, profile rollup in observability.
3. **Anonymous → authenticated identity stitching.** On `AUTH_OPTIONAL` upgrade, do we attribute prior anonymous turns to the now-known user in the recorder, or keep them anonymous? *Lean:* keep the historical attribution (anonymous turns stay anonymous), tag the transition.
4. **Challenge default.** Ship Turnstile on by default for `ANONYMOUS_ONLY`, or off until abuse is observed? *Lean:* off by default, one-flip enable, with the cost circuit-breaker always on.
5. **Handle in the URL vs. signed deep link.** Plain opaque handle (simple, rotatable) vs. a signed, optionally-expiring deep link (revocable campaigns, but heavier)? *Lean:* plain opaque handle in v1; signed/expiring links as a later QR-campaign feature.
6. **Web embed.** Do we also ship an embeddable `<script>`/iframe widget for tenant websites in v1, or is the standalone app enough? *Lean:* standalone app first; the public surface already supports an embed with no backend change.

---

## 18. Implementation status

| Milestone | State | Notes |
|---|---|---|
| **C0** Channel wiring | ✅ Done | `ChannelKind.Chat`, `ChannelEndpointRef.ChatEntryPointRef`, `ChatChannelAdapter` (reuses WebSSE `toSse`). |
| **C1** Entry point + admin CRUD | ✅ Done | `ChatEntryPoint` aggregate + JPA + service (handle mint/rotate, lifecycle, auto `MatchAny` binding), `ChatEntryPointController`, DTOs, migration `V46`. |
| **C2** Public front door + anonymous | ✅ Done | `PublicChatController` (`/v1/chat/*`), `AnonymousAuthenticator`, `ChatSessionRegistry` + `ChatRateLimiter` (Redis), `ChannelProfileResolver` resolves `channel.entryPoint`. |
| **C3** Authenticated entry points | 🟡 Mostly done | Anonymous, AUTH_REQUIRED (bearer via existing OIDC/HS chain), AUTH_OPTIONAL, and the **mid-session upgrade endpoint** (`POST /v1/chat/sessions/{id}/authenticate`, verified via the authenticator chain, anon-fallback rejected) all wired backend + app. **Deferred:** in-app OIDC **PKCE** (paste-token shim today); the **`ResolverExchange`** path — its `AuthResolver.ResolveRequest` is phone/`SenderId`-typed, so chat support needs an SPI evolution (a design-gated change, not made here). |
| **C4** End-user app (`yayaagenticchat`) | ✅ Done | Directory + QR scan + on-device store, streaming chat (SSE, quick replies, citations), branded dark theme with the Yaya Chat logo. |
| **C5** Admin "Inbound Channels → Chats" | ✅ Done | List + create/edit + publish/pause/resume/rotate-handle/deactivate, QR + deep-link dialog (`qr_flutter`), `requiresVerifiedPhone` toggle. **Config UX:** profile dropdown, identity = OIDC **or** auth-resolver dropdown (reusing `channel_auth_resolvers`, like sender bindings — sets `AuthConfig.ResolverExchange` → the entry point's binding), logo **upload** (platform-hosted blob, `chat_logos` / migration `V49`, served at `/v1/chat/logos/{id}`), accent **color palette**. **Deferred:** playground "open as end user" + auth-flow inspector hook; the resolver runtime exchange is still the §19 SPI item. |
| **§20** Phone-verified device identity | ✅ Done | `PhoneVerifier` SPI (Twilio Verify + dev no-op via `PhoneVerifierConfig`), `DevicePassService` (HS256 platform-signed pass), `VerifiedDeviceAuthenticator`, `/v1/chat/verify/*` with toll-fraud rate limits, `X-Device-Pass` integration (L1 baseline), per-entry-point `requires_verified_phone` (migration `V48`). App: first-run onboarding gate + device-pass storage. **Deferred:** Twilio Lookup VOIP filtering; Fraud Guard tuning. |
| **i18n** Chat app localization | ✅ Done | `flutter_localizations` + ARB (`app_en.arb` / `app_es.arb`), `AppL10n`; all end-user labels localized en/es, locale auto-selected from device. **Note:** transient controller error text (partly backend-sourced) not yet localized. |

Verified: backend `mvn compile` + targeted tests green; both Flutter apps `flutter analyze` clean. Pre-existing on the `sms-channel` branch (not introduced here): `RepositoryScanTest` flags three SMS/auth repos, and three admin-app widget tests fail to VM-compile the `web` package.

---

## 19. Configurable authentication & reusing the channel-auth resolver (proposal)

Auth is **per-entry-point configurable** along two orthogonal axes already modelled in §6:

- **`AuthMode`** — *when* identity is required: `ANONYMOUS_ONLY` | `AUTH_REQUIRED` | `AUTH_OPTIONAL`.
- **`AuthConfig`** — *how* a verified JWT is obtained: `None` | `Oidc(issuer,…)` | `ResolverExchange(authResolverId)`.

`ResolverExchange` points at a row in the **existing** `channel_auth_resolvers` table — the same resolvers WhatsApp/SMS use, managed in the shared "Auth resolvers" admin screen. This section validates that reuse and specifies the runtime + the minimal SPI change it needs.

### 19.1 Validation — what's reusable as-is vs. what's phone-coupled

**Reusable unchanged (channel-agnostic):** the `channel_auth_resolvers` table + CRUD, `AuthResolverRegistry.materialize`, `HttpAuthResolver` (the tenant-webhook POST), `AuthResolverTokenCache` (Redis), `auth_resolver_calls` audit, the `ResolverExchangeStore` / `ChannelAuthTrace` **inspector**, JWT verification via `AuthenticatorChain`, and the per-binding `auth_resolver_id` / `on_unresolved` linkage.

**Phone/`SenderId`-coupled (needs an additive generalization):**
1. `AuthResolver.ResolveRequest` — fields are `SenderId sender`, `e164SenderPhone`, `e164CallerPhone`; no slot for chat's `(entryPoint, deviceId, presentedToken)`.
2. `AuthResolverDispatcher.dispatch(...)` — takes `SenderId`+phones; the cache key + audit are `(tenant, sender, callerPhone)`.
3. `ChannelAuthIntegrator` — hardcodes `"whatsapp"` and `WhatsAppPhoneAuthenticator` baseline scopes/claims when stamping the verified principal.

### 19.2 The semantic difference (the crux)

- **WhatsApp/SMS = identity *lookup*.** The platform already holds a **verified** caller phone (the inbound webhook is signature-checked). It asks the tenant "who owns +1809…?" — no client secret is involved.
- **Chat = token *exchange*.** The platform holds **no** inherently-trusted identity (the handle is public; `deviceId` is untrusted). So the **client must present an opaque credential** — typically the tenant's own app-session token from an embed. The platform forwards that token to the tenant webhook, which validates it and returns a JWT. The platform never interprets the token; the **tenant is the trust authority**.

Same transport and JWT-verify; different "what is being resolved." This is why a phone-shaped `ResolveRequest` can't just be borrowed.

### 19.3 Minimal SPI change (additive, backward-compatible)

Generalize three types; WhatsApp/SMS call sites keep working unchanged:

- **`ResolveRequest`** gains nullable channel-generic fields: `String endpointId`, `String presentedToken`, `Map<String,String> attributes` (carries `entryPoint`, `deviceId`, `locale`…). Phone fields stay (null for chat). The HTTP body serialises whatever is non-null — so WhatsApp sends `callerPhone`, chat sends `token`+`attributes`.
- **`AuthResolverDispatcher`** gains a generic overload keyed on an `identityKey` string (phone for WhatsApp; `sha256(presentedToken)` for chat). The existing phone overload delegates to it. `CacheKey` becomes `(tenant, resolverId, identityKey)`.
- **Claim-stamping** is parameterised by channel (baseline scopes/claims passed in) — a thin `ChatAuthIntegrator`, or a generalised `ChannelAuthIntegrator`, stamps `channel:chat` instead of `channel:whatsapp`.

### 19.4 Runtime — the resolver path for chat

```
App (embed, already logged into tenant)
  └─ POST /v1/chat/e/{handle}/sessions   X-Chat-Auth-Token: <opaque tenant token>
        PublicChatController: authConfig == ResolverExchange(resolverId)
          └─ dispatcher.dispatch(resolverId, tenant,
                 identityKey = sha256(token), channel="chat",
                 presentedToken=token, attributes={entryPoint, deviceId})
                └─ HttpAuthResolver → POST tenant webhook
                       { channel:"chat", entryPoint, deviceId, token, sessionId, ts }
                   tenant validates → { "token": "<JWT>" }  (or 404)
                └─ AuthenticatorChain verifies JWT → Principal
        on 404 → on_unresolved: REJECT (AUTH_REQUIRED) | ANONYMOUS (AUTH_OPTIONAL)
        engine.start(req, auth)   // inspector lights up via ResolverExchangeStore
```

Rate limits (§7.1) run **before** the resolver call, so bots can't drive unbounded tenant-webhook traffic. The presented token is a bearer secret: TLS-only, redacted in the exchange store, never in the prompt.

### 19.5 Admin UX (small delta on the Chats tab)

When `AuthMode != ANONYMOUS_ONLY`, the create/edit form offers a method radio: **OIDC** (issuer/audience) or **Auth resolver** (dropdown of the tenant's active `channel_auth_resolvers`). The resolvers themselves are authored once in the shared Auth-resolvers screen and reused across WhatsApp / SMS / Chat.

### 19.6 Decision for the operator

- Authenticated chats where the **user logs into the standalone app** → **OIDC** (no resolver).
- Chat **embedded in a tenant app where the user is already signed in** → **resolver** (token exchange), reusing the WhatsApp machinery.
- Both can coexist across different entry points; `AuthConfig` already discriminates them per entry point.

---

## 20. Phone-verified device identity (proposal)

A first-run onboarding in the app: collect **phone + full name**, verify the phone via a **one-time SMS code (Twilio Verify)**, and thereafter treat the device as a **platform-verified human with a known phone**. Two payoffs, plus a bonus:

1. **Proof-of-human / anti-abuse.** A verified phone is the strongest cheap human signal — it supersedes the `AbusePolicy.Challenge` (CAPTCHA/PoW) and lets rate limits key on a real identity instead of a spoofable `deviceId`.
2. **A real, stable identity** for otherwise-anonymous chats: the principal becomes `chat:+E164` instead of `chat:…:anon:deviceId`.
3. **Bonus — it unifies chat with the phone-identity model.** A chat caller now *has* an `e164CallerPhone`, so the **existing** phone-lookup `AuthResolver` (`ResolveRequest` unchanged) works for chat with **zero SPI change** — a tenant who wrote a "phone → account JWT" resolver for WhatsApp gets authenticated chat for free.

### 20.1 Three identity layers (they compose)

| Layer | Established | Trust authority | Principal |
|---|---|---|---|
| **L0 anonymous** | nothing | none | `chat:{handle}:anon:{deviceId}` (untrusted) |
| **L1 verified-phone device** | once, app-wide, Twilio Verify | **platform** | `chat:+E164` + self-asserted name |
| **L2 tenant account** | per entry point, OIDC / resolver | **tenant** | JWT subject |

L1 is a **device-level baseline** beneath the per-entry-point `AuthMode`/`AuthConfig`. A user can be L1 (phone-verified) *and* sign into "Buy Yaya Market" at L2 (OIDC) for account actions. The phone (L1) is verified by yaya, not by any tenant — so it is a platform assertion the tenant can *consume* (via a phone-lookup resolver) but does not have to trust blindly.

### 20.2 Flow

```
First run:  app → POST /v1/chat/verify/start { phone, name }
                    platform → Twilio Verify (send OTP)         [heavily rate-limited — §20.4]
            app → POST /v1/chat/verify/check { phone, code }
                    platform → Twilio Verify check → approved
                    platform issues a DEVICE PASS: platform-signed JWT
                      claims { sub: "chat:+E164", phone, name (self-asserted), deviceId, verifiedAt }
                    app stores the device pass (secure storage), refreshes before expiry
Each chat:  app sends the device pass as the bearer → platform verifies its own signature
                    → mints principal chat:+E164 (baseline scopes), phone in EntityMemory only
```

The device pass is a normal JWT signed by a **platform** key (new trust root, sibling to the per-tenant ones), so it flows through the existing `AuthenticatorChain` unchanged. Re-install → new `deviceId`, re-verify same phone. Cap devices-per-phone; expire on recycle.

### 20.3 New SPI + surface

- **`PhoneVerifier`** SPI — `startVerification(phone)` / `checkVerification(phone, code)`. Impls: `TwilioVerifyPhoneVerifier` (reuses the platform Twilio account already used by SMS) and a dev no-op (`123456`). Mirrors the `SmsTransport` / `SmsNumberProvisioner` SPI split.
- **`/v1/chat/verify/*`** public endpoints + a **device-pass issuer/verifier** (platform-signed JWT).
- **App**: a first-run onboarding screen (phone + name + OTP), device-pass storage + refresh.
- **Principal**: a `VerifiedDeviceAuthenticator` mints `chat:+E164` from a valid device pass — the chat analog of `WhatsAppPhoneAuthenticator`.

### 20.4 Trust & abuse — the load-bearing risks

- **SMS-pumping / toll fraud (the #1 risk).** An attacker hammering `verify/start` against premium-rate ranges can run up a carrier bill fast. Non-negotiables: aggressive per-IP/per-device rate limits on `verify/start`, **Twilio Verify Fraud Guard**, geo/ISO-country allowlist, and a hard daily ceiling on verifications. This must ship *with* the feature, not after.
- **SIM farms / VOIP numbers.** A verified phone ≠ a unique human at scale. Use **Twilio Lookup (line-type intelligence)** to block VOIP/non-mobile ranges where the entry point demands it; cap accounts per number.
- **Privacy / consent.** Collecting phone + name is a real ask — explicit consent on first run, clear purpose, deletion path. The phone reaches the LLM/tenant **only via `EntityMemory`** (the §G10 WhatsApp rule), never auto-projected; the name is **self-asserted** and labelled as such.
- **Verification cost.** Twilio Verify is ~$0.05/attempt — the rate limits above double as a cost cap.

### 20.5 How it's configured

- **Platform/app policy:** `verifyPhoneOnFirstRun` (default ON for the public app — it's the anti-abuse baseline). One verification per device, app-wide; the verified phone flows to every entry point.
- **Per entry point:** `requiresVerifiedPhone` (default = app policy) for granularity, and an escape hatch for entry points that want **truly** anonymous chat (e.g. a feedback bot) to opt out.
- The verified phone **feeds the principal regardless of `AuthConfig`**; an entry point may still layer OIDC/resolver (L2) on top for tenant-account identity.

### 20.6 Recommendation

Ship L1 as the **default app onboarding** — it is the cleanest answer to the anonymous-LLM-cost problem (§7.1), gives every chat a real identity, and unlocks the phone-lookup resolver for chat with no SPI change. Gate it behind the §20.4 anti-fraud controls from day one.
