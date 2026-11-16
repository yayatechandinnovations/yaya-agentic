# Registering a customer's WhatsApp number — end-to-end runbook

> **Audience:** platform operators onboarding customers (tenants) onto
> WhatsApp. This is the "do it in order" runbook; the reference material
> lives in `twilio-whatsapp-setup.md` (parent Twilio account) and
> `whatsapp-embedded-signup-setup.md` (self-service Connect WhatsApp).

---

## 0. Pick the path for each customer

| | **Start sender registration** (platform account) | **Connect WhatsApp** (Embedded Signup) |
|---|---|---|
| Whose Meta business owns the number | Yours — one WABA for every number | The customer's own |
| Twilio | The platform's account | A dedicated subaccount per customer, created automatically |
| Who proves the number | The platform: automatic for Twilio SMS numbers, otherwise an OTP the operator types into the sender card | The customer, inside Meta's popup |
| Extra setup | None beyond §1–§3 | Meta app + App Review + Twilio Tech Provider (§5) |

A Twilio account holds exactly one WABA, and Meta reviews every display name
against the business that owns it. A customer brand registered under *your*
WABA is likely to be rejected — **customers with their own brand go through
Connect WhatsApp**. The platform path fits your own brand, or numbers you
operate for customers under your name.

---

## 1. Twilio account (one time)

1. Create the account and add a card — registered senders need a paid
   account. Copy the **Account SID** (`AC…`).
2. Console → Account → API keys & tokens: copy the **Auth Token**; **Create
   API key** (Standard) and copy its **SID** (`SK…`) and **Secret** at once —
   the secret is shown only once.
3. For the platform path, buy an **SMS-capable Twilio number** for your first
   numbers: Twilio verifies its own SMS numbers automatically. Any other number
   works too — the platform asks for its verification code (§4 step 4).
4. A Messaging Service is **not** needed. WhatsApp replies always go out from
   the sender's own number.

## 2. Configure the platform (one time)

On the server, edit `/opt/yayaagentic/.env` (never overwritten by `deploy.sh`):

```bash
TWILIO_ACCOUNT_SID=AC...
TWILIO_API_KEY_SID=SK...
TWILIO_API_KEY_SECRET=...
TWILIO_AUTH_TOKEN=...
TWILIO_WEBHOOK_BASE_URL=https://yayaagentic.smartlottery.do
```

```bash
./deploy/deploy.sh --restart-only
./deploy/deploy.sh --logs   # want: "Twilio readiness probe OK (account ACxx…xxxx reachable)"
```

The **WhatsApp senders** page must no longer show "WhatsApp message
provisioning is currently unavailable".

## 3. Link your Meta business to Twilio (one time, platform path)

Twilio requires the **first** sender of a direct account to be registered with
**Self Sign-up** in its Console (Messaging → Senders → WhatsApp senders →
Create). That links your Meta Business Manager + WABA to the account; every
later registration goes through the platform.

The number you register there is not lost: register the **same number**
through the platform wizard afterwards and the platform **adopts** it — it
finds the existing Twilio sender, points its webhooks at the platform and
keeps its approved profile. First-time Meta business verification takes 1–3
business days.

## 4. Register a number (platform path, per number)

1. **Workspace + assistant** — Admin → Tenants: create/select the customer's
   workspace and make sure the assistant (profile) that should answer exists.
2. **WhatsApp senders → Start sender registration.** Four steps:
   - **Business profile** — legal name, display name, contact email, vertical.
   - **Phone number** — E.164 (`+18095551234`), not active on any WhatsApp
     app. Pick how WhatsApp sends the verification code: **SMS**, or **Voice
     call** for landlines.
   - **Display name** — Meta's rules apply (a real business name; no "Bot",
     "Test" or generic terms). Pick the **assistant that answers this number**:
     the wizard routes every message to it, so the number answers the moment
     it goes live.
   - **Review → Save & submit for review.** ("Save as draft" keeps it for
     later; the draft's card has **Submit for review**.)
3. **Wait.** The card shows plain-language progress; the platform polls every
   5 minutes, or click **Refresh status**.
4. **Verification code.** If WhatsApp texts or calls the phone with a code,
   the card shows **Code needed** — open it, click **Enter code**, type the
   code. Codes expire after a few minutes. (Twilio SMS numbers skip this.)
5. **Active.** The sender flips to **Active** when WhatsApp approves it.
   Message the number from a phone and check the assistant answers.

**When it goes wrong**

| What you see | Do this |
|---|---|
| **Review failed** + "Why it was rejected" | **Fix & resubmit** — edit what the reason points at, then submit again. |
| "website must be a valid URL" | Senders saved before the website fix kept a bare domain. Open **Fix & resubmit** and submit again; the website now gets `https://` automatically. |
| Code arrived but the card has no code box | Open the card: every registration in progress has **Enter code**, whatever WhatsApp reports. |
| "WhatsApp reported a problem" while registering | Enter the code if one arrived. Otherwise **Cancel registration** and register the number again for a new code. |
| Code expired / never arrived | **Cancel registration**, then register the number again (choose **Voice call** if it is a landline). |
| Stuck on **Submitting** | Nothing reached WhatsApp yet; the platform retries every 5 minutes, or click **Refresh status** to retry now. |
| "Phone number already registered" | The number is live in another workspace. Removed numbers are free again. |

## 5. Connect WhatsApp (customers with their own brand)

**One-time setup**

1. developers.facebook.com: a Meta app with the WhatsApp product and an
   **Embedded Signup configuration** (`META_APP_ID`,
   `META_WHATSAPP_CONFIGURATION_ID`); App Review for advanced access to
   `whatsapp_business_management` + `whatsapp_business_messaging`; add
   `yayaagentic.smartlottery.do` to the JS SDK allowed domains.
2. Twilio Console: enroll in the **WhatsApp Tech Provider** program, link the
   Meta app (Partner Solution ID if issued); subaccount creation allowed.
3. Server `.env` (boot fails fast when any is missing):

   ```bash
   WHATSAPP_ONBOARDING_ENABLED=true
   META_APP_ID=...
   META_WHATSAPP_CONFIGURATION_ID=...
   META_TWILIO_PARTNER_SOLUTION_ID=...
   YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY=...
   ```

   ⚠️ If `YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY` is **already set** it also
   protects tenant JWT secrets — keep it. Generate one
   (`openssl rand -base64 32`) only when it is missing.
4. Restart; the **Connect WhatsApp (self-service)** card appears.

**Per customer:** workspace + assistant as in §4 step 1 → **Connect WhatsApp**
→ business & phone details → **Connect with Meta**: the customer signs in with
*their* Facebook account, picks/creates their business + WABA and verifies the
number inside the popup (they must be at the keyboard — give them ADMIN on
their workspace or do it together on a call). The platform provisions their
subaccount and registers the sender; then add a route on the sender card
(**Routes → Add route → Match every message**) and test as in §4 step 5. If the
card ever shows **Code needed**, enter the code exactly as in §4 step 4.

---

## What the platform does behind each step

| Step | Backend |
|---|---|
| Save & submit | `POST …/whatsapp-senders` (DRAFT) → `POST /channel-bindings` (MatchAny route) → `POST …/submit {verificationMethod}` |
| Submit | Looks up the number on the Twilio account first: an existing sender is **adopted** (webhooks re-pointed, profile untouched); otherwise `POST /v2/Channels/Senders` |
| Enter code | `POST …/whatsapp-senders/{id}/verify {code}` → Twilio `POST /v2/Channels/Senders/{sid}` with `configuration.verification_code` |
| Refresh status | Syncs from Twilio; for a submission that never reached Twilio, retries it |
| Cancel registration / Remove | `POST …/revoke` — allowed from any live state; frees the number for re-registration |
