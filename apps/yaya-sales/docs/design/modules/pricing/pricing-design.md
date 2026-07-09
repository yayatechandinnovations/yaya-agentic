# yaya-sales · pricing — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.pricing` (§6 row 3)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §5.4 (exact money/FX), §6.2 (pricing, tax & the money quote), §9 (tables).
**Consumes:** stores (region/channel/tax policy), catalog (list price), platform (money/FX).
**Consumed by:** cart, orders (checkout saga quote), fulfillment (shipping rate → shippingTotal).

---

## 1. Purpose

The **money authority**: given a cart + selling context, produce a `PricedQuote` combining list price, promotions, tax, and shipping. All money is computed here and nowhere else (parent §5.4); the agent and client only display.

## 2. Scope

**IN:** price lists scoped by `(region, channel, customer-group)`; **tax-inclusive & exclusive** computation (parent §6.2 — correctness, not display); promotions/coupons/tiered pricing (deterministic); fee breakdown for the two-phase confirm; FX handling + snapshot onto orders.

**OUT:** capturing money (→ payments), the tax *rate table* config (owned as `regions.tax_json` in stores; pricing reads it), stock (→ inventory).

## 3. Responsibilities & key types

See parent §6.2 for `TaxPolicy(inclusive, basis)`, `PricingEngine.quote(...)`, and `PricedQuote` (with `taxInclusive` flag). Promotions are rule records (`percentage | fixed | bogo | free_shipping`) with eligibility predicates.

## 4. Data model (owned tables)

`price_lists` (region×channel×customer-group), `promotions`, `fx_rates` — parent §9. Tax policy lives on `regions.tax_json` (stores module); pricing consumes it.

## 5. API surface

No dedicated public surface — pricing is invoked by cart/orders. Admin authoring of price lists & promotions: `GET/POST/PUT /v1/sales/admin/price-lists[/{id}]` (§10.1) and a promotions CRUD (**TBD** endpoint shape).

## 6. SPIs

- `PricingEngine { PricedQuote quote(CartSnapshot, PricingContext) }` — region-aware, tax-inclusive.
- `TaxProvider { TaxResult tax(PricedQuote partial, TaxPolicy, Region) }` — v1 flat/region table; external tax service vNext.

## 7. Invariants

- **Tax-inclusive rounding:** for an inclusive region, `subtotal` + `taxTotal` reconstruct `grandTotal` to the minor unit with no drift across quantities (parent §14).
- A quote is reproducible from its inputs + FX snapshot (refunds years later reconstruct exact figures).

## 8. Open questions (module-local)

- **TBD:** promotion stacking/priority rules (parent scope table: complex stacking is vNext).
- **TBD:** rounding strategy per currency (half-up vs bankers') and where it's applied in the inclusive path.
- **TBD:** customer-group resolution (who is "wholesale") — coordinate with customers module.
