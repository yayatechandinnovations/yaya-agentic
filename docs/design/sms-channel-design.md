# SMS Channel — A2P 10DLC Sender Registration & Compliance (v1)

> Companion to `whatsapp-channel-design.md` — this design is deliberately a
> **thin delta** on top of it. SMS reuses that doc's `MessagingChannelProvider`
> SPI (§5.1), sender state machine (§6), platform-singleton Twilio account (§7),
> channel binding (§8), inbound/outbound lifecycle (§9, §10), and `ChannelKind`
> (§5.1). Read `whatsapp-channel-design.md` first; this doc only describes what
> SMS adds or changes.
>
> Companion to `yaya-agentic-design.md` §5.2 (Profile), §5.4 (AuthN), §5.6
> (Profile resolver chain), §12 (Safety); and `tenant-registry-design.md` (the
> tenant is the single trust root; an SMS sender is the same "channel endpoint"
> projection of the tenant trust bundle that a WhatsApp sender is).

| | |
|---|---|
| **Status** | 🟡 Proposed |
| **Owner** | Yenfri Herrera |
| **Audience** | Engineering team building yaya-agentic; PM stakeholders evaluating the SMS go-to-market |
| **Last updated** | 2026-06-28 |
| **Scope** | Make **SMS/MMS** a first-class deployment target for any profile a tenant has already built, reusing the WhatsApp channel machinery wholesale. The one genuinely new subsystem is **US carrier compliance**: programmatic **A2P 10DLC** registration (a per-tenant Brand → Campaign approval chain) that must clear before a US number can send. The BSP (Twilio) and the carrier-registry (TCR) stay **fully invisible to the tenant** — they describe their business and a use case, and the platform registers on their behalf under the platform's ISV identity. Markets: **US** (A2P 10DLC) and **Dominican Republic** (number provisioning via regulatory bundles; sender-ID realities). |
| **Out of scope (v1)** | **Voice.** It is not a messaging channel — different Twilio API, different compliance (SHAKEN/STIR, CNAM, TCPA), and a different SPI than `MessagingChannelProvider`. Explicitly deferred; §12 keeps the seam. **Dedicated short codes** (US + DR) — 8–12 wk carrier provisioning, modelled as a sender sub-type seam (§4.2), not built. **Toll-free verification** as a distinct path. **BYO Twilio account** — reuses the existing `ProviderCredsResolver` seam (`whatsapp-channel-design.md` §7.3), unchanged. Other BSPs. RCS. MMS rich media beyond a single image. Per-tenant **multiple Brands** (v1 is one Brand per tenant; §6.4 keeps the seam). |

---

## 1. The problem in one paragraph

A tenant who has built a profile can ship it over WhatsApp today (once `whatsapp-channel-design.md` lands) but not over plain SMS — the channel a huge share of users still default to, and the one with the lowest friction (no app, no business verification, just a phone number). The blocker is not the transport: Twilio's Programmable Messaging API is simpler than WhatsApp's, and the inbound webhook, outbound dispatch, binding, and phone-authenticator are **identical in shape** to what the WhatsApp design already specifies. The blocker is **US carrier compliance**: since 2023 every US long-code SMS sender must be registered under **A2P 10DLC** — the business registered as a *Brand* with The Campaign Registry (TCR), each messaging use case registered as a *Campaign*, and carriers individually approving before traffic flows. Unregistered traffic is filtered or blocked outright. So the work that doesn't already exist is: (1) promote `ChannelKind.Sms` from a pinned stub to a shipped channel with its own adapter, and (2) a **per-tenant compliance registration subsystem** — Brand + Campaign lifecycle, polled to approval, gating the sender's ability to send — that WhatsApp never needed because Meta's business verification plays that role there.

## 2. Trust principle (read first)

> An SMS sender is the **same channel-endpoint projection of the tenant trust bundle** as a WhatsApp sender (`whatsapp-channel-design.md` §2). Everything there holds: one sender belongs to exactly one tenant, the inbound webhook is a non-bypassable signed trust edge, and the BSP is a platform implementation detail the tenant never sees. SMS adds **one** new trust object: the **A2P registration** (Brand + Campaign), which is a compliance assertion *the platform makes to carriers on the tenant's behalf*. It is owned by the tenant, registered under the platform's ISV account, and — like the sender — can never be re-pointed at another tenant.

Two corollaries specific to SMS:

- **The registration is invisible to the tenant, the same way Twilio is.** The tenant declares a business and a use case in plain language; the platform renders that into a TCR Brand + Campaign under its own ISV (Twilio Tech-Provider / Secondary Customer Profile) relationship. The tenant never logs into Twilio or TCR, never sees a Brand SID. This is the direct SMS analogue of `whatsapp-channel-design.md` §7.4 ("the operator is the registered Meta Tech Provider; each tenant's business is registered as a Meta business under our Tech Provider").
- **A sender cannot reach `ACTIVE` until its campaign is `APPROVED`.** Compliance is a hard gate in the state machine (§5), not advice. An operator cannot flip a binding live on an unregistered number, because carriers will silently drop the traffic and burn the platform's sending reputation for *every* tenant on the shared account.

## 3. Glossary additions

| Term | Definition |
|---|---|
| **A2P 10DLC** | Application-to-Person messaging over 10-digit long codes. The US carrier framework requiring brand + campaign registration for business SMS. |
| **TCR** | The Campaign Registry — the industry registry US carriers use. Twilio brokers registration with it; we talk only to Twilio. |
| **Brand** | The registered identity of a *business* (the tenant). Carries legal name, tax ID (EIN), address, vertical. One per tenant in v1. Maps to a Twilio/TrustHub **Secondary Customer Profile** + A2P **Brand**. |
| **Campaign** | A registered *use case* under a Brand (e.g. "account notifications", "marketing", "2FA"). Carries sample messages + opt-in description. A tenant has one or more. Carriers approve per-campaign. |
| **ISV** | Independent Software Vendor — Twilio's term for a platform (us) registering messaging on behalf of many businesses. Drives the "Secondary Customer Profile per tenant under one Primary Profile" shape. |
| **Regulatory bundle** | Twilio's per-country identity/address document set required to purchase a number in regulated markets (DR is one). Distinct from A2P 10DLC, which is US-carrier-specific. |
| **Messaging Service** | Twilio's container that pools numbers for throughput/sticky-sender and is the attach point linking a number to its approved campaign. SMS senders dispatch through it. |
| **Porting / hosting** | Bringing a phone number the tenant **already owns** into the platform's Twilio account (vs. buying a fresh one). Requires an **LOA** + proof of ownership; the losing carrier processes it asynchronously over days–weeks. Modelled via `sms_senders.acquisition='HOSTED'` (§7.1). |
| **LOA** | Letter of Authorization — the document, signed by the number's owner, that authorizes the port/hosting request to the losing carrier. |

## 4. Goals & non-goals

### 4.1 Goals (v1)

1. A tenant operator registers an **SMS sender** (a US or DR phone number) from the admin console by describing their business + use case — no Twilio, no TCR exposure.
2. The platform drives **A2P 10DLC** Brand + Campaign registration to approval, polled and surfaced as status, with friendly remediation copy on rejection.
3. An `ACTIVE` SMS sender binds to a profile through the **existing** `channel_bindings` table and `ChannelProfileResolver` — zero new binding code.
4. Inbound SMS runs through the engine and replies dispatch back, reusing the WhatsApp inbound/outbound lifecycle (buffer-until-`End`, signature verification, dedup, opt-out keywords).
5. DR numbers provision via **regulatory bundles**, with the sender-ID-overwrite reality (§7) surfaced honestly in the UI rather than discovered at send time.

### 4.2 Non-goals (v1) — and the seams kept open

| Deferred | Seam that admits it later |
|---|---|
| **Voice** | A sibling `VoiceChannelProvider` SPI + `ChannelKind.Voice`; shares the tenant trust bundle and number-provisioning code, nothing else. §12. |
| **Dedicated short codes** | `sms_senders.sender_kind` discriminator (`LONG_CODE` v1; `SHORT_CODE`/`TOLL_FREE` later). Short codes skip A2P 10DLC but add their own carrier program brief. |
| **Bringing an owned number** (porting/hosting) | `sms_senders.acquisition` discriminator (`PURCHASED` v1; `HOSTED` adds the LOA + port sub-flow). Independent of BYO Twilio. Modelled now, built post-MVP — §7.1, §13 (S6). |
| **BYO Twilio** | The existing `ProviderCredsResolver` swap (`whatsapp-channel-design.md` §7.3). Unchanged. |
| **Multiple Brands per tenant** | `sms_brands` is keyed by id, not `UNIQUE(tenant_id)`; v1 enforces one in the service layer, the table already admits N. |
| **WhatsApp reusing the Brand** | The compliance registration is modelled as its own aggregate, not buried in `sms_senders`, so a future channel could reference the same tenant Brand. |

## 5. What SMS reuses vs. adds

| Subsystem | WhatsApp (`whatsapp-channel-design.md`) | SMS (this doc) |
|---|---|---|
| `ChannelKind` | `WhatsApp` | **`Sms`** — promote from pinned stub to shipped (§5.1 of that doc) |
| `MessagingChannelProvider` | Twilio WhatsApp methods | **Same SPI**; Twilio impl gains the SMS sender path. `supports()` adds `Sms`. |
| Sender state machine (§6) | `DRAFT…ACTIVE…REVOKED` | **Same 7 states**, with one added precondition on the `→ ACTIVE` edge: campaign `APPROVED` (§5.2) |
| Provider creds (§7) | platform-singleton | **identical**, same resolver, same account |
| Channel binding (§8) | `channel_bindings`, `ChannelProfileResolver` | **identical**, `endpoint_kind = 'SMS_SENDER'` |
| Inbound lifecycle (§9) | signed webhook, dedup, ack-then-process, `PhoneAuthenticator` | **identical**; same `inbound_messages` table, same authenticator (subject `sms:+E164`) |
| Outbound (§10) | buffer-until-`End`, retries, status callbacks | **identical minus** templates/24h-window/interactive — SMS is plain text, simpler |
| Opt-in/opt-out (§11) | STOP/HELP keywords | **identical**; carrier-mandated for SMS, Twilio Advanced Opt-Out handles STOP natively |
| **A2P 10DLC compliance** | *n/a — Meta verification plays this role* | **NEW (§6)** — the whole reason this doc exists |
| Templates (§11) | required outside 24h window | **dropped** — SMS has no template concept; no `whatsapp_templates` analogue |
| Interactive (Quick Replies/Lists) | native WhatsApp surfaces | **dropped** — SMS is plaintext; quick-replies degrade to numbered text (engine `UiHint` already handles this) |

### 5.1 The one engine-visible change

`CompletionPolicy.Complete` already exists for WhatsApp (buffer the stream, dispatch one finalized message). SMS uses the same policy with a tighter constraint: **segment awareness**. A single SMS is 160 GSM-7 chars (70 for UCS-2 / accented Spanish for DR); longer bodies split into billed segments. The SMS adapter doesn't truncate — it dispatches multi-segment — but it surfaces segment count in `outbound_messages` for billing and warns in evals when a profile habitually emits 6+ segment replies. No new engine SPI; this lives entirely in the adapter.

### 5.2 Sender state machine — the added gate

The 7 states from `whatsapp-channel-design.md` §6.1 are unchanged. The only difference is the guard on the `PENDING_REVIEW → ACTIVE` transition:

```
WhatsApp:  PENDING_REVIEW ──(Meta + display-name approved)──▶ ACTIVE
SMS:       PENDING_REVIEW ──(number provisioned  AND  campaign APPROVED)──▶ ACTIVE
```

The sender's `PENDING_REVIEW` poll (the existing `SenderSyncJob`, §6.4 of that doc) additionally checks the campaign's compliance state. A number can be bought and webhook-wired while its campaign is still in carrier review; the sender simply waits in `PENDING_REVIEW` until both conditions hold. No new states, no new poller — one extra predicate.

---

## 6. The new subsystem — A2P 10DLC compliance registration

This is the only genuinely new design surface. Everything else is reuse.

### 6.1 Shape

Two new aggregates, both tenant-scoped, both invisible to the tenant as Twilio objects:

```java
/** The tenant's registered business identity with the carriers. One per
 *  tenant in v1 (service-enforced; table admits N — §4.2 seam). */
public record SmsBrand(
    SmsBrandId        id,                 // platform-stable
    TenantId          tenant,
    BusinessIdentity  business,           // legal name, EIN, address, vertical, website, contact
    BrandStatus       status,             // see §6.2
    String            twilioProfileSid,   // BU... Secondary Customer Profile, after submit
    String            tcrBrandId,         // assigned by TCR via Twilio, after verify
    String            lastFailureCode,
    String            lastFailureMessage,
    Instant           createdAt, updatedAt, lastSyncAt
) {}

/** A registered use case under a Brand. A tenant has one or more. */
public record SmsCampaign(
    SmsCampaignId     id,
    TenantId          tenant,
    SmsBrandId        brand,
    A2pUseCase        useCase,            // §6.3 — enumerated; drives carrier review path
    String            description,        // tenant's plain-language use-case description
    List<String>      sampleMessages,     // 2–5; MUST match real traffic (§6.5)
    OptInDescription  optIn,              // how end users consent + a proof URL/screenshot ref
    CampaignStatus    status,             // see §6.2
    String            twilioCampaignSid,  // after submit
    String            messagingServiceSid,// Twilio MS the numbers attach to
    String            lastFailureCode,
    String            lastFailureMessage,
    Instant           createdAt, updatedAt, lastSyncAt
) {}
```

`sms_senders` (the phone numbers) reference a campaign: `campaign_id NOT NULL`. A sender inherits its sending eligibility from its campaign's `APPROVED` state.

### 6.2 Compliance state machine

Brand and Campaign each have a small durable state machine, polled by the **same** `SenderSyncJob` extended to sweep `sms_brands` / `sms_campaigns` in non-terminal states (one job, three table sweeps — not a new scheduler).

```
Brand:     DRAFT → SUBMITTING → PENDING_VERIFICATION → VERIFIED
                        │                  │
                        └──────────────────┴──▶ FAILED  (edit → DRAFT)

Campaign:  DRAFT → SUBMITTING → PENDING_REVIEW → APPROVED
                        │              │
                        └──────────────┴──▶ FAILED  (edit → DRAFT)
                                       │
              APPROVED ──(carrier suspends)──▶ SUSPENDED
```

A Campaign cannot leave `DRAFT` until its Brand is `VERIFIED`. The UI enforces the ordering; the service re-checks it (never trust the client). On a `Rejected` from Twilio at submit, the result maps onto the existing `SenderRegistrationResult.Rejected` shape — we reuse that sealed type rather than inventing a parallel one.

### 6.3 Use cases — where the lottery/restricted-content branch lives

`A2pUseCase` is an enum mirroring TCR's standard + special use cases. Most tenants pick a **standard** case (e.g. `ACCOUNT_NOTIFICATION`, `CUSTOMER_CARE`, `TWO_FACTOR_AUTH`, `MARKETING`) and clear in days. A subset are **special / restricted** (`GAMBLING`, `SWEEPSTAKES`, high-risk financial) requiring extra carrier approval and tenant-supplied licensing evidence, and some carriers refuse them outright.

This is the right home for the compliance reality I over-indexed on earlier: it is **one branch of an enum**, not an assumption about the product. The registration service:

- routes restricted use cases into an **evidence-collection** step (license number, regulator, jurisdiction list, age-gate attestation) stored on the campaign,
- sets realistic expectations in the UI (carrier approval can take longer / be refused),
- and lets the platform operator (not the tenant) review restricted submissions before they go to Twilio — a `requires_operator_review` flag on the campaign, defaulting true only for the restricted enum members.

A lottery tenant is exactly such a tenant; nothing in the data model is lottery-specific.

### 6.4 ISV identity — the platform's one-time setup

Registration hangs off a **platform-owned TrustHub Primary Business Profile** (yaya's own identity) configured once, the same way the platform Twilio account is configured once (`whatsapp-channel-design.md` §7.1). It is operator config, not a DB row:

```yaml
yaya:
  agentic:
    channels:
      twilio:
        # ...existing WhatsApp config (account-sid, api-key, webhook-base-url)...
        a2p:
          primary-profile-sid: ${TWILIO_PRIMARY_PROFILE_SID}   # yaya's TrustHub identity
          # CSP / ISV brand registered once with TCR; tenant Brands are Secondary
          # Customer Profiles registered UNDER this. Empty in dev → A2P disabled,
          # SMS senders allowed only for DR/non-A2P numbers + the sim sender.
```

Each tenant `SmsBrand` is registered as a **Secondary Customer Profile** under this Primary — the exact ISV shape Twilio documents for platforms. The tenant never sees any of it.

### 6.5 Sample-message integrity

The single biggest cause of A2P rejection is sample messages that don't match real traffic, or links on public shorteners. Two guardrails, both cheap:

- Sample messages are validated at submit for the obvious carrier red flags (bare `bit.ly`/`tinyurl` links, ALL-CAPS spam patterns, missing opt-out language for marketing campaigns) — a lint, surfaced as warnings, not a hard block.
- An eval scenario per SMS-bound profile checks that the profile's actual outbound copy is *consistent in kind* with the registered samples, so drift between "what we registered" and "what we send" is caught in CI rather than by a carrier audit.

---

## 7. Number acquisition & market specifics

How a number gets into the system has two facets the MVP must not hard-code away: **where the number comes from** (purchased fresh vs. an owned number ported in), and **per-market provisioning rules** (DR). Both are orthogonal to the §6 A2P compliance subsystem — a number, however acquired, still attaches to the tenant's Campaign on the US path.

### 7.1 Bringing an owned number (porting / hosting)

A tenant often already owns a phone number their customers recognize and would rather keep it than adopt a fresh one. This is **number porting / hosting into the platform's Twilio account** — distinct from both "pick a vanity number from inventory" (just provisioning) and "BYO Twilio account" (§4.2 / `whatsapp-channel-design.md` §7.3, a separate seam). A tenant can bring *their number* into the *platform's* account while the platform-singleton model stays fully intact.

- **It's a different acquisition flow, not a flag.** Hosting/porting requires a **Letter of Authorization (LOA)** signed by the number owner, proof of ownership (a recent carrier bill), and a Twilio port/hosting request the losing carrier processes **asynchronously over days–weeks**. This is genuinely more work and an ops process (someone chases the port), which is why it is **deferred out of the MVP** (§13, S6) — but the data model admits it from day one so it isn't a painful retrofit.
- **A2P 10DLC is unchanged.** A ported US long code attaches to the tenant's Campaign / Messaging Service exactly like a purchased one. The §6 subsystem doesn't change; only the acquisition step before `DRAFT` does.
- **Modelled as a discriminator + sub-flow.** `sms_senders.acquisition` is `PURCHASED` (v1 default) or `HOSTED`. A `HOSTED` sender runs a port sub-flow before entering the normal state machine; its progress and the LOA artifact reference live in the existing `onboarding_state_json` (same reuse pattern as the WhatsApp `OnboardingState`), polled by the same `SenderSyncJob`. The terminal `PORTED` step hands the sender into `DRAFT` and everything downstream is identical.

```
HOSTED only:  PORT_REQUESTED → LOA_PENDING → PORT_IN_PROGRESS → PORTED ──▶ DRAFT → … → ACTIVE
PURCHASED:    (search + buy) ───────────────────────────────────────────▶ DRAFT → … → ACTIVE
```

### 7.2 Dominican Republic specifics

DR is **not** an A2P 10DLC market — that framework is US-only. DR registration is about **number provisioning** and a hard sender-identity reality:

- **Regulatory bundle.** Buying a DR number requires a Twilio regulatory bundle (business identity + local address documents; INDOTEL regime). The platform attaches the bundle at purchase. Operationally this is the same per-purchase document set the operator already manages for any regulated market; v1 surfaces a "DR number requires regulatory documents" gate in the wizard and blocks `submit` until the bundle is approved.
- **Sender-ID overwrite (surface it, don't fight it).** Per Twilio's DR guidelines, local operators overwrite dynamic alphanumeric/long-code sender IDs with a **random short code** — the tenant's chosen "from" identity will *not* reliably display. v1 states this plainly in the UI for DR senders rather than letting operators discover it in production. The only way to preserve a branded sender in DR is a **dedicated short code** (~4-wk provisioning) — kept as the §4.2 `sender_kind` seam, not built in v1.
- **Compliance.** Law 310-14 (SPAM) + Law 172-13 (data protection): explicit opt-in retained as proof, STOP/HELP in **Spanish**, send windows 08:00–20:00 local. Opt-in/opt-out reuses the WhatsApp §11.2 mechanism; the only addition is locale-aware keyword sets (`PARAR`/`AYUDA` alongside `STOP`/`HELP`).

No A2P aggregates are created for DR-only senders; the campaign FK is nullable-by-market, gated on `sms_senders.market`. (DDL: a DR sender carries `campaign_id NULL` and `regulatory_bundle_sid NOT NULL`; a US sender the inverse. A `CHECK` enforces exactly-one path.)

---

## 8. Data model

Tenant-scoped, sibling to the WhatsApp tables. Next free migrations are **V43+** (V42 is `api_keys`). One table per concern so each milestone has a self-contained delta.

```sql
-- V43__sms_a2p_registration.sql
CREATE TABLE sms_brands (
    id                    VARCHAR(64)  PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    business_json         JSONB        NOT NULL,        -- legal name, EIN, address, vertical…
    status                VARCHAR(24)  NOT NULL,
    twilio_profile_sid    VARCHAR(64),                  -- BU… Secondary Customer Profile
    tcr_brand_id          VARCHAR(64),
    last_failure_code     VARCHAR(64),
    last_failure_message  TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_sync_at          TIMESTAMPTZ,
    CHECK (status IN ('DRAFT','SUBMITTING','PENDING_VERIFICATION','VERIFIED','FAILED'))
);
CREATE INDEX sms_brands_tenant_idx ON sms_brands(tenant_id, status);
-- v1: one brand per tenant, enforced in the service layer (table admits N — §4.2).

CREATE TABLE sms_campaigns (
    id                     VARCHAR(64)  PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    brand_id               VARCHAR(64)  NOT NULL REFERENCES sms_brands(id),
    use_case               VARCHAR(40)  NOT NULL,        -- A2pUseCase enum
    description            TEXT         NOT NULL,
    sample_messages_json   JSONB        NOT NULL,        -- 2–5 strings
    opt_in_json            JSONB        NOT NULL,
    evidence_json          JSONB,                        -- restricted use cases only (§6.3)
    requires_operator_review BOOLEAN    NOT NULL DEFAULT FALSE,
    status                 VARCHAR(24)  NOT NULL,
    twilio_campaign_sid    VARCHAR(64),
    messaging_service_sid  VARCHAR(64),
    last_failure_code      VARCHAR(64),
    last_failure_message   TEXT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_sync_at           TIMESTAMPTZ,
    CHECK (status IN ('DRAFT','SUBMITTING','PENDING_REVIEW','APPROVED','SUSPENDED','FAILED'))
);
CREATE INDEX sms_campaigns_tenant_idx ON sms_campaigns(tenant_id, status);

-- V44__sms_senders.sql  (sibling to whatsapp_senders; reuses the §6 state machine)
CREATE TABLE sms_senders (
    id                     VARCHAR(64)  PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    phone_e164             VARCHAR(20)  NOT NULL,
    market                 VARCHAR(4)   NOT NULL,        -- 'US' | 'DO'
    sender_kind            VARCHAR(16)  NOT NULL DEFAULT 'LONG_CODE',  -- §4.2 seam
    acquisition            VARCHAR(12)  NOT NULL DEFAULT 'PURCHASED',  -- 'PURCHASED' | 'HOSTED' (§7.1)
    campaign_id            VARCHAR(64)  REFERENCES sms_campaigns(id),  -- US: required
    regulatory_bundle_sid  VARCHAR(64),                                -- DR: required
    status                 VARCHAR(20)  NOT NULL,
    twilio_sender_sid      VARCHAR(64),                  -- the IncomingPhoneNumber SID
    onboarding_state_json  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    webhook_secret_enc     BYTEA        NOT NULL,
    default_locale         VARCHAR(16)  NOT NULL DEFAULT 'en-US',
    metadata_json          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_sync_at           TIMESTAMPTZ,
    UNIQUE(phone_e164),
    CHECK (status IN ('DRAFT','SUBMITTING','PENDING_REVIEW','ACTIVE',
                      'SUSPENDED','FAILED','REVOKED')),
    -- exactly one compliance path: US→campaign, DR→regulatory bundle
    CHECK ( (market = 'US' AND campaign_id IS NOT NULL AND regulatory_bundle_sid IS NULL)
         OR (market = 'DO' AND regulatory_bundle_sid IS NOT NULL AND campaign_id IS NULL) )
);
CREATE INDEX sms_senders_tenant_idx ON sms_senders(tenant_id, status);
```

`channel_bindings`, `channel_sessions`, `inbound_messages`, `outbound_messages` from `whatsapp-channel-design.md` §13 are **reused as-is** — `inbound/outbound_messages` already key on Twilio's `SMxx` message SID and a phone number, which is exactly SMS. `outbound_messages` gains a nullable `segment_count INT` (additive, V45). No `outbound_pending` (no service window) and no templates table for SMS.

## 9. API surface

Mirrors `whatsapp-channel-design.md` §14 under `/v1/admin/tenants/{tenantId}/sms/*`, plus the compliance resources. Webhook URLs remain platform-internal (§14.1 there).

```
# Compliance registration (the new surface)
POST   /v1/admin/tenants/{t}/sms/brand                 # create/update tenant Brand (DRAFT)
POST   /v1/admin/tenants/{t}/sms/brand/submit          # → SUBMITTING
GET    /v1/admin/tenants/{t}/sms/brand                  # status
POST   /v1/admin/tenants/{t}/sms/campaigns             # create campaign (DRAFT)
POST   /v1/admin/tenants/{t}/sms/campaigns/{c}/submit  # → SUBMITTING (Brand must be VERIFIED)
GET    /v1/admin/tenants/{t}/sms/campaigns

# Senders (same shape as WhatsApp senders)
POST   /v1/admin/tenants/{t}/sms/senders               # DRAFT; picks market + campaign
POST   /v1/admin/tenants/{t}/sms/senders/{s}/submit    # provision number + wire webhook
POST   /v1/admin/tenants/{t}/sms/senders/{s}/sync      # force poll
GET    /v1/admin/tenants/{t}/sms/senders

# Bindings — REUSED, no new endpoints (endpoint_kind = 'SMS_SENDER')

# Inbound webhook (platform-internal, signed) — reuses the channel webhook controller
POST   /channels/twilio/inbound/{senderId}
POST   /channels/twilio/status/{senderId}
```

Operator-review queue for restricted campaigns (§6.3) surfaces under the existing operator (platform-admin) console, not the tenant console.

## 10. Flutter admin UX

A new **SMS** tab beside the WhatsApp tab, with a wizard that is the WhatsApp wizard plus a compliance preamble:

1. **Business (Brand)** — collected once per tenant; reused across campaigns. Skipped on later senders if the Brand is already `VERIFIED`.
2. **Use case (Campaign)** — pick from standard vs. restricted (§6.3); restricted reveals the evidence fields. Sample messages with the inline lint (§6.5).
3. **Number** — pick market (US/DR). DR shows the regulatory-bundle gate + the sender-ID-overwrite warning honestly. US attaches to the chosen campaign.
4. **Review & submit** — shows realistic timelines per path ("US carrier review: hours–days; restricted: longer / may be refused").
5. **Status panel** — Brand + Campaign + sender states, polled, with remediation copy on `FAILED`.

Bindings tab and playground integration are the existing WhatsApp ones, parameterized by channel.

## 11. Security & safety

Inherits every invariant from `whatsapp-channel-design.md` §16 (signed non-bypassable webhook, phone never reaches the LLM unless the profile authorizes it, per-tenant suspension before the shared account faults). SMS-specific:

- **A2P submissions are operator-gated for restricted use cases.** A tenant cannot push a `GAMBLING` campaign to carriers without the platform operator releasing it (`requires_operator_review`), protecting the shared sending reputation.
- **Sample-message ↔ real-traffic drift** is a CI eval (§6.5), not a trust-me.
- **EIN / business identity** in `sms_brands.business_json` is tenant PII at the same tier as the WhatsApp `business_profile_json`; same redaction and at-rest posture.

## 12. The Voice seam (explicitly not built)

Voice was raised alongside SMS and is deliberately excluded. It does not fit `MessagingChannelProvider` — it is media/session-oriented (TwiML, call legs), its compliance is SHAKEN/STIR attestation + CNAM + TCPA rather than A2P 10DLC, and its "registration" is caller-ID/branded-calling, not Brand/Campaign. Folding it into this doc would double the surface for a channel with a different shape end to end. The seam: a sibling `VoiceChannelProvider` SPI + `ChannelKind.Voice`, reusing the tenant trust bundle, number provisioning, and regulatory bundles (which *are* shared), and nothing else. A separate `voice-channel-design.md` when there's demand.

## 13. Milestone breakdown

Reuse keeps this small — most of the heavy lifting is done once WhatsApp's W0–W5 land. SMS is largely "the same, minus templates/interactive/window, plus the A2P subsystem."

| Milestone | Scope |
|---|---|
| **S0 — `ChannelKind.Sms` + adapter** (0.5 wk) | Promote `Sms` to shipped; `SmsChannelAdapter` (buffer-until-`End`, segment-aware); Twilio provider `supports()` adds `Sms`. Depends on WhatsApp W0. |
| **S1 — A2P compliance subsystem** (2 wk) | `sms_brands` + `sms_campaigns` tables, Brand/Campaign aggregates + state machines, `SenderSyncJob` extension, Twilio TrustHub/A2P calls, restricted-use-case evidence + operator-review gate, sample-message lint. **The bulk of new work.** |
| **S2 — SMS sender CRUD + provisioning** (1 wk) | `sms_senders` table, number search/purchase, US campaign-attach, DR regulatory-bundle gate, the `→ ACTIVE` campaign predicate. Reuses sender state machine + poller. |
| **S3 — Inbound/outbound wiring** (0.5 wk) | Point the existing channel webhook controller + dispatch worker at SMS; locale-aware STOP/HELP (`PARAR`/`AYUDA`). Reuses `inbound/outbound_messages`, dedup, phone authenticator. |
| **S4 — Flutter SMS wizard + status** (1 wk) | The §10 wizard; compliance status panel; bindings/playground reuse. |
| **S5 — Evals + hardening** (0.5 wk) | Sample-message-drift eval, segment-count eval, restricted-flow eval, OTel spans. |
| **S6 — Owned-number porting / hosting** (1.5 wk, **post-MVP**) | `acquisition='HOSTED'` path: LOA + proof-of-ownership document collection, Twilio port/hosting request, the port sub-flow (§7.1) on the existing `SenderSyncJob`, wizard "use a number I own" branch. Deferred — async multi-week carrier process + ops chase; the data model already admits it so this is purely additive. |

Total **~5.5 wk** (S0–S5), almost entirely gated on WhatsApp W0–W5 already existing (otherwise add those). **S0–S3 is the MVP** that registers a tenant Brand+Campaign, provisions a compliant US number, and routes a real inbound SMS to a profile. S1 is the only genuinely novel slice; everything else is parameterized reuse. **S6 (owned-number porting) is post-MVP** — modelled now (§7.1), built when a tenant needs it.

## 14. Open questions

1. **One Brand per tenant — right call for v1?** Some tenants are multi-brand holding companies. Service-enforced 1:1 with a table that admits N (§4.2). Revisit if a real tenant needs N before we generalize.
2. **DR conversational SMS demand vs. WhatsApp.** In DR, WhatsApp penetration is very high; conversational SMS may matter less than US. If DR demand is purely transactional (OTP/notifications), the existing `SmsSender` OTP transport may already cover it and DR conversational SMS can defer. Worth confirming with the DR go-to-market before building S2's DR path.
3. **Operator-review SLA for restricted campaigns.** Who in platform ops owns the review queue, and what's the turnaround commitment to tenants? Product/ops decision, not architectural.
