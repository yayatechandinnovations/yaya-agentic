# yaya-sales · cart — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.cart` (§6 row 4)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §5.3 (soft-hold at add-to-cart), §6 (module map), §7.4 (cart-handle threading), §11.3 (cartHandle in working memory).
**Consumes:** pricing (re-quote at view/checkout), inventory (soft-reserve), catalog (resolve SKU), stores (channel/region on the cart).
**Consumed by:** orders (checkout reads the cart), integration (agent cart tools).

---

## 1. Purpose

Own the mutable, session-scoped cart. yaya-sales is the source of truth; a yaya-agentic session references a cart by an opaque `cartHandle` (= `CartId`) threaded through working memory (parent §7.4/§11.3). The unit price shown is a display snapshot; the authoritative price is re-quoted by pricing.

## 2. Scope

**IN:** `Cart` + `CartLine`; add/remove/setQty/clear/view; **soft-reserve at add-to-cart** (short TTL, §5.3) via inventory; cart→cart merge (anonymous → authenticated on login); channel/region binding on the cart; durable (Postgres) so a cart survives restarts/channels.

**OUT:** the reservation mechanics (→ inventory), pricing math (→ pricing), turning a cart into an order (→ orders' checkout saga).

## 3. Responsibilities & key types

```java
record Cart(CartId id, StoreId store, ChannelId channel, RegionId region, String customerRef,
            String currency, List<CartLine> lines, Instant updatedAt) {}
record CartLine(VariantId sku, int qty, Money unitPriceSnapshot) {}
```

- On `add`, request a `SOFT` inventory hold (parent §5.3); on abandonment the hold is swept.
- On `view`, re-quote via pricing so totals/tax reflect the current region policy.

## 4. Data model (owned tables)

`carts(id, store_id, channel_id, region_id, customer_ref, currency, lines_json, updated_at)` — parent §9.

## 5. API surface (§10.2, agent-facing)

`POST /v1/sales/agent/cart/{add|remove|set-qty|view|clear}` → `cart` + `_uiHint:cart`. Each mutating call is idempotent and carries the cart-handle binding.

## 6. SPIs

None published; consumes `PricingEngine` and `InventoryLedger`.

## 7. Invariants

- A cart line's qty ≥ 1; clearing releases its soft holds.
- The cart snapshot never drives a charge — checkout re-quotes and re-validates (parent §8.2).

## 8. Open questions (module-local)

- **TBD:** merge conflict rules (same SKU in anon + auth cart — sum vs max).
- **TBD:** soft-hold policy when a shopper adds more than available under `DENY` backorder.
- **TBD:** cart TTL/expiry vs the working-memory snapshot cached agent-side (parent §16 Q4).
