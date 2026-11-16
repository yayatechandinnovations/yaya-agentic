# WhatsApp self-service onboarding — Meta Embedded Signup + Twilio subaccounts

> **Audience:** operators of yaya-agentic (the platform team). This guide
> covers the **self-service** path where a tenant connects their *own*
> WhatsApp Business number through Meta's Embedded Signup popup — without a
> Twilio account, the Twilio Console, or leaving the admin app.
>
> **Companions:** `docs/guides/twilio-whatsapp-setup.md` (parent Twilio
> account + platform-singleton path, which keeps working unchanged) and
> `docs/design/whatsapp-channel-design.md` §7.3 (the resolver seam this
> feature lands).

---

## 1. Architecture

The platform-singleton model (one Twilio account for all tenants) remains the
default for legacy senders. Embedded-signup senders instead follow the
**Twilio WhatsApp Tech Provider** model:

```
Tenant admin (Flutter)                Backend                          Twilio / Meta
──────────────────────                ───────                          ─────────────
1. "Connect WhatsApp" wizard:
   phone (E.164), display name,
   business profile, consent
2. Meta Embedded Signup popup  ──────────────────────────────────────▶ Meta auth +
   (Facebook JS SDK, loaded             ◀── postMessage FINISH ─────── WABA creation
   only on this page; postMessage       { waba_id, phone_number_id }
   origins strictly allowlisted)
3. POST /v1/admin/tenants/{t}/whatsapp/embedded-signup
   { phoneE164, wabaId, metaPhoneNumberId, displayName, businessProfile }
                                      4. Idempotent orchestration
                                         (whatsapp_onboarding_attempts):
                                         a. ownership checks (phone, WABA)
                                         b. get-or-provision the WABA's
                                            DEDICATED SUBACCOUNT   ──────▶ POST /2010-04-01/Accounts.json
                                            (auth token encrypted           (parent account creds)
                                             at rest, AES-256-GCM)
                                         c. create/reuse sender draft
                                         d. register sender          ──────▶ POST messaging.twilio.com
                                            with configuration.waba_id       /v2/Channels/Senders
                                            (SUBACCOUNT creds)               (subaccount basic auth)
                                      5. Sender = PENDING_REVIEW;
                                         SenderSyncJob polls          ──────▶ GET /v2/Channels/Senders/{sid}
                                         CREATING → ONLINE → ACTIVE
6. Wizard/status panel shows the real
   asynchronous provisioning status
```

Key invariants:

- **One WABA = one tenant = one dedicated Twilio subaccount**
  (`whatsapp_messaging_accounts`, `UNIQUE(waba_id)`). A second number under
  the same WABA reuses the subaccount. A different brand/WABA gets its own
  subaccount. A WABA claimed by another tenant is a 409.
- **All Twilio billing/infrastructure stays under the parent account.**
  Tenants never see a SID, token, or the word "Twilio".
- **Credentials are server-side only**, encrypted with `JwtSecretCipher`
  (AES-256-GCM, master key `YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY`).
- **Creds resolution** (`TenantSubaccountTwilioCredsResolver`, `@Primary`):
  senders with a `messaging_account_id` speak with their subaccount's
  credentials (outbound *and* inbound signature verification); legacy senders
  fall back to the platform credential. Tenant mismatch fails closed.
- **Webhook signatures**: Twilio signs a subaccount sender's webhooks with the
  *subaccount* auth token; the webhook controller resolves and verifies with
  it (`X-Twilio-Signature`, official HMAC scheme, canonical URL from platform
  config — never from proxy headers).
- **Idempotency**: `whatsapp_onboarding_attempts` keys on
  (tenant, phone, WABA). Refreshes, double-clicks, and Twilio timeouts resume
  the same attempt; retries never create duplicate subaccounts or senders.
  Two-transaction split around every Twilio call (no row locks held).

## 2. Prerequisites (external, cannot be automated)

| Step | Where | Notes |
|---|---|---|
| Meta app with WhatsApp product + **Embedded Signup** configuration | developers.facebook.com | Yields `META_APP_ID` + `META_WHATSAPP_CONFIGURATION_ID`. App must pass Meta review (`whatsapp_business_management`, `whatsapp_business_messaging` advanced access). |
| **Twilio Tech Provider / self-sign-up program** enrollment | Twilio Console → WhatsApp | Links your Meta app to Twilio's Tech Provider integration; may yield a **Partner Solution ID** (`META_TWILIO_PARTNER_SOLUTION_ID`). |
| Parent Twilio account, API key, auth token | see `twilio-whatsapp-setup.md` §1 | Same values as the singleton path. Subaccount creation must be allowed on the parent. |
| HTTPS everywhere | your ingress | The admin app page hosting the popup **must** be HTTPS in production (Facebook SDK requirement), and webhook URLs are HTTPS. |
| JS SDK allowed domains | Meta app settings | Add the admin console's domain to the Meta app's allowed domains for the JS SDK login. |

## 3. Configuration

```bash
# Feature flag — boot fails fast if enabled with anything below missing.
WHATSAPP_ONBOARDING_ENABLED=true

META_APP_ID=...                      # public — appears in the browser
META_WHATSAPP_CONFIGURATION_ID=...   # public
META_TWILIO_PARTNER_SOLUTION_ID=...  # public; optional if the Meta config embeds it

# Parent Twilio account (same vars as the singleton path):
TWILIO_ACCOUNT_SID=AC...
TWILIO_API_KEY_SID=SK...
TWILIO_API_KEY_SECRET=...
TWILIO_AUTH_TOKEN=...                # parent webhook signing + subaccount-create fallback auth
TWILIO_WEBHOOK_BASE_URL=https://api.example.com   # or YAYA_PUBLIC_BASE_URL

# Credential encryption at rest (subaccount auth tokens):
YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY=$(openssl rand -base64 32)
```

Webhook URLs are platform-set at sender registration (unchanged):
`{base}/v1/channels/whatsapp/twilio/webhook/{senderId}` and
`.../status-callback/{senderId}`. The Senders API `status_callback_url` is
for **message-delivery** callbacks; sender-*registration* status is polled
via the Senders API by `SenderSyncJob` (5-minute sweep) and on demand via
`POST .../whatsapp-senders/{id}/sync`.

## 4. API surface (tenant-scoped, ADMIN role for writes)

```
GET  /v1/admin/tenants/{t}/whatsapp/embedded-signup/config        browser-safe Meta ids + enabled flag
POST /v1/admin/tenants/{t}/whatsapp/embedded-signup               start/resume onboarding (idempotent)
GET  /v1/admin/tenants/{t}/whatsapp/embedded-signup/attempts      attempt history
GET  /v1/admin/tenants/{t}/whatsapp/embedded-signup/attempts/{id}
GET  /v1/admin/tenants/{t}/whatsapp/messaging-accounts            WABA↔account rows (no SIDs/credentials)
POST /v1/admin/tenants/{t}/whatsapp/messaging-accounts/{id}/disconnect
```

Attempt states: `RECEIVED → ACCOUNT_PROVISIONED → SENDER_SUBMITTED →
COMPLETED | FAILED`. `COMPLETED` means the sender was handed to the async
registration machinery (`PENDING_REVIEW`); live status is on the sender.
Sender-visible Twilio statuses (`CREATING`, `PENDING_VERIFICATION`,
`VERIFYING`, `TWILIO_REVIEW`, `ONLINE`, `ONLINE:UPDATING`, `OFFLINE`, …) are
stored verbatim in `onboarding_state_json`; unknown future values are held
safely without a state transition. Outbound sending is gated on the internal
`ACTIVE` state (Twilio `ONLINE`).

## 5. Data model (V75)

- `whatsapp_messaging_accounts` — tenant, `waba_id` (UNIQUE),
  `twilio_subaccount_sid` (UNIQUE), `auth_token_ciphertext`/`auth_token_iv`,
  status `PROVISIONING|ACTIVE|SUSPENDED|DISCONNECTED`.
- `whatsapp_onboarding_attempts` — the idempotency ledger
  (UNIQUE `idempotency_key` = tenant|phone|waba), sanitized failure text.
- `whatsapp_senders` gains `waba_id`, `meta_phone_number_id`,
  `messaging_account_id`, and a partial UNIQUE on `twilio_sender_sid`.

## 6. Local development & testing without production credentials

- Leave `TWILIO_ACCOUNT_SID` unset → `DevNoopSubaccountProvisioner` mints
  fake `ACdev…` subaccounts, so the whole flow runs on a laptop. Keep
  `WHATSAPP_ONBOARDING_ENABLED=false` OR set the Meta ids + encryption key to
  pass the fail-fast validator (`EmbeddedSignupStartupValidator`).
- Automated tests stub Twilio with WireMock via the overridable
  `yaya.agentic.channels.twilio.api-base-url` / `messaging-base-url` —
  see `WhatsAppEmbeddedSignupE2ETest`. No production API is ever called.
- Flutter widget tests inject a fake `EmbeddedSignupLauncher` — the real
  Facebook SDK is only loaded in the browser on the wizard page. Run with
  `flutter test --platform chrome`.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Boot fails: "embedded signup is enabled but …" | Missing `META_*`, parent Twilio, or encryption-key config. Set them or disable the flag. |
| Wizard card absent in the admin UI | `GET .../embedded-signup/config` returns `enabled=false` — flag off or Meta ids missing. |
| Popup blocked / SDK never loads | HTTPS + Meta app "allowed domains" must include the console origin; ad-blockers commonly block `connect.facebook.net`. |
| 409 `waba_owned_by_other_tenant` | The WABA is already connected to a different workspace — each brand/WABA belongs to exactly one. |
| 409 `phone_in_use` | The number is registered to another workspace's sender. |
| 503 `subaccount_provisioning_unavailable` | Transient Twilio outage — the attempt stays resumable; retry from the wizard. |
| Attempt `FAILED` with sanitized message | Twilio/Meta rejected the sender (policy, display name). Fix the input and retry — the same attempt resumes; no duplicates are created. |
| Sender stuck in `PENDING_REVIEW` | Meta business review can take days. `POST .../sync` polls on demand; the poller sweeps every 5 min. If the card shows **Code needed** (Twilio `PENDING_VERIFICATION`), enter the OTP WhatsApp sent to the number — `POST .../whatsapp-senders/{id}/verify`. |
| Number needs to be connected again after removal | Removed (revoked) senders free their number; run Connect WhatsApp again — a fresh sender row is created. |
| Inbound 403 `signature_mismatch` on a connected sender | The signing token is the **subaccount** auth token; if the subaccount token was rotated in Twilio, the stored token no longer matches — reconnect the account. |

## 8. Disconnect / rollback

- **Disconnect one number:** revoke the sender (existing
  `POST .../whatsapp-senders/{id}/revoke`) — deletes the Twilio sender,
  keeps history.
- **Disconnect a business/WABA:** `POST .../messaging-accounts/{id}/disconnect`
  — local-first: credentials stop resolving immediately (inbound fails
  closed, outbound refuses), the row stays as an audit record, and **no
  external Twilio resources are deleted**. Sender-level revoke remains the
  explicit external-teardown surface.
- **Feature rollback:** set `WHATSAPP_ONBOARDING_ENABLED=false` and redeploy.
  Already-connected senders keep working — the resolver still serves their
  subaccount credentials; only *new* onboarding is disabled. Full rollback of
  connected senders = revoke each sender, then disconnect the account.

## 9. Existing customers / migration

Nothing changes for senders provisioned under the platform-singleton account:
their rows carry no `messaging_account_id`, the resolver falls back to the
platform credential, and webhooks keep verifying with the parent auth token.
Migrating a legacy sender to a dedicated subaccount is a **re-onboarding**
(WhatsApp numbers can be re-registered by the owning business through
Embedded Signup) — do not attempt to silently re-parent senders in the
database; Twilio senders cannot move between accounts. If legacy data looks
inconsistent with a requested mapping (e.g. the phone exists under another
tenant), the onboarding rejects with a 409 for administrative review rather
than guessing.
