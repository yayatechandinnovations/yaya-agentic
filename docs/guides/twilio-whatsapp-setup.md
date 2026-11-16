# Twilio WhatsApp — platform setup guide

> **Audience:** operators of yaya-agentic (the platform team), not tenant
> users. v1 ships a *single platform-owned Twilio account* — every tenant
> sender provisioned through the admin UI lives under this one account.
> Tenants never see, configure, or learn about Twilio.
>
> **Companion:** `docs/design/whatsapp-channel-design.md` §7 (platform-
> singleton decision) and §7.3 (BYO escape hatch). For the step-by-step
> "onboard a customer's number" flow, follow
> **`docs/guides/whatsapp-sender-registration.md`**.

---

## 0. What you need before you start

| Asset                       | Where you get it                         | When it's needed |
|---|---|---|
| A Twilio account            | https://www.twilio.com/try-twilio        | Up front |
| A Meta Business Manager     | https://business.facebook.com            | Before any sender can be *approved* |
| A phone number you control  | A SIM, a virtual landline, anything not currently active on WhatsApp | When you (or a tenant) registers their first sender |
| Yaya-agentic deployed       | Backend reachable on a public HTTPS URL  | Required — Twilio dials webhooks back to it |

You'll need a credit card on Twilio before WhatsApp messaging can leave
sandbox mode. The platform-singleton account is billed monthly; tenant
usage is mirrored in your billing.

---

## 1. Create the Twilio account + API key

### 1.1 Sign up

1. Go to **twilio.com/try-twilio** and create an account.
2. Verify the email; complete the phone-number challenge.
3. On the console landing page, note the **Account SID** (starts with
   `AC…`, 34 chars). You'll bind it to `yaya.agentic.channels.twilio.account-sid`.

### 1.2 Capture the **Auth Token**

The Auth Token is what Twilio uses to sign inbound webhooks. The platform
verifies every `X-Twilio-Signature` against this token — without it, every
inbound is rejected with `auth_token_unset` and the webhook controller
returns 403 (which is the design's "fail closed" stance).

1. Console → **Account → API keys & tokens**.
2. Click **Show** next to the primary Auth Token.
3. Copy it. Bind it to `yaya.agentic.channels.twilio.auth-token`.

> ⚠️ **The Auth Token can ONLY be retrieved once from the console.** If
> you lose it, you regenerate, which **rotates** the secret — every
> existing webhook signature math breaks the moment you rotate. Schedule
> this for a maintenance window.

### 1.3 Create an **API Key + Secret** (NOT the Auth Token)

The Auth Token is for inbound *verification*. Outbound API calls
(register sender, sync sender, send message) authenticate with a Twilio
**API Key SID + Secret** pair instead — this is the recommended practice
because it can be scoped to specific resources and rotated without
breaking webhook signing.

1. Console → **Account → API keys & tokens → Create API key**.
2. **Friendly name:** `yaya-agentic-platform` (something traceable).
3. **Key type:** *Standard* (Main is also fine for platform-tier).
4. **Region:** match your Messaging region — default `us1` for most.
5. Click **Create API Key**.
6. Twilio shows the key once. Capture:
   - **SID** → `yaya.agentic.channels.twilio.api-key-sid`
   - **Secret** → `yaya.agentic.channels.twilio.api-key-secret`

> ⚠️ **The API Key Secret can ONLY be retrieved at creation.** Store it in
> your secrets manager *immediately*. If lost, delete the key and make a
> new one.

### 1.4 Messaging Service — not needed for WhatsApp

A Messaging Service pools senders for throughput and sticky-sender routing.
**WhatsApp doesn't use one:** every reply goes out `From` the sender's own
number, because one platform-wide pool holds every tenant's numbers and Twilio
would be free to answer a customer of tenant A from tenant B's number. The
platform never adds senders to a service either. Leave
`yaya.agentic.channels.twilio.messaging-service-sid` unset unless another
channel needs it; WhatsApp only falls back to it for a send that carries no
sender phone.

---

## 2. Configure yaya-agentic

### 2.1 The four required values

| Property                                            | Where it goes      | Where you got it |
|---|---|---|
| `yaya.agentic.channels.twilio.account-sid`          | yaml + env         | §1.1 |
| `yaya.agentic.channels.twilio.api-key-sid`          | yaml + env         | §1.3 |
| `yaya.agentic.channels.twilio.api-key-secret`       | **vault** (not yaml) | §1.3 |
| `yaya.agentic.channels.twilio.auth-token`           | **vault** (not yaml) | §1.2 |

### 2.2 Optional (but recommended in prod)

| Property                                                | Default                              | Recommended in prod |
|---|---|---|
| `yaya.agentic.channels.twilio.messaging-service-sid`    | (none — uses `From` directly)        | Leave unset for WhatsApp — replies always use the sender's own number (§1.4). |
| `yaya.agentic.channels.twilio.webhook-base-url`         | `${yaya.agentic.public-base-url}`    | Override only if you terminate Twilio webhooks on a different ingress than the rest of the platform. |

### 2.3 application.yaml

```yaml
yaya:
  agentic:
    public-base-url: https://api.yourplatform.example
    channels:
      twilio:
        account-sid:           ${TWILIO_ACCOUNT_SID}
        api-key-sid:           ${TWILIO_API_KEY_SID}
        api-key-secret:        ${TWILIO_API_KEY_SECRET}
        auth-token:            ${TWILIO_AUTH_TOKEN}
        messaging-service-sid: ${TWILIO_MESSAGING_SERVICE_SID:}
        # webhook-base-url defaults to ${yaya.agentic.public-base-url} —
        # uncomment only if your Twilio ingress is a separate hostname.
        # webhook-base-url:    https://hooks.yourplatform.example
```

The `${VAR:default}` syntax with an empty default leaves the property
unset when the env var is missing — which means `TwilioProperties.isConfigured()`
returns `false`, the WhatsApp channel disables itself gracefully, and the
admin UI surfaces a "Twilio not configured" banner instead of failing
sender submits with a confusing 5xx.

### 2.4 Inject the secrets at deploy time

The `api-key-secret` and `auth-token` are Twilio's account-tier secrets —
treat them like database passwords. Recommended patterns:

- **Kubernetes:** mount from a `Secret` resource as env vars on the pod
  template.
- **AWS:** Secrets Manager → ECS task definition references.
- **Docker compose (dev/local):** `.env` file kept out of git via
  `.gitignore`.

The platform never writes these secrets to disk and never logs them
(the startup probe masks the account SID before logging — see §3).

### 2.5 Verify with the startup probe

After deploying with the new config:

1. Restart the backend.
2. Look for one of these log lines on boot:

```text
INFO ... TwilioStartupProbe : Twilio readiness probe OK (account ACxx…xxxx reachable).
WARN ... TwilioStartupProbe : [SECURITY-ADJACENT] Twilio readiness probe failed for account ACxx…xxxx: <error>
INFO ... TwilioStartupProbe : Twilio not configured — WhatsApp channel is disabled.
```

The **OK** line means: the account SID + API key pair authenticated
against `https://api.twilio.com/2010-04-01/Accounts/{sid}.json`. You're
ready to register senders.

The **probe failed** line means: the creds are present but Twilio
rejected them. Most common causes:
- Wrong region (the API key was minted in `us1`, you're hitting it from
  the default endpoint, which is fine — region only matters for in-region
  Messaging Service calls).
- Typo'd Account SID vs. API Key SID (they're both prefixed, but different
  resources — `AC…` vs. `SK…`).
- Expired or deleted API key. Re-mint and redeploy.

The **not configured** line means: at least one of `account-sid`,
`api-key-sid`, `api-key-secret` was unset. The WhatsApp channel disables
itself; sender registration in the admin UI is blocked at the
form-validation layer.

---

## 3. Tell Meta who you are

> Sending tenants need a Meta Business Manager linked to their actual
> business. v1 uses a single Tech Provider account on Meta — yours — that
> tenants' Meta businesses are nested under. From Twilio's side this is
> the **WhatsApp Business Account (WABA)** linkage.

### 3.1 Register the first sender with Self Sign-up

Twilio requires the **first** WhatsApp sender of a direct account to be
registered through **Self Sign-up** in its Console — that is what links your
Meta Business Manager + WABA to the account. Senders API registrations (what
the platform does) only work after it.

1. Console → **Messaging → Senders → WhatsApp senders → Create new sender**.
2. Twilio walks you through the **Meta Business connection** wizard:
   - Sign in with the Facebook account that controls your Meta Business
     Manager.
   - Create or select the WABA and register a number.
3. Capture your **WhatsApp Business Account ID** for support tickets —
   yaya-agentic stores Twilio's sender SID (`XE…`), not the WABA id.

You don't have to burn a number on this step: register the **same number**
through the admin console's sender wizard afterwards. On submit the platform
looks the number up on the account, finds the Self Sign-up sender and
**adopts** it — re-points its webhooks at the platform (§4) without touching
its approved profile — instead of trying to create it twice. The same lookup
makes a submit whose response was lost safe to retry.

> ⏱️ **First-time Meta business verification is slow.** Per Meta's docs,
> first-business approval is 1–3 business days; subsequent senders under
> the same approved Tech Provider take minutes to hours.

### 3.2 Verify Twilio can talk to Meta

Once §3.1 is done, the Senders dashboard in Twilio shows a green
**Connected to Meta** indicator. If it's red, no platform-tier sender
registration will progress past `SUBMITTING`.

---

## 4. Webhook URLs (the platform sets these — you don't)

When a tenant operator clicks **Submit** on a sender in the admin UI,
the platform calls Twilio's Senders API with:

```text
WebhookUrl      = ${webhook-base-url}/v1/channels/whatsapp/twilio/webhook/{senderId}
StatusCallback  = ${webhook-base-url}/v1/channels/whatsapp/twilio/status-callback/{senderId}
```

An adopted sender (§3.1) gets the same two URLs written onto it at submit.

You **must not** override these in the Twilio Console after submission —
Twilio replays `MessageSid` retries against whatever URL is configured
at the time of retry, so changing the URL post-hoc breaks the dedup
guarantee.

### 4.1 What Twilio actually hits

When a customer messages a registered sender:

```text
POST https://api.yourplatform.example/v1/channels/whatsapp/twilio/webhook/snd_acme_main
Content-Type: application/x-www-form-urlencoded
X-Twilio-Signature: <base64 HMAC-SHA1 of the URL + sorted form params>

MessageSid=SMabcdef&From=whatsapp%3A%2B15551234567&To=whatsapp%3A%2B15558881111&Body=Hello
```

The platform's verifier:
1. Looks up the sender by path segment (`snd_acme_main`).
2. Reconstructs the canonical URL from `yaya.agentic.channels.twilio.webhook-base-url`
   plus the request path (NOT from the request's `Host` header — that
   would be a SSRF vector).
3. Computes the HMAC over `URL + sortedFormParams` with the Auth Token.
4. Constant-time compares to the presented `X-Twilio-Signature`.
5. Rejects with 403 + audit row on mismatch; never invokes the engine.

### 4.2 Status callbacks

Same path as the webhook with `/status-callback/` instead of `/webhook/`.
Twilio fires these for every delivery transition (`queued` → `sent` →
`delivered` → `read`, or `failed` / `undelivered`). The platform updates
the matching `outbound_messages` row's status and timestamp.

---

## 5. Operations

### 5.1 Rotating the Auth Token

The Auth Token signs every inbound webhook. Rotating it requires a
short maintenance window because there's a brief gap where Twilio is
signing with the new token but yaya-agentic still has the old one.

1. Console → **Account → API keys & tokens → Auth tokens → Promote**
   (this stages a new primary; the old one becomes secondary for a few
   minutes).
2. Deploy yaya-agentic with the new token in `auth-token`.
3. Console → revoke the secondary (old) token.

During the gap, any inbound that arrived signed with the new token gets
rejected — Twilio retries with exponential backoff, so messages aren't
lost. Plan the rotation outside peak hours.

### 5.2 Rotating the API Key

The API Key is used only for outbound API calls (register, sync, send).
Rotation is safer than Auth Token:

1. Console → Create a NEW API key with the same friendly name (or a
   versioned one — `yaya-agentic-platform-v2`).
2. Deploy yaya-agentic with the new SID + Secret.
3. Console → Delete the old API key.

There's no gap because outbound calls go out fresh on each request; the
old key is only "live" for any in-flight requests at the moment of
rotation.

### 5.3 Monitoring

The platform exports these Micrometer metrics (Prometheus-scrapable
via `/actuator/prometheus`):

| Metric                                                  | What to alert on |
|---|---|
| `channel_inbound_signature_failures_total`              | Any sustained non-zero rate → either a misconfigured Auth Token or someone hammering the endpoint with forged requests. |
| `twilio_api_call_latency_seconds_p99`                   | > 5s sustained → Twilio side issue. |
| `channel_outbound_total{result="provider_failure"}`     | Spike → check `result`-labeled error code; transient HTTP 5xx is normal, `twilio_21408` (display-name policy) means a tenant needs operator attention. |
| `sender_status{status="FAILED"}`                        | Increasing → tenants are seeing rejected submissions; surface in operator dashboards. |

### 5.4 Suspending the entire WhatsApp channel

If Meta or Twilio flags the platform's account and you need to stop
processing inbound:

1. **Quickest:** unset `auth-token` and redeploy. All inbound 403s with
   `auth_token_unset`. No state is lost; outbound is unaffected.
2. **Surgical:** disable the inbound webhook controller bean via a
   profile-gated `@ConditionalOnProperty`. (Not in v1; file a follow-up
   if you actually need this knob.)

---

## 6. Per-tenant subaccounts (the §7.3 seam — now shipped)

The §7.3 escape hatch has landed as the **Meta Embedded Signup +
Tech Provider** path: each customer business/WABA gets a dedicated Twilio
subaccount under this parent account, provisioned automatically when a
tenant connects their own WhatsApp number through the admin console's
"Connect WhatsApp" wizard. The resolver swap the design reserved is
`TenantSubaccountTwilioCredsResolver` (`@Primary`): subaccount-owned
senders use their subaccount's credentials, legacy senders keep resolving
the platform singleton configured above.

Setup, configuration (`WHATSAPP_ONBOARDING_ENABLED`, `META_APP_ID`, …),
data model, and troubleshooting live in
**`docs/guides/whatsapp-embedded-signup-setup.md`**. Tenants still never
see, hold, or configure Twilio credentials — a true BYO-Twilio-account
model (tenant-owned account + billing) remains unimplemented.

---

## 7. Troubleshooting cheat-sheet

| Symptom                                              | Most likely cause | Fix |
|---|---|---|
| `Twilio not configured — WhatsApp channel is disabled` on boot | At least one of `account-sid`, `api-key-sid`, `api-key-secret` is unset | Set all three env vars + redeploy |
| `Twilio readiness probe returned HTTP 401` on boot   | Wrong API key SID / Secret pairing | Re-create the API key in §1.3 and redeploy |
| Submit shows `provider_not_ready` in the admin UI    | Same as above — the resolver couldn't return creds | Same as above |
| Sender stuck in `SUBMITTING` for > 5 min             | The registration call keeps failing transiently (the poller retries it every sweep; **Refresh status** retries now) | Check logs for `Twilio registerSender transport failure` / `registerSender threw` |
| Card shows **Code needed** (Twilio status `PENDING_VERIFICATION`) | The number isn't a Twilio SMS number, so WhatsApp sent an OTP to it | Operator opens the card → **Enter code**. Expired/never arrived → **Cancel registration** and register again (Voice call for landlines) |
| "phone number … is already registered to another sender" | The number is live (not removed) in some workspace | Removed senders free their number; remove the old one first |
| Inbound webhook 403s with `signature_mismatch`       | Auth Token rotated without redeploy, OR `webhook-base-url` doesn't match what Twilio actually called | Verify `auth-token` matches Console; verify `webhook-base-url` matches the URL Twilio configured for the sender |
| Inbound webhook 403s with `auth_token_unset`         | `yaya.agentic.channels.twilio.auth-token` is unset | Set the env var + redeploy |
| Sender flips to `FAILED` immediately on submit       | Meta rejected the business profile or display name | The rejection reason is on the sender row — operator clicks **Fix & resubmit** |
| Live sender goes `SUSPENDED` with review notes       | Twilio reports it `OFFLINE`; its `offline_reasons` become the card's review notes | Act on the notes; contact Twilio support with the WABA id if unclear |
| New sender shows "WhatsApp reported a problem"       | Twilio reports `OFFLINE` mid-registration (seen: 410 before the code was entered); it stays in review | Enter the WhatsApp code if one arrived; otherwise **Cancel registration** and register again for a new code |

---

## 8. References

- `docs/design/whatsapp-channel-design.md` — full architecture
- `src/main/java/.../channel/whatsapp/twilio/TwilioProperties.java` — the
  yaml binding
- `src/main/java/.../channel/whatsapp/twilio/TwilioStartupProbe.java` —
  the readiness probe
- `src/main/java/.../channel/whatsapp/twilio/TwilioMessagingChannelProvider.java`
  — the outbound BSP shim
- `src/main/java/.../channel/whatsapp/twilio/TwilioSignatureVerifier.java`
  — the inbound HMAC verifier (constant-time compare; tests in
  `TwilioSignatureVerifierTest`)
- Twilio docs — https://www.twilio.com/docs/whatsapp
- Meta WABA verification — https://developers.facebook.com/docs/whatsapp/business-management-api/get-started
