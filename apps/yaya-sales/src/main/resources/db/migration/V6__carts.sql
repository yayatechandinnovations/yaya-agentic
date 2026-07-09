-- S2: durable session carts (design §5.3, cart module). yaya-sales is the
-- source of truth; the agent session holds only the opaque cart handle.

CREATE TABLE carts (
    id           VARCHAR(64) PRIMARY KEY,
    store_id     VARCHAR(64) NOT NULL REFERENCES stores (id),
    channel_id   VARCHAR(64) NOT NULL,
    region_id    VARCHAR(64) NOT NULL,
    customer_ref VARCHAR(200),               -- verified principal subject, null while anonymous
    currency     VARCHAR(3)  NOT NULL,
    lines_json   JSONB       NOT NULL,       -- [{sku, qty, unitPriceMinor, reservationId}]
    updated_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_carts_store ON carts (store_id);
CREATE INDEX idx_carts_customer ON carts (store_id, customer_ref);
