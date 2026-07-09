-- S6: returns (RMA) + purchasing (suppliers, POs, goods receipts) — design §8.8, §9.
-- Draft orders reuse the orders table (source = DRAFT, status = DRAFT, §8.1):
-- a draft is an order that has not reserved stock yet, not a new aggregate.

-- ── returns ────────────────────────────────────────────────────────────────

CREATE TABLE rmas (
    id                   VARCHAR(64)  PRIMARY KEY,
    order_id             VARCHAR(64)  NOT NULL REFERENCES orders (id),
    store_id             VARCHAR(64)  NOT NULL,
    lines_json           JSONB        NOT NULL,     -- [{sku, qty}] authorized to come back
    status               VARCHAR(16)  NOT NULL,     -- REQUESTED | AUTHORIZED | RECEIVED | CLOSED | REJECTED | CANCELLED
    reason               VARCHAR(500),
    return_shipment_json JSONB,                     -- {carrier?, trackingNumber?, instructions?} — v1 reverse-logistics
                                                    -- metadata on the RMA, NOT a fulfillment shipment row (§8.8)
    dispositions_json    JSONB,                     -- [{sku, qty, kind RESTOCK|SHRINKAGE|DAMAGE}] captured at receive;
                                                    -- only RESTOCK moves stock, the rest is audit-only
    refund_txn_ref       VARCHAR(200),              -- how the money went back (store_credit | original_tender + amount)
    received_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_rmas_order ON rmas (order_id);
CREATE INDEX idx_rmas_store_status ON rmas (store_id, status, created_at);

-- ── purchasing (stock in, §9 / §7.5) ──────────────────────────────────────

CREATE TABLE suppliers (
    id           VARCHAR(64)  PRIMARY KEY,
    store_id     VARCHAR(64)  NOT NULL REFERENCES stores (id),
    name         VARCHAR(200) NOT NULL,
    contact_json JSONB,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_suppliers_store ON suppliers (store_id);

CREATE TABLE purchase_orders (
    id          VARCHAR(64) PRIMARY KEY,
    store_id    VARCHAR(64) NOT NULL REFERENCES stores (id),
    supplier_id VARCHAR(64) NOT NULL REFERENCES suppliers (id),
    location_id VARCHAR(64) NOT NULL,
    lines_json  JSONB       NOT NULL,               -- [{sku, qty, unitCostMinor}]
    status      VARCHAR(20) NOT NULL,               -- DRAFT | PLACED | PARTIALLY_RECEIVED | RECEIVED | CANCELLED
    expected_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_purchase_orders_store_status ON purchase_orders (store_id, status, created_at);

-- One row per physical receipt; each line posted to the stock ledger as a
-- RECEIPT movement with its unit cost (drives the §7.5 moving average).
CREATE TABLE goods_receipts (
    id                VARCHAR(64) PRIMARY KEY,
    purchase_order_id VARCHAR(64) NOT NULL REFERENCES purchase_orders (id),
    store_id          VARCHAR(64) NOT NULL,
    lines_json        JSONB       NOT NULL,         -- [{sku, qty, unitCostMinor}] as posted
    received_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_goods_receipts_po ON goods_receipts (purchase_order_id);
