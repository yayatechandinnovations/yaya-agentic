# yaya-sales console

The merchant admin console for **yaya-sales** — a Flutter web app built with
**clean architecture** and the **BLoC (event → state)** pattern, mirroring the
yaya-identity console conventions.

## Architecture

Each feature is three layers, dependencies pointing inward:

```
features/<feature>/
├── domain/         entities · repository interfaces · use-cases   (no Flutter, no Dio)
├── data/           models (fromJson) · datasources (Dio) · repository impls
└── presentation/   bloc (event/state) · pages                     (depends on domain)
core/               config · network (Dio + auth/store interceptors) · storage · router · theme · di · widgets
```

- **Presentation → domain only.** Pages dispatch events and render state; they never touch Dio or JSON.
- **Use-cases** are one-intention classes the BLoC depends on, wrapping repository interfaces.
- **`core/di/service_locator.dart`** is the single composition root (`get_it`).
- **Failures, not exceptions**, cross the repository boundary (`core/error/failure.dart`).
- **Money is display-only.** Every amount arrives as server minor units + ISO-4217
  currency and is rendered ÷100 at 2dp (`core/domain/money.dart`); the console
  never computes money. Amount *inputs* are entered in minor units (1999 = 19.99).

## Auth & store scoping

- Sign-in is **OAuth auth-code + PKCE** against a **yaya-identity** deployment
  (`/signin` → identity `/oauth2/authorize` → `/callback` exchanges the code).
  The bearer token rides every admin call via the Dio auth interceptor.
- After login a **store picker** (`/stores`) lists the stores of a tenant id
  (remembered in `shared_preferences`) and can create stores. The chosen store
  becomes the **`X-Yaya-Store-Id`** header on every request via the store
  interceptor; switch stores any time from the app bar.

## Feature areas (left nav)

| Route | Area |
|---|---|
| `/orders`, `/orders/:id` | Orders: list w/ status filter; detail (lines, tax-inclusive totals, delivery snapshot, event timeline, tenders + payment ledger); cancel, add tender, settle tender, refund (amount → psp \| store_credit); shipments panel (create, pick/pack/dispatch, delivery advance w/ proof, courier assign, tracking) |
| `/draft-orders` | Draft orders: build/edit lines (catalog SKU picker, qty, one-off price override), send (tender choice) → hosted URL / instructions / insufficient-stock outcome, cancel |
| `/catalog`, `/catalog/:id` | Catalogs: list/create/publish; products: authoring w/ variants (sku/label/price/options/weight/barcode), archive/restore, CSV import (row errors) + export |
| `/inventory` | Levels (location filter, low-only), per-SKU ledger, receive / adjust / transfer / reorder policy / backorder policy |
| `/pricing` | Price lists (region/channel/group + tiered rules) w/ activate toggle; promotions (kind, value, eligibility, code, window) w/ toggle |
| `/shipping` | Zones (country/region/postal matchers), delivery methods, rates (FLAT / BY_WEIGHT / BY_PRICE / FREE_OVER + handling + transit), carriers, slot windows w/ capacity+booked (created this session — the admin API has no slot listing) |
| `/fleet`, `/fleet/:id` | Invites (token shown once), agents w/ status filter, KYC review (decrypt-on-demand documents), approve (courier token shown once) / reject / suspend |
| `/returns`, `/returns/:id` | RMAs: create from an order (line qty picker), list w/ status filter, authorize/reject/cancel, return-shipment info, receive (RESTOCK/SHRINKAGE/DAMAGE dispositions + refund amount/target) |
| `/purchasing` | Suppliers CRUD; purchase orders: create, place, cancel, receive (partial ok, received-so-far per line) |
| `/store-credit` | Balance lookup + manual issue |
| `/psp`, `/psp/return/:provider` | Provider cards (stripe/paypal/dlocal) w/ status + charges badge; connect (hosted onboarding URL in a new tab), auto/paste return-param reconciliation, disconnect |
| `/reports` | Sales, inventory valuation, margin, low stock, COD reconciliation (per-courier + mismatch lists) — date-range where applicable |
| `/settings` | Store detail, policies (deduction point, backorder, TTLs), locations, channels, regions (currency, tax inclusive + rateBps, tender multi-select) |

## Run

```bash
cd apps/yaya-sales/console
flutter pub get
flutter test        # BLoC unit tests (mocked repositories)
flutter analyze     # clean
flutter run -d chrome --web-port 8093 \
  --dart-define=IDENTITY_BASE_URL=http://localhost:8090 \
  --dart-define=SALES_API_BASE_URL=http://localhost:8092 \
  --dart-define=CONSOLE_CLIENT_ID=yaya-console \
  --dart-define=CONSOLE_REDIRECT_URI=http://localhost:8093/callback
```

Defaults (no dart-defines needed for local dev): identity `http://localhost:8090`,
sales `http://localhost:8092`, redirect `http://localhost:8093/callback`. Base
URLs may include a subpath (e.g. `https://host/sales`) — the base-path
interceptor prefixes it per request. The redirect URI must match the console
client registered in yaya-identity, and yaya-sales must trust the identity
issuer (`yaya.sales.auth.issuer-uri`).

## Testing

BLoC tests (`bloc_test` + `mocktail`) assert the event→state transitions with
mocked repositories and real use-cases: auth (PKCE session lifecycle), store
picker, inventory (levels + movement actions), order detail (refund/settle/
cancel), fleet (approve gate + one-time credential), draft orders (send
outcomes).
