# Merchant PSP onboarding — connect PayPal / Stripe from inside the app

**Status:** proposed · **Owner:** commerce · **Depends on:** `ecommerce-chat-design.md` §11 (payments), `commerce-redesign-authoring-rendering-paypal.md` Pillar C (PayPal provider)

## 1. Problem

A store binds to a PSP today via the profile's **payment provider** dropdown (`CommerceConfig.paymentProvider`) plus a **merchant account id** text field (`CommerceConfig.connectedAccountId`). The providers already *consume* that id — Stripe as the `Stripe-Account` header, PayPal as `purchase_units[].payee.merchant_id`, dLocal as `merchant_id`. But **nothing produces it**: the merchant must already have a PayPal/Stripe account, find their connected-account id in a developer dashboard, and paste it in. Store owners generally don't know what any of that means.

We want the merchant to click **“Connect PayPal”** in the admin/merchant UI, sign in (or sign up) on PayPal's own pages with nothing more than their email + business details, and come back **connected** — no API keys, no dashboards, no ids to copy. This is the standard *platform onboarding* both PayPal and Stripe provide; we just have to drive it.

### Goals
- One-click, in-app onboarding for **PayPal first**, **Stripe** next, under a single SPI + UI.
- The merchant never sees an API key or a connected-account id.
- The connected account is captured, verified (webhook), and used automatically at checkout.
- **Sandbox-first**, like every existing PSP: the whole flow is demoable with no live credentials.
- Additive — no change to the sealed `CheckoutOutcome`; no change to the flow-of-funds posture (yaya-agentic is never a processor).

### Non-goals
- dLocal self-serve onboarding (platform/sub-account model — no merchant signup API; stays API/manual-provisioned; see §10).
- Payouts/settlement UI, tax/KYC beyond what the PSP's hosted flow handles (that's the PSP's liability, by design — §11).

## 2. The onboarding model (how “no keys” works)

The **platform** (yaya-agentic operator) enrolls **once** in Stripe **Connect** / PayPal **Commerce Platform** and holds *platform-level* credentials (the existing `yaya.agentic.commerce.{paypal,stripe}.*`). After that, every merchant onboards through a **hosted flow the platform initiates**:

```
Merchant (operator)         yaya-agentic backend            PSP (PayPal/Stripe)
      │  click "Connect"          │                                │
      ├──────────────────────────▶│  create onboarding link        │
      │                           ├───────────────────────────────▶│
      │                           │◀───────────────  action_url ───┤
      │◀──── open action_url ─────┤                                │
      │  sign in / sign up + grant permissions on the PSP's pages  │
      ├───────────────────────────────────────────────────────────▶│
      │◀──── redirect to returnUrl (merchantId) ──────────────────┤
      │  land back in app         │                                │
      │                           │◀──── webhook: onboarded/active ┤
      │                           │  persist account_id + status   │
```

The merchant only ever interacts with the PSP's own login/signup. We receive the **connected-account id** on the return redirect and confirm **charges-enabled** via webhook.

### Where the connected account lives
Onboarding is **per workspace/tenant**, not per profile: an operator connects their PayPal once and all their stores reuse it. (Recall a workspace's `tenant_id` is its slug.) So the connected account is a **tenant-level record**; a profile's `connectedAccountId` becomes an *optional override* and is otherwise resolved from that record at checkout.

## 3. New SPI — `MerchantOnboarding`

A sibling to `PaymentProvider`, same package (`commerce.payment`). Kept separate so a PSP can support charging without self-serve onboarding (dLocal) and vice-versa.

```java
public interface MerchantOnboarding {
    /** Matches PaymentProvider.name() — "paypal" | "stripe". */
    String provider();

    /** Begin onboarding for a tenant. Returns a hosted URL to open + our tracking ref. */
    OnboardingSession start(OnboardingStart start);

    /** Reconcile status from a return redirect or webhook payload → normalized status. */
    MerchantAccountStatus reconcile(OnboardingCallback callback);
}

record OnboardingStart(Ids.TenantId tenant, String returnUrl, String refreshUrl) {}
record OnboardingSession(String actionUrl, String trackingRef) {}   // actionUrl opened by the UI
enum MerchantAccountStatus { PENDING, ONBOARDING, ACTIVE, RESTRICTED, DISABLED }
```

- `PayPalMerchantOnboarding` and `StripeMerchantOnboarding` implement it, reusing the OAuth/RestClient plumbing already in `PayPalPaymentProvider`.
- **Sandbox-first:** with no live credentials, `start()` fabricates a deterministic `actionUrl` (mirrors `PayPalPaymentProvider`'s sandbox approval URL) and `reconcile()` returns `ACTIVE` immediately, so the Connect button works end-to-end in a demo.
- An `Optional<MerchantOnboarding>` lookup by provider name (like the router) lets the API 404/hide Connect for providers without onboarding (dLocal).

## 4. PayPal flow (Partner Referrals) — first implementation

PayPal's **Partner Referrals API** is the “Connected Path”:

1. **Start.** `POST {apiBase}/v2/customer/partner-referrals` with `operations: [API_INTEGRATION]`, `products: [EXPRESS_CHECKOUT]` (or `PPCP`), `legal_consents`, and `partner_config_override.return_url = returnUrl`. Response `links[] rel=action_url` → the “Connect with PayPal” URL. We stamp a `tracking_id` = our tracking ref.
2. **Merchant completes** signup/login + grants third-party permissions on PayPal.
3. **Return.** PayPal redirects to `returnUrl?merchantIdInPayPal=…&permissionsGranted=true&consentStatus=true`. We persist `merchantIdInPayPal` as the connected account id, status `ONBOARDING`.
4. **Confirm.** Webhook `MERCHANT.ONBOARDING.COMPLETED` / `MERCHANT.PARTNER-CONSENT.REVOKED`, or a `GET /v1/customer/partners/{partnerId}/merchant-integrations/{merchantId}` status poll → set `ACTIVE` when `payments_receivable && primary_email_confirmed`, else `RESTRICTED`. Reuses the existing `/v1/commerce/webhooks/paypal` controller (add the merchant event types alongside the capture events).
5. **Checkout** already sends `payee.merchant_id` = the stored id (`PayPalPaymentProvider.orderBody`), so no change to the charge path — it just gets its id from the onboarding record instead of a pasted field.

Platform prerequisite: the operator's PayPal account is a **Partner** (Commerce Platform) and `yaya.agentic.commerce.paypal.client-id/secret` are the partner credentials; `partner-attribution-id` already exists in `PayPalProperties`.

## 5. Persistence — `merchant_psp_accounts` (migration **V72**)

```sql
CREATE TABLE merchant_psp_accounts (
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider        VARCHAR(16)  NOT NULL,           -- 'paypal' | 'stripe'
    account_id      VARCHAR(255),                    -- merchantIdInPayPal / acct_…  (null until returned)
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    charges_enabled BOOLEAN      NOT NULL DEFAULT false,
    tracking_ref    VARCHAR(128),                    -- our id passed to the PSP for correlation
    detail_json     JSONB        NOT NULL DEFAULT '{}',  -- last raw status snapshot (audit/debug)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, provider)
);
```

`MerchantPspAccountEntity` sets `updated_at` via `@PrePersist/@PreUpdate` (same lesson as `CommerceCartEntity` — Hibernate sends the column, so the DB default never fires). Onboarding status is **per (tenant, provider)**, so an operator can connect both PayPal and Stripe.

## 6. Checkout resolution change

`CheckoutToolHandler` today reads `cfg.connectedAccountId()` off the profile. New order of precedence:

1. `CommerceConfig.connectedAccountId` if explicitly set (power-user override) — unchanged.
2. else the **ACTIVE** `merchant_psp_accounts` row for `(tenant, resolvedProvider)`.
3. else `null` → **platform charge** (current behavior; the demo store).

If a store is bound to a provider whose account is `PENDING/RESTRICTED`, the router still resolves the provider but checkout falls back to platform charge (safe default) and the admin surfaces a “Payments not finished — Connect to receive funds” banner (§7). Marketplaces that must *refuse* platform charge can flip a tenant policy flag (open question §12-Q2).

## 7. API + UI

### Endpoints (admin, workspace-scoped)
- `POST /v1/admin/tenants/{id}/commerce/psp/{provider}/onboarding-link` → `{ actionUrl, trackingRef }` (calls `MerchantOnboarding.start`). 404 if the provider has no onboarding.
- `GET  /v1/admin/tenants/{id}/commerce/psp/{provider}` → `{ status, chargesEnabled, accountId? }` for the status badge / polling.
- `GET  /v1/admin/tenants/{id}/commerce/psp/{provider}/return` (or a small SPA route) → captures the PSP return redirect, calls `reconcile`, persists, then redirects back into the console.
- Webhooks: extend `PayPalWebhookController` (`/v1/commerce/webhooks/paypal`) with the `MERCHANT.*` event types; Stripe adds `/v1/commerce/webhooks/stripe` for `account.updated`.

### UI (Flutter admin)
Replace the raw **“merchant account id”** text field in the profile Commerce section (`profiles_screen.dart`) — or better, a dedicated **Payments** settings page per workspace — with:
- a **Connect PayPal** / **Connect Stripe** button per provider,
- on click: call `onboarding-link`, open `actionUrl` in a popup/new tab, then **poll** the status endpoint,
- a **status badge**: *Not connected* → *Onboarding…* → **Connected ✓ (charges enabled)** / *Action needed*,
- a **Disconnect** affordance (revokes/soft-disables the row).

The raw id field stays only as an “advanced override”, collapsed.

## 8. Trust & security (delta from the spine)
- **No keys client-side.** The browser only ever receives the hosted `actionUrl`; platform credentials stay server-side. (Non-negotiable: HTTP tools never forward tokens — same posture.)
- **Webhook verification is mandatory before trusting status.** PayPal via `verify-webhook-signature` (already wired in `PayPalWebhookController`); Stripe via signing-secret. A return redirect alone never sets `ACTIVE` — it sets `ONBOARDING`; only a verified webhook/poll promotes to `ACTIVE`.
- **Tenant isolation.** `account_id` is stored per tenant and only ever used for that tenant's checkouts; the onboarding-link endpoint is workspace-guarded like the rest of `/v1/admin/tenants/{id}/…`.
- **`tracking_ref` correlates** the async return/webhook to the initiating tenant so a spoofed return can't bind an account to the wrong workspace.
- Still **never in the flow of funds**: charges settle to the merchant's own connected account; yaya-agentic orchestrates only.

## 9. Sandbox-first behavior
With no live credentials, `start()` returns a fabricated `actionUrl` (e.g. `…/sandbox/connect?ref=…`) and a tiny built-in “sandbox connect” page (or immediate auto-return) that drives `reconcile()` → `ACTIVE, chargesEnabled=true`. So the Connect button, status badge, and “connected” checkout path are all demoable offline — matching how `PayPalPaymentProvider`/`DLocalPaymentProvider` already fabricate hosted URLs. Live mode swaps in the real Partner Referrals/AccountLink calls with zero UI change.

## 10. Stripe (second provider) & dLocal (excluded)
- **Stripe** under the same SPI: `start()` = create `Account` (Express) + `AccountLink` (`type=account_onboarding`, `return_url/refresh_url`); `reconcile()` = read `account.updated` (`charges_enabled`, `details_submitted`) → store `acct_…`. Checkout already sends `Stripe-Account`.
- **dLocal**: no self-serve merchant signup; sub-accounts are provisioned by the platform via dLocal ops/API. It simply has no `MerchantOnboarding` bean, so the Connect button doesn't render for it — the existing manual `connectedAccountId` field remains the path.

## 11. Rollout (dependency-ordered, each shippable)
1. **O1 — SPI + persistence + sandbox PayPal.** `MerchantOnboarding`, `merchant_psp_accounts` (V72), `PayPalMerchantOnboarding` (sandbox), the three endpoints, checkout resolution (§6). Demoable offline.
2. **O2 — PayPal live.** Partner Referrals calls + `MERCHANT.*` webhook handling + verify-signature gating.
3. **O3 — Flutter UI.** Connect button + status badge + polling + Disconnect, wired to O1's endpoints.
4. **O4 — Stripe.** `StripeMerchantOnboarding` + `/webhooks/stripe`, same UI.

## 11b. Implementation status

| Phase | Status | What landed |
|---|---|---|
| **O1 — SPI + persistence + sandbox PayPal** | ✅ done | `MerchantOnboarding` SPI + DTOs; `MerchantAccountStatus`/`MerchantAccountView`; `merchant_psp_accounts` (**V72**) + `MerchantPspAccountEntity`/`Repository` (`@PrePersist` stamps `updated_at`); `PayPalMerchantOnboarding` (sandbox fabricates an action URL back to our return endpoint; reconcile → ACTIVE); `MerchantPspAccountService` (begin / reconcile-return / status / disconnect / `resolveConnectedAccount`); `MerchantPspOnboardingController` (`/v1/admin/tenants/{id}/commerce/psp/{provider}` — status, `/onboarding-link`, `/return`, DELETE); checkout resolution wired in `CheckoutToolHandler` (profile override → ACTIVE record → platform). |
| **O2 — PayPal live** | ✅ done | Live Partner Referrals call in `PayPalMerchantOnboarding.live()` (OAuth → `/v2/customer/partner-referrals` → `action_url`); `PayPalWebhookController` routes `MERCHANT.*` events to `reconcileWebhook` (by `tracking_id`); verify-signature gating already present. Needs a real partner-sandbox account to verify end-to-end (Q4). |
| **O3 — Flutter UI** | ✅ done | `PaymentsScreen` (nav **Payments** + route `/admin/payments`): per-provider Connect button, opens the hosted URL in a popup, polls status → **Connected ✓** badge, Disconnect. dLocal shows "Not self-serve". Wired via `AdminApi.psp*` + `PspAccountView`/`PspOnboardingLink`. |
| **O4 — Stripe** | ⬜ pending | `StripeMerchantOnboarding` (Account + AccountLink) + `/webhooks/stripe`; the SPI, service, UI, and checkout resolution are already provider-generic, so it's an additive bean + webhook. |

Tests: `PayPalMerchantOnboardingTest` (sandbox/return/webhook state machine), `MerchantPspOnboardingIT` (begin→ONBOARDING, return/webhook→ACTIVE, `resolveConnectedAccount`, disconnect, dLocal unsupported), `CheckoutToolHandlerTest` (onboarding resolution seam). `flutter analyze` clean (info-only: `dart:html` house pattern).

## 12. Open questions
- **Q1.** Onboarding at the **tenant** level (one PayPal per workspace, shared by all its stores) vs. per-**profile** (a workspace running distinct legal merchants). Proposed: tenant-level record + optional per-profile override. Revisit if multi-merchant-per-workspace is a real case.
- **Q2.** When a store is bound to a provider with no ACTIVE account: **platform-charge fallback** (proposed default) vs. **refuse checkout** (marketplace policy flag).
- **Q3.** Where the Connect UI lives: inline in the profile Commerce section vs. a dedicated per-workspace **Payments** settings page (proposed — it's tenant-level, not profile-level).
- **Q4.** Live-PSP verification against real PayPal-partner / Stripe-Connect sandbox accounts (needs real platform enrollment) — the same live-seam caveat the checkout providers already carry.
