-- S1: the inventory core (design §7, §9). The LEDGER is the source of truth;
-- the summary is a projection + concurrency guard maintained in the same
-- transaction (§5.1/§5.2). Corrections are new movements, never edits.

CREATE TABLE stock_movements (
    id              VARCHAR(64)  PRIMARY KEY,
    store_id        VARCHAR(64)  NOT NULL,
    sku_id          VARCHAR(64)  NOT NULL,    -- variant id
    location_id     VARCHAR(64)  NOT NULL,
    qty_delta       BIGINT       NOT NULL,    -- +receipt/return, −sale/shrinkage, ± adjustment/transfer
    kind            VARCHAR(16)  NOT NULL,    -- RECEIPT|SALE|RETURN|ADJUSTMENT|TRANSFER_IN|TRANSFER_OUT|SHRINKAGE|DAMAGE
    unit_cost_minor BIGINT,                   -- captured on RECEIPT; drives valuation
    currency        VARCHAR(3),
    ref_type        VARCHAR(24)  NOT NULL,    -- ORDER|RMA|PURCHASE_ORDER|ADJUSTMENT|TRANSFER|OPENING_BALANCE
    ref_id          VARCHAR(128),
    reason          VARCHAR(500),
    lot             VARCHAR(100),             -- schema-ready, off in v1 (§7.4)
    serial          VARCHAR(100),
    expires_on      DATE,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_movements_sku_loc ON stock_movements (sku_id, location_id, recorded_at);
CREATE INDEX idx_movements_store ON stock_movements (store_id, recorded_at);

-- The ledger is append-only: no UPDATE or DELETE, ever (§7.1).
CREATE OR REPLACE FUNCTION yaya_sales.stock_movements_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'stock_movements is append-only — corrections are new ADJUSTMENT movements';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_movements_immutable
    BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION yaya_sales.stock_movements_immutable();

CREATE TABLE stock_summary (
    sku_id           VARCHAR(64) NOT NULL,
    location_id      VARCHAR(64) NOT NULL,
    store_id         VARCHAR(64) NOT NULL,
    on_hand          BIGINT      NOT NULL DEFAULT 0,   -- = Σ movements (same-tx projection)
    reserved         BIGINT      NOT NULL DEFAULT 0,   -- = Σ open reservations
    avg_cost_minor   BIGINT,                           -- moving-average valuation (decision 3)
    currency         VARCHAR(3),
    reorder_point    INT         NOT NULL DEFAULT 0,
    reorder_qty      INT         NOT NULL DEFAULT 0,
    backorder_policy VARCHAR(16) NOT NULL DEFAULT 'DENY',  -- DENY | ALLOW | PREORDER (§7.4)
    version          BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (sku_id, location_id),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved >= 0)
);
CREATE INDEX idx_stock_summary_store ON stock_summary (store_id);

CREATE TABLE reservations (
    id          VARCHAR(64) PRIMARY KEY,
    store_id    VARCHAR(64) NOT NULL,
    sku_id      VARCHAR(64) NOT NULL,
    location_id VARCHAR(64) NOT NULL,
    qty         BIGINT      NOT NULL CHECK (qty > 0),
    state       VARCHAR(16) NOT NULL,   -- HELD | COMMITTED | RELEASED | EXPIRED
    kind        VARCHAR(8)  NOT NULL,   -- SOFT (add-to-cart) | FIRM (checkout)
    cart_id     VARCHAR(64),
    order_id    VARCHAR(64),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_reservations_sweep ON reservations (state, expires_at);
CREATE INDEX idx_reservations_sku_loc ON reservations (sku_id, location_id);
