# yaya-sales — Operator Cutover Runbook

End-to-end procedure to deploy yaya-sales and cut a tenant over from the
legacy in-core commerce (`commerce/*`, removed from the agent by
`V73__decommission_commerce.sql`) to the standalone sales app
(design: [`design/yaya-sales-design.md`](design/yaya-sales-design.md), migration plan §12).

Every step is copy-paste-able. Shell variables used throughout:

```bash
export TENANT_ID=acme                                  # the tenant being cut over
export SALES_API=http://localhost:8092                 # yaya-sales base URL
export AGENT_API=http://localhost:8080                 # yaya-agentic base URL
export AGENT_DB_URL=postgresql://user:pass@host/yaya   # the AGENT database
export SALES_OPERATOR_TOKEN=…                          # Bearer JWT for the yaya-sales admin API (step 1)
export AGENT_ADMIN_TOKEN=…                             # core admin API key (or use the console)
```

**Rollback posture:** the legacy data stays intact in the agent DB's
`legacy_*` tables until the very last step (9). Everything before that is
additive and reversible.

---

> **Tenant identity convention (multi-tenant deployments).** The admin plane
> binds operators to stores by tenant: the operator JWT's `org` claim (name
> configurable via `YAYA_SALES_OPERATOR_TENANT_CLAIM`) must equal the store's
> tenant id — which is the SAME id the agent plane's service-token secrets are
> keyed by. In practice: use the yaya-identity organization id as the tenant id
> when creating stores and when wiring `tenant-secrets`, or mint a custom
> tenant claim in yaya-identity. Single-merchant deployments whose tokens carry
> no such claim may set `YAYA_SALES_ENFORCE_OPERATOR_TENANT=false`.

## 0. Prerequisites

- Docker + Docker Compose v2, `openssl` on the deploy host.
- `psql` (Postgres 16 client), `curl`, `jq` on the machine running the migration.
- The agent is on a build that includes `V73__decommission_commerce.sql`
  (tables `products`, `product_catalogs`, `commerce_carts`, `commerce_orders`,
  `merchant_psp_accounts` are renamed to `legacy_*`).
- A **yaya-identity** deployment (or any OIDC issuer) — yaya-sales
  authenticates operators and forwarded shoppers against it
  (`apps/yaya-identity/deploy/`).
- A way to call the **core admin API**: a console session (cookie
  `YAYA_SESSION`) or an API key sent as `Authorization: Bearer …`. The curl
  examples below use the API key; every core call can equally be done in the
  admin console UI.

## 1. Deploy yaya-sales

```bash
cd apps/yaya-sales/deploy
./deploy.sh secrets                # creates .env with generated secrets
```

Edit `deploy/.env` before first start:

- `SALES_TENANT_ID=acme` — the tenant this deployment serves.
- `YAYA_SALES_AUTH_ISSUER_URI=https://identity.example.com` — the yaya-identity
  issuer (binds `yaya.sales.auth.issuer-uri`). Leave blank and the admin API is
  reachable only with service tokens — set it now.

Then:

```bash
./deploy.sh                        # up -d --build
./deploy.sh status                 # waits on /actuator/health + prints trust wiring
```

Get an operator JWT from the issuer (e.g. a yaya-identity client-credentials
or console login token) and verify:

```bash
curl -fsS -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" \
  "$SALES_API/v1/sales/admin/stores?tenantId=$TENANT_ID"    # → [] on a fresh install
```

## 2. Trust wiring — the shared service-token secret

yaya-agentic's `HttpToolDispatcher` mints an HMAC-SHA256 token
(`tenantId.subject.exp.scopes.signature`, 60 s TTL) into the
`X-Yaya-Service-Token` header for every tool whose spec says
`authForwarding: SERVICE_TOKEN`. yaya-sales verifies it with the same secret.
**One base64 secret, set on BOTH sides, keyed by tenant id:**

| Side | Property | Where |
|---|---|---|
| yaya-sales | `yaya.sales.auth.service-secrets.<tenantId>` | `deploy/.env` → `SALES_SERVICE_TOKEN_SECRET` (compose injects it via `SPRING_APPLICATION_JSON`) |
| yaya-agentic | `yaya.agentic.auth.service-token.tenant-secrets.<tenantId>` | agent config (below) |

`deploy.sh` already generated the secret — read it:

```bash
cd apps/yaya-sales/deploy && ./deploy.sh credentials
```

Mirror it into the agent. Both properties are **maps keyed by tenant id** —
env-var relaxed binding mangles map keys, so use YAML or
`SPRING_APPLICATION_JSON`:

```yaml
# agent application-<profile>.yml
yaya:
  agentic:
    auth:
      service-token:
        tenant-secrets:
          "acme": "<the same base64 secret>"
```

or, for a containerized agent:

```bash
SPRING_APPLICATION_JSON='{"yaya":{"agentic":{"auth":{"service-token":{"tenant-secrets":{"acme":"<secret>"}}}}}}'
```

Restart the agent after setting it.

## 3. Point the tenant's `host_base_url` at yaya-sales

The imported tools carry **path-only** URLs (`/v1/sales/agent/...`); the host
is resolved per tenant from `tenants.host_base_url` at dispatch time.

```bash
curl -fsS -X PATCH "$AGENT_API/v1/admin/tenants/$TENANT_ID" \
  -H "Authorization: Bearer $AGENT_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"hostBaseUrl": "https://sales.example.com"}'
```

Notes:

- Use the URL the **agent backend** can reach (in a shared Docker network that
  may be `http://sales:8092`; across hosts, the public HTTPS URL).
- If the tenant has `requireHttps` or a `hostBaseUrlAllowlist` configured,
  include/relax them in the same PATCH
  (`{"hostBaseUrl": …, "hostBaseUrlAllowlist": ["https://sales.example.com"]}`).
- Also make sure the agent's HTTP-tool egress allowlist
  (`yaya.agentic.http-tools.egress-allowlist`) permits the sales host.

## 4. Import the `sales.*` tools from the manifest

yaya-sales publishes its tools in the core's wire shape at
`GET /v1/sales/agent/manifest` (any authenticated caller). The manifest entry
differs from the core's `POST /v1/admin/tools` request in three ways, which
the jq below bridges: `inputSchema` (object) → `inputSchemaJson` (string),
`handler.http` → `handler.{kind:"HTTP", httpSpec}`, and `description` is
dropped (the core `ToolDescriptor` has no description field — the LLM-facing
wording lives on the *capability*, step 4b).

```bash
curl -fsS -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" "$SALES_API/v1/sales/agent/manifest" \
| jq -c --arg tenant "$TENANT_ID" '.tools[] | {
    tenant: $tenant,
    id: .id,
    inputSchemaJson: (.inputSchema | tojson),
    outputSchemaJson: null,
    requires: null,
    handler: {kind: "HTTP", beanName: null, httpSpec: .handler.http},
    policy: .policy
  }' \
| while IFS= read -r tool; do
    curl -fsS -X POST "$AGENT_API/v1/admin/tools" \
      -H "Authorization: Bearer $AGENT_ADMIN_TOKEN" -H "Content-Type: application/json" \
      -d "$tool" | jq -r '"imported " + .id'
  done
```

This registers (as of this writing) 13 tools: `sales.search_products`,
`sales.get_product`, `sales.cart_add`, `sales.cart_set_qty`,
`sales.cart_remove`, `sales.cart_view`, `sales.cart_clear`, `sales.checkout`,
`sales.get_order`, `sales.delivery_options`, `sales.track_delivery`,
`sales.create_draft_order`, `sales.send_draft_order`. All are
`authForwarding: SERVICE_TOKEN` with the store id riding the
`X-Yaya-Store-Id: {ctx.storeId}` header template — review before trusting
(the manifest is an operator import, never auto-trusted).

**4b. Wrap them in a capability** (this is where the tool descriptions go):

```bash
curl -fsS -X POST "$AGENT_API/v1/admin/capabilities" \
  -H "Authorization: Bearer $AGENT_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "$(jq -n --arg tenant "$TENANT_ID" '{
    tenant: $tenant,
    id: "sales",
    label: "Shopping",
    description: "Browse the catalog, manage the cart, check out, and track orders.",
    llmGuidance: "Use sales.search_products / sales.get_product to browse. Cart changes go through sales.cart_* and totals ALWAYS come from sales.cart_view — never compute prices yourself. sales.checkout and sales.send_draft_order are confirmable: ask the shopper before calling. Use sales.delivery_options for shipping quotes and sales.track_delivery for where-is-my-order.",
    tools: ["sales.search_products","sales.get_product","sales.cart_add","sales.cart_set_qty",
            "sales.cart_remove","sales.cart_view","sales.cart_clear","sales.checkout",
            "sales.get_order","sales.delivery_options","sales.track_delivery",
            "sales.create_draft_order","sales.send_draft_order"],
    followUpHints: []
  }')"
```

## 5. Bind the commerce profile to the store

You need the sales `storeId` first — created in step 6's import (or create the
store now and let the import reuse it by name). Then re-POST the profile with
the `commerce` binding (`POST /v1/admin/profiles` creates a new version):

```bash
export STORE_ID=…    # printed by import-legacy-commerce.sh, or:
# curl -s -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" \
#   "$SALES_API/v1/sales/admin/stores?tenantId=$TENANT_ID" | jq -r '.[0].id.value'

curl -fsS "$AGENT_API/v1/admin/profiles?tenant=$TENANT_ID" \
     -H "Authorization: Bearer $AGENT_ADMIN_TOKEN" \
| jq --arg store "$STORE_ID" '.[] | select(.id == "retail-customer")   # ← your profile id
      | del(.version, .status, .createdAt)
      | .capabilities |= (. + ["sales"] | unique)
      | .commerce = {storeId: $store, checkoutTool: "sales.checkout", cartEnabled: true}' \
| curl -fsS -X POST "$AGENT_API/v1/admin/profiles" \
    -H "Authorization: Bearer $AGENT_ADMIN_TOKEN" -H "Content-Type: application/json" -d @- \
| jq -r '"profile " + .id + " → v" + (.version|tostring)'
```

The binding is deliberately thin (§12.3): a store pointer, the checkout tool
id, and the cart flag — no catalogs, currency, or PSP config on the agent side.

## 6. Data migration (export → import → opening balance)

One shot per tenant (§12.2). All read-only against the agent DB until step 9.

**6a. Export** (writes CSVs into the current directory):

```bash
mkdir -p /tmp/sales-migration/$TENANT_ID && cd /tmp/sales-migration/$TENANT_ID
psql "$AGENT_DB_URL" -v tenant_id="$TENANT_ID" \
     -f "$REPO/apps/yaya-sales/deploy/migrate/export-legacy-commerce.sql"
```

Read the review output: it lists **duplicate titles** (the importer groups new
products by title — rename in the CSV or they merge) and **variant products
with a product-level stock count** (not auto-seeded — adjust per variant SKU
by hand afterwards). Carts are deliberately skipped (ephemeral); orders are
exported to `legacy_orders_archive.csv` for archive only.

**6b. Import** (dry-run first):

```bash
cd /tmp/sales-migration/$TENANT_ID
OPERATOR_TOKEN=$SALES_OPERATOR_TOKEN TENANT_ID=$TENANT_ID CURRENCY=DOP \
  STORE_NAME="Main Store" \
  "$REPO/apps/yaya-sales/deploy/migrate/import-legacy-commerce.sh" --dry-run

OPERATOR_TOKEN=$SALES_OPERATOR_TOKEN TENANT_ID=$TENANT_ID CURRENCY=DOP \
  STORE_NAME="Main Store" \
  "$REPO/apps/yaya-sales/deploy/migrate/import-legacy-commerce.sh"
```

The script creates/reuses the store, a sellable location, and the catalog;
bulk-upserts the products; publishes the catalog; then seeds one
**opening-balance ADJUSTMENT** per SKU (reason
`opening balance (legacy import)`) so the ledger starts clean and on-hand
matches legacy reality on day one. It is safe to re-run: products upsert and
already-seeded SKUs are skipped.

**6c. Verify** (`STORE_ID`/`CATALOG_ID` are printed by the import script):

```bash
# product count matches the export (rows group into products by title)
curl -s -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" -H "X-Yaya-Store-Id: $STORE_ID" \
  "$SALES_API/v1/sales/admin/catalogs/$CATALOG_ID/products" | jq length

# on-hand equals legacy inventory — spot-check a few SKUs
psql "$AGENT_DB_URL" -c "SELECT product_id, inventory FROM legacy_products
  WHERE tenant_id='$TENANT_ID' AND inventory IS NOT NULL LIMIT 5;"
curl -s -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" -H "X-Yaya-Store-Id: $STORE_ID" \
  "$SALES_API/v1/sales/admin/inventory" | jq '.[] | {sku: .sku.value, onHand: .onHand}'
```

Then run a **test checkout in the admin playground**: open the commerce
profile, search a product, add to cart, and check out with the sandbox tender.
The whole path (agent → `X-Yaya-Service-Token` → sales) exercises steps 2–5.

## 7. PSP onboarding + webhooks

Merchant PSP accounts are provider-side links — they do **not** migrate.
Reconnect each provider listed in the exported `legacy_psp_accounts.csv`
(or via the yaya-sales console once it ships):

```bash
# begin hosted onboarding (repeat for paypal / dlocal)
curl -fsS -X POST "$SALES_API/v1/sales/admin/psp/stripe/onboarding-link" \
  -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" -H "X-Yaya-Store-Id: $STORE_ID" \
  -H "Content-Type: application/json" \
  -d '{"returnUrl": "https://merchant-console.example.com/psp/return"}' | jq
# open .actionUrl in a browser, complete onboarding, then reconcile:
curl -fsS -X POST "$SALES_API/v1/sales/admin/psp/stripe/return" \
  -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" -H "X-Yaya-Store-Id: $STORE_ID" \
  -H "Content-Type: application/json" -d '{"params": {}}' | jq
```

Provider API keys go in git-ignored `deploy/.env.local` (see
`deploy/.env.example` for the full key list), then `./deploy.sh up`.

At each PSP dashboard, point the webhook at the **publicly reachable** sales
deployment:

| Provider | Webhook URL | Signature config |
|---|---|---|
| Stripe | `https://<sales-host>/v1/sales/webhooks/stripe` | `YAYA_SALES_PAYMENTS_STRIPE_WEBHOOK_SIGNING_SECRET` (whsec\_…) |
| PayPal | `https://<sales-host>/v1/sales/webhooks/paypal` | `YAYA_SALES_PAYMENTS_PAYPAL_WEBHOOK_ID` |
| dLocal | `https://<sales-host>/v1/sales/webhooks/dlocal` | `YAYA_SALES_PAYMENTS_DLOCAL_SECRET_KEY` |
| sandbox (dev) | `https://<sales-host>/v1/sales/webhooks/sandbox` | `YAYA_SALES_PAYMENTS_SANDBOX_WEBHOOK_SECRET` (generated by deploy.sh) |

## 8. Notifications + courier fleet

**Courier fleet** (design §8.9) — enabled by inviting couriers:

```bash
curl -fsS -X POST "$SALES_API/v1/sales/admin/delivery/agents/invites" \
  -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" -H "X-Yaya-Store-Id: $STORE_ID" | jq
# courier self-registers with the invite token (POST /v1/sales/delivery/agents/register,
# then /verify with the SMS OTP); approve to mint their bearer token:
curl -fsS -X POST "$SALES_API/v1/sales/admin/delivery/agents/<agentId>/approve" \
  -H "Authorization: Bearer $SALES_OPERATOR_TOKEN" -H "X-Yaya-Store-Id: $STORE_ID" | jq
```

Knobs in `deploy/.env`: `SALES_DELIVERY_OTP_TTL_MINUTES`,
`SALES_DELIVERY_INVITE_TTL_HOURS`, and `SALES_DELIVERY_OTP_ECHO_ENABLED`
(**dev/test only** — echoes the OTP in the register response instead of SMS).

**Notifications** — the `yaya.sales.notifications.*` knobs are landing in a
parallel change; once present, pass them through git-ignored
`deploy/.env.local` (Spring relaxed binding, e.g.
`YAYA_SALES_NOTIFICATIONS_ENABLED=true`) — the compose file forwards
`.env.local` into the container as-is, so no compose change is needed.

## 9. Final cleanup — drop the legacy tables

Only after 6c's verification passed **for every tenant** and
`legacy_orders_archive.csv` is safely stored (the drop removes the historical
order lookup):

```bash
pg_dump "$AGENT_DB_URL" \
  -t legacy_products -t legacy_product_catalogs -t legacy_commerce_carts \
  -t legacy_commerce_orders -t legacy_merchant_psp_accounts \
  -f legacy_commerce_backup.sql

psql "$AGENT_DB_URL" -f "$REPO/apps/yaya-sales/deploy/migrate/drop-legacy-commerce.sql"
```

This script is deliberately **not** a Flyway migration — it can never run as
part of a deploy, so no rollout can destroy the legacy data before the
export/import happened.

**Rollback:** up to this step, the cutover is reversible — the `legacy_*`
tables still hold everything, and reverting means pointing the profile's
capabilities/commerce binding back and (if desired) renaming the tables back
to their pre-V73 names. After this step, recovery is the `pg_dump` backup.
