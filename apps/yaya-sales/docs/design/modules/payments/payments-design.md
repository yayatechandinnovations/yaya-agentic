# yaya-sales · payments — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.payments` (§6 row 6)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §8.3 (tenders, PSPs, COD), §9 (tables), §10.3 (webhooks), §13 (events), §15 (secrets).
**Consumes:** orders (order to settle), stores (enabled tenders per region, connected accounts), fulfillment/courier (COD collection), platform (idempotency, secret cipher).
**Consumed by:** orders (checkout saga tender step), returns (refunds → PSP or store credit).

---

## 1. Purpose

Move money. An order is paid by one or more **tenders** — a PSP charge, **cash on delivery**, manual/bank transfer, or store credit — with split tender and a full **payment ledger**.

## 2. Scope

**IN:** the `TenderProvider` model (`PSP | COD | MANUAL | STORE_CREDIT`); PSP auth/capture/refund + `PaymentProvider` engines (dLocal/Stripe/PayPal); `payment_transactions` ledger; **COD settled by the courier** (§8.9/§10.4); manual-transfer confirm; **store-credit ledger** (tender + default refund target); merchant onboarding (Connect); signature-verified idempotent webhooks; per-region COD cap + reconciliation.

**OUT:** which tenders a region offers (config → stores), placing the order (→ orders), the courier UI that triggers COD-collect (→ fulfillment/integration).

## 3. Responsibilities & key types

See parent §8.3 for `TenderKind`, `TenderProvider` (`begin/settle/refund`), the retained `PaymentProvider` (PSP engine), `AuthorizeOutcome`. Store credit is a per-customer balance ledger.

## 4. Data model (owned tables)

`order_tenders`, `payment_transactions` (PSP ledger), `store_credit_ledger`, `merchant_psp_accounts`, `psp_credentials` (AES-256-GCM) — parent §9.

## 5. API surface

- Webhooks (§10.3): `POST /v1/sales/webhooks/{stripe|paypal|dlocal}`.
- Onboarding (§10.1): `GET/POST/DELETE /v1/sales/admin/psp/{provider}[/onboarding-link|/return]`.
- Admin tenders (§10.1): `POST /orders/{id}/tenders`, `…/tenders/{tid}/settle`.
- COD settle is invoked from the courier surface (§10.4 `collect-cod`).

## 6. SPIs

- `TenderProvider` (published), `PaymentProvider` (PSP engine), `MerchantOnboarding`.

## 7. Invariants (parent §14)

- **Split-tender balance:** settled tenders sum exactly to `grandTotal`; store credit never drives balance negative.
- **COD single-settle:** courier `collect-cod` is idempotent — settles once, one `PAID`, no double SALE.
- Webhooks are the only thing that flips a PSP order `→ PAID`; deduped on PSP event id.
- PSP secrets never returned to any client.

## 8. Open questions (module-local)

- **TBD:** saved methods / 3DS-SCA vault (vNext) — reserve the shape.
- **TBD:** COD fraud scoring beyond cap + OTP (parent §16 Q12).
- **TBD:** refund routing precedence (original tender vs store credit) per store policy.
