# yaya-sales

A standalone, multi-tenant **sales & commerce application** shipped alongside yaya-agentic as an integration (see [`../README.md`](../README.md)) — **not** part of the agent core. It owns the commerce domain end to end: catalog, ledgered inventory (immutable movements, reservations, opening balances), pricing, cart, draft orders, the full order → payment → fulfillment → delivery → return lifecycle, a tender model (PSP + cash-on-delivery + manual + store credit), an in-house courier fleet with self-registration, purchasing, and reporting. yaya-agentic reaches it **only** through HTTP tools (`/v1/sales/agent/*`) authenticated by a per-tenant `X-Yaya-Service-Token`; the agent decides *whether* a shopper may act, yaya-sales decides *what it costs and whether money and inventory actually moved*.

**Docs:**

- [`docs/design/yaya-sales-design.md`](docs/design/yaya-sales-design.md) — architecture, module map, integration seam, migration plan (§12); per-module deep-dives in [`docs/design/modules/`](docs/design/modules/).
- [`docs/RUNBOOK.md`](docs/RUNBOOK.md) — **operator cutover runbook**: deploy, trust wiring with the agent, tool import, legacy-data migration, PSP onboarding, final legacy drop.

## Local dev

Requires Java 21 and Docker (integration tests use Testcontainers).

```bash
cd apps/yaya-sales
../../mvnw -f pom.xml verify          # or: mvn verify

# run against a local Postgres (Flyway creates the yaya_sales schema)
SALES_DB_URL=jdbc:postgresql://localhost:5432/yaya_sales \
SALES_DB_USER=postgres SALES_DB_PASSWORD=postgres \
YAYA_SALES_SECRETS_ENCRYPTION_KEY=$(openssl rand -base64 32) \
mvn spring-boot:run
```

All config knobs are env-overridable — see [`src/main/resources/application.yml`](src/main/resources/application.yml) and [`deploy/.env.example`](deploy/.env.example).

## Deploy quickstart

Self-contained Docker Compose stack (Postgres + backend):

```bash
cd apps/yaya-sales/deploy
./deploy.sh              # generates secrets into git-ignored .env, builds, starts
./deploy.sh status       # health + the trust-wiring values for the agent side
```

Then follow [`docs/RUNBOOK.md`](docs/RUNBOOK.md) for the agent trust wiring
(shared service-token secret, tenant `host_base_url`, `sales.*` tool import,
profile commerce binding) and the one-shot legacy-commerce data migration
(`deploy/migrate/`).

> TODO: the merchant console is being built in parallel — a commented stub
> service is already sketched in `deploy/compose.yaml`.

## Ports

| Port | What |
|---|---|
| 8092 | yaya-sales backend (`/actuator/health`, `/v1/sales/**`) — host port via `SALES_PORT` |
| 8093 | merchant console (reserved — not shipped yet) |
| 5432 | Postgres (compose-internal only, volume `sales_pg_data`) |

## Module map

Spring Modulith packages under `com.yayatechandinnovations.yayasales`:

| Module | One-liner |
|---|---|
| `platform` | shared kernel: typed ids, exact `Money`, auth filters (service token, courier token, operator JWT), secrets cipher, outbox |
| `stores` | store + selling policies + inventory locations per tenant |
| `catalog` | catalogs, products/variants, publish lifecycle, CSV bulk import/export |
| `inventory` | the stock ledger: movements, on-hand projection, holds, reorder points, sweeps |
| `pricing` | price resolution and promotions |
| `cart` | durable carts with authoritative totals |
| `orders` | order lifecycle + draft orders ("tap to pay") |
| `payments` | tenders, PSP adapters (Stripe/PayPal/dLocal/sandbox), merchant onboarding, webhooks |
| `fulfillment` | shipments, delivery options/tracking, in-house courier fleet + self-registration |
| `purchasing` | purchase orders and receiving (feeds inventory cost) |
| `returns` | returns and refunds |
| `reporting` | sales/margin reporting projections |
| `integration` | the agent seam: `/v1/sales/agent/*` tools + the importable tool manifest |
