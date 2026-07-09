-- S2: pricing config (design §6.2, §9). Price lists override the variant's
-- list price per (region × channel × customer-group); promotions and FX
-- snapshots feed the quote. All money math happens in the pricing engine.

CREATE TABLE price_lists (
    id             VARCHAR(64)  PRIMARY KEY,
    store_id       VARCHAR(64)  NOT NULL REFERENCES stores (id),
    region_id      VARCHAR(64),               -- null = any region
    channel_id     VARCHAR(64),               -- null = any channel
    customer_group VARCHAR(100),              -- null = everyone
    rules_json     JSONB        NOT NULL,     -- [{sku, minQty, priceMinor}] tiered overrides
    active         BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_price_lists_store ON price_lists (store_id);

CREATE TABLE promotions (
    id               VARCHAR(64)  PRIMARY KEY,
    store_id         VARCHAR(64)  NOT NULL REFERENCES stores (id),
    kind             VARCHAR(24)  NOT NULL,   -- PERCENT_OFF | FIXED_OFF | FREE_SHIPPING
    value_json       JSONB        NOT NULL,   -- { bps } | { amountMinor, currency }
    eligibility_json JSONB,                   -- { minSubtotalMinor, skus[] }
    code             VARCHAR(100),            -- null = automatic
    starts_at        TIMESTAMPTZ,
    ends_at          TIMESTAMPTZ,
    active           BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_promotions_store ON promotions (store_id);

CREATE TABLE fx_rates (
    base  VARCHAR(3)     NOT NULL,
    quote VARCHAR(3)     NOT NULL,
    rate  NUMERIC(18, 8) NOT NULL,
    as_of TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (base, quote, as_of)
);
