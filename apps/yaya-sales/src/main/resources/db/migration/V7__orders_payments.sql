-- S3: orders + tenders + payment ledger (design §8.1–8.3, §9).

CREATE TABLE orders (
    id                VARCHAR(64)  PRIMARY KEY,
    store_id          VARCHAR(64)  NOT NULL REFERENCES stores (id),
    channel_id        VARCHAR(64)  NOT NULL,
    region_id         VARCHAR(64)  NOT NULL,
    customer_ref      VARCHAR(200),
    currency          VARCHAR(3)   NOT NULL,
    source            VARCHAR(16)  NOT NULL,     -- CHECKOUT | DRAFT
    lines_json        JSONB        NOT NULL,     -- [{sku, title, qty, unitPriceMinor, reservationIds[]}]
    pricing_json      JSONB        NOT NULL,     -- the PricedQuote snapshot (tax-inclusive-aware)
    status            VARCHAR(24)  NOT NULL,     -- §8.1 FSM
    payment_state     VARCHAR(24)  NOT NULL,     -- UNPAID | PARTIALLY_SETTLED | PAID | PARTIALLY_REFUNDED | REFUNDED
    fulfillment_state VARCHAR(24)  NOT NULL,     -- UNFULFILLED | PARTIALLY_FULFILLED | FULFILLED (S5)
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_orders_store_status ON orders (store_id, status, created_at);
CREATE INDEX idx_orders_customer ON orders (store_id, customer_ref);

-- Append-only: every transition is a row (actor, from→to). Same discipline as
-- the stock ledger — "why is this order PAID?" is answerable down to the event.
CREATE TABLE order_events (
    id         BIGSERIAL    PRIMARY KEY,
    order_id   VARCHAR(64)  NOT NULL REFERENCES orders (id),
    type       VARCHAR(48)  NOT NULL,
    actor      VARCHAR(200) NOT NULL,
    from_state VARCHAR(24),
    to_state   VARCHAR(24),
    data_json  JSONB,
    at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_order_events_order ON order_events (order_id, at);

-- An order is paid by ONE OR MORE tenders (§8.3) — split tender is a list,
-- not a special case. Owned by the payments module.
CREATE TABLE order_tenders (
    id           VARCHAR(64) PRIMARY KEY,
    order_id     VARCHAR(64) NOT NULL REFERENCES orders (id),
    store_id     VARCHAR(64) NOT NULL,
    kind         VARCHAR(16) NOT NULL,           -- PSP | COD | MANUAL | STORE_CREDIT
    amount_minor BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency     VARCHAR(3)  NOT NULL,
    status       VARCHAR(16) NOT NULL,           -- PENDING | SETTLED | REFUNDED | VOIDED
    detail_json  JSONB,                          -- psp intent/ref | cod note | manual ref | credit txn
    created_at   TIMESTAMPTZ NOT NULL,
    settled_at   TIMESTAMPTZ
);
CREATE INDEX idx_tenders_order ON order_tenders (order_id);

-- The PSP payment ledger (§8.3): every AUTH/CAPTURE/REFUND/VOID with its PSP
-- ref, so an order's financials reconcile against payouts.
CREATE TABLE payment_transactions (
    id           VARCHAR(64) PRIMARY KEY,
    order_id     VARCHAR(64) NOT NULL,
    tender_id    VARCHAR(64) NOT NULL,
    provider     VARCHAR(32) NOT NULL,
    kind         VARCHAR(16) NOT NULL,           -- AUTH | CAPTURE | REFUND | VOID
    amount_minor BIGINT      NOT NULL,
    currency     VARCHAR(3)  NOT NULL,
    psp_ref      VARCHAR(200),
    status       VARCHAR(16) NOT NULL,           -- OK | FAILED
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_payment_txns_order ON payment_transactions (order_id, created_at);

-- Store credit (§8.3): merchant-issued balance — a tender AND the default
-- refund target. Balance = Σ delta; the ledger never edits rows.
CREATE TABLE store_credit_ledger (
    id           VARCHAR(64)  PRIMARY KEY,
    store_id     VARCHAR(64)  NOT NULL,
    customer_ref VARCHAR(200) NOT NULL,
    delta_minor  BIGINT       NOT NULL,
    currency     VARCHAR(3)   NOT NULL,
    reason       VARCHAR(200) NOT NULL,
    ref_id       VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_store_credit_customer ON store_credit_ledger (store_id, customer_ref);

-- PSP merchant onboarding (Connect) — populated by the S4 provider port;
-- tables land now so the credentials shape is settled (§9, §15).
CREATE TABLE merchant_psp_accounts (
    store_id        VARCHAR(64)  NOT NULL,
    provider        VARCHAR(32)  NOT NULL,
    account_id      VARCHAR(200),
    status          VARCHAR(24)  NOT NULL,
    charges_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    tracking_ref    VARCHAR(200),
    detail_json     JSONB,
    PRIMARY KEY (store_id, provider)
);

CREATE TABLE psp_credentials (
    store_id   VARCHAR(64) NOT NULL,
    provider   VARCHAR(32) NOT NULL,
    ciphertext TEXT        NOT NULL,             -- AES-256-GCM envelope (platform SecretCipher)
    iv         TEXT        NOT NULL,
    key_id     VARCHAR(64),
    PRIMARY KEY (store_id, provider)
);
