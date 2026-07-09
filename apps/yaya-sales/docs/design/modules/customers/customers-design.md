# yaya-sales · customers — Module Design

**Status:** Scaffold (to expand)
**Module:** `com.yayatechandinnovations.yayasales.customers` (§6 row 10)
**Parent:** [`../../yaya-sales-design.md`](../../yaya-sales-design.md) — anchors §9 (tables), §11.3 (principal forwarding), §15 (PII retention/deletion).
**Consumes:** platform (principal JWT verification).
**Consumed by:** orders/cart (attribution), pricing (customer group), returns (history).

---

## 1. Purpose

A minimal customer record keyed by the **external principal subject** (the verified `subject` from the forwarded yaya-agentic principal token). yaya-sales does not run its own login — identity is asserted by the platform.

## 2. Scope

**IN:** `Customer` + `Address`; principal-subject keying; order/return history views; customer groups (for B2B/wholesale pricing hooks); **PII retention/deletion** path (parent §15).

**OUT:** authentication (asserted by yaya-agentic), the principal token verification itself (→ platform), marketing/CRM.

## 3. Responsibilities & key types

```java
record Customer(CustomerId id, StoreId store, String principalSubject, String displayName,
                List<Address> addresses, Map<String,Object> metadata, Instant createdAt) {}
```

- A customer exists per `(store, principalSubject)`; anonymous carts merge into a customer on first authenticated action.

## 4. Data model (owned tables)

`customers(UNIQUE(store_id, principal_subject))`, `addresses` — parent §9. `metadata_json` custom fields.

## 5. API surface

Admin (§10.1): customer list/detail (**TBD** endpoints). No public write path beyond what orders/cart create implicitly. PII deletion/redaction endpoint (**TBD**, §15).

## 6. SPIs

None published; consumes the platform principal verifier.

## 7. Invariants

- Never trust a client-supplied identity — customer attribution comes only from the verified `PRINCIPAL_TOKEN` (parent §11.3, #6).
- PII (addresses, history) is deletable/redactable on request (parent §15).

## 8. Open questions (module-local)

- **TBD:** customer-group model + who assigns it (wholesale/B2B) — coordinate with pricing.
- **TBD:** consent/marketing-preference storage.
- **TBD:** retention policy automation vs on-request deletion.
