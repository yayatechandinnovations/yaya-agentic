# Chat-channel server-side auth resolution (transparent sign-in)

> **Companion doc.** This extends `chat-channel-design.md` (§6 entry points, §8 auth)
> and reuses the resolver machinery specified in `channel-auth-resolver-design.md`
> (the SPI, cache, and JWT lifecycle already shipped for WhatsApp/SMS). It does not
> introduce a new SPI — it wires the **existing** `AuthResolver` flow into the public
> chat front door so the `yayaagenticchat` app stops asking users to paste a JWT.

| | |
|---|---|
| **Status** | 🟡 Design |
| **Owner** | Yenfry Herrera Feliz |
| **Last updated** | 2026-06-30 |
| **Scope** | The `/v1/chat/**` front door (`PublicChatController`) and the `yayaagenticchat` Flutter client. Server-side resolver-exchange auth for chat entry points, mirroring the WhatsApp/SMS channel flow. |
| **Out of scope** | Interactive OIDC/PKCE login (already modelled via `AuthConfig.Oidc`; unchanged here). New resolver transports. Changes to the resolver SPI, cache, or `SessionAuthTokenStore`. Device phone-verification (OTP) itself — reused as-is. |

---

## 1. Problem & goal

**Problem.** A chat entry point can be configured with `AuthMode.AUTH_REQUIRED`. When it is,
the `yayaagenticchat` app renders `_SignInPrompt` (`chat_screen.dart`) and pops a dialog whose
hint text is literally *"Paste access token."* The user is expected to obtain a JWT out of band
and paste it in. This is a dev-only shell (the code says so), and as a product experience it is
unacceptable: end users do not have JWTs, and asking for one breaks the "scan a code → start
chatting" promise of the channel.

**What we actually want** is the behavior the WhatsApp channel already has: when a session opens
against an entry point that has an **auth resolver bound**, the platform calls that resolver
server-side, mints/looks-up the user's JWT from their *verified identity*, verifies it through the
normal authenticator chain, and starts the session authenticated — with **zero token handling in
the client**. The user never sees a token; they just chat as themselves.

**Goal.** Make an `AUTH_REQUIRED` chat entry point whose `AuthConfig` is `ResolverExchange`
authenticate transparently at session start, using the same `AuthResolver` → verify → stamp →
persist pipeline that `ChannelAuthIntegrator` runs for WhatsApp. Remove the paste-token prompt
from the resolver-exchange path.

**Non-goal.** We are not deleting bearer/OIDC support. `AuthConfig.Oidc` (interactive PKCE) and a
raw bearer on the `Authorization` header remain valid ways to start an authenticated chat. This
design adds the *third, transparent* path that is already the default for WhatsApp.

---

## 2. Why this is a wiring gap, not a new capability

Everything needed already exists; it is simply not connected on the chat front door.

**What's already there:**

- `ChatEntryPoint` carries **both** an `AuthMode` *and* an `AuthConfig`
  (`None | Oidc | ResolverExchange(authResolverId)`) — `ChatEntryPoint.java:22-23`.
- The resolver pipeline is production code for WhatsApp: `AuthResolver` (SPI),
  `AuthResolverRegistry` (BEAN/HTTP materialization), `AuthResolverDispatcher`
  (cache + dispatch), `ChannelAuthIntegrator` (dispatch → verify via `authChain` →
  stamp channel claims → `Resolved`), and `SessionAuthTokenStore` (AES-GCM persist
  of the minted JWT on the session row).
- The chat front door already has a **verified caller identity** available: the
  phone-verified device pass (`X-Device-Pass` → `DevicePassService.verify` →
  `VerifiedDevice`), and an `ep.requiresVerifiedPhone()` gate that already forces it.

**What's missing (the whole bug):** `PublicChatController.authForStart`
(`PublicChatController.java:232-250`) branches **only** on `ep.authMode()`. It never reads
`ep.authConfig()`. So `ResolverExchange` is stored, editable in admin, surfaced nowhere at start,
and the app is left to satisfy `requiresTokenToStart()` the only way it can — by supplying a
bearer, i.e. the paste prompt.

```java
// PublicChatController.authForStart — today
if (ep.authMode().requiresTokenToStart()) {
    if (!hasBearer) throw ChatApiException.authRequired();   // ← forces the client to bring a JWT
    return verifiedContext(ep, exchange);
}
```

The fix is to consult `authConfig` first: if it's `ResolverExchange`, resolve server-side instead
of demanding a client bearer.

---

## 3. The trust model — what feeds the resolver

The resolver maps a **verified identity** to a tenant JWT. The single most important design
decision is *which identity we hand it*, because the resolver trusts its input completely.

| Channel | Verified caller identity | Trust anchor |
|---|---|---|
| WhatsApp | `callerPhone` (E.164) | Twilio-signed inbound webhook |
| SMS | `callerPhone` (E.164) | Twilio-signed inbound webhook |
| **Chat (this doc)** | **`VerifiedDevice.phone` (E.164)** | **Platform OTP → device pass (§20 of chat-channel-design)** |

The chat channel's analog of "Twilio told us the caller's number" is "the user proved control of
this phone via our OTP flow, and holds a device pass that attests it." That device pass is already
verified on every request via `X-Device-Pass`. **We feed the resolver the phone from the verified
device — never a phone or identifier the client merely claims.** This is the same discipline as
Non-negotiable #1 ("the client is never trusted"): the resolver input is a server-verified fact.

**Consequence:** `ResolverExchange` on a chat entry point *implies a verified phone is required*.
If the device isn't verified, we cannot safely call the resolver, so we return
`verification_required` (the app already knows how to launch OTP onboarding for this). Practically,
`ResolverExchange` should force `requiresVerifiedPhone = true` at config time (see §7).

A nice product consequence falls out of this: because the resolver's input is `(tenant, e164phone)`
in both cases, **a tenant can reuse the exact same resolver for WhatsApp, SMS, and chat.** One
"phone → account JWT" webhook serves every channel.

---

## 4. The flow

### 4.1 Sequence (resolver-exchange chat start)

```
App (yayaagenticchat)                PublicChatController              Resolver pipeline
  |                                        |                                  |
  | GET /v1/chat/e/{handle}                |                                  |
  |--------------------------------------->|                                  |
  |   EntryPointCard                       |                                  |
  |   authMode=AUTH_REQUIRED               |                                  |
  |   authMechanism=RESOLVER  <── NEW      |                                  |
  |<---------------------------------------|                                  |
  |                                        |                                  |
  | (device already OTP-verified;          |                                  |
  |  hold X-Device-Pass)                   |                                  |
  |                                        |                                  |
  | POST /e/{handle}/sessions              |                                  |
  |  X-Device-Pass: <pass>                 |                                  |
  |  (NO Authorization header)             |                                  |
  |--------------------------------------->|                                  |
  |                                        | verify device pass → phoneE164   |
  |                                        |--------------------------------->|
  |                                        |   dispatch(resolverId, tenant,   |
  |                                        |     callerPhone=phoneE164,        |
  |                                        |     channel="chat", INITIAL)      |
  |                                        |   cache HIT? → JWT                |
  |                                        |   else resolver.resolve() → JWT  |
  |                                        |   verify JWT via authChain        |
  |                                        |   stamp channel claims            |
  |                                        |<---------------------------------|
  |                                        | Resolved(JWT_VERIFIED, principal)|
  |                                        | engine.start(req, alreadyVerified)|
  |                                        | SessionAuthTokenStore.persist()   |
  |   StartResponse (greeting, …)          |                                  |
  |<---------------------------------------|                                  |
  |  session runs AUTHENTICATED, no token ever touched the client            |
```

### 4.2 Decision table at `authForStart`

Evaluated top-down; first match wins. Only the **new** row changes existing behavior.

| `authConfig` | `authMode` | Device pass | Bearer | Outcome |
|---|---|---|---|---|
| `ResolverExchange` | `AUTH_REQUIRED` | present | — | **Resolve server-side → authenticated (NEW)** |
| `ResolverExchange` | `AUTH_REQUIRED` | missing | — | `verification_required` → app runs OTP, retries (NEW) |
| `ResolverExchange` | `AUTH_OPTIONAL` | present | — | Resolve; on `Unresolved` fall back to anonymous (NEW) |
| `Oidc` / `None` | `AUTH_REQUIRED` | — | present | Verify bearer (unchanged) |
| `Oidc` / `None` | `AUTH_REQUIRED` | — | missing | `auth_required` (unchanged) |
| any | `AUTH_OPTIONAL` | — | present | Verify bearer (unchanged) |
| any | `AUTH_OPTIONAL`/`ANON` | present | — | Verified-device principal (unchanged) |
| any | `ANONYMOUS_ONLY` | — | — | Anonymous principal (unchanged) |

### 4.3 `Unresolved` / `Failed` handling

The resolver can return **`Unresolved`** (a clean 404 — "this phone has no account") or **`Failed`**
(transport/config error). We route both through the entry point's `OnUnresolvedPolicy`, exactly as
WhatsApp does via `applyOnUnresolved`. `ChatEntryPointService` already derives this policy from the
mode (`ChatEntryPointService.java:111`): `AUTH_REQUIRED → REJECT`, otherwise `ANONYMOUS`.

- **`REJECT`** (AUTH_REQUIRED): surface a typed `resolver_unresolved` error. The app shows a
  human message — *"We couldn't find an account for this number."* — not a token dialog.
- **`ANONYMOUS`** (AUTH_OPTIONAL): start the session as the verified-device principal (still
  phone-attested, just not tenant-account-linked). The existing AUTH_OPTIONAL upgrade path remains
  available if the user later authenticates.

`Failed` (as opposed to `Unresolved`) is a platform/tenant outage, not a "user not found." Under
`REJECT` we still fail closed, but with a distinct `resolver_unavailable` code and an audit line, so
ops can tell "your backend is down" from "this person isn't a customer."

---

## 5. Backend changes

All changes are additive and localized to the chat front door; the resolver pipeline itself is
untouched.

### 5.1 `PublicChatController.authForStart`

Add a resolver-exchange branch **before** the `requiresTokenToStart()` check:

```java
private AuthContext authForStart(ChatEntryPoint ep, String handle, String deviceId,
                                 ServerWebExchange exchange) {
    Optional<VerifiedDevice> device = verifiedDevice(exchange);

    // Server-side transparent auth: the resolver maps the verified phone → a tenant JWT.
    if (ep.authConfig() instanceof AuthConfig.ResolverExchange rx) {
        if (device.isEmpty()) throw ChatApiException.verificationRequired();
        return resolverExchangeContext(ep, handle, rx.authResolverId(), device.get());
    }

    if (ep.requiresVerifiedPhone() && device.isEmpty()) throw ChatApiException.verificationRequired();
    // …existing bearer / verified-device / anonymous logic unchanged…
}
```

`resolverExchangeContext` is a thin chat-side adapter that calls the shared pipeline. It should reuse
`ChannelAuthIntegrator`'s dispatch+verify+stamp rather than re-implement it. Because
`ChannelAuthIntegrator.resolve` is currently shaped around `ChannelBinding` + hardcodes
`"whatsapp"`, we factor its core into a channel-agnostic entry point:

```java
// ChannelAuthIntegrator (generalized) — new overload, existing WhatsApp one delegates to it
public Resolved resolveFor(Ids.TenantId tenant, long authResolverId,
                           OnUnresolvedPolicy onUnresolved,
                           AuthContext baseCtx, Ids.SessionId session,
                           SenderId sender, String senderPhone, String callerPhone,
                           String channel);   // "chat" | "whatsapp" | "sms"
```

The WhatsApp caller (`InboundDispatchWorker`) keeps calling the binding-shaped method, which now
just unpacks the binding and delegates. This keeps one verify/stamp implementation for every channel
(honors Non-negotiable #3: reasons/claims stamped in exactly one place).

### 5.2 Persist the minted JWT

On a fresh session, mirror the WhatsApp step (`InboundDispatchWorker.java`):

```java
StartSessionResult result = engine.start(req, auth);
resolved.jwt().ifPresent(jwt -> authTokenStore.persist(result.session().id(), jwt));
```

This gives tool calls their forwardable principal token and enables tool-level 401 refresh
(`ResolveReason.REFRESH`) — no chat-specific work; it's the same store WhatsApp uses.

### 5.3 `authForExisting` (send / resume / end)

For resolver-exchange sessions, subsequent requests are already authenticated via the persisted
principal on the session; the client keeps sending only `X-Device-Pass`. `authForExisting`
(`PublicChatController.java:252-261`) treats a resolver-exchange session as **not anonymous**
(it started `JWT_VERIFIED`), so it rebuilds a verified context from the stored token rather than
demanding a bearer. The registry `Context.startedAuthenticated` flag already records this at start
(`startedAuthenticated`, line 286-292) — extend it to return `true` for resolver-exchange.

### 5.4 Wire protocol — no client token, ever

`POST /v1/chat/e/{handle}/sessions` for a resolver-exchange entry point:

- **Request:** `X-Device-Pass: <pass>`, JSON body `{deviceId, locale}`. **No `Authorization`.**
- **200:** normal `StartResponse`; the session is authenticated server-side.
- **401 `verification_required`:** the device isn't phone-verified → app launches OTP, retries.
- **403 `resolver_unresolved`:** resolver returned 404 under `REJECT`.
- **503 `resolver_unavailable`:** resolver transport/config failure under `REJECT`.

---

## 6. Client changes (`yayaagenticchat`)

The card must tell the app *how* to authenticate so it can stop guessing. Today the app infers
"AUTH_REQUIRED with no `oidc` block ⇒ paste a token." We replace inference with an explicit signal.

### 6.1 Card: add an explicit mechanism

Extend `PublicChatDtos.EntryPointCard` with a discriminator derived from `authConfig`:

```java
String authMechanism   // "NONE" | "OIDC" | "RESOLVER"
```

`EntryPointCard.of` already inspects `authConfig` for the `Oidc` case; add the `ResolverExchange`
arm to set `authMechanism = "RESOLVER"`. (Keeping the coarse `authMode` string too, for the
paused/optional UI copy — the two are orthogonal: `authMode` = *whether* auth is needed,
`authMechanism` = *how* it's obtained.)

### 6.2 App behavior by mechanism

| `authMechanism` | App does |
|---|---|
| `RESOLVER` | Ensure device is OTP-verified (existing onboarding). Start the session with `X-Device-Pass` and **no** token UI. On `resolver_unresolved`, show a friendly "no account for this number" state. |
| `OIDC` | Launch interactive PKCE (the existing `oidc` seam; still a future item). |
| `NONE` | Anonymous / verified-device as today. |

Concretely in Flutter:

- `ChatController._init` / `signInWithToken`: for a `RESOLVER` card, **do not** render
  `_SignInPrompt`. Instead gate on device verification and call `_startSession()` directly.
- `_SignInPrompt` (the paste-token dialog) is retained **only** behind a dev/debug flag or removed
  outright — it is never shown for a `RESOLVER` or `NONE` card, and for `OIDC` it's superseded by
  the PKCE flow.
- `ChatState.needsSignIn` becomes mechanism-aware: a `RESOLVER` card "needs" *device verification*,
  not a token.

### 6.3 Confirm card copy

`ChatConfirmCard` currently shows `signInRequired` vs `chatAsGuest` off `requiresAuth`. For a
`RESOLVER` card the honest copy is closer to *"Verify your phone to chat as yourself"* — the user
authenticates by proving their number, not by signing in with a password.

---

## 7. Admin / config

- **Setting it up:** an operator creates a chat entry point with `authMode = AUTH_REQUIRED` and
  `authConfig = ResolverExchange(authResolverId)` pointing at an existing `channel_auth_resolvers`
  row (the same table WhatsApp resolvers live in). No new admin surface — `ChatEntryPointController`
  already accepts `authConfig` (`ChatEntryPointController.java:89-90,108-109`).
- **Invariant to enforce (`ChatEntryPointService`):** when `authConfig` is `ResolverExchange`,
  force `requiresVerifiedPhone = true`. A resolver-exchange entry point *cannot* function without a
  verified phone to feed the resolver, so we make that structurally true at write time rather than
  discovering it at session start. Reject the config otherwise with a validation error.
- **Source of truth for the resolver id:** the chat front door reads it from
  `AuthConfig.ResolverExchange.authResolverId`, consistent with how `Oidc` is read from `authConfig`
  today. (The entry point's underlying `MatchAny` `ChannelBinding` also has an `authResolverId`
  column; to avoid two sources of truth, treat `authConfig` as authoritative for the chat channel
  and keep the binding column in sync — or leave it null for chat. Flagged in Open questions.)

---

## 8. Security & trust boundaries

1. **Resolver input is server-verified.** The resolver only ever receives a phone that came from a
   verified device pass. A client cannot influence *which* account it gets a token for. (§2, §3)
2. **The minted JWT is still verified.** The resolver's output is not trusted on its face — it goes
   through the same `authChain.tryAuthenticate` as any bearer, against the tenant's
   `tenant_jwt_secrets` / `tenant_oidc_issuers` trust roots. A resolver that returns a garbage or
   wrong-tenant token yields `Unresolved`, not a session. (reuses `ChannelAuthIntegrator.verifyAndStamp`)
3. **Fail closed under REJECT.** `AUTH_REQUIRED` never degrades to anonymous on resolver failure;
   it errors. Only `AUTH_OPTIONAL` falls back, and only to a *phone-verified* (not fabricated)
   principal.
4. **Token at rest.** The minted JWT is AES-256-GCM encrypted on the session row via the existing
   `SessionAuthTokenStore` / `JwtSecretCipher`; it is never returned to the client. The client holds
   only its device pass.
5. **No token in the client = smaller blast radius.** Removing the paste flow removes an entire
   class of user-pasted-the-wrong-token / token-in-clipboard problems and keeps the JWT server-side
   for its whole life.
6. **Abuse gates unchanged.** The per-device / per-IP start limits and per-session message caps
   (`ChatRateLimiter`) run before any resolver dispatch, so a resolver-exchange entry point can't be
   used to hammer the tenant webhook.

---

## 9. Testing

- **Unit — `authForStart`:** resolver-exchange + verified device → dispatch called with the
  device's E.164 and `channel="chat"`; resolver-exchange + no device → `verification_required`;
  bearer/OIDC/anonymous rows unchanged (regression lock on the decision table §4.2).
- **Unit — `ChannelAuthIntegrator.resolveFor`:** channel-agnostic overload produces the same
  `Resolved` for `channel="chat"` as the WhatsApp path for the same `(tenant, phone)`; the
  WhatsApp binding method still delegates correctly.
- **Unit — Unresolved/Failed routing:** `REJECT` → `resolver_unresolved` / `resolver_unavailable`;
  `ANONYMOUS` → verified-device principal.
- **Integration — happy path:** start against a resolver-exchange entry point with only
  `X-Device-Pass`; assert the session runs authenticated (principal subject = resolved account),
  the JWT is persisted encrypted, and no `Authorization` header was required.
- **Cache:** second start for the same phone within the window is a cache HIT (no second webhook
  call) — reuses `AuthResolverDispatcher` behavior; assert via audit/cache-state.
- **Flutter widget:** a `RESOLVER` card never shows `_SignInPrompt`; an unverified device routes to
  OTP onboarding; `resolver_unresolved` renders the "no account" state, not a token dialog.

---

## 10. Rollout

1. **BE-1** — generalize `ChannelAuthIntegrator` (`resolveFor`, channel param); WhatsApp delegates.
   Pure refactor, no behavior change; ship behind existing tests.
2. **BE-2** — wire `authForStart` resolver-exchange branch + persist JWT + `authForExisting` /
   `startedAuthenticated` updates. Guarded implicitly: only entry points configured with
   `ResolverExchange` take the new path, and none exist in prod until an operator sets one.
3. **BE-3** — `ChatEntryPointService` invariant (`ResolverExchange ⇒ requiresVerifiedPhone`) +
   `authMechanism` on the card DTO.
4. **FE-1** — app consumes `authMechanism`; resolver-exchange cards skip the paste prompt and gate
   on OTP; remove/flag `_SignInPrompt`.
5. **Verify** — configure one internal tenant entry point as resolver-exchange pointing at the same
   webhook its WhatsApp bot uses; confirm scan → verify phone → authenticated chat, no token.

Backward compatible throughout: existing `AUTH_REQUIRED`+bearer and `ANONYMOUS`/`OPTIONAL` entry
points are untouched because they don't carry `AuthConfig.ResolverExchange`.

---

## 11. Open questions

1. **Two `authResolverId` homes.** `AuthConfig.ResolverExchange.authResolverId` vs the entry point's
   `MatchAny` `ChannelBinding.authResolverId`. Proposal: `authConfig` is authoritative for chat;
   either mirror into the binding on write or leave the binding's column null for chat entry points.
   Decide which, and enforce it in `ChatEntryPointService`.
2. **Identity beyond phone.** Some tenants may want to key the resolver on a device/account id rather
   than a phone (e.g., app installs with no phone verification). Out of scope now — the resolver
   `ResolveRequest` is phone-centric — but worth noting as a future `ResolveRequest` extension.
3. **Refresh trigger in chat.** WhatsApp refreshes on tool-level 401 (`ResolveReason.REFRESH`).
   Confirm the same trigger fires for chat sessions (it should, since it's engine/tool-level, not
   channel-level) and that a refresh failure surfaces as a graceful "please re-verify" rather than a
   dead session.
4. **`_SignInPrompt` fate.** Delete entirely, or keep behind a debug flag for internal testing of
   raw-bearer entry points? Recommend keep-behind-flag until the OIDC PKCE path lands, then delete.
