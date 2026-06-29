# SMS channel (A2P 10DLC) — setup, config & manual test

> **Audience:** operators of yaya-agentic (the platform team). Like WhatsApp,
> v1 runs under a *single platform-owned Twilio account*; tenants describe a
> business + use case and the platform registers with the carriers on their
> behalf. Tenants never see Twilio or The Campaign Registry (TCR).
>
> **Companion:** `docs/design/sms-channel-design.md` (architecture),
> `docs/guides/twilio-whatsapp-setup.md` (the Twilio account/API-key setup —
> SMS reuses the same `yaya.agentic.channels.twilio.*` credentials).

---

## 0. What this gives you

A tenant can register an **SMS sender** (a US or DR phone number), have the
platform drive **A2P 10DLC** Brand + Campaign registration to approval, bind
the sender to a profile, and then have inbound SMS run through the normal
engine with the reply sent back — all from the admin console.

Two ways to run it:

| Mode | Twilio account? | What runs | Use for |
|---|---|---|---|
| **Dev / simulated** | No | Dev stubs (`DevNoop*`, `DevConsoleSmsTransport`) auto-approve registration and log outbound to the console; an unsigned **sim inbound** endpoint injects messages | Local end-to-end testing (§3) |
| **Production** | Yes | The `Twilio*` adapters supersede the stubs and hit real carriers; the real signed webhook receives inbound | Live traffic |

---

## 1. Configuration

All SMS config lives under `yaya.agentic.channels`. SMS **send** (both OTP and
conversational) and registration reuse the platform Twilio credentials from the
WhatsApp setup — there is no separate Twilio account for SMS.

### 1.1 Dev / simulated (no Twilio account)

The only hard requirement is an LLM key for the engine. Enable the sim inbound
endpoint and tighten the A2P poller so dev registrations approve in seconds
instead of the 5-minute production cadence.

```yaml
# application-local.yaml  (or env)
yaya:
  agentic:
    public-base-url: http://localhost:8080
    channels:
      sms:
        sim-inbound:
          enabled: true          # DEV ONLY — injects unsigned inbound (§3). Never true in prod.
        a2p-poller:
          initial-delay-ms: 2000
          fixed-delay-ms: 5000   # fast sweep so dev brand/campaign auto-approve quickly
```

```bash
export ANTHROPIC_API_KEY=sk-ant-...      # the engine needs it to generate replies
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

With no `yaya.agentic.channels.twilio.account-sid` set, the `Twilio*` beans stay
inactive and the dev stubs serve: `A2pRegistrar` auto-approves on poll,
`SmsNumberProvisioner` reports every number ready, and `SmsTransport` is
`DevConsoleSmsTransport` (logs outbound as `[SMS] from=… to=…`).

### 1.2 Production (real Twilio)

Setting `account-sid` flips the three SMS Twilio adapters on (they're
`@ConditionalOnProperty(account-sid)`); each supersedes its dev stub.

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
        a2p:
          # TrustHub bundle SIDs from the operator's one-time A2P vetting (§1.3).
          customer-profile-bundle-sid: ${TWILIO_A2P_CUSTOMER_PROFILE_BUNDLE_SID:}
          a2p-profile-bundle-sid:      ${TWILIO_A2P_PROFILE_BUNDLE_SID:}
      sms:
        sim-inbound:
          enabled: false        # MUST be false — the sim endpoint bypasses signature checks
        a2p-poller:
          fixed-delay-ms: 300000  # 5 min (default); carriers don't approve faster
```

In Twilio, point each SMS number's inbound webhook at
`https://<public-base-url>/v1/channels/sms/twilio/webhook/{senderId}` — the
`TwilioSmsNumberProvisioner` does this automatically on sender submit, so you
normally don't set it by hand.

### 1.3 The A2P TrustHub prerequisite (one-time, operator)

Real Brand registration requires two TrustHub bundles that come out of Twilio's
A2P vetting flow (partly manual in the Twilio Console): a **Customer Profile**
bundle and an **A2P Profile** bundle. That vetting is **not** automated here —
create the platform's Primary Business Profile + bundles once in the Console,
then supply their SIDs as `...a2p.customer-profile-bundle-sid` /
`...a2p.a2p-profile-bundle-sid`. Until they're set, `submitBrand` returns a
clear `trusthub_bundles_missing` error rather than a confusing Twilio 4xx.

### 1.4 Config reference

| Property | Default | Effect |
|---|---|---|
| `channels.twilio.account-sid` | _(unset)_ | Set → activates real `TwilioSmsTransport` / `TwilioSmsNumberProvisioner` / `TwilioA2pRegistrar`. Unset → dev stubs. |
| `channels.twilio.a2p.customer-profile-bundle-sid` | _(unset)_ | TrustHub Customer Profile bundle for Brand registration (§1.3). |
| `channels.twilio.a2p.a2p-profile-bundle-sid` | _(unset)_ | TrustHub A2P Profile bundle for Brand registration. |
| `channels.sms.sim-inbound.enabled` | `false` | Dev-only unsigned inbound injection (`/v1/channels/sms/sim/{senderId}`). |
| `channels.sms.a2p-poller.enabled` | `true` | Master switch for the Brand/Campaign approval sweep. |
| `channels.sms.a2p-poller.fixed-delay-ms` | `300000` | Sweep interval. Lower for dev. |
| `channels.sms.a2p-poller.initial-delay-ms` | `30000` | Delay before the first sweep after boot. |

---

## 2. How a message flows (the mental model)

```
Registration (one-time per tenant):
  Brand DRAFT ──submit──▶ SUBMITTING ──poller──▶ VERIFIED
  Campaign DRAFT ──submit──▶ SUBMITTING ──poller──▶ APPROVED   (needs Brand VERIFIED)
  Sender DRAFT ──submit──▶ PENDING_REVIEW ──sync──▶ ACTIVE      (needs Campaign APPROVED [US] / bundle [DR])

Conversation (per inbound):
  Twilio/sim ─▶ webhook (verify+dedup+persist) ─▶ SmsInboundWorker
        ─▶ binding resolve ─▶ session open/reuse ─▶ engine.start/send
        ─▶ SmsOutboundDispatcher (buffer tokens ─▶ one SMS) ─▶ SmsTransport
```

The compliance gate is real: a US sender will sit in `PENDING_REVIEW` until its
campaign is `APPROVED`, then the next `sync` promotes it to `ACTIVE`.

---

## 3. Manual end-to-end test (dev, no Twilio)

Proves brand → campaign → sender → binding → inbound → reply, all locally.

**Prerequisites**
- Backend running per §1.1 (`ANTHROPIC_API_KEY` set, `sim-inbound.enabled=true`,
  fast `a2p-poller`).
- A tenant that has at least one **profile** (e.g. the seeded `default` tenant /
  retail profile). The binding in step 7 needs a profile id to route to.
- An operator credential with `ADMIN` on the tenant's workspace. Easiest is an
  API key from **Settings → API keys** (`yk_live_…`). Export it:

```bash
export BASE=http://localhost:8080
export TENANT=default
export YK='yk_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
auth() { echo "-H"; echo "Authorization: Bearer $YK"; }   # helper
```

> All admin calls below send `Authorization: Bearer $YK` and
> `Content-Type: application/json`. A `YAYA_SESSION` cookie from `/v1/auth/login`
> works too.

**1) Create + submit the Brand**

```bash
curl -s -X POST "$BASE/v1/admin/tenants/$TENANT/sms/brand" \
  -H "Authorization: Bearer $YK" -H 'Content-Type: application/json' \
  -d '{"business":{"legalName":"Acme Co","taxId":"12-3456789","vertical":"RETAIL","contactEmail":"ops@acme.example"}}'

curl -s -X POST "$BASE/v1/admin/tenants/$TENANT/sms/brand/submit" \
  -H "Authorization: Bearer $YK"
```

Wait ~5–7s for the A2P poller, then confirm it reached `VERIFIED`:

```bash
curl -s "$BASE/v1/admin/tenants/$TENANT/sms/brand" -H "Authorization: Bearer $YK" | jq .status
# "VERIFIED"
```

**2) Create + submit a Campaign** (standard use case clears immediately in dev)

```bash
CAMP=$(curl -s -X POST "$BASE/v1/admin/tenants/$TENANT/sms/campaigns" \
  -H "Authorization: Bearer $YK" -H 'Content-Type: application/json' \
  -d '{"useCase":"ACCOUNT_NOTIFICATION","description":"Order updates",
       "sampleMessages":["Your order shipped","Out for delivery"],
       "optIn":{"description":"Checkout opt-in"},"brandId":"'"$(curl -s "$BASE/v1/admin/tenants/$TENANT/sms/brand" -H "Authorization: Bearer $YK" | jq -r .id)"'"}' \
  | jq -r .id)

curl -s -X POST "$BASE/v1/admin/tenants/$TENANT/sms/campaigns/$CAMP/submit" -H "Authorization: Bearer $YK"
# wait ~5s, then:
curl -s "$BASE/v1/admin/tenants/$TENANT/sms/campaigns" -H "Authorization: Bearer $YK" | jq '.[].status'
# "APPROVED"
```

**3) Create + submit + activate a Sender**

```bash
SENDER=$(curl -s -X POST "$BASE/v1/admin/tenants/$TENANT/sms/senders" \
  -H "Authorization: Bearer $YK" -H 'Content-Type: application/json' \
  -d '{"phoneE164":"+15551230000","market":"US","campaignId":"'"$CAMP"'"}' | jq -r .id)

curl -s -X POST "$BASE/v1/admin/tenants/$TENANT/sms/senders/$SENDER/submit" -H "Authorization: Bearer $YK"
# -> PENDING_REVIEW. Promote it now that the campaign is APPROVED:
curl -s -X POST "$BASE/v1/admin/tenants/$TENANT/sms/senders/$SENDER/sync" -H "Authorization: Bearer $YK" | jq .status
# "ACTIVE"
```

**4) Bind the sender to a profile**

Easiest in the admin UI: **Inbound channels → Bindings → New**, endpoint kind
`SMS_SENDER`, pick the sender, pick a profile, routing *Match any*. Or via API
(`POST /v1/admin/tenants/$TENANT/channel-bindings` with
`endpoint:{kind:"SMS_SENDER",id:"$SENDER"}`, a `profileId`, `routing:{"kind":"MatchAny"}`,
and a `sessionPolicy` — see `channel-bindings` design for the full body).

**5) Inject an inbound message and watch the reply**

```bash
curl -s -X POST "$BASE/v1/channels/sms/sim/$SENDER" \
  -H 'Content-Type: application/json' \
  -d '{"from":"+15557654321","body":"hi, what are your hours?"}'
# {"messageSid":"SMsim…","status":"accepted"}
```

In the **backend logs** you'll see the dev transport print the outbound reply:

```
[SMS] from=+15551230000 to=+15557654321
----- body -----
<the assistant's answer>
----- end -----
```

**6) Continue the conversation** — a second sim inbound reuses the same engine
session (continuity via `channel_sessions`):

```bash
curl -s -X POST "$BASE/v1/channels/sms/sim/$SENDER" \
  -H 'Content-Type: application/json' -d '{"from":"+15557654321","body":"and on weekends?"}'
```

**7) (Optional) Verify persistence**

```sql
SELECT status, segment_count, body FROM outbound_messages ORDER BY sent_at DESC LIMIT 3;
SELECT status, end_user_phone FROM channel_sessions WHERE sender_id = '<SENDER>';
```

**8) (Optional) Opt-out keyword** — `STOP` / `HELP` short-circuit the engine:

```bash
curl -s -X POST "$BASE/v1/channels/sms/sim/$SENDER" \
  -H 'Content-Type: application/json' -d '{"from":"+15557654321","body":"STOP"}'
# logs show the canned unsubscribe reply; the engine is not invoked
```

---

## 4. Status reference

| Aggregate | States | Advances via |
|---|---|---|
| **Brand** | `DRAFT → SUBMITTING → PENDING_VERIFICATION → VERIFIED` / `FAILED` | submit, then the A2P poller |
| **Campaign** | `DRAFT → SUBMITTING → PENDING_REVIEW → APPROVED` / `SUSPENDED` / `FAILED` | submit (Brand must be `VERIFIED`), then the A2P poller |
| **Sender** | `DRAFT → SUBMITTING → PENDING_REVIEW → ACTIVE` → `SUSPENDED`/`REVOKED` / `FAILED` | submit, then **`/sync`** (gated on campaign `APPROVED` / DR bundle) |

---

## 5. Known limitations & follow-ons

Honest list of what is **not** wired yet (see `sms-channel-design.md` §4.2/§13):

- **Sender auto-poller.** Brands/Campaigns auto-advance via the scheduled A2P
  poller; **senders advance via the `/sync` endpoint** (manual or operator-UI
  button). A scheduled sender sweep is a small follow-on.
- **Restricted use cases** (`GAMBLING`, `SWEEPSTAKES`, `HIGH_RISK_FINANCIAL`)
  submit-block with `operator_review_required`. The operator *release* action
  (`SmsComplianceService.releaseForSubmission`) exists but is **not yet exposed**
  on an operator endpoint — wire it before selling those use cases.
- **Owned-number porting** (`acquisition=HOSTED`) and **buying brand-new
  numbers** are rejected/again deferred (S6); v1 configures numbers already in
  the Twilio account.
- **Persistent opt-out** is basic (STOP/HELP canned replies); durable opt-out
  tracking reuses `OptOutService` as a follow-on.
- **Real Twilio adapters are unverified in CI** — they issue live calls. Verify
  against a Twilio test account before production.

---

## 6. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `sim` returns 403 `sim_disabled` | `channels.sms.sim-inbound.enabled` is false (it must be true in dev). |
| Brand stuck `SUBMITTING` | A2P poller disabled or interval too long; check `a2p-poller.*`. In prod, `trusthub_bundles_missing` → set the bundle SIDs (§1.3). |
| Campaign submit → 409 `brand_not_verified` | Brand hasn't reached `VERIFIED` yet — wait for the poller. |
| Campaign submit → 409 `operator_review_required` | Restricted use case (§5). |
| Sender submit → 409 `provider_not_ready` | No SMS provisioner configured (prod without `account-sid`). |
| Sender stays `PENDING_REVIEW` after `/sync` | Its campaign isn't `APPROVED` yet (US), or no regulatory bundle attached (DR). |
| Inbound accepted but no reply in logs | No active binding for the sender, or the bound profile produced an empty turn. |

---

## 7. References

- `docs/design/sms-channel-design.md` — architecture, state machines, data model.
- `docs/guides/twilio-whatsapp-setup.md` — Twilio account + API key setup (shared creds).
- `docs/design/whatsapp-channel-design.md` §7 — platform-singleton + BYO seam.
